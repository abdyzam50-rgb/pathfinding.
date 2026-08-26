package net.netherite.tutorialmod.pathfinder;

import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced physics simulation engine for parkour pathfinding.
 * Implements exact Minecraft player movement physics tick-by-tick,
 * including gravity, air drag, sprint boosts, and precise collision detection
 * with sub-block fractional AABB support for chains, candles, fences, etc.
 * 
 * Physics Constants (Minecraft 1.21+):
 * - Air acceleration: 0.02 (walking) to 0.026 (sprinting)
 * - Horizontal air drag: 0.91 per tick
 * - Jump impulse: +0.42 blocks/tick vertical velocity
 * - Sprint jump boost: +0.2 blocks/tick forward vector
 * - Gravity: 0.08 subtracted from vy per tick
 * - Vertical drag: 0.98 applied after gravity
 */
public class PathPhysics {

    // ==================== Minecraft Physics Constants ====================

    /** Gravity applied per tick (subtracted from vy) */
    public static final double GRAVITY = 0.08;

    /** Air drag multiplier applied to horizontal velocity */
    public static final double AIR_DRAG = 0.91;

    /** Vertical drag applied after gravity */
    public static final double VERTICAL_DRAG = 0.98;

    /** Initial jump impulse velocity (blocks/tick) */
    public static final double JUMP_VELOCITY = 0.42;

    /** Sprint jump forward boost (blocks/tick) */
    public static final double SPRINT_JUMP_BOOST = 0.2;

    /** Air acceleration when sprinting */
    public static final double AIR_ACCELERATION_SPRINT = 0.026;

    /** Air acceleration when walking */
    public static final double AIR_ACCELERATION_WALK = 0.02;

    /** Ground friction multiplier */
    public static final double GROUND_FRICTION = 0.546;

    /** Player bounding box half-width */
    public static final double PLAYER_HALF_WIDTH = 0.3;

    /** Player bounding box height */
    public static final double PLAYER_HEIGHT = 1.8;

    /** Player width for collision (0.6 blocks) */
    public static final double PLAYER_WIDTH = 0.6;

    /** Maximum ticks to simulate for a single jump trajectory */
    public static final int MAX_JUMP_TICKS = 30;

    /** Tolerance for ground detection */
    public static final double GROUND_TOLERANCE = 0.001;

    // ==================== Micro-block Hitbox Definitions ====================

    /**
     * Represents the precise bounding box of a micro-block element.
     * All coordinates are relative to the block's origin (0-1 range).
     */
    public static class MicroBlockBB {
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;

        public MicroBlockBB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        /** Chain: 0.125 x 1.0 x 0.125 centered */
        public static final MicroBlockBB CHAIN = new MicroBlockBB(0.4375, 0.0, 0.4375, 0.5625, 1.0, 0.5625);

        /** Candle: 0.125 x 0.375 x 0.125 centered */
        public static final MicroBlockBB CANDLE = new MicroBlockBB(0.4375, 0.0, 0.4375, 0.5625, 0.375, 0.5625);

        /** Fence/Wall: 0.25 x 1.5 x 0.25 centered (1.5 height for wall top) */
        public static final MicroBlockBB FENCE = new MicroBlockBB(0.375, 0.0, 0.375, 0.625, 1.5, 0.625);

        /** Iron Bars: similar to fence but shorter */
        public static final MicroBlockBB IRON_BARS = new MicroBlockBB(0.375, 0.0, 0.375, 0.625, 1.0, 0.625);

        /** Glass Pane: thin vertical pane */
        public static final MicroBlockBB GLASS_PANE = new MicroBlockBB(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0);

        /**
         * Transforms this local BB to world coordinates.
         */
        public Box toWorldBox(BlockPos blockPos) {
            return new Box(
                    blockPos.getX() + minX, blockPos.getY() + minY, blockPos.getZ() + minZ,
                    blockPos.getX() + maxX, blockPos.getY() + maxY, blockPos.getZ() + maxZ
            );
        }
    }

    // ==================== Core Physics Simulation ====================

