package dev.cushionscreen.server;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.network.Payloads.ShareState;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.share.VideoFingerprint;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Passes videos between players so that only one of them needs the file. When a player is missing a screen's
 * video, the server asks a player who has it to upload it (in the background, with flow control), keeps a copy in
 * {@code <world>/cushionscreen/media/}, and streams it to everyone who needs it. Copies are checked against the
 * full SHA-256 the loading player declared before anyone receives them. All state lives on the server thread;
 * reading files for downloads happens on one background thread.
 */
public final class MediaShare {
    private static final long UPLOAD_ACK_EVERY = 1 << 20;     // acknowledge every 1 MiB
    private static final long DOWNLOAD_WINDOW = 4L << 20;     // at most 4 MiB unacknowledged per download
    private static final long STATUS_EVERY = 2L << 20;        // progress updates every 2 MiB
    private static final long UPLOAD_TIMEOUT_MS = 30_000;
    private static final long DOWNLOAD_TIMEOUT_MS = 60_000;

    private static @Nullable MediaShare instance;

    private final MinecraftServer server;
    private final ServerConfig config;
    private final Path dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CushionScreen media share");
        t.setDaemon(true);
        return t;
    });

    /** Who has each video locally. */
    private final Map<String, Set<UUID>> sources = new HashMap<>();
    /** Who is waiting for each video. */
    private final Map<String, Set<UUID>> waiting = new HashMap<>();
    private final Map<String, Upload> uploads = new HashMap<>();
    private final Map<UUID, Map<String, Download>> downloads = new HashMap<>();
    private final Set<UUID> badSources = new LinkedHashSet<>();
    private int tickCounter;

    private static final class Upload {
        final MediaInfo media;
        final UUID uploader;
        final String uploaderName;
        final Path partial;
        final FileChannel channel;
        final MessageDigest digest = VideoFingerprint.sha256();
        long received;
        long lastAck;
        long lastStatus;
        long lastActivity = System.currentTimeMillis();

        Upload(MediaInfo media, ServerPlayer uploader, Path partial) throws IOException {
            this.media = media;
            this.uploader = uploader.getUUID();
            this.uploaderName = uploader.getGameProfile().name();
            this.partial = partial;
            this.channel = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static final class Download {
        final String fingerprint;
        final Path file;
        final long total;
        volatile long sent;
        volatile long acked;
        volatile boolean cancelled;
        boolean pumping;
        long lastActivity = System.currentTimeMillis();

        Download(String fingerprint, Path file, long total) {
            this.fingerprint = fingerprint;
            this.file = file;
            this.total = total;
        }
    }

    private MediaShare(MinecraftServer server) {
        this.server = server;
        this.config = ServerConfig.load();
        this.dir = server.getWorldPath(LevelResource.ROOT).resolve("cushionscreen").resolve("media");
        try {
            Files.createDirectories(dir);
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".part")).forEach(p -> p.toFile().delete());
            }
        } catch (IOException e) {
            CushionScreen.LOG.warn("Could not prepare {}", dir, e);
        }
    }

    public static void start(MinecraftServer server) {
        stop();
        instance = new MediaShare(server);
    }

    public static void stop() {
        if (instance != null) {
            instance.uploads.values().forEach(u -> closeQuietly(u.channel));
            instance.downloads.values().forEach(m -> m.values().forEach(d -> d.cancelled = true));
            instance.io.shutdownNow();
            instance = null;
        }
    }

    public static @Nullable MediaShare get() {
        return instance;
    }

    private Path cached(String fingerprint) {
        return dir.resolve(fingerprint + ".video");
    }

    private void status(UUID player, String fp, ShareState state, long done, long total, String from) {
        ServerPlayer p = server.getPlayerList().getPlayer(player);
        if (p != null) Platform.INSTANCE.sendToPlayer(p, new Payloads.ShareStatus(fp, state, done, total, from));
    }

    private void statusToWaiting(String fp, ShareState state, long done, long total, String from) {
        for (UUID id : waiting.getOrDefault(fp, Set.of())) status(id, fp, state, done, total, from);
    }

    /** The media description of a fingerprint, from any screen in any dimension. */
    private Optional<MediaInfo> findMedia(String fp) {
        if (!VideoFingerprint.isHex64(fp)) return Optional.empty();
        for (ServerLevel level : server.getAllLevels()) {
            for (ScreenData d : ScreenRegistry.get(level).all()) {
                if (d.media().isPresent() && d.media().get().fingerprint().equals(fp)) return d.media();
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ requests from players

    /** The player who loads a video has it. */
    public void noteLoaded(ServerPlayer player, MediaInfo media) {
        sources.computeIfAbsent(media.fingerprint(), k -> new LinkedHashSet<>()).add(player.getUUID());
        badSources.remove(player.getUUID());
    }

    public void handleHave(ServerPlayer player, Payloads.HaveMedia msg) {
        if (findMedia(msg.fingerprint()).isEmpty()) return;
        sources.computeIfAbsent(msg.fingerprint(), k -> new LinkedHashSet<>()).add(player.getUUID());
        if (!waiting.getOrDefault(msg.fingerprint(), Set.of()).isEmpty()) ensureUpload(msg.fingerprint());
    }

    public void handleRequest(ServerPlayer player, Payloads.RequestMedia msg) {
        String fp = msg.fingerprint();
        Optional<MediaInfo> media = findMedia(fp);
        if (media.isEmpty()) return; // only videos that are on a screen
        MediaInfo m = media.get();
        if (!config.shareVideos) {
            status(player.getUUID(), fp, ShareState.DISABLED, 0, m.sizeBytes(), "");
            return;
        }
        if (m.sizeBytes() > (long) config.maxVideoSizeMB << 20) {
            status(player.getUUID(), fp, ShareState.TOO_LARGE, 0, m.sizeBytes(), "");
            return;
        }
        if (isCached(m)) {
            startDownload(player, m);
            return;
        }
        waiting.computeIfAbsent(fp, k -> new LinkedHashSet<>()).add(player.getUUID());
        ensureUpload(fp);
    }

    private boolean isCached(MediaInfo m) {
        try {
            Path f = cached(m.fingerprint());
            return Files.isRegularFile(f) && Files.size(f) == m.sizeBytes();
        } catch (IOException e) {
            return false;
        }
    }

    /** Makes sure someone is uploading a video that players are waiting for, or tells them nobody can. */
    private void ensureUpload(String fp) {
        Upload running = uploads.get(fp);
        if (running != null) {
            statusToWaiting(fp, ShareState.UPLOADING, running.received, running.media.sizeBytes(), running.uploaderName);
            return;
        }
        Optional<MediaInfo> media = findMedia(fp);
        if (media.isEmpty()) {
            waiting.remove(fp);
            return;
        }
        if (uploads.size() >= config.maxConcurrentUploads) {
            statusToWaiting(fp, ShareState.WAITING_FOR_UPLOAD, 0, media.get().sizeBytes(), "");
            return; // the tick retries when a slot frees up
        }
        Set<UUID> wanting = waiting.getOrDefault(fp, Set.of());
        for (UUID id : sources.getOrDefault(fp, Set.of())) {
            if (wanting.contains(id) || badSources.contains(id)) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            try {
                Upload u = new Upload(media.get(), p, dir.resolve(fp + "." + id + ".part"));
                uploads.put(fp, u);
                Platform.INSTANCE.sendToPlayer(p, new Payloads.UploadRequest(fp, 0));
                statusToWaiting(fp, ShareState.WAITING_FOR_UPLOAD, 0, media.get().sizeBytes(), u.uploaderName);
                return;
            } catch (IOException e) {
                CushionScreen.LOG.warn("Could not start receiving {}", fp, e);
                return;
            }
        }
        statusToWaiting(fp, ShareState.NO_SOURCE, 0, media.get().sizeBytes(), "");
    }

    public void handleUploadPiece(ServerPlayer player, Payloads.UploadPiece msg) {
        Upload u = uploads.get(msg.fingerprint());
        if (u == null || !u.uploader.equals(player.getUUID())) {
            Platform.INSTANCE.sendToPlayer(player, new Payloads.UploadAck(msg.fingerprint(), -1)); // not expected: stop
            return;
        }
        byte[] data = msg.data();
        if (msg.offset() != u.received || data.length == 0 || u.received + data.length > u.media.sizeBytes()) {
            abortUpload(u, "out-of-order or oversized piece");
            return;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(data);
            while (buf.hasRemaining()) u.channel.write(buf);
        } catch (IOException e) {
            abortUpload(u, "disk write failed: " + e.getMessage());
            return;
        }
        u.digest.update(data);
        u.received += data.length;
        u.lastActivity = System.currentTimeMillis();
        long size = u.media.sizeBytes();
        if (u.received - u.lastAck >= UPLOAD_ACK_EVERY || u.received == size) {
            u.lastAck = u.received;
            Platform.INSTANCE.sendToPlayer(player, new Payloads.UploadAck(u.media.fingerprint(), u.received));
        }
        if (u.received - u.lastStatus >= STATUS_EVERY) {
            u.lastStatus = u.received;
            statusToWaiting(u.media.fingerprint(), ShareState.UPLOADING, u.received, size, u.uploaderName);
        }
        if (u.received == size) finishUpload(u);
    }

    private void finishUpload(Upload u) {
        String fp = u.media.fingerprint();
        uploads.remove(fp);
        closeQuietly(u.channel);
        String hash = HexFormat.of().formatHex(u.digest.digest());
        try {
            boolean hashOk = u.media.contentHash().isEmpty() || u.media.contentHash().equals(hash);
            if (!hashOk || !VideoFingerprint.of(u.partial).equals(fp)) {
                Files.deleteIfExists(u.partial);
                badSources.add(u.uploader);
                CushionScreen.LOG.warn("Upload of {} from {} did not match the video; trying another player", u.media.fileName(), u.uploaderName);
                ensureUpload(fp);
                return;
            }
            Files.move(u.partial, cached(fp), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CushionScreen.LOG.warn("Could not store {}", u.media.fileName(), e);
            statusToWaiting(fp, ShareState.FAILED, 0, u.media.sizeBytes(), "");
            waiting.remove(fp);
            return;
        }
        CushionScreen.LOG.info("Received {} ({} MB) from {}; sharing it", u.media.fileName(), u.media.sizeBytes() >> 20, u.uploaderName);
        Set<UUID> wanting = waiting.remove(fp);
        if (wanting != null) {
            for (UUID id : wanting) {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                if (p != null) startDownload(p, u.media);
            }
        }
        evict(fp);
    }

    private void abortUpload(Upload u, String reason) {
        String fp = u.media.fingerprint();
        uploads.remove(fp);
        closeQuietly(u.channel);
        try {
            Files.deleteIfExists(u.partial);
        } catch (IOException ignored) {
        }
        ServerPlayer p = server.getPlayerList().getPlayer(u.uploader);
        if (p != null) Platform.INSTANCE.sendToPlayer(p, new Payloads.UploadAck(fp, -1));
        badSources.add(u.uploader);
        CushionScreen.LOG.info("Stopped receiving {} from {}: {}", u.media.fileName(), u.uploaderName, reason);
        ensureUpload(fp); // another player may have it
    }

    /** In-game tests: pretend someone is waiting for a video, which makes the server ask a source to upload it. */
    public void debugAddWaiter(String fingerprint, UUID waiter) {
        waiting.computeIfAbsent(fingerprint, k -> new LinkedHashSet<>()).add(waiter);
        ensureUpload(fingerprint);
    }

    public Path cacheFolder() {
        return dir;
    }

    // ------------------------------------------------------------------ sending to players

    private void startDownload(ServerPlayer player, MediaInfo m) {
        Path file = cached(m.fingerprint());
        file.toFile().setLastModified(System.currentTimeMillis()); // recently used: evicted last
        Map<String, Download> mine = downloads.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Download old = mine.get(m.fingerprint());
        if (old != null) old.cancelled = true;
        Download d = new Download(m.fingerprint(), file, m.sizeBytes());
        mine.put(m.fingerprint(), d);
        status(player.getUUID(), m.fingerprint(), ShareState.DOWNLOADING, 0, m.sizeBytes(), "");
        pump(player, d);
    }

    public void handleDownloadAck(ServerPlayer player, Payloads.DownloadAck msg) {
        Download d = downloads.getOrDefault(player.getUUID(), Map.of()).get(msg.fingerprint());
        if (d == null) return;
        d.acked = Math.max(d.acked, Math.min(msg.received(), d.sent));
        d.lastActivity = System.currentTimeMillis();
        if (d.acked >= d.total) {
            downloads.get(player.getUUID()).remove(msg.fingerprint());
            return;
        }
        pump(player, d);
    }

    /** Sends pieces until 4 MiB are unacknowledged; reading happens off the server thread. */
    private void pump(ServerPlayer player, Download d) {
        if (d.pumping || d.cancelled) return;
        d.pumping = true;
        io.execute(() -> {
            try (FileChannel ch = FileChannel.open(d.file, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(Payloads.DOWNLOAD_PIECE);
                while (!d.cancelled && d.sent < d.total && d.sent - d.acked < DOWNLOAD_WINDOW) {
                    buf.clear();
                    buf.limit((int) Math.min(Payloads.DOWNLOAD_PIECE, d.total - d.sent));
                    int n = ch.read(buf, d.sent);
                    if (n <= 0) {
                        d.cancelled = true; // the stored copy is shorter than it should be
                        break;
                    }
                    byte[] piece = new byte[n];
                    buf.flip().get(piece);
                    Platform.INSTANCE.sendToPlayer(player, new Payloads.DownloadPiece(d.fingerprint, d.sent, d.total, piece));
                    d.sent += n;
                }
            } catch (IOException e) {
                d.cancelled = true;
                CushionScreen.LOG.warn("Could not send {} to {}", d.fingerprint, player.getGameProfile().name(), e);
            } finally {
                server.execute(() -> {
                    d.pumping = false;
                    // acknowledgements that arrived meanwhile may have opened the window again
                    if (!d.cancelled && d.sent < d.total && d.sent - d.acked < DOWNLOAD_WINDOW) pump(player, d);
                });
            }
        });
    }

    // ------------------------------------------------------------------ housekeeping

    public void playerLeft(UUID player) {
        sources.values().forEach(s -> s.remove(player));
        waiting.values().forEach(s -> s.remove(player));
        Map<String, Download> mine = downloads.remove(player);
        if (mine != null) mine.values().forEach(d -> d.cancelled = true);
        for (Upload u : List.copyOf(uploads.values())) {
            if (u.uploader.equals(player)) {
                uploads.remove(u.media.fingerprint());
                closeQuietly(u.channel);
                u.partial.toFile().delete();
                ensureUpload(u.media.fingerprint());
            }
        }
        badSources.remove(player);
    }

    public void tick() {
        if (++tickCounter % 20 != 0) return;
        long now = System.currentTimeMillis();
        for (Upload u : List.copyOf(uploads.values())) {
            if (now - u.lastActivity > UPLOAD_TIMEOUT_MS) abortUpload(u, "no data for 30 seconds");
        }
        for (Map<String, Download> mine : downloads.values()) {
            Iterator<Download> it = mine.values().iterator();
            while (it.hasNext()) {
                Download d = it.next();
                if (now - d.lastActivity > DOWNLOAD_TIMEOUT_MS) {
                    d.cancelled = true;
                    it.remove();
                }
            }
        }
        // waiting for an upload slot or a source that has since come online
        for (String fp : new ArrayList<>(waiting.keySet())) {
            if (waiting.get(fp).isEmpty()) waiting.remove(fp);
            else if (!uploads.containsKey(fp) && tickCounter % 100 == 0) ensureUpload(fp);
        }
    }

    /** Keeps the cache under its size limit, removing the least recently used videos first. */
    private void evict(String keep) {
        long limit = (long) config.cacheSizeMB << 20;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".video")).toList());
            files.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
            long total = 0;
            for (Path p : files) total += Files.size(p);
            for (Path p : files) {
                if (total <= limit) break;
                if (p.getFileName().toString().startsWith(keep)) continue;
                total -= Files.size(p);
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            CushionScreen.LOG.warn("Could not tidy the shared-video cache", e);
        }
    }

    private static void closeQuietly(FileChannel ch) {
        try {
            ch.close();
        } catch (IOException ignored) {
        }
    }
}
