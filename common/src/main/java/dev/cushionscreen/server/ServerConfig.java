package dev.cushionscreen.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.platform.Platform;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server settings in {@code config/cushionscreen-server.json} (written with defaults on first start). */
public final class ServerConfig {
    /** Pass videos between players through the server, so only one player needs the file. */
    public boolean shareVideos = true;
    /** Largest video the server will pass along. */
    public int maxVideoSizeMB = 4096;
    /** Disk space for the server's copy of shared videos; the least recently used are removed first. */
    public int cacheSizeMB = 16384;
    /** Uploads running at once (each one is a player sending a video to the server). */
    public int maxConcurrentUploads = 2;

    public static ServerConfig load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path f = Platform.INSTANCE.gameDirectory().resolve("config").resolve("cushionscreen-server.json");
        ServerConfig cfg = new ServerConfig();
        try {
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    ServerConfig read = gson.fromJson(r, ServerConfig.class);
                    if (read != null) cfg = read;
                }
            }
            cfg.maxVideoSizeMB = Math.max(1, cfg.maxVideoSizeMB);
            cfg.cacheSizeMB = Math.max(cfg.maxVideoSizeMB, cfg.cacheSizeMB);
            cfg.maxConcurrentUploads = Math.max(1, Math.min(16, cfg.maxConcurrentUploads));
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
