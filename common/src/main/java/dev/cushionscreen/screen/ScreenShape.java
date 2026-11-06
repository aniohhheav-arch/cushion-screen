package dev.cushionscreen.screen;

import dev.cushionscreen.block.CushionScreenBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Geometry of a connected screen. Blocks join when they are Cushion Screens facing the same way in the same plane.
 * A screen is drawable when its blocks fill their bounding rectangle exactly. Shared by server (id bookkeeping) and
 * client (rendering), so both always agree on the shape.
 *
 * <p>Plane axes: {@code right} and {@code up} are the directions a viewer standing in front of the screen
 * sees as right and up. The origin is the bottom-left block.
 */
public record ScreenShape(Direction facing, BlockPos origin, int width, int height, boolean complete, List<BlockPos> blocks) {
    public static final int MAX_BLOCKS = 64 * 64;

    public static Direction right(Direction facing) {
        return switch (facing) {
            case UP -> Direction.EAST;
            case DOWN -> Direction.WEST;
            default -> facing.getCounterClockWise();
        };
    }

    public static Direction up(Direction facing) {
        return facing.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    private static int u(BlockPos p, Direction right) {
        return p.getX() * right.getStepX() + p.getY() * right.getStepY() + p.getZ() * right.getStepZ();
    }

    /** Which blocks are screen tiles, and which way they face. */
    public interface Tiles {
        /** The tile's facing, or null if the state is not a screen tile. */
        Direction facingOf(BlockState state);
    }

    public static final Tiles CUSHION_SCREENS = s -> s.getBlock() instanceof CushionScreenBlock ? s.getValue(CushionScreenBlock.FACING) : null;

    /** Flood-fills the screen that contains {@code start}. Returns null when start is not a screen block. */
    public static ScreenShape find(BlockGetter level, BlockPos start) {
        return find(level, start, CUSHION_SCREENS);
    }

    public static ScreenShape find(BlockGetter level, BlockPos start, Tiles tiles) {
        Direction facing = tiles.facingOf(level.getBlockState(start));
        if (facing == null) return null;
        Direction right = right(facing);
        Direction up = up(facing);

        List<BlockPos> blocks = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        Direction[] neighbours = {right, right.getOpposite(), up, up.getOpposite()};
        boolean overflow = false;

        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            blocks.add(p);
            int pu = u(p, right), pv = u(p, up);
            minU = Math.min(minU, pu);
            maxU = Math.max(maxU, pu);
            minV = Math.min(minV, pv);
            maxV = Math.max(maxV, pv);
            if (blocks.size() >= MAX_BLOCKS) {
                overflow = true;
                break;
            }
            for (Direction d : neighbours) {
                BlockPos n = p.relative(d);
                if (seen.contains(n)) continue;
                if (tiles.facingOf(level.getBlockState(n)) == facing) {
                    seen.add(n);
                    queue.add(n);
                }
            }
        }

        int width = maxU - minU + 1;
        int height = maxV - minV + 1;
        boolean complete = !overflow && blocks.size() == width * height;
        BlockPos origin = start
            .relative(right, minU - u(start, right))
            .relative(up, minV - u(start, up));
        return new ScreenShape(facing, origin.immutable(), width, height, complete, List.copyOf(blocks));
    }

    public Direction right() {
        return right(facing);
    }

    public Direction up() {
        return up(facing);
    }

    public boolean isOrigin(BlockPos pos) {
        return origin.equals(pos);
    }

    /** World-space centre of the display surface (on the front face). */
    public net.minecraft.world.phys.Vec3 frontCenter() {
        Direction r = right(), u = up();
        double cx = origin.getX() + 0.5 + (width - 1) * 0.5 * r.getStepX() + (height - 1) * 0.5 * u.getStepX() + 0.5 * facing.getStepX();
        double cy = origin.getY() + 0.5 + (width - 1) * 0.5 * r.getStepY() + (height - 1) * 0.5 * u.getStepY() + 0.5 * facing.getStepY();
        double cz = origin.getZ() + 0.5 + (width - 1) * 0.5 * r.getStepZ() + (height - 1) * 0.5 * u.getStepZ() + 0.5 * facing.getStepZ();
        return new net.minecraft.world.phys.Vec3(cx, cy, cz);
    }

    public net.minecraft.world.phys.AABB bounds() {
        BlockPos far = origin.relative(right(), width - 1).relative(up(), height - 1);
        return new net.minecraft.world.phys.AABB(
            Math.min(origin.getX(), far.getX()), Math.min(origin.getY(), far.getY()), Math.min(origin.getZ(), far.getZ()),
            Math.max(origin.getX(), far.getX()) + 1, Math.max(origin.getY(), far.getY()) + 1, Math.max(origin.getZ(), far.getZ()) + 1);
    }
}
