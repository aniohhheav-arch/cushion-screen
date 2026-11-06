package dev.cushionscreen.client;

import dev.cushionscreen.block.CushionScreenBlockEntity;
import dev.cushionscreen.network.Payloads;
import dev.cushionscreen.screen.ScreenData;
import dev.cushionscreen.screen.ScreenShape;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The client's copy of the screens in its dimension (from the server) plus their geometry (computed locally from
 * the loaded blocks, so it is always what the player actually sees).
 */
public final class ClientScreens {
    private static final Map<UUID, ScreenData> screens = new HashMap<>();
    private static final Map<UUID, Long> changedAt = new HashMap<>();
    private static final Set<BlockPos> loadedBlocks = new HashSet<>();
    private static final Map<BlockPos, ScreenShape> shapeByBlock = new HashMap<>();
    private static final Map<UUID, ScreenShape> shapeById = new HashMap<>();
    private static boolean shapesValid;

    private ClientScreens() {}

    public static void clear() {
        screens.clear();
        changedAt.clear();
        loadedBlocks.clear();
        invalidate();
    }

    public static void apply(Payloads.SyncScreens msg) {
        if (msg.replaceAll()) screens.clear();
        long now = System.currentTimeMillis();
        for (ScreenData d : msg.screens()) {
            ScreenData old = screens.put(d.id(), d);
            if (old == null || !old.playback().equals(d.playback()) || old.powered() != d.powered() || !old.media().equals(d.media())) {
                changedAt.put(d.id(), now);
            }
        }
        for (UUID id : msg.removed()) {
            screens.remove(id);
            changedAt.remove(id);
        }
    }

    public static @Nullable ScreenData get(@Nullable UUID id) {
        return id == null ? null : screens.get(id);
    }

    public static Collection<ScreenData> all() {
        return screens.values();
    }

    /** When the screen's playback/power/media last changed (drives the on-screen display). */
    public static long changedAt(UUID id) {
        return changedAt.getOrDefault(id, 0L);
    }

    // ------------------------------------------------------------------ geometry

    public static void blockChanged(BlockPos pos, boolean present) {
        if (present) loadedBlocks.add(pos.immutable());
        else loadedBlocks.remove(pos);
        invalidate();
    }

    public static void invalidate() {
        shapesValid = false;
        shapeByBlock.clear();
        shapeById.clear();
    }

    private static void rebuild() {
        shapesValid = true;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        for (BlockPos pos : loadedBlocks) {
            if (shapeByBlock.containsKey(pos)) continue;
            ScreenShape shape = ScreenShape.find(level, pos);
            if (shape == null) continue;
            for (BlockPos p : shape.blocks()) shapeByBlock.put(p, shape);
            UUID id = idAt(level, shape.origin());
            if (id != null) shapeById.put(id, shape);
        }
    }

    public static @Nullable ScreenShape shapeAt(BlockPos pos) {
        if (!shapesValid) rebuild();
        return shapeByBlock.get(pos);
    }

    public static @Nullable ScreenShape shapeOf(UUID id) {
        if (!shapesValid) rebuild();
        return shapeById.get(id);
    }

    public static @Nullable UUID idAt(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CushionScreenBlockEntity be ? be.screenId() : null;
    }
}
