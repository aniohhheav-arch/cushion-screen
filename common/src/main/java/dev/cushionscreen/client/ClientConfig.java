package dev.cushionscreen.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.platform.Platform;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-computer settings in {@code config/cushionscreen-client.json}. */
public final class ClientConfig {
    /** Largest texture a video is decoded to; bigger videos are scaled down (keeping aspect ratio). */
    public int maxVideoWidth = 1920;
    public int maxVideoHeight = 1080;
    /** Try GPU video decoding (D3D11VA, VideoToolbox, VA-API, ...) and fall back to software. */
    public boolean hardwareDecoding = true;
    /** At most this many screens decode at once; the nearest win. */
    public int maxActivePlayers = 6;
    /** Screens farther than this (blocks) are not decoded even when in view. */
    public int maxViewDistance = 160;
    /** Audible distance = base + per-block x the screen's larger side. */
    public float audioRangeBase = 16f;
    public float audioRangePerBlock = 2f;

    private static ClientConfig instance;

    public static ClientConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path file() {
        return Platform.INSTANCE.gameDirectory().resolve("config").resolve("cushionscreen-client.json");
    }

    private static ClientConfig load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path f = file();
        ClientConfig cfg = new ClientConfig();
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    ClientConfig read = gson.fromJson(r, ClientConfig.class);
                    if (read != null) cfg = read;
                }
            }
            cfg.maxVideoWidth = Math.max(160, Math.min(7680, cfg.maxVideoWidth));
            cfg.maxVideoHeight = Math.max(90, Math.min(4320, cfg.maxVideoHeight));
            cfg.maxActivePlayers = Math.max(1, Math.min(32, cfg.maxActivePlayers));
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f)) {
                gson.toJson(cfg, w);
            }
        } catch (Exception e) {
            CushionScreen.LOG.warn("Could not read {}; using defaults", f, e);
        }
        return cfg;
    }
}
