package dev.cushionscreen;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cushionscreen.client.media.AudioDecoder;
import dev.cushionscreen.client.media.AudioOutput;
import dev.cushionscreen.client.media.FFmpeg;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

/**
 * The real audio path (FFmpeg decoder into an OpenAL streaming source) on OpenAL Soft's silent "null" device,
 * which plays in real time, so the audio clock can be measured against the timeline.
 */
class AudioSyncTest {
    private static long device, context;

    @BeforeAll
    static void openAudio() {
        assertTrue(FFmpeg.load().join(), FFmpeg::failure);
        device = ALC10.alcOpenDevice((java.nio.ByteBuffer) null);
        assertTrue(device != 0, "no OpenAL device (is ALSOFT_DRIVERS=null set?)");
        ALCCapabilities caps = ALC.createCapabilities(device);
        context = ALC10.alcCreateContext(device, (IntBuffer) null);
        ALC10.alcMakeContextCurrent(context);
        AL.createCapabilities(caps);
    }

    @AfterAll
    static void closeAudio() {
        ALC10.alcMakeContextCurrent(0);
        ALC10.alcDestroyContext(context);
        ALC10.alcCloseDevice(device);
    }

    private static Path video(String name) throws Exception {
        return Path.of(AudioSyncTest.class.getResource("/videos/" + name).toURI());
    }

    private record Run(long startedAfterMs, long worstDriftMs, int reseeks) {}

    /**
     * Drives the output like the render loop: every 16 ms, timeline = {@code timelineStart} + elapsed real time.
     * The decoder was opened at {@code decoderStart}, so it may be behind or ahead of the timeline.
     */
    private static Run drive(String file, long decoderStart, long timelineStart, long durationMs) throws Exception {
        try (AudioDecoder dec = new AudioDecoder(video(file), decoderStart, 2);
             AutoCloseableOutput out = new AutoCloseableOutput()) {
            long t0 = System.nanoTime();
            long startedAfter = -1, worst = 0, lastSeek = 0, openedAfter = -1;
            int reseeks = 0;
            while (true) {
                long elapsed = (System.nanoTime() - t0) / 1_000_000;
                if (elapsed > durationMs) break;
                long target = timelineStart + elapsed;
                if (dec.isOpened()) {
                    if (openedAfter < 0) openedAfter = elapsed;
                    if (out.out == null) out.out = new AudioOutput(dec.sampleRate(), dec.channels());
                    AudioOutput.Result r = out.out.update(dec, target, true, Vec3.ZERO, 1f, 32f);
                    if (r == AudioOutput.Result.RESEEK && elapsed - lastSeek > 750) {
                        lastSeek = elapsed;
                        reseeks++;
                        dec.seek(target);
                    }
                    if (out.out.isStarted()) {
                        if (startedAfter < 0) startedAfter = elapsed;
                        else if (elapsed - startedAfter > 300) worst = Math.max(worst, Math.abs(out.out.clockMs() - target));
                    }
                }
                Thread.sleep(16);
            }
            System.out.println("AUDIODBG " + file + " opened=" + openedAfter + " started=" + startedAfter + " worst=" + worst + " reseeks=" + reseeks);
            return new Run(startedAfter, worst, reseeks);
        }
    }

    private static final class AutoCloseableOutput implements AutoCloseable {
        AudioOutput out;

        @Override
        public void close() {
            if (out != null) out.close();
        }
    }

    @Test
    void startsInStepFromTheBeginning() throws Exception {
        Run r = drive("colors.mp4", 0, 0, 2500);
        assertTrue(r.startedAfterMs >= 0 && r.startedAfterMs < 400, "sound started after " + r.startedAfterMs + " ms");
        assertTrue(r.worstDriftMs <= 45, "audio drifted " + r.worstDriftMs + " ms from the timeline");
        assertTrue(r.reseeks == 0, r.reseeks + " reseeks");
    }

    @Test
    void decoderBehindTheTimelineStillStartsWithoutLooping() throws Exception {
        // the old code asked for a 150 ms head start and resynced forever when decoding was slower than that
        Run r = drive("colors.webm", 1000, 1600, 2000);
        assertTrue(r.startedAfterMs >= 0 && r.startedAfterMs < 500, "sound started after " + r.startedAfterMs + " ms");
        assertTrue(r.worstDriftMs <= 45, "audio drifted " + r.worstDriftMs + " ms");
        assertTrue(r.reseeks <= 1, r.reseeks + " reseeks: it is looping");
    }

    @Test
    void decoderFarFromTheTimelineSeeksOnceAndPlays() throws Exception {
        // the timeline jumped 2.5 s ahead of where the decoder is
        Run r = drive("colors.mov", 0, 2500, 1500);
        assertTrue(r.startedAfterMs >= 0 && r.startedAfterMs < 900, "sound started after " + r.startedAfterMs + " ms");
        assertTrue(r.worstDriftMs <= 45, "audio drifted " + r.worstDriftMs + " ms");
        assertTrue(r.reseeks <= 1, r.reseeks + " reseeks: it is looping");
    }

    @Test
    void reportsWhenEverySoundSourceIsTakenAndRecoversAfter() {
        List<Integer> hog = new ArrayList<>();
        try {
            for (int i = 0; i < 4096; i++) {
                AL10.alGetError();
                int s = AL10.alGenSources();
                if (AL10.alGetError() != AL10.AL_NO_ERROR) break;
                hog.add(s);
            }
            assertThrows(IllegalStateException.class, () -> new AudioOutput(48000, 2));
            AL10.alDeleteSources(hog.remove(hog.size() - 1));
            new AudioOutput(48000, 2).close(); // a free source is picked up again
        } finally {
            hog.forEach(AL10::alDeleteSources);
        }
    }
}
