package dev.cushionscreen.client.media;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/**
 * One OpenAL streaming source per playing screen, driven from the render thread. It knows exactly which sample is
 * audible ({@code AL_SAMPLE_OFFSET} over the queued chunk timestamps), which gives a precise audio clock to hold
 * against the shared timeline.
 *
 * <p>Starting is done by <em>trimming</em>: whatever the decoder delivers is cut to the sample that is due right
 * now, so playback starts in step however long decoding took. Small drift while playing is absorbed by a slight
 * pitch change; larger drift realigns the same way. Only when the decoder is somewhere else entirely (the
 * timeline jumped, or decoding fell far behind) does {@link #update} ask for a seek.
 */
public final class AudioOutput implements AutoCloseable {
    public enum Result { OK, RESEEK }

    private static final int BUFFERS = 8;
    private static final long QUEUE_AHEAD_MS = 350;
    private static final long START_TOLERANCE_MS = 15;
    private static final long REALIGN_DRIFT_MS = 200;
    /** AL_SOFT_source_spatialize: lets OpenAL Soft place a stereo source in 3D (downmixed). */
    private static final int AL_SOURCE_SPATIALIZE_SOFT = 0x1214;

    private record Queued(int buffer, long ptsMs, int samples) {}

    private final int source;
    private final int sampleRate;
    private final int channels;
    private final int bytesPerFrame;
    private final Deque<Integer> freeBuffers = new ArrayDeque<>();
    private final Deque<Queued> queued = new ArrayDeque<>();
    private final boolean canSpatializeStereo;
    private int generation = -1;
    private boolean closed;
    private boolean started;
    private float pitch = 1f;
    /** Timeline position of the first sample queued since the last flush; the clock counts samples from there. */
    private long basePtsMs = -1;
    private long consumedSamples;

    public static boolean supportsStereoSpatialization() {
        try {
            return AL.getCapabilities() != null && AL10.alIsExtensionPresent("AL_SOFT_source_spatialize");
        } catch (Throwable t) {
            return false;
        }
    }

