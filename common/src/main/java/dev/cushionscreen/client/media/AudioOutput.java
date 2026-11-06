package dev.cushionscreen.client.media;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

/**
 * One OpenAL streaming source per playing screen, driven from the render thread. Knows exactly which sample is
 * audible ({@code AL_SAMPLE_OFFSET} over the queued chunk timestamps), which gives a precise audio clock to hold
 * against the shared timeline: small drift is absorbed by a slight pitch change, large drift by a resync.
 */
public final class AudioOutput implements AutoCloseable {
    private static final int BUFFERS = 8;
    private static final long QUEUE_AHEAD_MS = 350;
    /** AL_SOFT_source_spatialize: lets OpenAL Soft place a stereo source in 3D (downmixed). */
    private static final int AL_SOURCE_SPATIALIZE_SOFT = 0x1214;

    private record Queued(int buffer, long ptsMs, int samples) {}

    private final int source;
    private final int sampleRate;
    private final int channels;
    private final Deque<Integer> freeBuffers = new ArrayDeque<>();
    private final Deque<Queued> queued = new ArrayDeque<>();
    private final boolean canSpatializeStereo;
    private int generation = -1;
    private boolean closed;
    private float pitch = 1f;
    private boolean wasPlaying;

    public static boolean supportsStereoSpatialization() {
        try {
            return AL.getCapabilities() != null && AL10.alIsExtensionPresent("AL_SOFT_source_spatialize");
        } catch (Throwable t) {
            return false;
        }
    }

    public AudioOutput(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.canSpatializeStereo = channels == 2 && supportsStereoSpatialization();
        source = AL10.alGenSources();
        int[] ids = new int[BUFFERS];
        AL10.alGenBuffers(ids);
        for (int id : ids) freeBuffers.add(id);
        AL10.alSourcef(source, AL10.AL_PITCH, 1f);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
    }

    /**
     * @param targetMs   where the shared timeline says playback is now
     * @param playing    whether the timeline is running
     * @param position   world position of the screen's centre, or null for non-positional (fullscreen) sound
     * @param gain       final linear gain (screen volume x game volume sliders, 0 when muted)
     * @param range      distance at which the screen falls silent
     * @return true when the decoder must be re-seeked to the target (large drift or underrun)
     */
    public boolean update(AudioDecoder decoder, long targetMs, boolean playing, Vec3 position, float gain, float range) {
        if (closed) return false;
        // placement
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

        if (decoder.generation() != generation) {
            flush();
            generation = decoder.generation();
        }
        reclaimProcessed();

        if (!playing) {
            if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source);
            wasPlaying = false;
            return false;
        }

        // keep ~350 ms queued
        while (!freeBuffers.isEmpty() && queuedMs() < QUEUE_AHEAD_MS) {
            AudioDecoder.Chunk c = decoder.poll();
            if (c == null) break;
            int buf = freeBuffers.poll();
            int format = channels == 2 ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
            AL10.alBufferData(buf, format, c.pcm(), sampleRate);
            AL10.alSourceQueueBuffers(source, buf);
            queued.add(new Queued(buf, c.ptsMs(), c.samples()));
        }

        if (queued.isEmpty()) {
            wasPlaying = false;
            return false; // waiting for the decoder
        }

        // hold the audio clock to the timeline
        long clock = clockMs();
        long drift = clock - targetMs;
        if (!wasPlaying) {
            // Starting (or restarting after a seek): the decoder was asked to land slightly ahead of the
            // timeline, so wait for the timeline to reach the first sample, then start in step.
            if (drift > 0 && drift < 1000) {
                if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source);
                return false;
            }
            if (drift < -60 || drift >= 1000) return true;
        } else if (Math.abs(drift) > 180) {
            return true;
        }
        float wanted = Math.abs(drift) < 15 ? 1f : 1f - Math.max(-0.04f, Math.min(0.04f, drift / 1000f * 0.6f));
        pitch += (wanted - pitch) * 0.2f;
        AL10.alSourcef(source, AL10.AL_PITCH, pitch);

        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) AL10.alSourcePlay(source);
        wasPlaying = true;
        return false;
    }

    /** False once OpenAL was restarted under us (sound device change, F3+T): the source no longer exists. */
    public boolean isValid() {
        return !closed && AL10.alIsSource(source);
    }

    /** Timeline position of the sample being heard now. */
    public long clockMs() {
        Queued head = queued.peek();
        if (head == null) return -1;
        int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
        return head.ptsMs + offset * 1000L / sampleRate;
    }

    private long queuedMs() {
        long samples = 0;
        for (Queued q : queued) samples += q.samples;
        return samples * 1000 / sampleRate;
    }

    private void reclaimProcessed() {
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        while (processed-- > 0 && !queued.isEmpty()) {
            int buf = AL10.alSourceUnqueueBuffers(source);
            Queued q = queued.poll();
            freeBuffers.add(q != null ? q.buffer : buf);
        }
    }

    public void pause() {
        if (!closed && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) AL10.alSourcePause(source);
        wasPlaying = false;
    }

    /** Drops everything queued, e.g. after a seek. */
    public void flush() {
        AL10.alSourceStop(source);
        int count = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        while (count-- > 0) AL10.alSourceUnqueueBuffers(source);
        for (Queued q : queued) freeBuffers.add(q.buffer);
        queued.clear();
        pitch = 1f;
        wasPlaying = false;
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
