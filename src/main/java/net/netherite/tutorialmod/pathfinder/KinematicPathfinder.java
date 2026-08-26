package net.netherite.tutorialmod.pathfinder;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/**
 * Advanced Kinematic A* pathfinder for complex parkour movement.
 * 
 * Unlike standard grid-based A*, this pathfinder operates on discrete game ticks (20/sec)
 * and calculates exact physics trajectories. It expands neighbors by macro-inputs
 * (key combinations) rather than grid directions, and validates each trajectory
 * against precise collision detection with sub-block fractional AABB support.
 * 
 * Key features:
 * - Tick-based physics simulation with exact Minecraft math
 * - Complete kinematic state tracking (position, velocity, rotation, flags)
 * - Sub-block collision detection for chains, candles, fences, etc.
 * - Mid-air yaw interpolation for neo jumps (triple fence navigation)
 * - Head-hitter detection and ceiling collision handling
 * - Prioritized A* with Euclidean distance heuristic and penalty scoring
 * - Support for sprint jumps, normal jumps, drops, and curved trajectories
 */
public class KinematicPathfinder {

    // ==================== Configuration ====================

    /** Maximum number of nodes to search before giving up */
    private static final int MAX_SEARCH_NODES = 75000;

    /** Maximum ticks to simulate per jump trajectory */
    private static final int MAX_TRAJECTORY_TICKS = 30;

    /** Tolerance for considering a landing successful (blocks) */
    private static final double LANDING_TOLERANCE = 0.5;

    /** Goal distance threshold (horizontal) */
    private static final double GOAL_THRESHOLD = 1.0;

    /** Goal vertical distance threshold */
    private static final double GOAL_VERTICAL_THRESHOLD = 1.5;

    /** Yaw variation increments for neo jump detection (degrees) */
    private static final float[] YAW_VARIANTS = {-20.0f, -15.0f, -10.0f, -5.0f, 0.0f, 5.0f, 10.0f, 15.0f, 20.0f};

    /** Fine-grained yaw variations for precise neo jumps */
    private static final float[] FINE_YAW_VARIANTS = {-3.0f, -2.0f, -1.0f, 0.0f, 1.0f, 2.0f, 3.0f};

    /** Turn rate for neo jump interpolation (degrees per tick) */
    private static final double NEO_TURN_RATE = 5.0;

    /** Minimum improvement threshold to re-add a node to open set */
    private static final double COST_IMPROVEMENT_THRESHOLD = 0.01;

    // ==================== Priority Queue Comparator ====================

    /** Comparator for the priority queue, ordered by fCost with tiebreakers */
    private static final Comparator<ParkourNode> NODE_COMPARATOR = (a, b) -> {
        int cmp = Double.compare(a.fCost, b.fCost);
        if (cmp != 0) return cmp;
        // Tiebreaker: prefer nodes closer to goal (lower hCost)
        cmp = Double.compare(a.hCost, b.hCost);
        if (cmp != 0) return cmp;
        // Tiebreaker: prefer nodes on ground (more stable)
        if (a.isOnGround() && !b.isOnGround()) return -1;
        if (!a.isOnGround() && b.isOnGround()) return 1;
        // Tiebreaker: prefer fewer ticks in air
        return Integer.compare(a.ticksInAir, b.ticksInAir);
    };

    // ==================== State ====================

    private final ServerWorld world;
    private final BlockPos goalBlock;
    private final double goalX, goalY, goalZ;

    /** Priority queue for the open set, ordered by fCost */
    private final PriorityQueue<ParkourNode> openSet;

    /** Closed set using quantized state keys */
    private final Set<Long> closedSet;

    /** Map from state key to best known gCost for that state */
    private final Map<Long, Double> stateCosts;

    /** Map from state key to best node for that state */
    private final Map<Long, ParkourNode> stateNodes;

    /** Statistics for debugging */
    private int nodesExpanded;
    private int nodesGenerated;
    private int collisionChecks;
    private long searchTimeMs;