    /**
     * Simulates one tick of Minecraft player physics in the air.
     * This mirrors Minecraft's internal movement code exactly.
     *
     * @param current The current physical state
     * @param input   The movement input for this tick
     * @return The new physical state after one tick
     */
    public static ParkourNode simulateTick(ParkourNode current, MovementInput input) {
        ParkourNode next = new ParkourNode(
                current.x, current.y, current.z,
                current.vx, current.vy, current.vz,
                current.yaw,
                input.sprinting,
                input.sneaking,
                current.ticksInAir + 1,
                current,
                input
        );

        // Select air acceleration based on sprinting state
        double acceleration = input.sprinting ? AIR_ACCELERATION_SPRINT : AIR_ACCELERATION_WALK;

        // 1. Calculate horizontal intent based on inputs (W, A, S, D keys)
        double strafe = input.strafe;
        double forward = input.forward;

        // 2. Apply Minecraft Air Acceleration Math
        double factor = strafe * strafe + forward * forward;
        if (factor >= 1.0E-4) {
            factor = Math.sqrt(factor);
            if (factor < 1.0) factor = 1.0;
            factor = acceleration / factor;
            strafe *= factor;
            forward *= factor;

            double sinYaw = Math.sin(current.yaw * Math.PI / 180.0);
            double cosYaw = Math.cos(current.yaw * Math.PI / 180.0);

            next.vx = current.vx + (strafe * cosYaw - forward * sinYaw);
            next.vz = current.vz + (forward * cosYaw + strafe * sinYaw);
        }

        // 3. Apply Gravity and Vertical Movement
        if (current.ticksInAir == 0 && input.jumping) {
            // Initial jump from ground
            next.vy = JUMP_VELOCITY;
            if (input.sprinting) {
                // Apply sprint jump forward vector boost using sine/cosine yaw components
                float rad = current.yaw * 0.017453292F; // degrees to radians
                next.vx -= Math.sin(rad) * SPRINT_JUMP_BOOST;
                next.vz += Math.cos(rad) * SPRINT_JUMP_BOOST;
            }
        } else if (current.ticksInAir > 0) {
            // In air: apply gravity then vertical drag
            next.vy = (current.vy - GRAVITY) * VERTICAL_DRAG;
        } else {
            // On ground but not jumping: no vertical movement
            next.vy = 0;
        }

        // 4. Update Position
        next.x = current.x + next.vx;
        next.y = current.y + next.vy;
        next.z = current.z + next.vz;

        // 5. Apply Air Drag to Horizontal Velocity
        next.vx *= AIR_DRAG;
        next.vz *= AIR_DRAG;

        return next;
    }

    /**
     * Simulates one tick with mid-air yaw interpolation for neo jumps.
     * Allows gradual turning during flight to navigate around obstacles.
     *
     * @param current     The current physical state
     * @param input       The movement input for this tick
     * @param targetYaw   The target yaw to interpolate towards
     * @param turnRate    Maximum degrees to turn per tick
     * @return The new physical state after one tick
     */
    public static ParkourNode simulateTickWithYawInterpolation(ParkourNode current, MovementInput input,
                                                                float targetYaw, double turnRate) {
        ParkourNode modified = current.copy();

        // Interpolate yaw towards target
        float yawDiff = normalizeYaw(targetYaw - current.yaw);
        if (Math.abs(yawDiff) > turnRate) {
            modified.yaw = current.yaw + (float) (Math.signum(yawDiff) * turnRate);
        } else {
            modified.yaw = targetYaw;
        }

        return simulateTick(modified, input);
    }

    /**
     * Normalizes a yaw difference to the range [-180, 180].
     */
    private static float normalizeYaw(float yaw) {
        while (yaw > 180.0f) yaw -= 360.0f;
        while (yaw < -180.0f) yaw += 360.0f;
        return yaw;
    }

