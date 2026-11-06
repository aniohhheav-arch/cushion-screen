package dev.cushionscreen.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cushionscreen.CushionScreen;
import dev.cushionscreen.block.CushionScreenBlock;
import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.platform.Platform;
import dev.cushionscreen.screen.MediaInfo;
import dev.cushionscreen.screen.Playback;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenPermission;
import dev.cushionscreen.screen.ScreenShape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Per-dimension authority over every screen: their settings, media and timeline, and which blocks form which
 * screen. Invariant: one screen id belongs to exactly one connected group of blocks. Placements and removals are
 * queued and resolved once per tick so building a wall of screens merges it once, not once per block.
 */
public final class ScreenRegistry extends SavedData {
    private static final Codec<ScreenRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
        ScreenData.CODEC.listOf().fieldOf("screens").forGetter(r -> List.copyOf(r.screens.values()))
    ).apply(i, ScreenRegistry::new));

    // Saved-data files need a datafix type; command storage's fixes never touch our keys.
    public static final SavedDataType<ScreenRegistry> TYPE = new SavedDataType<>(
        CushionScreen.id("screens"), ScreenRegistry::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private static final double MAX_INTERACT_DISTANCE_SQ = 64 * 64;

    private final Map<UUID, ScreenData> screens = new LinkedHashMap<>();
    private transient ServerLevel level;
    private final transient Set<BlockPos> pendingPlaced = new LinkedHashSet<>();
    private final transient List<Removed> pendingRemoved = new ArrayList<>();
    private final transient Map<BlockPos, Player> placers = new HashMap<>();
    private final transient Set<UUID> changed = new LinkedHashSet<>();
    private final transient Set<UUID> removedIds = new LinkedHashSet<>();
    /** One known block of each screen, used to find its blocks again (for lighting). */
    private final transient Map<UUID, BlockPos> anchors = new HashMap<>();
    private final transient Map<UUID, long[]> rateWindows = new HashMap<>();

    private record Removed(BlockPos pos, Direction facing, @Nullable UUID id) {}

    public ScreenRegistry() {}

    private ScreenRegistry(List<ScreenData> list) {
        list.forEach(d -> screens.put(d.id(), d));
    }

    public static ScreenRegistry get(ServerLevel level) {
        ScreenRegistry r = level.getDataStorage().computeIfAbsent(TYPE);
        r.level = level;
        return r;
    }

    public Optional<ScreenData> screen(UUID id) {
        return Optional.ofNullable(screens.get(id));
    }

    public List<ScreenData> all() {
        return List.copyOf(screens.values());
    }

    // ------------------------------------------------------------------ structure

    public void notePlacer(BlockPos pos, Player player) {
        placers.put(pos.immutable(), player);
    }

    public void queuePlaced(BlockPos pos) {
        pendingPlaced.add(pos.immutable());
    }

    public void queueRemoved(BlockPos pos, Direction facing, @Nullable UUID id) {
        pendingRemoved.add(new Removed(pos.immutable(), facing, id));
    }

    public void tick() {
        if (!pendingRemoved.isEmpty()) {
            List<Removed> removals = new ArrayList<>(pendingRemoved);
            pendingRemoved.clear();
            removals.forEach(this::resolveRemoval);
        }
        if (!pendingPlaced.isEmpty()) {
            List<BlockPos> placed = new ArrayList<>(pendingPlaced);
            pendingPlaced.clear();
            Set<BlockPos> done = new HashSet<>();
            for (BlockPos pos : placed) {
                if (!done.contains(pos)) resolvePlacement(pos, done);
            }
            placers.clear();
        }
        long clock = MediaClock.nowMs();
        for (ScreenData d : List.copyOf(screens.values())) {
            if (d.playback().hasEnded(clock, d.durationMs())) {
                // Hold the last frame instead of jumping back to the start.
                put(d.withPlayback(new Playback(Playback.Status.PAUSED, d.durationMs(), clock, false)));
            }
        }
        flush();
    }

    private void resolvePlacement(BlockPos pos, Set<BlockPos> done) {
        ScreenShape shape = ScreenShape.find(level, pos);
        if (shape == null) return;
        done.addAll(shape.blocks());

        Map<UUID, Integer> counts = new HashMap<>();
        for (BlockPos p : shape.blocks()) {
            UUID id = idAt(p);
            if (id != null && screens.containsKey(id)) counts.merge(id, 1, Integer::sum);
        }
        UUID winner = counts.keySet().stream()
            .max(Comparator.<UUID>comparingInt(id -> screens.get(id).media().isPresent() ? 1 : 0)
                .thenComparingInt(counts::get))
            .orElse(null);
        if (winner == null) {
            Player placer = shape.blocks().stream().map(placers::get).filter(Objects::nonNull).findFirst().orElse(null);
            ScreenData created = ScreenData.create(UUID.randomUUID(),
                placer == null ? null : placer.getUUID(), placer == null ? "" : placer.getGameProfile().name());
            put(created);
            winner = created.id();
        }
        for (UUID loser : counts.keySet()) {
            if (!loser.equals(winner)) delete(loser);
        }
        assign(shape, winner);
    }

    private void resolveRemoval(Removed r) {
        Direction right = ScreenShape.right(r.facing), up = ScreenShape.up(r.facing);
        List<ScreenShape> parts = new ArrayList<>();
        Set<BlockPos> covered = new HashSet<>();
        for (Direction d : new Direction[]{right, right.getOpposite(), up, up.getOpposite()}) {
            BlockPos n = r.pos.relative(d);
            if (covered.contains(n)) continue;
            BlockState s = level.getBlockState(n);
            if (s.getBlock() instanceof CushionScreenBlock && s.getValue(CushionScreenBlock.FACING) == r.facing) {
                ScreenShape part = ScreenShape.find(level, n);
                if (part != null) {
                    parts.add(part);
                    covered.addAll(part.blocks());
                }
            }
        }
        if (r.id == null) return;
        if (parts.isEmpty()) {
            delete(r.id);
            return;
        }
        // The largest remaining part keeps the screen (and its video); the others become new screens.
        parts.sort(Comparator.comparingInt((ScreenShape s) -> s.blocks().size()).reversed());
        ScreenData original = screens.get(r.id);
        assign(parts.get(0), r.id);
        for (int i = 1; i < parts.size(); i++) {
            UUID fresh = UUID.randomUUID();
            if (original != null) put(original.copyAs(fresh));
            else put(ScreenData.create(fresh, null, ""));
            assign(parts.get(i), fresh);
        }
        if (original != null) changed.add(r.id); // its size changed; clients re-evaluate lighting/geometry
    }

    private void assign(ScreenShape shape, UUID id) {
        anchors.put(id, shape.origin());
        for (BlockPos p : shape.blocks()) {
            if (level.getBlockEntity(p) instanceof CushionScreenBlockEntity be) be.setScreenId(id);
        }
        updateLighting(id, shape);
    }

    private @Nullable UUID idAt(BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof CushionScreenBlockEntity screen ? screen.screenId() : null;
    }

    private void put(ScreenData data) {
        screens.put(data.id(), data);
        changed.add(data.id());
        removedIds.remove(data.id());
        setDirty();
    }

    private void delete(UUID id) {
        if (screens.remove(id) != null) {
            removedIds.add(id);
            changed.remove(id);
            anchors.remove(id);
            setDirty();
        }
    }

    private void updateLighting(UUID id, @Nullable ScreenShape known) {
        ScreenData d = screens.get(id);
        if (d == null) return;
        ScreenShape shape = known;
        if (shape == null) {
            BlockPos anchor = anchors.get(id);
            if (anchor == null || !level.isLoaded(anchor)) return;
            shape = ScreenShape.find(level, anchor);
            if (shape == null) return;
        }
        boolean lit = d.powered() && d.media().isPresent();
        for (BlockPos p : shape.blocks()) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof CushionScreenBlock && s.getValue(CushionScreenBlock.LIT) != lit) {
                level.setBlock(p, s.setValue(CushionScreenBlock.LIT, lit), 3);
            }
        }
    }

    /** Sends everything that changed this tick to the players in this dimension. */
    private void flush() {
        if (changed.isEmpty() && removedIds.isEmpty()) return;
        List<ScreenData> updates = changed.stream().map(screens::get).filter(Objects::nonNull).toList();
        Payloads.SyncScreens msg = new Payloads.SyncScreens(false, updates, List.copyOf(removedIds));
        changed.clear();
        removedIds.clear();
        for (ServerPlayer player : level.players()) Platform.INSTANCE.sendToPlayer(player, msg);
    }

    public void sendFullSync(ServerPlayer player) {
        List<ScreenData> list = all();
        int batch = 200; // keeps every packet far below the payload size limit
        if (list.isEmpty()) {
            Platform.INSTANCE.sendToPlayer(player, new Payloads.SyncScreens(true, List.of(), List.of()));
            return;
        }
        for (int i = 0; i < list.size(); i += batch) {
            Platform.INSTANCE.sendToPlayer(player,
                new Payloads.SyncScreens(i == 0, list.subList(i, Math.min(list.size(), i + batch)), List.of()));
        }
    }

    // ------------------------------------------------------------------ player requests

    /** Resolves the screen a request refers to, checking reach and rate limits. */
    private @Nullable ScreenData target(ServerPlayer player, BlockPos pos) {
        if (player.level() != level || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > MAX_INTERACT_DISTANCE_SQ) return null;
        if (!level.isLoaded(pos)) return null;
        long now = System.currentTimeMillis();
        long[] window = rateWindows.computeIfAbsent(player.getUUID(), k -> new long[]{now, 0});
        if (now - window[0] >= 1000) {
            window[0] = now;
            window[1] = 0;
        }
        if (++window[1] > 20) return null; // at most 20 requests per second per player
        UUID id = idAt(pos);
        if (id == null) return null;
        anchors.put(id, pos.immutable());
        return screens.get(id);
    }

    private static boolean isOperator(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static boolean isOwner(ServerPlayer player, ScreenData d) {
        return d.owner().isEmpty() || d.owner().get().equals(player.getUUID());
    }

    public static boolean mayControl(ServerPlayer player, ScreenData d) {
        return switch (d.permission()) {
            case EVERYONE -> true;
            case OWNER -> isOwner(player, d) || isOperator(player);
            case OPERATORS -> isOperator(player);
        };
    }

    public static boolean mayConfigure(ServerPlayer player, ScreenData d) {
        return isOwner(player, d) || isOperator(player);
    }

    private void deny(ServerPlayer player) {
        Platform.INSTANCE.sendToPlayer(player, new Payloads.Notice("cushionscreen.notice.no_permission"));
    }

    public void handleAction(ServerPlayer player, Payloads.ScreenAction msg) {
        ScreenData d = target(player, msg.pos());
        if (d == null) return;
        if (!mayControl(player, d)) {
            deny(player);
            return;
        }
        long clock = MediaClock.nowMs();
        long duration = d.durationMs();
        Playback p = d.playback();
        if (d.media().isEmpty() && msg.action() != Payloads.Action.SET_LOOP) return;
        Playback next = switch (msg.action()) {
            case PLAY -> p.play(clock, duration);
            case PAUSE -> p.pause(clock, duration);
            case STOP -> p.stop();
            case SEEK -> p.seek(clock, msg.value(), duration);
            case SET_LOOP -> p.withLoop(msg.value() != 0, clock, duration);
            case EJECT -> null;
        };
        if (next == null) {
            put(d.withMedia(Optional.empty(), Playback.STOPPED));
            updateLighting(d.id(), null);
        } else {
            put(d.withPlayback(next));
        }
        flush();
    }

    public void handleLoad(ServerPlayer player, Payloads.LoadMedia msg) {
        ScreenData d = target(player, msg.pos());
        if (d == null) return;
        if (!mayControl(player, d)) {
            deny(player);
            return;
        }
        MediaInfo media = msg.media();
        if (!media.isValid()) {
            CushionScreen.LOG.warn("Rejected invalid media description from {}", player.getGameProfile().name());
            return;
        }
        long clock = MediaClock.nowMs();
        Playback fresh = new Playback(Playback.Status.PAUSED, 0, clock, d.playback().loop());
        if (msg.autoplay()) fresh = fresh.play(clock, media.durationMs());
        put(d.withMedia(Optional.of(media), fresh));
        updateLighting(d.id(), null);
        flush();
    }

    public void handleConfigure(ServerPlayer player, Payloads.Configure msg) {
        ScreenData d = target(player, msg.pos());
        if (d == null) return;
        if (!mayConfigure(player, d)) {
            deny(player);
            return;
        }
        String name = msg.name().strip();
        if (name.length() > ScreenData.MAX_NAME) name = name.substring(0, ScreenData.MAX_NAME);
        name = name.chars().filter(c -> c >= 0x20 && c != 0x7f && c != '§')
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        float brightness = clamp(msg.brightness(), 0.1f, 1f);
        float volume = clamp(msg.volume(), 0f, 1f);
        ScreenPermission permission = msg.permission();
        boolean wasLit = d.powered() && d.media().isPresent();
        ScreenData updated = d.withSettings(name, permission, msg.powered(), brightness, msg.audioEnabled(), volume, msg.rotation());
        if (!msg.powered() && d.playback().status() == Playback.Status.PLAYING) {
            updated = updated.withPlayback(d.playback().pause(MediaClock.nowMs(), d.durationMs()));
        }
        put(updated);
        if (wasLit != (updated.powered() && updated.media().isPresent())) updateLighting(d.id(), null);
        flush();
    }

    private static float clamp(float v, float min, float max) {
        return Float.isNaN(v) ? max : Math.max(min, Math.min(max, v));
    }
}
