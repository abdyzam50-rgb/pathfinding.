package com.abdy2.aotvpathfinder.parkour;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class PathingEnvironment {

    /**
     * Checks if a block position is passable for pathfinding.
     * Takes into account block properties like fences, walls, open doors, etc.
     * Also considers if doors can be opened by the player.
     */
    public static boolean isPassable(Level world, BlockPos pos, BlockPos start, BlockPos end) {
        if (pos.equals(start) || pos.equals(end) || pos.equals(end.above())) return true;

        BlockState state = world.getBlockState(pos);

        // Climbable blocks (ladders, vines, scaffolding) have a thin wall-side collision
        // shape that is NOT empty, but a player can freely occupy the block space.
        if (isClimbable(world, pos)) return true;

        // Fences and walls are not passable (too high to walk through)
        if (isFenceOrWall(world, pos)) {
            return false;
        }
        
        // Check if it's an open door or trapdoor
        if (isOpenDoorOrTrapdoor(state)) {
            return true;
        }
        
        // Check if it's a closed door that can be opened
        if (state.getBlock() instanceof DoorBlock && canOpenDoor(world, pos)) {
            return true;
        }
        
        // Check if it's a closed trapdoor that can be opened
        if (state.getBlock() instanceof TrapDoorBlock && canOpenTrapdoor(world, pos)) {
            return true;
        }
        
        // Check if it's a carpet or pressure plate (thin, walkable)
        if (isThinWalkable(state)) {
            return true;
        }
        
        // Check collision shape
        VoxelShape collisionShape = state.getCollisionShape(world, pos);
        return collisionShape.isEmpty();
    }

    /**
     * Checks if a block is a fence, wall, or fence gate (closed).
     */
    public static boolean isFenceOrWall(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock) {
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock) {
            // Fence gates are only blocking when closed
            return !state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }
    
    /**
     * Checks if a block is a fence gate.
     */
    public static boolean isFenceGate(Level world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof FenceGateBlock;
    }
    
    /**
     * Checks if a fence gate is open.
     */
    public static boolean isFenceGateOpen(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) {
            return state.getValue(FenceGateBlock.OPEN);
        }
        return false;
    }
    
    /**
     * Checks if a fence or wall can be jumped over from the given direction.
     * Fences and walls are 1.5 blocks high, so they can't be normally jumped over.
     */
    public static boolean canJumpOverFence(Level world, BlockPos fencePos, Direction approachDirection) {
        // Fences and walls are too high to jump over normally (1.5 blocks)
        // But if there's a block next to it at the same height, we can step over
        BlockPos adjacentPos = fencePos.relative(approachDirection.getOpposite());
        BlockState adjacentState = world.getBlockState(adjacentPos);
        
        // If there's a solid block next to the fence at similar height, we can step over
        double adjacentHeight = getBlockMaxHeight(world, adjacentPos);
        double fenceHeight = getBlockMaxHeight(world, fencePos);
        
        return Math.abs(adjacentHeight - fenceHeight) < 0.5;
    }

    /**
     * Checks if a block is an open door or trapdoor.
     */
    private static boolean isOpenDoorOrTrapdoor(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        if (state.getBlock() instanceof TrapDoorBlock) {
            return state.getValue(TrapDoorBlock.OPEN);
        }
        return false;
    }
    
    /**
     * Checks if a door can be opened by a player.
     * Wooden doors can be opened, iron doors require redstone.
     */
    public static boolean canOpenDoor(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return false;
        }
        
        // If already open, it's passable
        if (state.getValue(DoorBlock.OPEN)) {
            return true;
        }
        
        // Check door material - wooden doors can be opened, iron doors cannot
        // Iron doors need redstone, wooden doors can be opened by players
        // Check if it's an iron door by checking the block name
        String blockName = state.getBlock().getDescriptionId();
        return !blockName.contains("iron");
    }
    
    /**
     * Checks if a trapdoor can be opened by a player.
     */
    public static boolean canOpenTrapdoor(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof TrapDoorBlock)) {
            return false;
        }
        
        // If already open, it's passable
        if (state.getValue(TrapDoorBlock.OPEN)) {
            return true;
        }
        
        // Most trapdoors can be opened by players
        return true;
    }
    
    /**
     * Checks if a block is thin and walkable (carpets, pressure plates, etc.).
     */
    private static boolean isThinWalkable(BlockState state) {
        return state.getBlock() instanceof CarpetBlock ||
               state.getBlock() instanceof BasePressurePlateBlock ||
               state.getBlock() instanceof RailBlock ||
               state.getBlock() instanceof TripWireHookBlock ||
               state.getBlock() instanceof TripWireBlock;
    }

    /**
     * Gets the effective height of a block for pathfinding purposes.
     * Takes into account slab types, stair directions, etc.
     */
    public static double getBlockMaxHeight(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return 0.0;
        
        // Slabs - different heights based on type
        if (state.getBlock() instanceof SlabBlock) {
            SlabType type = state.getValue(SlabBlock.TYPE);
            return switch (type) {
                case BOTTOM -> 0.5;
                case TOP -> 1.0;
                case DOUBLE -> 1.0;
            };
        }
        
        // Stairs - height depends on direction of travel
        if (state.getBlock() instanceof StairBlock) {
            return getStairEffectiveHeight(state);
        }
        
        // Fences and walls are 1.5 blocks high
        if (isFenceOrWall(world, pos)) return 1.5;
        
        // Soul sand is slightly shorter (14/16 = 0.875)
        if (state.is(BlockTags.SOUL_SPEED_BLOCKS)) return 0.875;
        
        // Honey blocks are full height but slow
        if (state.getBlock() instanceof HoneyBlock) return 1.0;
        
        // Default: use actual collision shape max height so non-full solids
        // (chests, anvils, slabs used as ceilings, etc.) report the right value.
        VoxelShape defShape = state.getCollisionShape(world, pos);
        if (!defShape.isEmpty()) return defShape.max(Direction.Axis.Y);
        return 0.0;
    }
    
    /**
     * Gets the effective height of a stair block based on travel direction.
     * Walking up stairs: effective height is 0.5 (half step)
     * Walking down stairs: effective height is 1.0 (full block)
     */
    public static double getStairEffectiveHeight(BlockState stairState) {
        Direction facing = stairState.getValue(StairBlock.FACING);
        StairsShape shape = stairState.getValue(StairBlock.SHAPE);
        
        // For simplicity, stairs are treated as full height for collision
        // but the pathfinder should prefer walking up stairs from the correct direction
        return 1.0;
    }
    
    /**
     * Gets the movement speed multiplier for a block.
     * Returns 1.0 for normal blocks, < 1.0 for slow blocks, > 1.0 for fast blocks.
     */
    public static double getMovementSpeedMultiplier(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        
        // Soul sand slows movement
        if (state.is(BlockTags.SOUL_SPEED_BLOCKS)) return 0.4;
        
        // Honey blocks slow movement significantly
        if (state.getBlock() instanceof HoneyBlock) return 0.4;
        
        // Ice and packed ice are faster
        if (state.getBlock() instanceof IceBlock) return 1.4;
        if (state.is(BlockTags.ICE)) return 1.4; // Covers packed ice and blue ice
        
        // Slime blocks bounce but don't affect horizontal speed
        if (state.getBlock() instanceof SlimeBlock) return 1.0;
        
        // Cobwebs are extremely slow
        if (state.getBlock() instanceof WebBlock) return 0.05;
        
        // Water and lava
        if (state.getFluidState().is(FluidTags.WATER)) return 0.2;
        if (state.getFluidState().is(FluidTags.LAVA)) return 0.1;
        
        return 1.0;
    }
    
    /**
     * True if block is a closed door/gate/trapdoor the player can right-click open.
     */
    public static boolean isInteractable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) return !state.getValue(FenceGateBlock.OPEN);
        if (state.getBlock() instanceof TrapDoorBlock) return !state.getValue(TrapDoorBlock.OPEN);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN)) return false;
            return !state.getBlock().getDescriptionId().contains("iron");
        }
        return false;
    }

    /**
     * True if block is a closed door/fence gate that can be opened (not iron doors).
     * Used by the pathfinder to route through openable barriers.
     */
    public static boolean isClosedOpenableDoor(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) return !state.getValue(FenceGateBlock.OPEN);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN)) return false;
            return !state.getBlock().getDescriptionId().contains("iron");
        }
        return false;
    }

    /**
     * Returns true if there is a block with a solid top surface directly below {@code pos},
     * meaning a player standing in {@code pos} would have solid footing.
     *
     * Used by PathLimiters and PathfinderEngine as the shared anti-cliff ground check.
     * A height threshold of 0.5 blocks includes bottom-slabs but excludes buttons/rails.
     */
    public static boolean hasSolidGround(Level world, BlockPos pos) {
        BlockPos below = pos.below();
        if (world.getBlockState(below).isAir()) return false;
        return getBlockMaxHeight(world, below) >= 0.5;
    }

    /**
     * Checks if a block is climbable (ladders, vines, twisting vines, etc.).
     */
    public static boolean isClimbable(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof LadderBlock ||
               state.getBlock() instanceof VineBlock ||
               state.getBlock() instanceof TwistingVinesBlock ||
               state.getBlock() instanceof WeepingVinesBlock ||
               state.getBlock() instanceof ScaffoldingBlock ||
               state.is(BlockTags.CLIMBABLE);
    }
    
    /**
     * True for blocks > 1 block tall that cannot be jumped onto: fences and walls.
     * Used to prevent canJumpTo from treating fence tops as valid landing spots.
     */
    public static boolean isTallObstacle(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof FenceBlock
            || state.getBlock() instanceof WallBlock;
    }

    /**
     * True for blocks that damage the player on contact: lava, fire, cactus, berry bush.
     */
    public static boolean isHazardous(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA)) return true;
        Block b = state.getBlock();
        return b instanceof BaseFireBlock
            || b instanceof CactusBlock
            || b instanceof SweetBerryBushBlock;
    }

    /**
     * Gets the direction a stair is facing for pathfinding optimization.
     * Returns the direction the stair "faces" (the direction you walk up).
     */
    public static Direction getStairFacing(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof StairBlock) {
            return state.getValue(StairBlock.FACING);
        }
        return Direction.UP; // Not a stair
    }
    
    /**
     * Checks if walking from one block to another is valid considering stair direction.
     * Walking up stairs from the correct direction is easier.
     */
    public static boolean canWalkUpStairs(Level world, BlockPos fromPos, BlockPos toPos) {
        BlockState toState = world.getBlockState(toPos);
        if (!(toState.getBlock() instanceof StairBlock)) return true;
        
        Direction stairFacing = toState.getValue(StairBlock.FACING);
        
        // Calculate approach direction
        int dx = toPos.getX() - fromPos.getX();
        int dz = toPos.getZ() - fromPos.getZ();
        Direction approachDirection;
        if (Math.abs(dx) > Math.abs(dz)) {
            approachDirection = dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            approachDirection = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        
        // Can walk up stairs more easily from the front
        return stairFacing == approachDirection.getOpposite();
    }
    
    /**
     * Checks if a block has a solid top surface for standing on.
     */
    public static boolean hasSolidTopSurface(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        
        // Full blocks have solid tops
        if (state.isCollisionShapeFullBlock(world, pos)) return true;
        
        // Bottom slabs have solid tops
        if (state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) return true;
        
        // Farmland and similar blocks
        if (state.getBlock() instanceof FarmlandBlock) return true;
        
        // Check VoxelShape for top face
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (!shape.isEmpty()) {
            return shape.max(Direction.Axis.Y) >= 0.5;
        }
        
        return false;
    }
}