    // ==================== Constructor ====================

    /**
     * Creates a new kinematic pathfinder.
     *
     * @param world    The server world for collision checking
     * @param goalPos  The target block position (center will be used)
     */
    public KinematicPathfinder(ServerWorld world, BlockPos goalPos) {
        this.world = world;
        this.goalBlock = goalPos;
        this.goalX = goalPos.getX() + 0.5;
        this.goalY = goalPos.getY();
        this.goalZ = goalPos.getZ() + 0.5;

        this.openSet = new PriorityQueue<>(NODE_COMPARATOR);
        this.closedSet = new HashSet<>();
        this.stateCosts = new HashMap<>();
        this.stateNodes = new HashMap<>();

        this.nodesExpanded = 0;
        this.nodesGenerated = 0;
        this.collisionChecks = 0;
        this.searchTimeMs = 0;
    }

    // ==================== Main Entry Point ====================

    /**
     * Finds a parkour path from start to goal.
     *
     * @param startX     Starting X position
     * @param startY     Starting Y position (feet)
     * @param startZ     Starting Z position
     * @param startYaw   Starting yaw in degrees
     * @param sprinting  Whether to start with sprinting
     * @return List of parkour nodes representing the path, or empty list if no path found
     */
    public List<ParkourNode> findPath(double startX, double startY, double startZ,
                                       float startYaw, boolean sprinting) {
        long startTime = System.currentTimeMillis();

        // Initialize
        openSet.clear();
        closedSet.clear();
        stateCosts.clear();
        stateNodes.clear();
        nodesExpanded = 0;
        nodesGenerated = 0;
        collisionChecks = 0;

        // Create start node
        ParkourNode startNode = new ParkourNode(
                startX, startY, startZ,
                0, 0, 0,
                startYaw, false, false,
                0, null, null
        );

        startNode.hCost = heuristic(startNode);
        startNode.gCost = 0;
        startNode.fCost = startNode.hCost;
        startNode.isKeyframe = true;

        openSet.add(startNode);
        long startKey = quantizeState(startNode);
        stateCosts.put(startKey, 0.0);
        stateNodes.put(startKey, startNode);

        ParkourNode goalNode = null;

        // Main A* loop
        while (!openSet.isEmpty() && nodesExpanded < MAX_SEARCH_NODES) {
            ParkourNode current = openSet.poll();
            nodesExpanded++;

            // Check if we've reached the goal
            if (isGoalReached(current)) {
                goalNode = current;
                break;
            }

            // Skip if we've already processed a better version of this state
            long currentStateKey = quantizeState(current);
            if (closedSet.contains(currentStateKey)) continue;
            closedSet.add(currentStateKey);

            // Generate and evaluate neighbors
            List<ParkourNode> neighbors = generateNeighbors(current);
            
            for (ParkourNode neighbor : neighbors) {
                long neighborKey = quantizeState(neighbor);

                // Skip if already closed with better cost
                if (closedSet.contains(neighborKey)) continue;

                Double existingCost = stateCosts.get(neighborKey);
                if (existingCost != null && neighbor.gCost >= existingCost - COST_IMPROVEMENT_THRESHOLD) continue;

                // Update costs
                neighbor.hCost = heuristic(neighbor);
                neighbor.fCost = neighbor.gCost + neighbor.hCost + neighbor.penalty;
                stateCosts.put(neighborKey, neighbor.gCost);
                stateNodes.put(neighborKey, neighbor);

                openSet.add(neighbor);
                nodesGenerated++;
            }
        }

        searchTimeMs = System.currentTimeMillis() - startTime;

        // Reconstruct path
        if (goalNode != null) {
            return reconstructPath(goalNode);
        }

        return Collections.emptyList();
    }

    // ==================== Heuristic ====================