    /** @throws IllegalStateException when OpenAL has no free source right now (the game's own sounds use them too) */
    public AudioOutput(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bytesPerFrame = 2 * channels;
        this.canSpatializeStereo = channels == 2 && supportsStereoSpatialization();
        AL10.alGetError(); // clear anything left over by others
        int src = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || src == 0 || !AL10.alIsSource(src)) {
            throw new IllegalStateException("no free OpenAL source");
        }
        int[] ids = new int[BUFFERS];
        AL10.alGenBuffers(ids);
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            AL10.alDeleteSources(src);
            throw new IllegalStateException("could not allocate OpenAL buffers");
        }
        source = src;
        for (int id : ids) freeBuffers.add(id);
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
    }

    /**
     * @param targetMs   where the shared timeline says playback is now
     * @param playing    whether sound should be playing (timeline running and audible)
     * @param position   world position of the screen's centre, or null for non-positional sound (fullscreen)
     * @param gain       final linear gain
     * @param range      distance at which the screen falls silent
     */
    public Result update(AudioDecoder decoder, long targetMs, boolean playing, Vec3 position, float gain, float range) {
        if (closed) return Result.OK;
        place(position, gain, range);

        if (decoder.generation() != generation) {
            flush();
            generation = decoder.generation();
        }
        reclaimProcessed();

        if (!playing) {
            pause();
            return Result.OK;
        }

        if (started) {
            fill(decoder, Long.MIN_VALUE);
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (queued.isEmpty() || state == AL10.AL_STOPPED) {
                // ran dry (slow disk, decoder hiccup): realign from whatever arrives next
                flush();
                started = false;
            } else {
                long drift = clockMs() - targetMs;
                if (Math.abs(drift) > REALIGN_DRIFT_MS) {
                    flush();
                    started = false;
                } else {
                    // up to ±5 % speed (inaudible for video sound) closes a 50 ms gap in about a second
                    float wanted = Math.abs(drift) < 8 ? 1f : 1f - Math.max(-0.05f, Math.min(0.05f, drift / 1000f * 1.5f));
                    pitch += (wanted - pitch) * 0.35f;
                    AL10.alSourcef(source, AL10.AL_PITCH, pitch);
                    return Result.OK;
                }
            }
        }

        // ---- not started: line the queue up with the timeline, then start in step
        if (!queued.isEmpty() && queued.peekFirst().ptsMs < targetMs - START_TOLERANCE_MS) flush(); // queued audio is already late
        long behindBy = fill(decoder, targetMs);
        if (queued.isEmpty()) {
            // Nothing usable yet. If the decoder is producing audio that is far in the past, it fell behind (or the
            // timeline jumped forward): skip ahead rather than chew through it.
            return behindBy > 1500 ? Result.RESEEK : Result.OK;
        }
        long lead = queued.peekFirst().ptsMs - targetMs;
        if (lead > 1500) return Result.RESEEK;       // decoder is far ahead (timeline went backwards)
        if (lead > START_TOLERANCE_MS) {             // a little early: wait for the timeline to arrive
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source);
            return Result.OK;
        }
        pitch = 1f;
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        AL10.alSourcePlay(source);
        started = true;
        return Result.OK;
    }

    /**
     * Queues decoded audio until ~350 ms are waiting. With {@code alignTo} set, audio that ends before that moment is
     * dropped and the first chunk is cut to start exactly there.
     *
     * @return how far behind {@code alignTo} the dropped audio was (0 if nothing was dropped)
     */
    private long fill(AudioDecoder decoder, long alignTo) {
        long behindBy = 0;
        while (!freeBuffers.isEmpty() && queuedMs() < QUEUE_AHEAD_MS) {
            AudioDecoder.Chunk c = decoder.poll();
            if (c == null) break;
            ByteBuffer pcm = c.pcm();
            long pts = c.ptsMs();
            int samples = c.samples();
            if (alignTo != Long.MIN_VALUE && queued.isEmpty()) {
                long endMs = pts + samples * 1000L / sampleRate;
                if (endMs <= alignTo) {
                    behindBy = Math.max(behindBy, alignTo - endMs);
                    continue; // entirely in the past
                }
                if (pts < alignTo) {
                    int skip = (int) Math.min(samples - 1, (alignTo - pts) * sampleRate / 1000);
                    pcm = pcm.duplicate().position(skip * bytesPerFrame);
                    samples -= skip;
                    pts += skip * 1000L / sampleRate;
                }
            }
            if (basePtsMs < 0) {
                basePtsMs = pts;
                consumedSamples = 0;
            }
            int buf = freeBuffers.poll();
            int format = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            AL10.alBufferData(buf, format, pcm.slice(), sampleRate);
            AL10.alSourceQueueBuffers(source, buf);
            queued.add(new Queued(buf, pts, samples));
        }
        return behindBy;
    }

    private void place(Vec3 position, float gain, float range) {
        if (position != null) {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(source, AL10.AL_POSITION, (float) position.x, (float) position.y, (float) position.z);
            AL10.alSourcei(source, AL11.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, Math.max(1f, range * 0.25f));
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1f);
            if (canSpatializeStereo) AL10.alSourcei(source, AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_TRUE);
        } else {
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0f);
            if (canSpatializeStereo) AL10.alSourcei(source, AL_SOURCE_SPATIALIZE_SOFT, AL10.AL_FALSE);
        }
        AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, gain)));
    }

    /** False once OpenAL was restarted under us (sound device change, F3+T): the source no longer exists. */
    public boolean isValid() {
        return !closed && AL10.alIsSource(source);
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Timeline position of the sample being heard now, or -1 with nothing queued. Counted in samples from the aligned
     * start rather than read from each chunk's timestamp, which some codecs (AAC) jitter by tens of milliseconds.
     */
    public long clockMs() {
        if (queued.isEmpty() || basePtsMs < 0) return -1;
        int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        return basePtsMs + (consumedSamples + offset) * 1000L / sampleRate;
    }

    private long queuedMs() {
        long samples = 0;
        for (Queued q : queued) samples += q.samples;
        return samples * 1000 / sampleRate;
    }

    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0 && !queued.isEmpty()) {
            AL10.alSourceUnqueueBuffers(source);
            Queued q = queued.poll();
            consumedSamples += q.samples;
            freeBuffers.add(q.buffer);
        }
    }

    public void pause() {
        if (!closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source);
        started = false;
    }

    /** Drops everything queued, e.g. after a seek. */
    public void flush() {
        AL10.alSourceStop(source);
        int count = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (count-- > 0) AL10.alSourceUnqueueBuffers(source);
        for (Queued q : queued) freeBuffers.add(q.buffer);
        queued.clear();
        pitch = 1f;
        started = false;
        basePtsMs = -1;
        consumedSamples = 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (AL10.alIsSource(source)) {
                AL10.alSourceStop(source);
                int count = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
                while (count-- > 0) AL10.alSourceUnqueueBuffers(source);
                AL10.alDeleteSources(source);
            }
            for (Queued q : queued) freeBuffers.add(q.buffer);
            for (int b : freeBuffers) if (AL10.alIsBuffer(b)) AL10.alDeleteBuffers(b);
        } catch (Throwable ignored) {
            // the audio device is gone; nothing left to free
        }
        queued.clear();
        freeBuffers.clear();
    }
}
