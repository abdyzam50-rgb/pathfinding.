package com.abdy2.aotvpathfinder.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;


import java.util.ArrayList;
import java.util.List;

/**
 * Post-processes sprint paths: when a sprint-jump landing has no floor but the player
 * can place a block there, converts the node to {@link PathNode.Type#BOOST_PLACE}.
 */
public final class BoostPlaceInjector {

    private BoostPlaceInjector() {}

    public static List<PathNode> inject(List<PathNode> path, Level world, boolean hasBlocks) {
        if (!hasBlocks || path == null || path.isEmpty()) return path;

        List<PathNode> out = new ArrayList<>(path.size());
        for (PathNode node : path) {
            if (node.type == PathNode.Type.SPRINT_JUMP) {
                BlockPos ground = node.pos.below();
                if (world.getBlockState(ground).isAir()) {
                    out.add(new PathNode(node.pos, node.precisePos, node.parent,
                            PathNode.Type.BOOST_PLACE, ground));
                    continue;
                }
            }
            out.add(node);
        }
        return out;
    }

    /**
     * Try to add boost-place sprint jumps during neighbor expansion when normal
     * sprint-jump fails due to missing ground at landing.
     */
    public static PathNode tryBoostPlaceNeighbor(Level world, BlockPos from, int dx, int dz,
                                                  int dist, PathNode current) {
        BlockPos landing = from.offset(dx * dist, 0, dz * dist);
        BlockPos ground  = landing.below();
        if (!world.getBlockState(ground).isAir()) return null;
        // Would this jump work if we placed a block at ground?
        if (!PathfinderEngine.canSprintJumpToWithPlacedGround(
                world, from, dx, dz, dist, ground, from, landing)) {
            return null;
        }
        Vec3 precise = new Vec3(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        return new PathNode(landing, precise, current, PathNode.Type.BOOST_PLACE, ground);
    }
}
