package dev.cushionscreen.client.media;

import dev.cushionscreen.client.ClientClock;
import dev.cushionscreen.client.ClientConfig;
import dev.cushionscreen.client.ClientScreens;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenShape;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Decides, every frame, which screens need a player on this client: a screen is decoded only while it is in view
 * (video) or within hearing range (audio), at most {@code maxActivePlayers} at once, nearest first. Players that
 * are no longer needed are disposed after a short grace period, freeing decoders, textures and audio sources.
 */
public final class PlayerManager {
    private static final long GRACE_MS = 3000;

    /** What the screen shows on this client, for the renderer and the GUI. */
    public enum Status { IDLE, OFF, LOADING_NATIVES, NO_FFMPEG, SEARCHING, DOWNLOADING, MISSING_FILE, OPENING, PLAYING, FAILED }

    private static final class Entry {
        final ScreenPlayer player;
        final String fingerprint;
        Playback lastPlayback;
        long lastNeeded;
        long lastTarget;

        Entry(ScreenPlayer player, String fingerprint, Playback playback) {
            this.player = player;
            this.fingerprint = fingerprint;
            this.lastPlayback = playback;
        }
    }

    private static final Map<UUID, Entry> players = new HashMap<>();
    private static final Map<UUID, Status> statuses = new HashMap<>();
    private static final Set<UUID> localMute = new HashSet<>();
    private static final Map<UUID, Float> localVolume = new HashMap<>();
    private static @Nullable UUID fullscreen;

    private PlayerManager() {}

    public static @Nullable ScreenPlayer player(UUID id) {
        Entry e = players.get(id);
        return e == null ? null : e.player;
    }

    public static Status status(UUID id) {
        return statuses.getOrDefault(id, Status.IDLE);
    }

    public static boolean isMutedLocally(UUID id) {
        return localMute.contains(id);
    }

    public static void setMutedLocally(UUID id, boolean muted) {
        if (muted) localMute.add(id);
        else localMute.remove(id);
    }

    /** This player's own volume for a screen (0..1), on top of the screen's speaker volume. */
    public static float localVolume(UUID id) {
        return localVolume.getOrDefault(id, 1f);
    }

    public static void setLocalVolume(UUID id, float volume) {
        localVolume.put(id, Math.max(0f, Math.min(1f, volume)));
    }

    public static void setFullscreen(@Nullable UUID id) {
        fullscreen = id;
    }

    public static @Nullable UUID fullscreen() {
        return fullscreen;
    }

    public static float audioRange(ScreenShape shape) {
        ClientConfig cfg = ClientConfig.get();
        return cfg.audioRangeBase + cfg.audioRangePerBlock * Math.max(shape.width(), shape.height());
    }

    public static void closeAll() {
        players.values().forEach(e -> e.player.close());
        players.clear();
        statuses.clear();
        fullscreen = null;
    }

    private static long lastShareCheck;

    /**
     * Once a second: for every screen in this dimension, tell the server we have its video, or ask for it. Fetching
     * starts as soon as a screen gets a video, before anyone looks at it.
     */
    private static void checkSharing(long now) {
        if (now - lastShareCheck < 1000) return;
        lastShareCheck = now;
        Set<String> seen = new HashSet<>();
        for (ScreenData d : ClientScreens.all()) {
            if (d.media().isEmpty() || !seen.add(d.media().get().fingerprint())) continue;
            MediaInfo media = d.media().get();
            Path[] found = new Path[1];
            VideoLibrary.Lookup lookup = VideoLibrary.lookup(media, found);
            if (lookup == VideoLibrary.Lookup.FOUND) ClientShare.reportHave(media.fingerprint());
            else if (lookup == VideoLibrary.Lookup.MISSING) ClientShare.requestIfMissing(media);
        }
    }

