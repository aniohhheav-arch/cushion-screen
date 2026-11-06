package dev.cushionscreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cushionscreen.client.media.AudioDecoder;
import dev.cushionscreen.client.media.FFmpeg;
import dev.cushionscreen.client.media.MediaProbe;
import dev.cushionscreen.client.media.VideoDecoder;
import dev.cushionscreen.client.media.VideoFingerprint;
import dev.cushionscreen.screen.MediaInfo;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Real decoding through the bundled FFmpeg. The test clips are four one-second solid colours
 * (red, green, blue, white) over a 440 Hz tone, so the right frame for any timestamp is known.
 */
class DecoderTest {
    private static final int[][] COLORS = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255}};

    @BeforeAll
    static void loadNatives() {
        assertTrue(FFmpeg.load().join(), () -> "FFmpeg failed to load: " + FFmpeg.failure());
    }

    static Path video(String name) throws Exception {
        return Path.of(DecoderTest.class.getResource("/videos/" + name).toURI());
    }

    @ParameterizedTest
    @ValueSource(strings = {"colors.mp4", "colors.webm", "colors.mov"})
    void probesFormats(String name) throws Exception {
        MediaInfo info = MediaProbe.probe(video(name));
        assertEquals(320, info.width());
        assertEquals(180, info.height());
        assertTrue(Math.abs(info.durationMs() - 4000) < 150, "duration " + info.durationMs());
        assertTrue(info.hasAudio());
        assertEquals(64, info.fingerprint().length());
        assertTrue(info.isValid());
        assertEquals(Files.size(video(name)), info.sizeBytes());
    }

    @Test
    void videoWithoutAudioIsDetected() throws Exception {
        MediaInfo info = MediaProbe.probe(video("silent.mp4"));
        assertFalse(info.hasAudio());
        assertEquals(640, info.width());
    }

    @Test
    void fingerprintIgnoresNameButNotContents() throws Exception {
        Path tmp = Files.createTempDirectory("cs");
        Path a = Files.copy(video("colors.mp4"), tmp.resolve("renamed copy.mp4"));
        assertEquals(VideoFingerprint.of(video("colors.mp4")), VideoFingerprint.of(a));
        assertFalse(VideoFingerprint.of(video("colors.mp4")).equals(VideoFingerprint.of(video("colors.webm"))));
    }

    private static int[] rgbAt(VideoDecoder.Frame f, int x, int y) {
        int i = (y * f.width + x) * 4;
        return new int[]{f.pixels.get(i) & 0xFF, f.pixels.get(i + 1) & 0xFF, f.pixels.get(i + 2) & 0xFF, f.pixels.get(i + 3) & 0xFF};
    }

    private static int segmentOf(int[] rgb) {
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int s = 0; s < COLORS.length; s++) {
            int d = 0;
            for (int c = 0; c < 3; c++) d += Math.abs(rgb[c] - COLORS[s][c]);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        assertTrue(bestDist < 90, "colour " + rgb[0] + "," + rgb[1] + "," + rgb[2] + " matches no segment");
        return best;
    }

    /** Waits for the first frame due at {@code targetMs}. */
    private static VideoDecoder.Frame frameAt(VideoDecoder dec, long targetMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            assertNull(dec.error(), dec.error());
            VideoDecoder.Frame f = dec.takeFrame(targetMs);
            if (f != null) return f;
            Thread.sleep(5);
        }
        throw new AssertionError("no frame for " + targetMs + " ms");
    }

    @ParameterizedTest
    @ValueSource(strings = {"colors.mp4", "colors.webm", "colors.mov"})
    void decodesFramesInOrderAtTheRightTime(String name) throws Exception {
        try (VideoDecoder dec = new VideoDecoder(video(name), 0, 1920, 1080, false)) {
            VideoDecoder.Frame first = frameAt(dec, 0);
            assertEquals(320, first.width);
            assertEquals(180, first.height);
            assertTrue(first.ptsMs() <= 40);
            int[] px = rgbAt(first, 160, 90);
            assertEquals(255, px[3], "opaque");
            assertEquals(0, segmentOf(px));
            long shownPts = first.ptsMs(); // read before recycling: the decoder reuses the buffer at once
            dec.recycle(first);
            // advance the timeline in 16 ms steps like the render loop, checking what is on screen as we go
            int[] shownColour = px;
            for (long t = 16; t <= 3900; t += 16) {
                long deadline = System.currentTimeMillis() + 2000;
                do {
                    VideoDecoder.Frame f = dec.takeFrame(t);
                    if (f != null) {
                        assertTrue(f.ptsMs() <= t, "frame from the future");
                        assertTrue(f.ptsMs() > shownPts, "frames go forward: got " + f.ptsMs() + " after " + shownPts + " at t=" + t);
                        shownPts = f.ptsMs();
                        shownColour = rgbAt(f, 160, 90);
                        dec.recycle(f);
                    }
                    if (t - shownPts <= 34) break; // at 30 fps a frame is due every 33 ms
                    Thread.sleep(1);
                } while (System.currentTimeMillis() < deadline);
                assertTrue(t - shownPts <= 34, "showing " + shownPts + " at " + t);
                assertEquals((int) (shownPts / 1000), segmentOf(shownColour), "colour at " + shownPts);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"colors.mp4", "colors.webm", "colors.mov"})
    void seeksPreciselyBothWays(String name) throws Exception {
        try (VideoDecoder dec = new VideoDecoder(video(name), 2500, 1920, 1080, false)) {
            VideoDecoder.Frame f = frameAt(dec, 2500);
            assertTrue(Math.abs(f.ptsMs() - 2500) <= 34, "start pts " + f.ptsMs());
            assertEquals(2, segmentOf(rgbAt(f, 10, 10)));
            dec.recycle(f);

            dec.seek(1200); // backwards, mid-GOP
            f = frameAt(dec, 1200);
            assertTrue(Math.abs(f.ptsMs() - 1200) <= 34, "seek pts " + f.ptsMs());
            assertEquals(1, segmentOf(rgbAt(f, 300, 170)));
            dec.recycle(f);

            dec.seek(3600); // forwards
            f = frameAt(dec, 3600);
            assertTrue(Math.abs(f.ptsMs() - 3600) <= 34, "seek pts " + f.ptsMs());
            assertEquals(3, segmentOf(rgbAt(f, 160, 90)));
            dec.recycle(f);
        }
    }

    @Test
    void hardwareDecodingFallsBackGracefullyWhenNoDeviceExists() throws Exception {
        // this machine has no GPU decoder: the candidate search must skip cleanly and decode in software
        try (VideoDecoder dec = new VideoDecoder(video("colors.mp4"), 1500, 1920, 1080, true)) {
            VideoDecoder.Frame f = frameAt(dec, 1500);
            assertEquals(1, segmentOf(rgbAt(f, 160, 90)));
            dec.recycle(f);
        }
    }

    @Test
    void keepsUpWithTheTimelineAtFiveFramesPerSecond() throws Exception {
        // a slow machine renders rarely; the picture must still track real time instead of drifting behind
        try (VideoDecoder dec = new VideoDecoder(video("colors.mp4"), 0, 1920, 1080, false)) {
            frameAt(dec, 0);
            long start = System.nanoTime();
            long worstLag = 0;
            for (int i = 0; i < 16; i++) {
                Thread.sleep(200);
                long t = (System.nanoTime() - start) / 1_000_000;
                if (t > 3800) break;
                dec.setTimelineHint(t, true);
                VideoDecoder.Frame f = dec.takeFrame(t);
                if (f != null) {
                    if (i > 2) worstLag = Math.max(worstLag, t - f.ptsMs());
                    assertEquals((int) (f.ptsMs() / 1000), segmentOf(rgbAt(f, 160, 90)));
                    dec.recycle(f);
                }
            }
            assertTrue(worstLag <= 120, "picture lagged the timeline by " + worstLag + " ms");
        }
    }

    @Test
    void downscalesBigVideosKeepingAspect() throws Exception {
        try (VideoDecoder dec = new VideoDecoder(video("silent.mp4"), 0, 320, 320, false)) {
            VideoDecoder.Frame f = frameAt(dec, 0);
            assertEquals(320, f.width);
            assertEquals(180, f.height);
            dec.recycle(f);
        }
    }

    private static List<AudioDecoder.Chunk> drain(AudioDecoder dec, long untilMs) throws InterruptedException {
        List<AudioDecoder.Chunk> out = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            assertNull(dec.error(), dec.error());
            AudioDecoder.Chunk c = dec.poll();
            if (c != null) {
                out.add(c);
                if (c.ptsMs() + c.samples() * 1000L / dec.sampleRate() >= untilMs) return out;
            } else if (dec.endOfStream()) {
                return out;
            } else {
                Thread.sleep(2);
            }
        }
        throw new AssertionError("audio stalled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"colors.mp4", "colors.webm", "colors.mov"})
    void decodesContinuousAudio(String name) throws Exception {
        try (AudioDecoder dec = new AudioDecoder(video(name), 0, 2)) {
            List<AudioDecoder.Chunk> chunks = drain(dec, 3900);
            assertFalse(chunks.isEmpty());
            int rate = dec.sampleRate();
            assertTrue(rate == 48000 || rate == 44100, "rate " + rate);
            long expected = chunks.get(0).ptsMs();
            assertTrue(expected <= 30, "audio starts at " + expected);
            long samples = 0;
            for (AudioDecoder.Chunk c : chunks) {
                assertTrue(Math.abs(c.ptsMs() - (chunks.get(0).ptsMs() + samples * 1000 / rate)) <= 25,
                    "gap or overlap at " + c.ptsMs());
                samples += c.samples();
                assertEquals(c.samples() * 4, c.pcm().remaining(), "stereo s16");
            }
            // the tone is there: count zero crossings in the first chunks (~440 Hz)
            ShortBuffer s = chunks.get(3).pcm().order(ByteOrder.nativeOrder()).asShortBuffer();
            int crossings = 0;
            for (int i = 2; i < s.limit(); i += 2) if ((s.get(i - 2) < 0) != (s.get(i) < 0)) crossings++;
            double hz = crossings / 2.0 / (chunks.get(3).samples() / (double) rate);
            assertTrue(Math.abs(hz - 440) < 40, "tone " + hz + " Hz");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"colors.mp4", "colors.webm"})
    void audioSeekStartsOnTheExactSample(String name) throws Exception {
        try (AudioDecoder dec = new AudioDecoder(video(name), 2000, 1)) {
            List<AudioDecoder.Chunk> chunks = drain(dec, 2100);
            assertTrue(Math.abs(chunks.get(0).ptsMs() - 2000) <= 2, "first audio at " + chunks.get(0).ptsMs());
            assertEquals(chunks.get(0).samples() * 2, chunks.get(0).pcm().remaining(), "mono s16");
            dec.seek(700);
            chunks = drain(dec, 800);
            assertNotNull(chunks.get(0));
            assertTrue(Math.abs(chunks.get(0).ptsMs() - 700) <= 2, "after seek " + chunks.get(0).ptsMs());
        }
    }

    @Test
    void reportsUnplayableFiles() throws Exception {
        Path junk = Files.createTempFile("cs", ".mp4");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        try {
            MediaProbe.probe(junk);
            throw new AssertionError("probe accepted garbage");
        } catch (java.io.IOException expected) {
            // fine
        }
        try (VideoDecoder dec = new VideoDecoder(junk, 0, 640, 360, false)) {
            long deadline = System.currentTimeMillis() + 5000;
            while (dec.error() == null && System.currentTimeMillis() < deadline) Thread.sleep(10);
            assertNotNull(dec.error());
        }
    }
}
