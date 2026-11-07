package dev.cushionscreen.client.media;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.client.ClientConfig;
import dev.cushionscreen.screen.MediaInfo;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Plays one video for one screen on this client: owns its decoders, its GPU texture and its OpenAL source, and
 * keeps all three on the shared timeline. Created on demand by {@link PlayerManager} and disposed as soon as the
 * screen is neither visible nor audible.
 */
public final class ScreenPlayer implements AutoCloseable {
    public enum State { OPENING, READY, FAILED }

    private final UUID screenId;
    private final MediaInfo media;
    private final Path file;
    private VideoDecoder video;
    private final AudioDecoder audioDecoder;
    private AudioOutput audio;
    private VideoTexture texture;
    private boolean closed;

    private long presentedMs = -1;
    private int frameCount;
    private long lastSeekAt;
    private boolean videoActive = true;
    private int avgColor = 0xFF101418;
    private long firstFrameAt = -1;
    private long audioRetryAt;
    private long lastAudioSeekAt;
    private long lastAudioWarnAt;
    private boolean triedSoftwareFallback;

    public ScreenPlayer(UUID screenId, MediaInfo media, Path file, long startMs) {
        this.screenId = screenId;
        this.media = media;
        this.file = file;
        ClientConfig cfg = ClientConfig.get();
        // land a little ahead: decoding the first frames takes time while the timeline keeps moving
        long start = Math.max(0, startMs);
        this.video = new VideoDecoder(file, start, cfg.maxVideoWidth, cfg.maxVideoHeight, cfg.hardwareDecoding);
        int channels = AudioOutput.supportsStereoSpatialization() ? 2 : 1;
        this.audioDecoder = media.hasAudio() ? new AudioDecoder(file, start, channels) : null;
    }

    public UUID screenId() {
        return screenId;
    }

    public MediaInfo media() {
        return media;
    }

    public Path file() {
        return file;
    }

    public State state() {
        if (closed || (video.error() != null && triedSoftwareFallback)) return State.FAILED;
        if (video.error() != null) return State.OPENING;
        return video.isOpened() && frameCount > 0 ? State.READY : State.OPENING;
    }

    public String error() {
        return video.error();
    }

    public String decoderName() {
        return video.decoderName();
    }

    public VideoTexture texture() {
        return texture;
    }

    /** Average colour of the latest frame (ARGB), for the glow around the screen. */
    public int averageColor() {
        return avgColor;
    }

    /** Milliseconds since the first frame appeared, for the fade-in. */
    public long msSinceFirstFrame() {
        return firstFrameAt < 0 ? -1 : System.currentTimeMillis() - firstFrameAt;
    }

    public long presentedMs() {
        return presentedMs;
    }