    /**
     * Calculates the heuristic cost from a node to the goal.
     * Uses Euclidean distance with penalties for undesirable states.
     * 
     * The heuristic is designed to:
     * - Prioritize horizontal distance (primary concern for parkour)
     * - Penalize being too high or too low relative to goal
     * - Penalize unstable states (in air, moving away from goal)
     * - Reward ground contact and forward momentum
     */
    private double heuristic(ParkourNode node) {
        double dx = node.x - goalX;
        double dz = node.z - goalZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Vertical distance
        double dy = node.y - goalY;
        double absDy = Math.abs(dy);

        // Base heuristic: horizontal distance is primary
        double h = horizontalDist * 1.0;

        // Vertical penalty - being above goal is easier than below
        if (dy > 0) {
            h += dy * 0.3; // Above goal - can fall down
        } else {
            h += absDy * 0.8; // Below goal - need to jump up
        }

        // Penalty for being in the air (less stable, harder to control)
        if (node.ticksInAir > 0) {
            h += node.ticksInAir * 0.1;
            
            // Extra penalty if still going up (need to come down)
            if (node.vy > 0) {
                h += 0.5;
            }
        }

        // Penalty for high horizontal velocity (harder to land precisely)
        double hSpeed = node.getHorizontalSpeed();
        if (hSpeed > 0.5) {
            h += (hSpeed - 0.5) * 0.2;
        }

        // Reward for facing towards goal
        float yawDiff = node.getYawDifferenceTo(goalX, goalZ);
        h += Math.abs(yawDiff) * 0.005;

        return h;
    }

    // ==================== Goal Check ====================

    /**
     * Checks if a node has reached the goal position.
     * More permissive to accept landings at various heights near the goal.
     */
    private boolean isGoalReached(ParkourNode node) {
        double dx = node.x - goalX;
        double dz = node.z - goalZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Must be close enough horizontally
        if (horizontalDist > GOAL_THRESHOLD + 0.5) return false;

        // Check vertical distance
        double dy = Math.abs(node.y - goalY);
        if (dy > GOAL_VERTICAL_THRESHOLD) return false;

        // Accept stable landings
        if (node.isStableLanding()) return true;

        // Accept nodes that are falling and close to the goal
        if (node.vy <= 0 && horizontalDist < GOAL_THRESHOLD) return true;

        // Accept nodes very close to goal even if in air
        if (horizontalDist < 0.5 && dy < 0.5) return true;

        return false;
    }

    // ==================== Neighbor Generation ====================

    /**
     * Generates neighbor nodes by simulating different input combinations.
     * This is the key difference from grid-based A* - we expand by actions, not directions.
     * 
     * The neighbor generation strategy:
     * 1. Calculate direction to goal for directional inputs
     * 2. Generate directional jumps when on ground near gaps
     * 3. Try standard macro inputs with collision avoidance
     * 4. For blocked paths, try yaw variations (neo jump logic)
     * 5. Generate mid-air inputs for trajectory continuation
     */
    private List<ParkourNode> generateNeighbors(ParkourNode current) {
        List<ParkourNode> neighbors = new ArrayList<>();

        // Determine which inputs are valid for the current state
        boolean onGround = current.ticksInAir == 0;

        // Calculate direction to goal for directional inputs
        double dxToGoal = goalX - current.x;
        double dzToGoal = goalZ - current.z;
        double distToGoal = Math.sqrt(dxToGoal * dxToGoal + dzToGoal * dzToGoal);
        float yawToGoal = (float) Math.toDegrees(Math.atan2(-dxToGoal, -dzToGoal));

        boolean directionalJumpsAdded = false;
        if (onGround) {
            // On ground - generate jump options
            boolean groundAhead = hasGroundAhead(current, current.yaw);

            if (!groundAhead || distToGoal > GOAL_THRESHOLD) {
                // Gap ahead or far from goal - try directional jumps toward goal.
                // These cover sprint and normal jump at multiple yaw angles; the macro
                // input loop below skips jump inputs to avoid duplicating those trajectories.
                neighbors.addAll(generateDirectionalJumps(current, yawToGoal, distToGoal));
                directionalJumpsAdded = true;
            }

            // Also try standard forward movement if ground is safe
            if (groundAhead && distToGoal > GOAL_THRESHOLD * 2) {
                addGroundMovementNeighbors(neighbors, current, current.yaw);
            }
        } else {
            // In air - generate mid-air control options
            neighbors.addAll(generateAirControlNeighbors(current, yawToGoal, distToGoal));
        }

        // Try standard macro inputs (skip jump inputs if directional jumps already handled them)
        for (MovementInput input : getRelevantMacroInputs(current, onGround, directionalJumpsAdded)) {
            PathPhysics.TrajectoryResult result = simulateWithYaw(current, input, current.yaw);
            collisionChecks++;

            if (result.collisionDetected) {
                // Try yaw variations to avoid collision (neo jump logic)
                neighbors.addAll(tryYawVariations(current, input, result));
                continue;
            }

            ParkourNode finalState = result.getFinalState();
            if (finalState == null) continue;

            // Validate the landing
            if (!isValidLanding(finalState)) continue;

            // Calculate cost for this trajectory
            double trajectoryCost = calculateTrajectoryCost(current, finalState, result);
            finalState.gCost = current.gCost + trajectoryCost;
            finalState.parent = current;
            finalState.inputUsed = input;

            // Apply penalties
            applyTrajectoryPenalties(finalState, result);

            neighbors.add(finalState);
        }

        return neighbors;
    }