    /** Called once per rendered frame on the render thread. */
    public static void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!players.isEmpty()) closeAll();
            return;
        }
        long now = System.currentTimeMillis();
        checkSharing(now);
        long clock = ClientClock.nowMs();
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 eye = camera.position();
        Frustum frustum = camera.getCullFrustum();
        ClientConfig cfg = ClientConfig.get();
        float volumeScale = mc.options.getFinalSoundSourceVolume(SoundSource.MASTER); // only Master: other sliders would silently mute screens
        boolean gamePaused = mc.isPaused();

        record Candidate(ScreenData data, ScreenShape shape, double distance, boolean visible, boolean audible) {}
        List<Candidate> wanted = new ArrayList<>();
        statuses.clear();

        for (ScreenData d : ClientScreens.all()) {
            if (d.media().isEmpty()) {
                statuses.put(d.id(), d.powered() ? Status.IDLE : Status.OFF);
                continue;
            }
            if (!d.powered()) {
                statuses.put(d.id(), Status.OFF);
                continue;
            }
            ScreenShape shape = ClientScreens.shapeOf(d.id());
            if (shape == null || !shape.complete()) continue;
            AABB box = shape.bounds();
            double distance = Math.sqrt(box.distanceToSqr(eye));
            boolean isFull = d.id().equals(fullscreen);
            Vec3 toEye = eye.subtract(shape.frontCenter());
            boolean inFront = toEye.dot(Vec3.atLowerCornerOf(shape.facing().getUnitVec3i())) > -0.25;
            boolean visible = isFull || (distance < cfg.maxViewDistance && inFront && frustum != null && frustum.isVisible(box.inflate(0.5)));
            boolean audible = d.audioEnabled() && d.media().get().hasAudio() && distance < audioRange(shape)
                && d.playback().status() == Playback.Status.PLAYING;
            if (visible || audible) wanted.add(new Candidate(d, shape, isFull ? -1 : distance, visible, audible));
        }

        wanted.sort(Comparator.comparingDouble(Candidate::distance));
        Set<UUID> active = new HashSet<>();
        for (Candidate c : wanted) {
            ScreenData d = c.data;
            MediaInfo media = d.media().get();
            if (active.size() >= cfg.maxActivePlayers) break;

            if (!FFmpeg.isReady()) {
                FFmpeg.load();
                statuses.put(d.id(), FFmpeg.hasFailed() ? Status.NO_FFMPEG : Status.LOADING_NATIVES);
                continue;
            }
            Entry e = players.get(d.id());
            if (e != null && !e.fingerprint.equals(media.fingerprint())) {
                e.player.close();
                players.remove(d.id());
                e = null;
            }
            // until the clock is synchronised, hold the last known position rather than guess
            long target = ClientClock.isSynced() ? d.playback().positionAt(clock, d.durationMs()) : d.playback().positionMs();
            if (e == null) {
                Path[] found = new Path[1];
                VideoLibrary.Lookup lookup = VideoLibrary.lookup(media, found);
                if (lookup != VideoLibrary.Lookup.FOUND) {
                    ClientShare.Progress share = ClientShare.progress(media.fingerprint());
                    boolean fetching = share != null && switch (share.state()) {
                        case DOWNLOADING, UPLOADING, WAITING_FOR_UPLOAD -> true;
                        default -> false;
                    };
                    statuses.put(d.id(), fetching ? Status.DOWNLOADING
                        : lookup == VideoLibrary.Lookup.SEARCHING ? Status.SEARCHING : Status.MISSING_FILE);
                    continue;
                }
                e = new Entry(new ScreenPlayer(d.id(), media, found[0], target), media.fingerprint(), d.playback());
                e.lastTarget = target;
                players.put(d.id(), e);
            }
            active.add(d.id());
            e.lastNeeded = now;

            // The server changed the timeline (seek, restart, loop toggle...) in a way that moves the picture.
            if (!e.lastPlayback.equals(d.playback())) {
                long continued = e.lastPlayback.positionAt(clock, d.durationMs());
                if (Math.abs(continued - target) > 300) e.player.hardSeek(target);
                e.lastPlayback = d.playback();
            }
            boolean playing = d.playback().status() == Playback.Status.PLAYING && !gamePaused && ClientClock.isSynced();
            boolean muted = !d.audioEnabled() || localMute.contains(d.id());
            float gain = muted ? 0f : d.volume() * localVolume(d.id()) * volumeScale;
            boolean isFull = d.id().equals(fullscreen);
            e.player.update(target, playing, c.visible, isFull ? null : c.shape.frontCenter(), gain, audioRange(c.shape));
            e.lastTarget = target;

            statuses.put(d.id(), switch (e.player.state()) {
                case OPENING -> Status.OPENING;
                case READY -> Status.PLAYING;
                case FAILED -> Status.FAILED;
            });
        }

        // dispose what nobody needs any more; silence what is in its grace period
        Iterator<Map.Entry<UUID, Entry>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Entry> me = it.next();
            Entry e = me.getValue();
            ScreenData d = ClientScreens.get(me.getKey());
            boolean gone = d == null || d.media().isEmpty() || !d.powered() || !d.media().get().fingerprint().equals(e.fingerprint);
            if (gone || now - e.lastNeeded > GRACE_MS) {
                e.player.close();
                it.remove();
            } else if (!active.contains(me.getKey())) {
                e.player.pauseAudio();
            }
        }
    }
}