    /**
     * Simulates a complete jump trajectory from the current state.
     * Returns the final state after maxTicks or when landing.
     * Includes head-hitter detection and mid-air collision avoidance.
     *
     * @param current   The starting physical state
     * @param input     The movement input to hold throughout
     * @param maxTicks  Maximum number of ticks to simulate
     * @param world     The world for collision checking
     * @return The trajectory result containing all intermediate states
     */
    public static TrajectoryResult simulateTrajectory(ParkourNode current, MovementInput input,
                                                       int maxTicks, ServerWorld world) {
        List<ParkourNode> trajectory = new ArrayList<>();
        ParkourNode node = current.copy();
        boolean landed = false;
        boolean collisionDetected = false;

        for (int tick = 0; tick < maxTicks; tick++) {
            ParkourNode previous = node.copy();
            node = simulateTick(node, input);
            trajectory.add(node);

            // Check for horizontal collision with world blocks
            if (world != null && checkHorizontalCollision(world, previous, node)) {
                // Step-up check: slabs, stairs, and low ledges (≤ 0.6 blocks) are auto-climbed
                // by Minecraft's built-in stepHeight mechanic.  Snap Y to the obstacle top
                // and retain horizontal velocity rather than treating this as a wall hit.
                double step = getStepHeightNeeded(world, previous, node);
                if (step > 0.0 && step <= 0.6) {
                    node.y           = previous.y + step;
                    node.vy          = 0.0;
                    node.ticksInAir  = 0;
                    trajectory.set(trajectory.size() - 1, node);
                } else {
                    collisionDetected = true;
                    node = handleHeadHitter(world, previous, node, input);
                    if (node == null) {
                        trajectory.remove(trajectory.size() - 1);
                        return new TrajectoryResult(trajectory, true, false);
                    }
                    trajectory.set(trajectory.size() - 1, node);
                }
            }

            // Check for head-hitter (ceiling collision)
            if (world != null && checkHeadHitter(world, node)) {
                node = handleHeadHitter(world, previous, node, input);
                if (node == null) {
                    trajectory.remove(trajectory.size() - 1);
                    return new TrajectoryResult(trajectory, true, false);
                }
                trajectory.set(trajectory.size() - 1, node);
            }

            // Check if we've landed (hit ground)
            BlockPos groundBelow = (world != null) ? getGroundBlockBelow(world, node) : null;
            if (groundBelow != null) {
                double groundY = getBlockTopSurface(world, groundBelow);
                node.y          = groundY;
                node.vy         = 0;
                node.ticksInAir = 0;
                // Micro-block landing (chain, candle, iron bars/glass pane): retain 30 % of
                // horizontal velocity instead of zeroing it.  The player needs a small amount
                // of forward momentum to stay balanced on the narrow edge, while the sneak
                // friction (applied by PathFollower injecting sneak=true at landing) reduces
                // it the rest of the way.  Normal landings zero velocity as before.
                if (isMicroTopBlock(world, groundBelow)) {
                    node.vx *= 0.30;
                    node.vz *= 0.30;
                } else {
                    node.vx = 0;
                    node.vz = 0;
                }
                landed = true;
                trajectory.set(trajectory.size() - 1, node);
                break;
            }
        }

        return new TrajectoryResult(trajectory, collisionDetected, landed);
    }

    /**
     * Handles head-hitter scenario where the player's head hits a ceiling.
     * Resets vertical velocity and projects forward trajectory.
     */
    private static ParkourNode handleHeadHitter(ServerWorld world, ParkourNode previous,
                                                 ParkourNode node, MovementInput input) {
        // Check if there's space to continue forward (crawling under)
        double forwardX = node.x + node.vx * 2;
        double forwardZ = node.z + node.vz * 2;
        double checkY = previous.y; // Try to maintain previous height

        BlockPos forwardPos = new BlockPos((int) Math.floor(forwardX),
                (int) Math.floor(checkY), (int) Math.floor(forwardZ));
        var forwardState = world.getBlockState(forwardPos);

        if (forwardState.isAir() || forwardState.getCollisionShape(world, forwardPos).isEmpty()) {
            // Can continue forward under the ceiling
            node.y = checkY - 0.1; // Drop slightly
            node.vy = -0.1; // Start falling
            return node;
        }

        // No space to continue - this is a blocking collision
        return null;
    }

    // ==================== Collision Detection ====================

