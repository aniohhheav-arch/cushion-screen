package dev.cushionscreen.client.media;

import java.util.List;
import java.util.Locale;

/** FFmpeg hardware decoder device types worth trying on this OS, best first. Unavailable ones are skipped. */
final class HardwareDecoding {
    private HardwareDecoding() {}

    static List<String> candidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return List.of("d3d11va", "dxva2", "cuda");
        if (os.contains("mac")) return List.of("videotoolbox");
        return List.of("vaapi", "cuda", "vdpau");
    }
}