    /**
     * Called every rendered frame.
     *
     * @param visible   whether any part of the screen is in view (video is only decoded then)
     * @param audioPos  where the sound comes from, or null for non-positional sound (fullscreen)
     * @param gain      final gain, already including mute and the game's volume sliders
     */
    public void update(long targetMs, boolean playing, boolean visible, Vec3 audioPos, float gain, float range) {
        if (closed) return;
        long now = System.currentTimeMillis();

        // A hardware decoder that fails (driver quirk, unsupported profile) gets one retry in software.
        if (video.error() != null && !triedSoftwareFallback) {
            triedSoftwareFallback = true;
            if (ClientConfig.get().hardwareDecoding) {
                CushionScreen.LOG.info("Retrying {} with software decoding ({})", media.fileName(), video.error());
                video.close();
                ClientConfig cfg = ClientConfig.get();
                video = new VideoDecoder(file, targetMs, cfg.maxVideoWidth, cfg.maxVideoHeight, false);
                video.setActive(videoActive);
                if (texture != null) {
                    texture.release();
                    texture = null;
                }
                frameCount = 0;
            }
        }

        // ---- video: decode only while visible; on returning to view, jump straight to the timeline.
        if (visible != videoActive) {
            videoActive = visible;
            video.setActive(visible);
            if (visible) seekVideo(targetMs, now);
        }
        if (visible && video.isOpened()) {
            if (texture == null && video.outputWidth() > 0) {
                texture = new VideoTexture(CushionScreen.id("video/" + screenId), video.outputWidth(), video.outputHeight());
            }
            video.setTimelineHint(targetMs, playing);
            VideoDecoder.Frame frame = video.takeFrame(targetMs);
            if (frame != null) {
                if (texture != null) texture.upload(frame.pixels);
                presentedMs = frame.ptsMs();
                if (frameCount++ == 0) firstFrameAt = now;
                if ((frameCount & 7) == 1) avgColor = sampleAverage(frame);
                video.recycle(frame);
            }
            // Way behind (slow machine, or the timeline jumped) or the timeline went backwards: resync.
            long next = video.peekNextMs();
            long newest = Math.max(presentedMs, video.lastDecodedMs());
            boolean behind = playing && newest >= 0 && newest < targetMs - 1500;
            boolean backwards = presentedMs >= 0 && targetMs < presentedMs - 400 && (next < 0 || next > targetMs + 400);
            boolean jumpedAhead = !playing && next >= 0 && next > targetMs + 400 && presentedMs > targetMs + 400;
            if ((behind || backwards || jumpedAhead) && now - lastSeekAt > 400) seekVideo(targetMs + (behind ? 300 : 0), now);
        }

        // ---- audio (never allowed to take rendering down with it; any failure is retried shortly)
        if (audioDecoder != null && audioDecoder.isOpened() && now >= audioRetryAt) {
            try {
                if (audio != null && !audio.isValid()) {
                    audio.close(); // sound engine restarted (device change, F3+T): make a new source
                    audio = null;
                }
                if (audio == null) audio = new AudioOutput(audioDecoder.sampleRate(), audioDecoder.channels());
                AudioOutput.Result r = audio.update(audioDecoder, targetMs, playing && gain > 0.0001f, audioPos, gain, range);
                if (r == AudioOutput.Result.RESEEK && now - lastAudioSeekAt > 750) {
                    lastAudioSeekAt = now;
                    audioDecoder.seek(targetMs);
                }
            } catch (Throwable t) {
                if (audio != null) audio.close();
                audio = null;
                audioRetryAt = now + 2000;
                if (now - lastAudioWarnAt > 30_000) {
                    lastAudioWarnAt = now;
                    CushionScreen.LOG.warn("Sound for {} paused, retrying: {}", media.fileName(), t.toString());
                }
            }
        }
    }

    private void seekVideo(long targetMs, long now) {
        lastSeekAt = now;
        video.seek(targetMs);
    }

    /** Called when the server timeline jumps (seek, restart, loop wrap). */
    public void hardSeek(long targetMs) {
        seekVideo(targetMs, System.currentTimeMillis());
        presentedMs = -1;
        if (audioDecoder != null) {
            audioDecoder.seek(targetMs);
            lastAudioSeekAt = System.currentTimeMillis();
            if (audio != null) audio.flush();
        }
    }

    public void pauseAudio() {
        if (audio != null) audio.pause();
    }

    private static int sampleAverage(VideoDecoder.Frame f) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        int stepX = Math.max(1, f.width / 24), stepY = Math.max(1, f.height / 14);
        for (int y = stepY / 2; y < f.height; y += stepY) {
            for (int x = stepX / 2; x < f.width; x += stepX) {
                int i = (y * f.width + x) * 4;
                r += f.pixels.get(i) & 0xFF;
                g += f.pixels.get(i + 1) & 0xFF;
                b += f.pixels.get(i + 2) & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0xFF101418;
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        video.close();
        if (audioDecoder != null) audioDecoder.close();
        if (audio != null) audio.close();
        if (texture != null) texture.release();
    }
}
