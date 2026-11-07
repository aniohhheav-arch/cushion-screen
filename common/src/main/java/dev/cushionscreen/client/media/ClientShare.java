package dev.cushionscreen.client.media;

import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.client.ClientScreens;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * This client's side of video sharing: asks the server for videos it doesn't have, writes and checks what arrives,
 * and uploads videos it has when the server asks. Everything except the upload thread runs on the client thread;
 * file writes go through the library's background thread in order.
 */
public final class ClientShare {
    private static final long UPLOAD_WINDOW = 2L << 20;   // at most 2 MiB not yet stored by the server
    private static final long ASK_AGAIN_MS = 20_000;

    /** What the screen and menu show while a video is being fetched. */
    public record Progress(ShareState state, long done, long total, String from) {
        public float fraction() {
            return total <= 0 ? 0f : Math.min(1f, done / (float) total);
        }
    }

    private static final Map<String, Progress> progress = new ConcurrentHashMap<>();
    private static final Map<String, Long> askedAt = new HashMap<>();
    private static final Set<String> reported = new HashSet<>();
    private static final Map<String, Incoming> incoming = new HashMap<>();
    private static final Map<String, Outgoing> outgoing = new ConcurrentHashMap<>();

    private static final class Incoming {
        final MediaInfo media;
        final Path partial;
        final FileChannel channel;
        final MessageDigest digest = VideoFingerprint.sha256();
        long expected;      // next offset we accept (client thread)
        volatile long written;
        volatile boolean failed;

        Incoming(MediaInfo media, Path partial) throws IOException {
            this.media = media;
            this.partial = partial;
            this.channel = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static final class Outgoing {
        final String fingerprint;
        volatile long acked;
        volatile boolean cancelled;

        Outgoing(String fingerprint) {
            this.fingerprint = fingerprint;
        }
    }

    private ClientShare() {}

    public static Path downloadFolder() {
        return VideoLibrary.root().resolve("downloads");
    }

    public static @Nullable Progress progress(String fingerprint) {
        return progress.get(fingerprint);
    }

    /** Called while a screen's video is missing here: asks the server for it (not more often than every 20 s). */
    public static void requestIfMissing(MediaInfo media) {
        String fp = media.fingerprint();
        if (incoming.containsKey(fp)) return;
        Progress p = progress.get(fp);
        long now = System.currentTimeMillis();
        Long last = askedAt.get(fp);
        boolean active = p != null && (p.state() == ShareState.DOWNLOADING || p.state() == ShareState.UPLOADING
            || p.state() == ShareState.WAITING_FOR_UPLOAD);
        if (last != null && (active || now - last < ASK_AGAIN_MS)) return;
        askedAt.put(fp, now);
        Platform.INSTANCE.sendToServer(new Payloads.RequestMedia(fp));
    }

    /** Called when this client has a screen's video: tells the server once, so it can ask us to share it. */
    public static void reportHave(String fingerprint) {
        if (reported.add(fingerprint)) Platform.INSTANCE.sendToServer(new Payloads.HaveMedia(fingerprint));
    }

    public static void handle(Payloads.ShareStatus msg) {
        if (incoming.containsKey(msg.fingerprint()) && msg.state() == ShareState.DOWNLOADING) return; // our own count is fresher
        progress.put(msg.fingerprint(), new Progress(msg.state(), msg.done(), msg.total(), msg.from()));
    }

    // ------------------------------------------------------------------ receiving

    private static Optional<MediaInfo> mediaOf(String fp) {
        for (ScreenData d : ClientScreens.all()) {
            if (d.media().isPresent() && d.media().get().fingerprint().equals(fp)) return d.media();
        }
        return Optional.empty();
    }

    public static void handle(Payloads.DownloadPiece msg) {
        String fp = msg.fingerprint();
        Incoming in = incoming.get(fp);
        if (in == null) {
            if (msg.offset() != 0) return;
            Optional<MediaInfo> media = mediaOf(fp);
            if (media.isEmpty() || media.get().sizeBytes() != msg.total() || !VideoFingerprint.isHex64(fp)) return;
            try {
                Files.createDirectories(downloadFolder());
                in = new Incoming(media.get(), downloadFolder().resolve(fp + ".part"));
            } catch (IOException e) {
                CushionScreen.LOG.warn("Cannot save a shared video", e);
                progress.put(fp, new Progress(ShareState.FAILED, 0, msg.total(), ""));
                return;
            }
            incoming.put(fp, in);
        }
        if (in.failed || msg.offset() != in.expected || in.expected + msg.data().length > in.media.sizeBytes()) return;
        in.expected += msg.data().length;
        progress.put(fp, new Progress(ShareState.DOWNLOADING, in.expected, in.media.sizeBytes(), ""));
        Incoming target = in;
        byte[] data = msg.data();
        boolean last = in.expected == in.media.sizeBytes();
        VideoLibrary.ioExecutor().execute(() -> {
            if (target.failed) return;
            try {
                ByteBuffer buf = ByteBuffer.wrap(data);
                while (buf.hasRemaining()) target.channel.write(buf);
                target.digest.update(data);
                target.written += data.length;
                Platform.INSTANCE.sendToServer(new Payloads.DownloadAck(fp, target.written));
                if (last) finish(target);
            } catch (IOException e) {
                target.failed = true;
                CushionScreen.LOG.warn("Writing the shared video failed", e);
                Minecraft.getInstance().execute(() -> fail(fp, target));
            }
        });
    }

    /** On the library thread: check the copy and put it where the library will find it. */
    private static void finish(Incoming in) throws IOException {
        in.channel.close();
        String fp = in.media.fingerprint();
        String hash = HexFormat.of().formatHex(in.digest.digest());
        boolean ok = (in.media.contentHash().isEmpty() || in.media.contentHash().equals(hash)) && VideoFingerprint.of(in.partial).equals(fp);
        if (!ok) {
            Files.deleteIfExists(in.partial);
            CushionScreen.LOG.warn("Shared copy of {} did not match; discarded", in.media.fileName());
            Minecraft.getInstance().execute(() -> fail(fp, in));
            return;
        }
        Path target = downloadFolder().resolve(safeName(in.media.fileName(), fp));
        Files.move(in.partial, target, StandardCopyOption.REPLACE_EXISTING);
        VideoLibrary.remember(fp, target);
        Minecraft.getInstance().execute(() -> {
            incoming.remove(fp);
            progress.remove(fp);
            reportHave(fp);
        });
        CushionScreen.LOG.info("Received shared video {}", in.media.fileName());
    }

    private static void fail(String fp, Incoming in) {
        incoming.remove(fp);
        try {
            in.channel.close();
            Files.deleteIfExists(in.partial);
        } catch (IOException ignored) {
        }
        progress.put(fp, new Progress(ShareState.FAILED, 0, in.media.sizeBytes(), ""));
        askedAt.remove(fp); // allow an immediate new request
    }

    /** A file name that can't escape the folder or clash with another video of the same name. */
    static String safeName(String name, String fp) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        if (clean.isEmpty() || clean.startsWith(".")) clean = "video" + clean;
        int dot = clean.lastIndexOf('.');
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        String ext = dot > 0 ? clean.substring(dot) : ".mp4";
        if (base.length() > 80) base = base.substring(0, 80);
        return base + " [" + fp.substring(0, 8) + "]" + ext;
    }