    /**
     * Gets relevant macro inputs based on current state.
     * When {@code skipJumps} is true, jump inputs are omitted because
     * {@link #generateDirectionalJumps} already generated them at multiple yaw angles.
     */
    private List<MovementInput> getRelevantMacroInputs(ParkourNode current, boolean onGround, boolean skipJumps) {
        List<MovementInput> inputs = new ArrayList<>();

        if (onGround) {
            if (!skipJumps) {
                inputs.add(MovementInput.sprintJump());
                inputs.add(MovementInput.normalJump());
            }
            inputs.add(MovementInput.sprintForward());
            inputs.add(MovementInput.walkForward());
        } else {
            // Air-based inputs (no jumping - can't double jump)
            inputs.add(MovementInput.idle());
            inputs.add(MovementInput.walkForward());
            inputs.add(MovementInput.sprintForward());
        }

        return inputs;
    }

    /**
     * Generates jump inputs in multiple directions toward the goal.
     * Critical for crossing gaps that aren't directly in front of the player.
     */
    private List<ParkourNode> generateDirectionalJumps(ParkourNode current, float targetYaw, double distToGoal) {
        List<ParkourNode> neighbors = new ArrayList<>();

        // Determine yaw spread based on distance to goal
        float[] yawOffsets;
        if (distToGoal > 3.0) {
            yawOffsets = new float[]{-30.0f, -15.0f, 0.0f, 15.0f, 30.0f};
        } else {
            yawOffsets = new float[]{-20.0f, -10.0f, 0.0f, 10.0f, 20.0f};
        }

        for (float yawOffset : yawOffsets) {
            float testYaw = targetYaw + yawOffset;

            // Sprint jump toward goal
            PathPhysics.TrajectoryResult result = simulateWithYaw(current, MovementInput.sprintJump(), testYaw);
            collisionChecks++;
            if (!result.collisionDetected) {
                ParkourNode finalState = result.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double cost = calculateTrajectoryCost(current, finalState, result);
                    finalState.gCost = current.gCost + cost;
                    finalState.parent = current;
                    finalState.inputUsed = MovementInput.sprintJump();
                    applyTrajectoryPenalties(finalState, result);
                    neighbors.add(finalState);
                }
            }

            // Normal jump toward goal (shorter distance)
            result = simulateWithYaw(current, MovementInput.normalJump(), testYaw);
            collisionChecks++;
            if (!result.collisionDetected) {
                ParkourNode finalState = result.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double cost = calculateTrajectoryCost(current, finalState, result);
                    finalState.gCost = current.gCost + cost;
                    finalState.parent = current;
                    finalState.inputUsed = MovementInput.normalJump();
                    applyTrajectoryPenalties(finalState, result);
                    neighbors.add(finalState);
                }
            }
        }

        return neighbors;
    }

    /**
     * Generates ground movement neighbors (walking/running without jumping).
     */
    private void addGroundMovementNeighbors(List<ParkourNode> neighbors, ParkourNode current, float yaw) {
        MovementInput[] groundInputs = {
            MovementInput.sprintForward(),
            MovementInput.walkForward()
        };

        for (MovementInput input : groundInputs) {
            PathPhysics.TrajectoryResult result = simulateWithYaw(current, input, yaw);
            collisionChecks++;

            if (!result.collisionDetected) {
                ParkourNode finalState = result.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double cost = calculateTrajectoryCost(current, finalState, result);
                    finalState.gCost = current.gCost + cost;
                    finalState.parent = current;
                    finalState.inputUsed = input;
                    neighbors.add(finalState);
                }
            }
        }
    }

    /**
     * Generates mid-air control neighbors for trajectory adjustment.
     */
    private List<ParkourNode> generateAirControlNeighbors(ParkourNode current, float targetYaw, double distToGoal) {
        List<ParkourNode> neighbors = new ArrayList<>();

        // Try moving towards goal in air
        if (distToGoal > 1.0) {
            // Try neo jump with interpolation towards goal
            MovementInput neoInput = MovementInput.sprintNeoJump(targetYaw, NEO_TURN_RATE);
            PathPhysics.TrajectoryResult result = simulateWithYawCurve(current, neoInput, targetYaw);
            collisionChecks++;

            if (!result.collisionDetected) {
                ParkourNode finalState = result.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double cost = calculateTrajectoryCost(current, finalState, result);
                    finalState.gCost = current.gCost + cost;
                    finalState.parent = current;
                    finalState.inputUsed = neoInput;
                    neighbors.add(finalState);
                }
            }

            // Try standard forward movement
            PathPhysics.TrajectoryResult fwdResult = simulateWithYaw(current, MovementInput.sprintForward(), targetYaw);
            collisionChecks++;
            if (!fwdResult.collisionDetected) {
                ParkourNode finalState = fwdResult.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double cost = calculateTrajectoryCost(current, finalState, fwdResult);
                    finalState.gCost = current.gCost + cost;
                    finalState.parent = current;
                    finalState.inputUsed = MovementInput.sprintForward();
                    neighbors.add(finalState);
                }
            }
        }

        // Try idle (let physics take over)
        PathPhysics.TrajectoryResult idleResult = simulateWithYaw(current, MovementInput.idle(), current.yaw);
        collisionChecks++;
        if (!idleResult.collisionDetected) {
            ParkourNode finalState = idleResult.getFinalState();
            if (finalState != null) {
                double cost = calculateTrajectoryCost(current, finalState, idleResult);
                finalState.gCost = current.gCost + cost;
                finalState.parent = current;
                finalState.inputUsed = MovementInput.idle();
                neighbors.add(finalState);
            }
        }

        return neighbors;
    }

    /**
     * Simulates a tick with a specific yaw angle.
     */
    private PathPhysics.TrajectoryResult simulateWithYaw(ParkourNode current, MovementInput input, float yaw) {
        ParkourNode modified = current.copy();
        modified.yaw = yaw;
        return PathPhysics.simulateTrajectory(modified, input, MAX_TRAJECTORY_TICKS, world);
    }

    /**
     * Simulates with yaw interpolation for neo jumps.
     */
    private PathPhysics.TrajectoryResult simulateWithYawCurve(ParkourNode current, MovementInput input, float targetYaw) {
        return PathPhysics.simulateTrajectoryWithYawCurve(current, input, MAX_TRAJECTORY_TICKS, world, targetYaw, NEO_TURN_RATE);
    }

    /**
     * Tries different yaw angles to find a path around obstacles (neo jump technique).
     * Used for navigating past fence posts, walls, and other narrow obstacles.
     */
    private List<ParkourNode> tryYawVariations(ParkourNode current, MovementInput input,
                                                  PathPhysics.TrajectoryResult failedResult) {
        List<ParkourNode> validNeighbors = new ArrayList<>();

        // Try standard yaw variations
        for (float yawOffset : YAW_VARIANTS) {
            if (yawOffset == 0) continue;

            ParkourNode yawAdjusted = current.copy();
            yawAdjusted.yaw = current.yaw + yawOffset;

            PathPhysics.TrajectoryResult result = PathPhysics.simulateTrajectory(
                    yawAdjusted, input, MAX_TRAJECTORY_TICKS, world
            );
            collisionChecks++;

            if (!result.collisionDetected) {
                ParkourNode finalState = result.getFinalState();
                if (finalState != null && isValidLanding(finalState)) {
                    double trajectoryCost = calculateTrajectoryCost(yawAdjusted, finalState, result);
                    finalState.gCost = yawAdjusted.gCost + trajectoryCost;
                    finalState.parent = current;
                    finalState.inputUsed = input;
                    // Add penalty for yaw deviation
                    finalState.addPenalty(Math.abs(yawOffset) * 0.05);
                    validNeighbors.add(finalState);
                }
            }
        }

        // If standard variations failed, try fine-grained adjustments
        if (validNeighbors.isEmpty()) {
            for (float yawOffset : FINE_YAW_VARIANTS) {
                if (yawOffset == 0) continue;

                ParkourNode yawAdjusted = current.copy();
                yawAdjusted.yaw = current.yaw + yawOffset;

                // Use neo jump with interpolation
                MovementInput neoInput = MovementInput.sprintNeoJump(yawAdjusted.yaw, 3.0);
                PathPhysics.TrajectoryResult result = PathPhysics.simulateTrajectoryWithYawCurve(
                        yawAdjusted, neoInput, MAX_TRAJECTORY_TICKS, world, yawAdjusted.yaw, 3.0
                );
                collisionChecks++;

                if (!result.collisionDetected) {
                    ParkourNode finalState = result.getFinalState();
                    if (finalState != null && isValidLanding(finalState)) {
                        double trajectoryCost = calculateTrajectoryCost(yawAdjusted, finalState, result);
                        finalState.gCost = yawAdjusted.gCost + trajectoryCost;
                        finalState.parent = current;
                        finalState.inputUsed = neoInput;
                        validNeighbors.add(finalState);
                    }
                }
            }
        }

        return validNeighbors;
    }

    /**
     * Checks if there is solid ground ahead in the given direction.
     * Prevents walking off cliffs or into holes.
     */
    private boolean hasGroundAhead(ParkourNode current, float yaw) {
        // Check a few blocks ahead in the direction of movement
        double checkDist = 1.0;
        double checkX = current.x - Math.sin(yaw * Math.PI / 180.0) * checkDist;
        double checkZ = current.z + Math.cos(yaw * Math.PI / 180.0) * checkDist;

        int checkBlockX = (int) Math.floor(checkX);
        int checkBlockY = (int) Math.floor(current.y) - 1;
        int checkBlockZ = (int) Math.floor(checkZ);

        BlockPos checkPos = new BlockPos(checkBlockX, checkBlockY, checkBlockZ);
        var state = world.getBlockState(checkPos);

        if (state.isAir()) {
            // Check closer
            checkDist = 0.5;
            checkX = current.x - Math.sin(yaw * Math.PI / 180.0) * checkDist;
            checkZ = current.z + Math.cos(yaw * Math.PI / 180.0) * checkDist;
            checkBlockX = (int) Math.floor(checkX);
            checkBlockZ = (int) Math.floor(checkZ);
            checkPos = new BlockPos(checkBlockX, checkBlockY, checkBlockZ);
            state = world.getBlockState(checkPos);
            if (state.isAir()) {
                return false;
            }
        }

        var shape = state.getCollisionShape(world, checkPos);
        if (shape.isEmpty()) {
            return false;
        }

        // Check that the space above is clear
        BlockPos abovePos = checkPos.up();
        var aboveState = world.getBlockState(abovePos);
        var aboveShape = aboveState.getCollisionShape(world, abovePos);

        if (!aboveShape.isEmpty()) {
            double aboveHeight = aboveShape.getMax(net.minecraft.util.math.Direction.Axis.Y);
            if (aboveHeight > 0.125) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a node represents a valid landing position.
     */
    private boolean isValidLanding(ParkourNode node) {
        // Allow a reasonable range of landing heights
        if (node.y > goalY + 4) return false;
        if (node.y < goalY - 10) return false;

        // If the node claims to be on ground, verify there's solid ground below
        if (node.ticksInAir == 0) {
            BlockPos below = node.getBlockPosBelow();
            var state = world.getBlockState(below);
            if (state.isAir()) return false;
            var shape = state.getCollisionShape(world, below);
            if (shape.isEmpty()) return false;
        }

        // In-air nodes are valid as intermediate states
        return true;
    }

    /**
     * Calculates the cost of a trajectory based on time, stability, and precision.
     */
    private double calculateTrajectoryCost(ParkourNode from, ParkourNode to,
                                            PathPhysics.TrajectoryResult result) {
        // Base cost: number of ticks (time)
        double cost = result.trajectory.size();

        // Penalty for being in the air
        if (to.ticksInAir > 5) {
            cost += (to.ticksInAir - 5) * 0.5;
        }

        // Penalty for high vertical velocity on landing
        if (Math.abs(to.vy) > 0.2) {
            cost += Math.abs(to.vy) * 2;
        }

        // Penalty for high horizontal velocity (harder to control)
        double hSpeed = to.getHorizontalSpeed();
        if (hSpeed > 0.3) {
            cost += (hSpeed - 0.3) * 0.5;
        }

        // Small penalty for direction changes
        if (from.inputUsed != null && to.inputUsed != null) {
            if (!from.inputUsed.equals(to.inputUsed)) {
                cost += 0.3;
            }
        }

        return cost;
    }

    /**
     * Applies additional penalties to a trajectory based on various factors.
     */
    private void applyTrajectoryPenalties(ParkourNode node, PathPhysics.TrajectoryResult result) {
        // Penalty for long air time
        if (node.ticksInAir > 10) {
            node.addPenalty((node.ticksInAir - 10) * 0.2);
        }

        // Penalty for trajectories that go very high (unnecessary)
        double maxHeight = result.getMaxHeight();
        if (maxHeight > node.y + 3) {
            node.addPenalty((maxHeight - node.y - 3) * 0.3);
        }

        // Reward for landing close to goal
        double goalDist = node.horizontalDistanceTo(goalX, goalZ);
        if (goalDist < 1.0) {
            node.addPenalty(-0.5); // Negative penalty = reward
        }
    }

    // ==================== State Quantization ====================

    /**
     * Creates a quantized hash key for a node's state.
     * This allows the closed set to recognize equivalent states.
     * 
     * Quantization precision:
     * - Position: 0.1 block
     * - Velocity: 0.01 blocks/tick
     * - Yaw: 5 degrees
     */
    private long quantizeState(ParkourNode node) {
        // Quantize position to 0.1 block precision
        int qx = (int) Math.round(node.x * 10);
        int qy = (int) Math.round(node.y * 10);
        int qz = (int) Math.round(node.z * 10);

        // Quantize velocity to 0.01 precision
        int qvx = (int) Math.round(node.vx * 100);
        int qvy = (int) Math.round(node.vy * 100);
        int qvz = (int) Math.round(node.vz * 100);

        // Quantize yaw to 5 degree precision
        int qyaw = (int) Math.round(node.yaw / 5);

        // Combine into a single hash
        long hash = qx;
        hash = hash * 31 + qy;
        hash = hash * 31 + qz;
        hash = hash * 31 + qvx;
        hash = hash * 31 + qvy;
        hash = hash * 31 + qvz;
        hash = hash * 31 + qyaw;
        hash = hash * 31 + node.ticksInAir;
        hash = hash * 31 + (node.isSprinting ? 1 : 0);
        hash = hash * 31 + (node.isSneaking ? 1 : 0);

        return hash;
    }

    // ==================== Path Reconstruction ====================

    /**
     * Reconstructs the path from goal to start by following parent pointers.
     * Returns keyframe nodes only (where input changes).
     */
    private List<ParkourNode> reconstructPath(ParkourNode goal) {
        List<ParkourNode> fullPath = new ArrayList<>();
        ParkourNode current = goal;

        while (current != null) {
            fullPath.add(0, current);
            current = current.parent;
        }

        // Simplify to keyframes
        return simplifyToKeyframes(fullPath);
    }

    /**
     * Simplifies a path to only include keyframe nodes where the input changes.
     */
    private List<ParkourNode> simplifyToKeyframes(List<ParkourNode> fullPath) {
        if (fullPath.size() <= 2) return fullPath;

        List<ParkourNode> keyframes = new ArrayList<>();
        keyframes.add(fullPath.get(0));

        MovementInput lastInput = null;
        for (int i = 1; i < fullPath.size(); i++) {
            ParkourNode node = fullPath.get(i);
            MovementInput currentInput = node.inputUsed;

            // Include the node where the input changes (not the one before it)
            if (currentInput == null || !currentInput.equals(lastInput) || i == fullPath.size() - 1) {
                node.isKeyframe = true;
                keyframes.add(node);
                lastInput = currentInput;
            }
        }

        // Always include the goal
        if (!keyframes.contains(fullPath.get(fullPath.size() - 1))) {
            fullPath.get(fullPath.size() - 1).isKeyframe = true;
            keyframes.add(fullPath.get(fullPath.size() - 1));
        }

        return keyframes;
    }

    // ==================== Statistics ====================

    /**
     * Gets the number of nodes expanded during the last search.
     */
    public int getNodesExpanded() {
        return nodesExpanded;
    }

    /**
     * Gets the number of nodes generated during the last search.
     */
    public int getNodesGenerated() {
        return nodesGenerated;
    }

    /**
     * Gets the number of collision checks performed.
     */
    public int getCollisionChecks() {
        return collisionChecks;
    }

    /**
     * Gets the time taken for the last search in milliseconds.
     */
    public long getSearchTimeMs() {
        return searchTimeMs;
    }

    // ==================== Static Helper Methods ====================

    /**
     * Finds a parkour path from start to goal.
     */
    public static List<ParkourNode> findParkourPath(ServerWorld world, Vec3d startPos,
                                                      BlockPos goalPos, float yaw, boolean sprinting) {
        KinematicPathfinder pathfinder = new KinematicPathfinder(world, goalPos);
        return pathfinder.findPath(startPos.x, startPos.y, startPos.z, yaw, sprinting);
    }

    /**
     * Finds a parkour path from start block to goal block.
     */
    public static List<ParkourNode> findParkourPath(ServerWorld world, BlockPos startBP,
                                                      BlockPos goalBP, float yaw) {
        Vec3d startPos = new Vec3d(startBP.getX() + 0.5, startBP.getY(), startBP.getZ() + 0.5);
        return findParkourPath(world, startPos, goalBP, yaw, true);
    }

    /**
     * Finds a parkour path with custom configuration.
     */
    public static List<ParkourNode> findParkourPath(ServerWorld world, double startX, double startY, double startZ,
                                                      BlockPos goalPos, float yaw, boolean sprinting) {
        KinematicPathfinder pathfinder = new KinematicPathfinder(world, goalPos);
        return pathfinder.findPath(startX, startY, startZ, yaw, sprinting);
    }
}