    /**
     * Checks if the player's bounding box collides with any block at the given position.
     * Uses precise sub-block bounding box intersection for micro-blocks.
     */
    public static boolean checkHorizontalCollision(ServerWorld world, ParkourNode from, ParkourNode to) {
        double minX = Math.min(from.x, to.x) - PLAYER_HALF_WIDTH;
        double maxX = Math.max(from.x, to.x) + PLAYER_HALF_WIDTH;
        double minZ = Math.min(from.z, to.z) - PLAYER_HALF_WIDTH;
        double maxZ = Math.max(from.z, to.z) + PLAYER_HALF_WIDTH;
        double minY = to.y;
        double maxY = to.y + PLAYER_HEIGHT;

        int minXBlock = (int) Math.floor(minX);
        int maxXBlock = (int) Math.floor(maxX);
        int minZBlock = (int) Math.floor(minZ);
        int maxZBlock = (int) Math.floor(maxZ);
        int minYBlock = (int) Math.floor(minY);
        int maxYBlock = (int) Math.floor(maxY);

        for (int bx = minXBlock; bx <= maxXBlock; bx++) {
            for (int bz = minZBlock; bz <= maxZBlock; bz++) {
                for (int by = minYBlock; by <= maxYBlock; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    if (isBlockCollidableHorizontal(world, pos, minX, minY, minZ, maxX, maxY, maxZ)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks for head-hitter (ceiling collision) at the given position.
     */
    public static boolean checkHeadHitter(ServerWorld world, ParkourNode node) {
        if (node.vy > 0) { // Only check when moving upward
            double minX = node.x - PLAYER_HALF_WIDTH;
            double maxX = node.x + PLAYER_HALF_WIDTH;
            double minZ = node.z - PLAYER_HALF_WIDTH;
            double maxZ = node.z + PLAYER_HALF_WIDTH;
            double headY = node.y + PLAYER_HEIGHT;

            int minXBlock = (int) Math.floor(minX);
            int maxXBlock = (int) Math.floor(maxX);
            int minZBlock = (int) Math.floor(minZ);
            int maxZBlock = (int) Math.floor(maxZ);
            int headBlockY = (int) Math.floor(headY);

            for (int bx = minXBlock; bx <= maxXBlock; bx++) {
                for (int bz = minZBlock; bz <= maxZBlock; bz++) {
                    BlockPos pos = new BlockPos(bx, headBlockY, bz);
                    var state = world.getBlockState(pos);
                    if (!state.isAir()) {
                        VoxelShape shape = state.getCollisionShape(world, pos);
                        if (!shape.isEmpty()) {
                            Box box = shape.getBoundingBox();
                            if (box != null) {
                                double blockMinY = pos.getY() + box.minY;
                                if (headY > blockMinY && node.y + PLAYER_HEIGHT - node.vy <= blockMinY) {
                                    return true; // Head hit the ceiling
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a specific block collides with the player's horizontal bounding box.
     * Handles narrow blocks (fences, walls, chains, candles) with precise AABB.
     */
    private static boolean isBlockCollidableHorizontal(ServerWorld world, BlockPos pos,
                                                        double playerMinX, double playerMinY, double playerMinZ,
                                                        double playerMaxX, double playerMaxY, double playerMaxZ) {
        var state = world.getBlockState(pos);
        if (state.isAir()) return false;

        // Get the collision shape
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return false;

        // Get precise bounding boxes for this shape
        List<Box> boxes = shape.getBoundingBoxes();
        for (Box box : boxes) {
            // Transform block-local coordinates to world coordinates
            double blockMinX = pos.getX() + box.minX;
            double blockMinY = pos.getY() + box.minY;
            double blockMinZ = pos.getZ() + box.minZ;
            double blockMaxX = pos.getX() + box.maxX;
            double blockMaxY = pos.getY() + box.maxY;
            double blockMaxZ = pos.getZ() + box.maxZ;

            // Check if this is a micro-block (narrow element)
            double blockWidthX = blockMaxX - blockMinX;
            double blockWidthZ = blockMaxZ - blockMinZ;
            boolean isMicroBlock = (blockWidthX < 0.3 || blockWidthZ < 0.3);

            if (isMicroBlock) {
                // For micro-blocks, use precise intersection
                if (checkMicroBlockCollision(playerMinX, playerMinY, playerMinZ,
                        playerMaxX, playerMaxY, playerMaxZ,
                        blockMinX, blockMinY, blockMinZ, blockMaxX, blockMaxY, blockMaxZ)) {
                    return true;
                }
            } else {
                // Standard AABB intersection
                if (playerMinX < blockMaxX && playerMaxX > blockMinX &&
                        playerMinY < blockMaxY && playerMaxY > blockMinY &&
                        playerMinZ < blockMaxZ && playerMaxZ > blockMinZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Precise collision check for micro-blocks (chains, candles, fence posts).
     * Accounts for the exact fractional bounding box dimensions.
     */
    private static boolean checkMicroBlockCollision(double playerMinX, double playerMinY, double playerMinZ,
                                                     double playerMaxX, double playerMaxY, double playerMaxZ,
                                                     double blockMinX, double blockMinY, double blockMinZ,
                                                     double blockMaxX, double blockMaxY, double blockMaxZ) {
        // Standard AABB intersection but with tighter tolerance
        double tolerance = 0.01; // Small tolerance for floating point
        return (playerMinX < blockMaxX + tolerance && playerMaxX > blockMinX - tolerance) &&
                (playerMinY < blockMaxY + tolerance && playerMaxY > blockMinY - tolerance) &&
                (playerMinZ < blockMaxZ + tolerance && playerMaxZ > blockMinZ - tolerance);
    }

    /**
     * Checks if there is solid ground directly below the player.
     * Uses precise fractional block top surface detection.
     */
    /**
     * Returns the BlockPos of the highest solid surface directly below the player,
     * scanning both floor(y) and floor(y)-1 to handle fractional surfaces like slabs.
     * Returns null if no solid ground is found.
     */
    public static BlockPos getGroundBlockBelow(ServerWorld world, ParkourNode node) {
        int feetFloor = (int) Math.floor(node.y);
        BlockPos xzBase = node.getBlockPosBelow(); // used only for X/Z
        for (int offset = 0; offset >= -1; offset--) {
            BlockPos candidate = new BlockPos(xzBase.getX(), feetFloor + offset, xzBase.getZ());
            var state = world.getBlockState(candidate);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(world, candidate);
            if (shape.isEmpty()) continue;
            double blockTopY = candidate.getY() + shape.getBoundingBox().maxY;
            if (node.y <= blockTopY + GROUND_TOLERANCE &&
                    node.y >= blockTopY - 0.5 &&
                    node.vy <= GROUND_TOLERANCE) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean checkGroundBelow(ServerWorld world, ParkourNode node) {
        return getGroundBlockBelow(world, node) != null;
    }

    /**
     * Gets the precise Y coordinate of the top surface of a block.
     * Handles micro-blocks with fractional heights.
     */
    public static double getBlockTopSurface(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return pos.getY();

        Box box = shape.getBoundingBox();
        if (box == null) return pos.getY();

        return pos.getY() + box.maxY;
    }

    /**
     * Checks if the player's bounding box intersects with a specific block's bounding box.
     * Used for precise intersection checks with chains, candles, fences, etc.
     */
    public static boolean isColliding(double playerX, double playerY, double playerZ,
                                       double blockMinX, double blockMinY, double blockMinZ,
                                       double blockMaxX, double blockMaxY, double blockMaxZ) {
        double minX = playerX - PLAYER_HALF_WIDTH;
        double maxX = playerX + PLAYER_HALF_WIDTH;
        double minZ = playerZ - PLAYER_HALF_WIDTH;
        double maxZ = playerZ + PLAYER_HALF_WIDTH;
        double minY = playerY;
        double maxY = playerY + PLAYER_HEIGHT;

        return (minX < blockMaxX && maxX > blockMinX) &&
                (minY < blockMaxY && maxY > blockMinY) &&
                (minZ < blockMaxZ && maxZ > blockMinZ);
    }

    /**
     * Checks collision with a specific micro-block type at a given position.
     * Uses the predefined micro-block bounding boxes.
     */
    public static boolean checkMicroBlockCollision(ServerWorld world, BlockPos pos,
                                                    MicroBlockBB microBB,
                                                    double playerX, double playerY, double playerZ) {
        Box worldBox = microBB.toWorldBox(pos);
        return isColliding(playerX, playerY, playerZ,
                worldBox.minX, worldBox.minY, worldBox.minZ,
                worldBox.maxX, worldBox.maxY, worldBox.maxZ);
    }

    // ==================== Advanced Parkour Detection ====================

    /**
     * Detects if a triple neo fence jump is possible.
     * A neo jump requires precise mid-air rotation to skim past fence edges.
     *
     * @param current     Current player state
     * @param fencePos    Position of the fence to clear
     * @param world       The world
     * @param input       The movement input
     * @return The yaw adjustment needed to clear the fence, or Float.NaN if impossible
     */
    public static float detectNeoJumpOpportunity(ParkourNode current, BlockPos fencePos,
                                                  ServerWorld world, MovementInput input) {
        // Fence edge is at 0.125 blocks from center (for 0.25 width fence)
        double fenceEdgeX = fencePos.getX() + 0.375; // Left edge
        double fenceEdgeZ = fencePos.getZ() + 0.375; // Front edge

        // Try different yaw curves to find one that clears the fence
        float[] yawOffsets = {-20.0f, -15.0f, -10.0f, -5.0f, 5.0f, 10.0f, 15.0f, 20.0f};

        for (float yawOffset : yawOffsets) {
            ParkourNode testNode = current.copy();
            testNode.yaw = current.yaw + yawOffset;

            // Simulate with gradual turn
            TrajectoryResult result = simulateTrajectoryWithYawCurve(
                    testNode, input, MAX_JUMP_TICKS, world, current.yaw + yawOffset, 5.0
            );

            if (!result.collisionDetected) {
                return yawOffset;
            }
        }

        return Float.NaN; // No valid neo jump found
    }

    /**
     * Simulates a trajectory with continuous yaw interpolation (for neo jumps).
     */
    public static TrajectoryResult simulateTrajectoryWithYawCurve(ParkourNode current, MovementInput input,
                                                                    int maxTicks, ServerWorld world,
                                                                    float targetYaw, double turnRate) {
        List<ParkourNode> trajectory = new ArrayList<>();
        ParkourNode node = current.copy();
        boolean landed = false;
        boolean collisionDetected = false;

        for (int tick = 0; tick < maxTicks; tick++) {
            ParkourNode previous = node.copy();
            node = simulateTickWithYawInterpolation(node, input, targetYaw, turnRate);
            trajectory.add(node);

            // Check for collision
            if (world != null && checkHorizontalCollision(world, previous, node)) {
                collisionDetected = true;
                break;
            }

            // Check for ground
            BlockPos groundBlock2 = (world != null) ? getGroundBlockBelow(world, node) : null;
            if (groundBlock2 != null) {
                double groundY = getBlockTopSurface(world, groundBlock2);
                node.y = groundY;
                node.vy = 0;
                node.vx = 0;
                node.vz = 0;
                node.ticksInAir = 0;
                landed = true;
                trajectory.set(trajectory.size() - 1, node);
                break;
            }
        }

        return new TrajectoryResult(trajectory, collisionDetected, landed);
    }

    // ==================== Trajectory Analysis ====================

    /**
     * Calculates where a jump will land given the current state and input.
     */
    public static Vec3d predictLandingPosition(ParkourNode current, MovementInput input, ServerWorld world) {
        TrajectoryResult result = simulateTrajectory(current, input, MAX_JUMP_TICKS, world);
        if (result.landed && !result.trajectory.isEmpty()) {
            ParkourNode last = result.trajectory.get(result.trajectory.size() - 1);
            return new Vec3d(last.x, last.y, last.z);
        }
        return null;
    }

    /**
     * Finds the best yaw angle to clear an obstacle.
     */
    public static float findClearYaw(ParkourNode current, MovementInput input,
                                      BlockPos obstaclePos, ServerWorld world, float[] yawVariants) {
        for (float yawOffset : yawVariants) {
            ParkourNode testNode = current.copy();
            testNode.yaw = current.yaw + yawOffset;

            TrajectoryResult result = simulateTrajectory(testNode, input, MAX_JUMP_TICKS, world);
            if (!result.collisionDetected) {
                return yawOffset;
            }
        }
        return 0; // No clear path found
    }

    /**
     * Calculates the maximum horizontal distance achievable with a sprint jump.
     */
    public static double calculateMaxJumpDistance(double startY, double targetY) {
        // Simplified physics calculation for max jump distance
        double heightDiff = targetY - startY;
        if (heightDiff > 1.25) return 0; // Can't jump that high

        // Base sprint jump distance on flat ground is approximately 2.6 blocks
        double baseDistance = 2.6;

        // Reduce distance for upward jumps, increase for downward
        return baseDistance - heightDiff * 0.5;
    }

    // ==================== Result Classes ====================

    /**
     * Contains the results of a trajectory simulation.
     */
    public static class TrajectoryResult {
        public final List<ParkourNode> trajectory;
        public final boolean collisionDetected;
        public final boolean landed;
        public final int ticksSimulated;

        public TrajectoryResult(List<ParkourNode> trajectory, boolean collisionDetected, boolean landed) {
            this.trajectory = trajectory;
            this.collisionDetected = collisionDetected;
            this.landed = landed;
            this.ticksSimulated = trajectory.size();
        }

        /**
         * Gets the final position of the trajectory.
         */
        public Vec3d getFinalPosition() {
            if (trajectory.isEmpty()) return Vec3d.ZERO;
            ParkourNode last = trajectory.get(trajectory.size() - 1);
            return new Vec3d(last.x, last.y, last.z);
        }

        /**
         * Gets the final state of the trajectory.
         */
        public ParkourNode getFinalState() {
            if (trajectory.isEmpty()) return null;
            return trajectory.get(trajectory.size() - 1);
        }

        /**
         * Gets the landing position if landed, otherwise the final position.
         */
        public Vec3d getLandingOrFinalPosition() {
            if (landed && !trajectory.isEmpty()) {
                ParkourNode last = trajectory.get(trajectory.size() - 1);
                return new Vec3d(last.x, last.y, last.z);
            }
            return getFinalPosition();
        }

        /**
         * Gets the highest point reached during the trajectory.
         */
        public double getMaxHeight() {
            double maxY = Double.MIN_VALUE;
            for (ParkourNode node : trajectory) {
                if (node.y > maxY) maxY = node.y;
            }
            return maxY == Double.MIN_VALUE ? 0 : maxY;
        }
    }

    // ==================== Fractional-Block Helpers ====================

    /**
     * Computes the step-up height needed when the player collides horizontally.
     *
     * Scans the blocks overlapping the player's footprint in the step-height range
     * (playerY to playerY + 0.6).  Returns the maximum obstacle top - playerY if any
     * block is found in that window, or 0 if none exist (full wall, not step-up).
     *
     * This mirrors Minecraft's own stepHeight=0.6 logic: slabs (0.5) and stairs (0.5-1.0
     * at the low side) are auto-climbed; fences (1.5) and walls are not.
     */
    private static double getStepHeightNeeded(ServerWorld world, ParkourNode from, ParkourNode to) {
        double minX = Math.min(from.x, to.x) - PLAYER_HALF_WIDTH;
        double maxX = Math.max(from.x, to.x) + PLAYER_HALF_WIDTH;
        double minZ = Math.min(from.z, to.z) - PLAYER_HALF_WIDTH;
        double maxZ = Math.max(from.z, to.z) + PLAYER_HALF_WIDTH;
        double playerY   = from.y;
        double stepLimit = playerY + 0.6;

        int minXb = (int) Math.floor(minX);
        int maxXb = (int) Math.floor(maxX);
        int minZb = (int) Math.floor(minZ);
        int maxZb = (int) Math.floor(maxZ);
        int minYb = (int) Math.floor(playerY);
        int maxYb = (int) Math.floor(stepLimit);

        double maxTop = playerY;
        for (int bx = minXb; bx <= maxXb; bx++) {
            for (int bz = minZb; bz <= maxZb; bz++) {
                for (int by = minYb; by <= maxYb; by++) {
                    BlockPos pos = new BlockPos(bx, by, bz);
                    var state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    VoxelShape shape = state.getCollisionShape(world, pos);
                    if (shape.isEmpty()) continue;
                    Box bounds = shape.getBoundingBox();
                    // Check horizontal overlap with player footprint
                    double bMinX = pos.getX() + bounds.minX;
                    double bMaxX = pos.getX() + bounds.maxX;
                    double bMinZ = pos.getZ() + bounds.minZ;
                    double bMaxZ = pos.getZ() + bounds.maxZ;
                    if (minX >= bMaxX || maxX <= bMinX || minZ >= bMaxZ || maxZ <= bMinZ) continue;
                    double top = pos.getY() + bounds.maxY;
                    // Only count obstacles whose top is within the step-height window
                    if (top > playerY && top <= stepLimit) {
                        maxTop = Math.max(maxTop, top);
                    }
                }
            }
        }
        return maxTop > playerY ? maxTop - playerY : 0.0;
    }

    /**
     * True if the block has a very narrow top surface (chain, candle, iron bars, glass pane).
     * Landing on these requires a velocity brake and sneak injection to prevent the player
     * from sliding off the sub-0.2-block-wide edge.
     */
    private static boolean isMicroTopBlock(ServerWorld world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ChainBlock
            || block instanceof CandleBlock
            || block instanceof AbstractCandleBlock
            || block instanceof PaneBlock;
    }

    // ==================== Legacy Compatibility ====================

    /**
     * Legacy method for compatibility with existing pathfinder.
     */
    public static List<BlockPos> getTrajectoryIfValid(ServerWorld world, BlockPos p1, BlockPos p2,
                                                       BlockPos start, BlockPos end, boolean isDropNode) {
        if (p2.getY() >= p1.getY()) {
            return null;
        }

        List<BlockPos> trajectoryArc = new ArrayList<>();

        double currentX = p1.getX() + 0.5;
        double currentY = p1.getY();
        double currentZ = p1.getZ() + 0.5;

        double targetX = p2.getX() + 0.5;
        double targetY = p2.getY();
        double targetZ = p2.getZ() + 0.5;

        double vecX = targetX - currentX;
        double vecZ = targetZ - currentZ;
        double horizontalDist = Math.sqrt(vecX * vecX + vecZ * vecZ);

        if (horizontalDist < 0.01) {
            int dropHeight = p1.getY() - p2.getY();
            for (int y = p1.getY() - 1; y >= p2.getY(); y--) {
                BlockPos check = new BlockPos(p1.getX(), y, p1.getZ());
                if (!isPassable(world, check)) return null;
                if (!isPassable(world, check.up())) return null;
                trajectoryArc.add(check);
            }
            return trajectoryArc;
        }

        double dirX = vecX / horizontalDist;
        double dirZ = vecZ / horizontalDist;

        int heightDifference = p1.getY() - p2.getY();
        double horizontalSpeed = isDropNode ? 0.21 : 0.21 * 1.3;

        if (heightDifference > 3) {
            horizontalSpeed = Math.max(0.21 * 0.5, horizontalSpeed - (heightDifference - 3) * 0.02);
        }

        double velocityY = 0.0;
        int safetyTicks = 0;
        BlockPos lastRecordedPos = p1;

        while (safetyTicks < 200) {
            safetyTicks++;

            currentX += dirX * horizontalSpeed;
            currentZ += dirZ * horizontalSpeed;

            velocityY -= GRAVITY;
            velocityY *= VERTICAL_DRAG;
            currentY += velocityY;

            BlockPos checkPos = new BlockPos((int) Math.floor(currentX), (int) Math.floor(currentY), (int) Math.floor(currentZ));

            if (!isPassable(world, checkPos)) {
                return null;
            }
            if (!isPassable(world, checkPos.up())) {
                return null;
            }

            if (!checkPos.equals(lastRecordedPos)) {
                trajectoryArc.add(checkPos);
                lastRecordedPos = checkPos;
            }

            double remainingDist = ((targetX - currentX) * dirX) + ((targetZ - currentZ) * dirZ);
            if (remainingDist <= 0 || currentY <= targetY) {
                break;
            }
        }

        double landingTolerance = 1.0;
        if (Math.abs(currentY - targetY) <= landingTolerance) {
            return trajectoryArc;
        }

        return null;
    }

    private static boolean isPassable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isAir() || state.getBlock() instanceof BlockWithEntity;
    }
}