    // ------------------------------------------------------------------ sending

    public static void handle(Payloads.UploadRequest msg) {
        String fp = msg.fingerprint();
        Optional<MediaInfo> media = mediaOf(fp);
        if (media.isEmpty() || outgoing.containsKey(fp)) return;
        Path[] found = new Path[1];
        if (VideoLibrary.lookup(media.get(), found) != VideoLibrary.Lookup.FOUND) return;
        Outgoing out = new Outgoing(fp);
        outgoing.put(fp, out);
        MediaInfo m = media.get();
        Path file = found[0];
        Thread t = new Thread(() -> upload(out, m, file, msg.offset()), "CushionScreen upload " + m.fileName());
        t.setDaemon(true);
        t.start();
        notice(Component.translatable("cushionscreen.share.sending", m.fileName()));
    }

    public static void handle(Payloads.UploadAck msg) {
        Outgoing out = outgoing.get(msg.fingerprint());
        if (out == null) return;
        if (msg.received() < 0) out.cancelled = true;
        else out.acked = Math.max(out.acked, msg.received());
    }

    private static void upload(Outgoing out, MediaInfo media, Path file, long from) {
        long size = media.sizeBytes();
        long sent = from;
        out.acked = from;
        int lastPercent = -1;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            if (ch.size() != size) return;
            ByteBuffer buf = ByteBuffer.allocate(Payloads.UPLOAD_PIECE);
            while (sent < size && !out.cancelled) {
                if (sent - out.acked >= UPLOAD_WINDOW) {
                    Thread.sleep(2); // wait for the server to catch up
                    continue;
                }
                buf.clear();
                buf.limit((int) Math.min(Payloads.UPLOAD_PIECE, size - sent));
                int n = ch.read(buf, sent);
                if (n <= 0) break;
                byte[] piece = new byte[n];
                buf.flip().get(piece);
                Platform.INSTANCE.sendToServer(new Payloads.UploadPiece(media.fingerprint(), sent, piece));
                sent += n;
                int percent = (int) (sent * 100 / size);
                if (percent / 25 != lastPercent / 25) {
                    lastPercent = percent;
                    if (percent < 100) notice(Component.translatable("cushionscreen.share.sending_progress", media.fileName(), percent));
                }
            }
            if (sent == size && !out.cancelled) notice(Component.translatable("cushionscreen.share.sent", media.fileName()));
        } catch (IOException e) {
            CushionScreen.LOG.warn("Sharing {} failed", media.fileName(), e);
        } catch (InterruptedException ignored) {
        } finally {
            outgoing.remove(out.fingerprint);
        }
    }

    private static void notice(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendOverlayMessage(message);
        });
    }

    /** Leaving the server: stop everything in flight. */
    public static void reset() {
        outgoing.values().forEach(o -> o.cancelled = true);
        outgoing.clear();
        for (Map.Entry<String, Incoming> e : incoming.entrySet()) {
            Incoming in = e.getValue();
            in.failed = true;
            VideoLibrary.ioExecutor().execute(() -> {
                try {
                    in.channel.close();
                    Files.deleteIfExists(in.partial);
                } catch (IOException ignored) {
                }
            });
        }
        incoming.clear();
        progress.clear();
        askedAt.clear();
        reported.clear();
    }
}
