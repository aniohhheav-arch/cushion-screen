package dev.cushionscreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cushionscreen.screen.ScreenShape;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** How screen blocks fuse into one display, for every orientation. */
class ScreenShapeTest {
    // The registries are frozen outside a running game, so a vanilla six-way block stands in for the screen tile.
    private static final ScreenShape.Tiles TILES =
        s -> s.is(Blocks.OBSERVER) ? s.getValue(net.minecraft.world.level.block.ObserverBlock.FACING) : null;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** A tiny world of block states. */
    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> states = new HashMap<>();

        void screen(BlockPos p, Direction facing) {
            states.put(p.immutable(), Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING, facing));
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }

    /** Builds a w x h wall in the plane of {@code facing}, starting at {@code start} and growing right/up. */
    private static World wall(Direction facing, BlockPos start, int w, int h) {
        World world = new World();
        Direction r = ScreenShape.right(facing), u = ScreenShape.up(facing);
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) world.screen(start.relative(r, i).relative(u, j), facing);
        return world;
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void axesFormARightHandedFrameFacingTheViewer(Direction facing) {
        Direction r = ScreenShape.right(facing), u = ScreenShape.up(facing);
        // right x up must point out of the screen, towards the viewer, or the picture would be mirrored
        Vec3 cross = Vec3.atLowerCornerOf(r.getUnitVec3i()).cross(Vec3.atLowerCornerOf(u.getUnitVec3i()));
        assertEquals(Vec3.atLowerCornerOf(facing.getUnitVec3i()), cross.add(0, 0, 0)); // +0 turns -0.0 into 0.0
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void fullRectangleIsOneDisplayFromAnyBlock(Direction facing) {
        BlockPos start = new BlockPos(10, 70, -3);
        World world = wall(facing, start, 4, 3);
        for (BlockPos p : world.states.keySet()) {
            ScreenShape s = ScreenShape.find(world, p, TILES);
            assertTrue(s.complete());
            assertEquals(4, s.width());
            assertEquals(3, s.height());
            assertEquals(start, s.origin(), "origin is the bottom-left as the viewer sees it");
            assertEquals(12, s.blocks().size());
        }
    }

    @Test
    void lShapeIsIncomplete() {
        World world = wall(Direction.NORTH, BlockPos.ZERO, 3, 2);
        world.states.remove(new BlockPos(-2, 1, 0)); // top-right as seen from the north (right = west)
        ScreenShape s = ScreenShape.find(world, BlockPos.ZERO, TILES);
        assertFalse(s.complete());
        assertEquals(5, s.blocks().size());
    }

    @Test
    void differentFacingsDoNotJoin() {
        World world = wall(Direction.NORTH, BlockPos.ZERO, 2, 1);
        world.screen(new BlockPos(-2, 0, 0), Direction.SOUTH); // in line, but facing the other way
        assertEquals(2, ScreenShape.find(world, BlockPos.ZERO, TILES).blocks().size());
    }

    @Test
    void blocksBehindTheScreenDoNotJoin() {
        World world = wall(Direction.UP, BlockPos.ZERO, 2, 2);
        world.screen(new BlockPos(0, -1, 0), Direction.UP); // stacked below: same facing, different plane
        assertEquals(4, ScreenShape.find(world, BlockPos.ZERO, TILES).blocks().size());
    }

    @Test
    void cinemaSizedScreen() {
        World world = wall(Direction.EAST, new BlockPos(0, 64, 0), 48, 27);
        ScreenShape s = ScreenShape.find(world, new BlockPos(0, 80, -20), TILES);
        assertTrue(s.complete());
        assertEquals(48, s.width());
        assertEquals(27, s.height());
        Vec3 c = s.frontCenter();
        assertEquals(1.0, c.x, 1e-9, "front face of an east-facing wall at x=0 is x=1");
        assertEquals(64 + 13.5, c.y, 1e-9);
    }

    @Test
    void notAScreen() {
        assertNull(ScreenShape.find(new World(), BlockPos.ZERO, TILES));
    }
}
