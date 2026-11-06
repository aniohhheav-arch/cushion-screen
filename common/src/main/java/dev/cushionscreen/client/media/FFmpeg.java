package dev.cushionscreen.client.media;

import dev.cushionscreen.CushionScreen;
import java.util.concurrent.CompletableFuture;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.global.swresample;
import org.bytedeco.ffmpeg.global.swscale;
import org.bytedeco.javacpp.Loader;

/** Loads the bundled FFmpeg native libraries once, off the render thread. */
public final class FFmpeg {
    private static volatile CompletableFuture<Boolean> loading;
    private static volatile String failure;

    private FFmpeg() {}

    public static CompletableFuture<Boolean> load() {
        CompletableFuture<Boolean> f = loading;
        if (f != null) return f;
        synchronized (FFmpeg.class) {
            if (loading == null) {
                loading = CompletableFuture.supplyAsync(() -> {
                    try {
                        long start = System.nanoTime();
                        Loader.load(avutil.class);
                        Loader.load(swresample.class);
                        Loader.load(swscale.class);
                        Loader.load(avcodec.class);
                        Loader.load(avformat.class);
                        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
                        CushionScreen.LOG.info("FFmpeg {} ready on {} ({} ms)", avutil.av_version_info().getString(),
                            Loader.getPlatform(), (System.nanoTime() - start) / 1_000_000);
                        return true;
                    } catch (Throwable t) {
                        failure = t.getClass().getSimpleName() + ": " + t.getMessage();
                        CushionScreen.LOG.error("Could not load FFmpeg natives for {}; video playback is disabled", Loader.getPlatform(), t);
                        return false;
                    }
                }, r -> {
                    Thread t = new Thread(r, "CushionScreen FFmpeg loader");
                    t.setDaemon(true);
                    t.start();
                });
            }
            return loading;
        }
    }

    public static boolean isReady() {
        CompletableFuture<Boolean> f = loading;
        return f != null && f.isDone() && Boolean.TRUE.equals(f.getNow(false));
    }

    public static boolean hasFailed() {
        return failure != null;
    }

    public static String failure() {
        return failure;
    }

    /** FFmpeg's message for an error code. */
    public static String error(int code) {
        byte[] buf = new byte[256];
        avutil.av_strerror(code, buf, buf.length);
        int len = 0;
        while (len < buf.length && buf[len] != 0) len++;
        return new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }
}
