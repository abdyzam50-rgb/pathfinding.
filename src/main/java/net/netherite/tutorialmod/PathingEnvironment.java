package net.netherite.tutorialmod;

import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

public class PathingEnvironment {

    public static boolean isPassable(ServerWorld world, BlockPos pos, BlockPos start, BlockPos end) {
        if (pos.equals(start) || pos.equals(end) || pos.equals(end.up())) return true;

        BlockState state = world.getBlockState(pos);

        // Fences and walls are never passable (1.5 blocks tall)
        if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock) return false;

        // Fence gates: passable only when open
        if (state.getBlock() instanceof FenceGateBlock) return state.get(FenceGateBlock.OPEN);

        // Doors: passable when open; closed wooden doors treated as passable so the
        // pathfinder can route through them (PathFollower will open them en route)
        if (state.getBlock() instanceof DoorBlock) {
            if (state.get(DoorBlock.OPEN)) return true;
            return !state.getBlock().getTranslationKey().contains("iron");
        }

        // Trapdoors: passable when open
        if (state.getBlock() instanceof TrapdoorBlock) return state.get(TrapdoorBlock.OPEN);

        // Thin decorative blocks are passable
        if (state.getBlock() instanceof CarpetBlock || state.getBlock() instanceof AbstractPressurePlateBlock) return true;

        VoxelShape shape = state.getCollisionShape(world, pos);
        return shape.isEmpty();
    }

    public static boolean isFenceOrWall(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock;
    }

    /** True if this block is a closed door/gate/trapdoor that a player can right-click open. */
    public static boolean isInteractable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) return !state.get(FenceGateBlock.OPEN);
        if (state.getBlock() instanceof TrapdoorBlock) return !state.get(TrapdoorBlock.OPEN);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.get(DoorBlock.OPEN)) return false;
            return !state.getBlock().getTranslationKey().contains("iron");
        }
        return false;
    }

    /** True if the block below pos is solid enough for the player to stand on. */
    public static boolean hasSolidGround(ServerWorld world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState state = world.getBlockState(below);
        if (state.isAir()) return false;
        return getBlockMaxHeight(world, below) > 0.0;
    }

    public static double getBlockMaxHeight(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return 0.0;
        if (state.getBlock() instanceof SlabBlock) {
            return (state.get(SlabBlock.TYPE) == SlabType.BOTTOM) ? 0.5 : 1.0;
        }
        if (state.getBlock() instanceof StairsBlock) return 1.0;
        if (isFenceOrWall(world, pos)) return 1.5;
        if (state.getBlock() instanceof FenceGateBlock) return 1.5;
        return state.isFullCube(world, pos) ? 1.0 : 0.0;
    }
}
