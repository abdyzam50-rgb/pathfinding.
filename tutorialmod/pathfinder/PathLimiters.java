package net.netherite.tutorialmod.pathfinder;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class PathLimiters {

    /**
     * Checks that the single block position {@code pos} (and its head block) is
     * passable and has solid ground, with extra validation against full-cube walls.
     * Used for the centre-column anti-cliff guard inside {@link #executeBresenhamCheck}.
     */
    public static boolean isPhysicalSpacePassable(ServerWorld world, BlockPos pos, BlockPos start, BlockPos end) {
        if (!PathingEnvironment.isPassable(world, pos, start, end) ||
                !PathingEnvironment.isPassable(world, pos.up(), start, end)) {
            return false;
        }

        // Structural wall limitation checks (allows stairs and slabs)
        if (!world.getBlockState(pos).isAir() && world.getBlockState(pos).isFullCube(world, pos)) {
            return false;
        }

        // ANTI-CLIFF SAFEGUARD: reject positions without solid footing.
        if (!PathingEnvironment.hasSolidGround(world, pos)) {
            return false;
        }

        return true;
    }

    /**
     * Validates that the player's full 0.6×1.8 hitbox can travel in a straight line
     * from {@code p1} to {@code p2} without clipping any solid block.
     *
     * Samples at 0.25-block intervals (half hitbox width) and checks all 4 XZ corners
     * (±0.3) at both the feet and head block at every sample — identical logic to
     * {@code PathfinderEngine.isHitboxSegmentClear()} used for client worlds.
     *
     * Anti-cliff: the centre block under each sample must also have solid ground.
     */
    public static boolean executeBresenhamCheck(ServerWorld world, BlockPos p1, BlockPos p2,
                                                BlockPos start, BlockPos end) {
        if (p1.getY() != p2.getY()) return false; // only flat WALK segments supported

        double fromCx = p1.getX() + 0.5, fromCz = p1.getZ() + 0.5;
        double toCx   = p2.getX() + 0.5, toCz   = p2.getZ() + 0.5;
        double segDx  = toCx - fromCx,   segDz   = toCz - fromCz;
        double dist   = Math.sqrt(segDx * segDx + segDz * segDz);
        int    y      = p1.getY();

        int steps = (int) Math.ceil(dist / 0.25);
        if (steps < 1) steps = 1;

        // 4 XZ hitbox corners (offsets from player centre)
        final double[] ox = {-0.3,  0.3,  0.3, -0.3};
        final double[] oz = {-0.3, -0.3,  0.3,  0.3};

        for (int s = 1; s <= steps; s++) {
            double t  = (double) s / steps;
            double cx = fromCx + segDx * t;
            double cz = fromCz + segDz * t;

            // Anti-cliff: solid ground required under centre column
            BlockPos centre = new BlockPos((int) Math.floor(cx), y, (int) Math.floor(cz));
            if (!PathingEnvironment.hasSolidGround(world, centre)) return false;

            // Hitbox corners: feet + head block at each corner
            for (int k = 0; k < 4; k++) {
                int bx = (int) Math.floor(cx + ox[k]);
                int bz = (int) Math.floor(cz + oz[k]);
                if (!PathingEnvironment.isPassable(world, new BlockPos(bx, y,     bz), start, end)) return false;
                if (!PathingEnvironment.isPassable(world, new BlockPos(bx, y + 1, bz), start, end)) return false;
            }
        }
        return true;
    }
}
