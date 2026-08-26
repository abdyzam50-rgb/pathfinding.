package net.netherite.tutorialmod.pathfinder;

import net.minecraft.block.SlimeBlock;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.netherite.tutorialmod.PathNode;
import java.util.*;

public class PathfinderEngine {

    private static final int MAX_SEARCH_NODES      = 10000;
    private static final int MAX_FALL_BLOCKS       = 4;
    // Maximum reliable sprint-jump distance center-to-center at full sprint speed.
    // Physics: ground sprint (0.286) + boost (0.2) = 0.486 initial, ~12 air ticks.
    // dist=4 → 4-block gap: achievable from ~0.34 past launch-node center.
    // dist=5 → 5-block gap: requires perfect launch position AND max speed — too
    // unreliable; the bot falls short and walks off the edge, so we cap at 4.
    private static final int MAX_SPRINT_JUMP       = 4;
    // Physics-based ceiling: max horizontal reach at full sprint speed with air accel.
    // Used to reject canSprintJumpTo calls that are geometrically valid but physically impossible.
    private static final double MAX_SPRINT_JUMP_REACH = computeMaxSprintJumpReach();
    // Neo jump: 5-block gap requiring a wall clip at position 1
    private static final int NEO_JUMP_DIST         = 5;
    // Water-bucket clutch allows falls up to this many blocks
    private static final int MAX_WATER_FALL_BLOCKS = 50;
    // Horizontal distance between consecutive stride waypoints in sprint paths
    private static final double SPRINT_STRIDE      = 3.0;
    /** Vanilla sprint-jump clears at most ~1.25 blocks vertically — never plan above this. */
    public static final double MAX_JUMP_UP_BLOCKS  = 1.25;

    // 8-direction movement: cardinal + diagonal
    private static final int[][] DIRS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1},
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    // Small RNG for heuristic jitter (humanization — breaks tie-breaking so
    // repeated runs take slightly different but equally valid routes)
    private static final Random JITTER = new Random();

    /**
     * Simulates a sprint jump at maximum achievable speed and returns the total
     * horizontal distance traveled before the player returns to launch height.
     * Constants: ground sprint 0.286 + boost 0.2 = 0.486 initial, 0.026/tick air
     * acceleration, 0.91/tick horizontal drag, 0.42 jump impulse.
     */
    /**
     * Maximum horizontal distance a sprint-jump can cover, used as the pathfinder's
     * physics gate in canSprintJumpTo().  Uses the same full-arc py-tracking physics
     * as estimateSprintJumpDistance() so the gate and the execution model agree.
     * Constants: sprint 0.286 + boost 0.2 = 0.486 initial, 0.026/tick air accel,
     * 0.91 horizontal drag, 0.42 jump impulse.
     */
    private static double computeMaxSprintJumpReach() {
        double vx = 0.286 + 0.2;
        double vy = 0.42;
        double py = 0;
        double totalX = 0;
        for (int t = 0; t < 40; t++) {
            vx += 0.026;
            totalX += vx;
            vx *= 0.91;
            py += vy;
            vy = (vy - 0.08) * 0.98;
            if (py <= 0 && t > 3) break; // full arc — lands when back at launch height
        }
        return totalX;
    }

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    public static List<PathNode> findNodePath(World world, BlockPos start, BlockPos end) {
        return runAStar(world, start, null, end, false, false);
    }

    public static List<PathNode> findNodePath(World world, BlockPos start, BlockPos end,
                                              boolean useMultiAngle) {
        return runAStar(world, start, null, end, useMultiAngle, false);
    }

    public static List<PathNode> findNodePath(World world, BlockPos start, BlockPos end,
                                              boolean useMultiAngle, boolean hasWaterBucket) {
        return runAStar(world, start, null, end, false, hasWaterBucket);
    }

    /** Overload with sub-block start precision — places the start node at the player's
     *  EXACT decimal position rather than the integer block centre.
     *  Use when the player may not be standing at block centre (re-plans, half-off-block). */
    public static List<PathNode> findNodePath(World world, BlockPos start, Vec3d startPrecise,
                                              BlockPos end, boolean hasWaterBucket) {
        return runAStar(world, start, startPrecise, end, false, hasWaterBucket);
    }

    /**
     * Sprint path: runs the same clean Theta*-based A* as the walk path, then
     * overlays SPRINT_JUMP stride nodes throughout every horizontal segment via
     * {@link #sprintifyPath}.  This gives the cleanest possible diagonal routes
     * (Theta* handles straightening) with sprint-jump locomotion on top of them.
     */
    public static List<PathNode> findSprintNodePath(World world, BlockPos start, BlockPos end) {
        return findSprintNodePath(world, start, null, end, false);
    }

    public static List<PathNode> findSprintNodePath(World world, BlockPos start, BlockPos end,
                                                    boolean hasWaterBucket) {
        List<PathNode> path = runAStar(world, start, null, end, false, hasWaterBucket);
        return sprintifyPath(path);
    }

    /** Sprint-path overload with sub-block start precision. */
    public static List<PathNode> findSprintNodePath(World world, BlockPos start, Vec3d startPrecise,
                                                     BlockPos end, boolean hasWaterBucket) {
        return findSprintNodePath(world, start, startPrecise, end, hasWaterBucket, false);
    }

    public static List<PathNode> findSprintNodePath(World world, BlockPos start, Vec3d startPrecise,
                                                     BlockPos end, boolean hasWaterBucket,
                                                     boolean hasBuildingBlocks) {
        List<PathNode> path = runAStar(world, start, startPrecise, end, true, hasWaterBucket, hasBuildingBlocks);
        path = sprintifyPath(path);
        return BoostPlaceInjector.inject(path, world, hasBuildingBlocks);
    }

    public static List<PathNode> findNodePath(World world, BlockPos start, Vec3d startPrecise,
                                              BlockPos end, boolean hasWaterBucket,
                                              boolean useMultiAngle, boolean hasBuildingBlocks) {
        List<PathNode> path = runAStar(world, start, startPrecise, end, false, hasWaterBucket, hasBuildingBlocks);
        return BoostPlaceInjector.inject(path, world, hasBuildingBlocks);
    }

    /**
     * If {@code candidate} is inside a solid block (player standing at block edge so
     * floor(playerX) lands inside a wall), scan the surrounding 8 XZ positions and return
     * the nearest passable, grounded block.  Falls back to {@code candidate} if nothing
     * better is found (the A* will simply produce an empty path).
     */
    private static BlockPos resolveValidStart(World world, BlockPos candidate, Vec3d precise) {
        // Fast path: candidate is already valid
        if (world.getBlockState(candidate).isAir()
                && world.getBlockState(candidate.up()).isAir()
                && hasSolidGround(world, candidate)) {
            return candidate;
        }
        // Scan neighbours at the same Y for the nearest passable block
        double px = (precise != null) ? precise.x : candidate.getX() + 0.5;
        double pz = (precise != null) ? precise.z : candidate.getZ() + 0.5;
        BlockPos best = candidate;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos nb = candidate.add(dx, 0, dz);
                if (world.getBlockState(nb).isAir()
                        && world.getBlockState(nb.up()).isAir()
                        && hasSolidGround(world, nb)) {
                    double dist = (px - (nb.getX() + 0.5)) * (px - (nb.getX() + 0.5))
                                + (pz - (nb.getZ() + 0.5)) * (pz - (nb.getZ() + 0.5));
                    if (dist < bestDist) { bestDist = dist; best = nb; }
                }
            }
        }
        return best;
    }

    private static List<PathNode> runAStar(World world, BlockPos start, Vec3d startPrecise,
                                           BlockPos end, boolean sprintMode, boolean hasWaterBucket) {
        return runAStar(world, start, startPrecise, end, sprintMode, hasWaterBucket, false);
    }

    private static List<PathNode> runAStar(World world, BlockPos start, Vec3d startPrecise,
                                           BlockPos end, boolean sprintMode, boolean hasWaterBucket,
                                           boolean hasBuildingBlocks) {
        if (world == null || start == null || end == null) return Collections.emptyList();
        // Resolve: player standing at block edge can have getBlockPos() land inside a solid
        // wall.  Find the nearest valid (passable, grounded) block before searching.
        start = resolveValidStart(world, start, startPrecise);

        PriorityQueue<PathNode> openSet = new PriorityQueue<>(
                Comparator.comparingDouble(n -> n.gCost + heuristic(n.pos, end)));
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, PathNode> openMap = new HashMap<>();

        // Use the caller's exact position if provided (e.g., player standing mid-block).
        // Falls back to block centre when no precise position is available.
        Vec3d preciseStart = (startPrecise != null) ? startPrecise
                : new Vec3d(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
        PathNode startNode = new PathNode(start, preciseStart, null, PathNode.Type.WALK);
        startNode.gCost = 0;
        openSet.add(startNode);
        openMap.put(start, startNode);

        PathNode targetNode = null;
        int searched = 0;

        while (!openSet.isEmpty() && searched < MAX_SEARCH_NODES) {
            PathNode current = openSet.poll();
            // Lazy deletion: skip stale entries (a cheaper update added a better node later)
            PathNode best = openMap.get(current.pos);
            if (best != null && current.gCost > best.gCost + 1e-9) continue;
            openMap.remove(current.pos);
            closedSet.add(current.pos);
            searched++;

            if (isGoalReached(current.pos, end)) {
                targetNode = current;
                break;
            }

            for (PathNode rawNeighbor : getNeighbors(world, current, start, end, sprintMode, hasWaterBucket, hasBuildingBlocks)) {
                if (closedSet.contains(rawNeighbor.pos)) continue;

                // ---------------------------------------------------------------
                // Theta* parent propagation — any-angle paths
                //
                // If the grandparent (current.parent) has line-of-sight to this
                // neighbour, create an edge directly from the grandparent, bypassing
                // the current node entirely.  The resulting edge can be at ANY angle
                // (not just the 8 grid directions), so paths naturally straighten out
                // across flat terrain and around corners.
                //
                // Only apply to WALK nodes on the same Y level; other types have
                // special physics that need explicit parent handling.
                // ---------------------------------------------------------------
                PathNode effectiveParent = current;
                double   newG            = current.gCost + moveCost(current, rawNeighbor, sprintMode);

                if (current.parent != null
                        && rawNeighbor.type == PathNode.Type.WALK
                        && current.type     == PathNode.Type.WALK
                        && rawNeighbor.pos.getY() == current.parent.pos.getY()
                        && isPathClear(world, current.parent.pos, rawNeighbor.pos, start, end)) {
                    int  tdx     = rawNeighbor.pos.getX() - current.parent.pos.getX();
                    int  tdz     = rawNeighbor.pos.getZ() - current.parent.pos.getZ();
                    double thetaG = current.parent.gCost
                                    + Math.sqrt((double) tdx * tdx + (double) tdz * tdz);
                    if (thetaG < newG) {
                        effectiveParent = current.parent;
                        newG            = thetaG;
                    }
                }

                // Build the neighbour node with the chosen parent
                PathNode neighbor = (effectiveParent == current) ? rawNeighbor
                        : new PathNode(rawNeighbor.pos, rawNeighbor.precisePos,
                                       effectiveParent, rawNeighbor.type);

                PathNode existing = openMap.get(neighbor.pos);
                if (existing == null || newG < existing.gCost - 1e-9) {
                    // Lazy insertion: just add the new node; the stale old entry will be
                    // skipped when it surfaces from the heap (see lazy-deletion check above).
                    neighbor.gCost = newG;
                    openSet.add(neighbor);
                    openMap.put(neighbor.pos, neighbor);
                }
            }
        }

        List<PathNode> path = new ArrayList<>();
        if (targetNode != null) {
            PathNode cur = targetNode;
            while (cur != null) { path.add(cur); cur = cur.parent; }
            Collections.reverse(path);
        }

        path = markEdgeNodes(path);
        path = simplifyPath(world, path, start, end);
        path = smoothPrecisePositions(path);
        path = adjustSprintJumpTakeoffPositions(path); // place launch nodes at the leading edge
        return path;
    }

    // -----------------------------------------------------------------------
    // Heuristic
    // -----------------------------------------------------------------------

    private static double heuristic(BlockPos pos, BlockPos end) {
        double dx = pos.getX() - end.getX();
        double dy = pos.getY() - end.getY();
        double dz = pos.getZ() - end.getZ();
        double base = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // ±1% jitter — humanizes tie-breaking without making the heuristic inadmissible
        return base * (1.0 + (JITTER.nextDouble() - 0.5) * 0.02);
    }

    private static boolean isGoalReached(BlockPos pos, BlockPos end) {
        return pos.getSquaredDistance(end) <= 1.0;
    }

    /**
     * Movement cost uses only the horizontal distance (XZ) so that single-block jumps
     * or drops are never penalised more than a short detour around them.
     *
     *  WALK        = hdist                 (pure horizontal distance)
     *  JUMP        = hdist + 0.4           (small fixed penalty for the jump action)
     *  SPRINT_JUMP = hdist + 0.2           (even cheaper; fast traversal)
     *  DROP        = hdist + 0.1 * |dy|    (scales gently with fall height)
     *  CLIMB       = hdist + |dy| * 1.5    (vertical climbing is the expensive part)
     *  INTERACT    = hdist + 2.0           (door interaction has fixed overhead)
     */
    private static double moveCost(PathNode from, PathNode to, boolean sprintMode) {
        double dx    = to.pos.getX() - from.pos.getX();
        double dz    = to.pos.getZ() - from.pos.getZ();
        double dy    = to.pos.getY() - from.pos.getY();
        double hdist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (sprintMode && to.type == PathNode.Type.SPRINT_JUMP) return hdist * 0.5;
        return switch (to.type) {
            case JUMP        -> hdist + 0.4;
            case SPRINT_JUMP -> hdist + 0.2;
            case BOOST_PLACE -> hdist + 0.5;
            case DROP        -> hdist + Math.abs(dy) * 0.1;
            case WATER_DROP  -> hdist + Math.abs(dy) * 0.1;
            // BOUNCE: cheap because the physics bounce covers the next segment for free
            case BOUNCE      -> hdist + Math.abs(dy) * 0.05;
            case CLIMB       -> hdist + Math.abs(dy) * 1.5;
            case INTERACT    -> hdist + 2.0;
            case BRIDGE      -> hdist + 1.5;  // place block then cross gap
            case PILLAR      -> 2.5;          // jump + place (1 block rise, no horizontal)
            default          -> hdist;
        };
    }

    // -----------------------------------------------------------------------
    // Neighbor generation
    // -----------------------------------------------------------------------

    /**
     * @param pathStart the global A* start — used for isPassable bypass only
     * @param pathEnd   the global A* end   — used for isPassable bypass only
     * @param sprintMode when true, flat sprint-jumps are generated and cost 50% less
     */
    private static List<PathNode> getNeighbors(World world, PathNode current,
                                               BlockPos pathStart, BlockPos pathEnd,
                                               boolean sprintMode, boolean hasWaterBucket,
                                               boolean hasBuildingBlocks) {
        List<PathNode> neighbors = new ArrayList<>();
        BlockPos pos = current.pos;

        // BOUNCE nodes (slime landing): generate bounce-arc reachable neighbors
        // instead of normal walk neighbors — the physics bounce carries the player.
        if (current.type == PathNode.Type.BOUNCE) {
            addBounceNeighbors(world, current, pathStart, pathEnd, neighbors);
            return neighbors;
        }

        for (int[] dir : DIRS) {
            addHorizontalOptions(world, current, dir[0], dir[1], pathStart, pathEnd,
                                 sprintMode, hasWaterBucket, hasBuildingBlocks, neighbors);
        }

        // Vertical climbing upward (ladders, vines, scaffolding)
        BlockPos climbUp = pos.up();
        if (canClimbTo(world, pos, climbUp, pathStart, pathEnd)) {
            Vec3d precise = new Vec3d(pos.getX() + 0.5, climbUp.getY(), pos.getZ() + 0.5);
            neighbors.add(new PathNode(climbUp, precise, current, PathNode.Type.CLIMB));
        }

        // Vertical climbing downward (ladders, vines)
        BlockPos climbDown = pos.down();
        if (canClimbDown(world, pos, climbDown, pathStart, pathEnd)) {
            Vec3d precise = new Vec3d(pos.getX() + 0.5, climbDown.getY(), pos.getZ() + 0.5);
            neighbors.add(new PathNode(climbDown, precise, current, PathNode.Type.CLIMB));
        }

        // PILLAR: jump and place block at feet to rise 1 block (requires building blocks)
        if (hasBuildingBlocks && hasSolidGround(world, pos)) {
            BlockPos pillarTarget = pos.up();
            BlockPos pillarHead   = pos.up(2);
            if (passable(world, pillarTarget, pathStart, pathEnd)
                    && passable(world, pillarHead, pathStart, pathEnd)
                    && !canClimbTo(world, pos, pillarTarget, pathStart, pathEnd)) {
                Vec3d precise = new Vec3d(pos.getX() + 0.5, pillarTarget.getY(), pos.getZ() + 0.5);
                neighbors.add(new PathNode(pillarTarget, precise, current, PathNode.Type.PILLAR, pos));
            }
        }

        return neighbors;
    }

    private static void addHorizontalOptions(World world, PathNode current, int dx, int dz,
                                             BlockPos pathStart, BlockPos pathEnd,
                                             boolean sprintMode, boolean hasWaterBucket,
                                             boolean hasBuildingBlocks,
                                             List<PathNode> out) {
        BlockPos from = current.pos;
        boolean diagonal = (dx != 0 && dz != 0);
        BlockPos target = from.add(dx, 0, dz);

        // Diagonal corner-cutting check
        if (diagonal && !canCutCorner(world, from, dx, dz, pathStart, pathEnd)) return;

        // --- Walk (same Y level) ---
        if (canWalkTo(world, from, target, pathStart, pathEnd)) {
            Vec3d precise = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            out.add(new PathNode(target, precise, current, PathNode.Type.WALK));
        } else if (!diagonal && hasBuildingBlocks && canBridgeTo(world, target, pathStart, pathEnd)) {
            // BRIDGE: target has no floor — place a block at the gap to cross it
            Vec3d precise = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
            out.add(new PathNode(target, precise, current, PathNode.Type.BRIDGE, target.down()));
        }

        // --- Jump up 1 block (adjacent solid block) ---
        BlockPos jumpLanding = target.up();
        if (canJumpTo(world, from, target, jumpLanding, pathStart, pathEnd)) {
            Vec3d precise = new Vec3d(target.getX() + 0.5, jumpLanding.getY(), target.getZ() + 0.5);
            out.add(new PathNode(jumpLanding, precise, current, PathNode.Type.JUMP));
        }

        // --- Drop (walk off edge) — cardinal only ---
        // Slime-block landing → BOUNCE; everything else → DROP.
        if (!diagonal) {
            BlockPos landing = findDropLanding(world, from, target, pathStart, pathEnd);
            if (landing != null) {
                boolean isSlime = world.getBlockState(landing.down()).getBlock() instanceof SlimeBlock;
                Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                out.add(new PathNode(landing, precise, current,
                        isSlime ? PathNode.Type.BOUNCE : PathNode.Type.DROP));
            }

            // Sprint-drop: player runs through 2-3 blocks of open ledge while falling,
            // landing further out than a plain gravity drop thanks to sprint momentum.
            for (int hFwd = 2; hFwd <= 3; hFwd++) {
                BlockPos sd = findSprintDropLanding(world, from, dx, dz, hFwd, pathStart, pathEnd);
                if (sd != null) {
                    boolean isSlime = world.getBlockState(sd.down()).getBlock() instanceof SlimeBlock;
                    Vec3d precise = new Vec3d(sd.getX() + 0.5, sd.getY(), sd.getZ() + 0.5);
                    out.add(new PathNode(sd, precise, current,
                            isSlime ? PathNode.Type.BOUNCE : PathNode.Type.DROP));
                    break; // closest valid sprint-drop landing is enough
                }
            }
        }

        // --- Sprint jump — cardinal only ---
        if (!diagonal) {
            if (sprintMode) {
                // Sprint mode: jump 2-3 blocks even on flat ground (continuous sprint-jumping)
                for (int dist = 2; dist <= 3; dist++) {
                    if (canSprintJumpFlat(world, from, dx, dz, dist, pathStart, pathEnd)) {
                        BlockPos landing = from.add(dx * dist, 0, dz * dist);
                        Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                        out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
                        break;
                    }
                }
            } else {
                // Normal mode: sprint jump over gaps (same Y)
                boolean added = false;
                for (int dist = 2; dist <= MAX_SPRINT_JUMP; dist++) {
                    if (canSprintJumpTo(world, from, dx, dz, dist, pathStart, pathEnd)) {
                        BlockPos landing = from.add(dx * dist, 0, dz * dist);
                        Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                        out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
                        added = true;
                        break;
                    }
                }
                // Boost-place: gap with no landing floor — place block mid-arc
                if (!added && hasBuildingBlocks) {
                    for (int dist = 2; dist <= MAX_SPRINT_JUMP; dist++) {
                        PathNode boost = BoostPlaceInjector.tryBoostPlaceNeighbor(
                                world, from, dx, dz, dist, current);
                        if (boost != null) { out.add(boost); break; }
                    }
                }
            }

            // --- Sprint-jump uphill: +1 Y, dist 1-2 (fence tops, elevated platforms) ---
            for (int dist = 1; dist <= 2; dist++) {
                if (canSprintJumpUphill(world, from, dx, dz, dist, pathStart, pathEnd)) {
                    BlockPos landing = from.add(dx * dist, 1, dz * dist);
                    Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                    out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
                    break;
                }
            }

            // --- Sprint-jump downhill: -1 Y (existing), -2 Y, -3 Y (new deep-drop) ---
            for (int yDrop = 1; yDrop <= 3; yDrop++) {
                int maxDist = (yDrop == 1) ? 3 : 2; // narrower range for larger drops
                for (int dist = 1; dist <= maxDist; dist++) {
                    boolean ok = (yDrop == 1)
                            ? canSprintJumpDownhill(world, from, dx, dz, dist, pathStart, pathEnd)
                            : canSprintJumpDeepDrop(world, from, dx, dz, dist, yDrop, pathStart, pathEnd);
                    if (ok) {
                        BlockPos landing = from.add(dx * dist, -yDrop, dz * dist);
                        Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                        out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
                        break;
                    }
                }
            }

            // --- Combined sprint-jump then large drop: yDrop 4..MAX_FALL_BLOCKS ---
            // Player jumps and stays airborne for the full arc, landing far below.
            // Skips small intermediate platforms that would be hard to land on.
            for (int yDrop = 4; yDrop <= MAX_FALL_BLOCKS; yDrop++) {
                for (int dist = 1; dist <= 3; dist++) {
                    if (canSprintJumpThenDrop(world, from, dx, dz, dist, yDrop, pathStart, pathEnd)) {
                        BlockPos landing = from.add(dx * dist, -yDrop, dz * dist);
                        Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                        out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
                        break;
                    }
                }
            }

            // --- Neo jump: 5-block gap with a solid neo-block at position 1 ---
            if (canNeoJump(world, from, dx, dz, pathStart, pathEnd)) {
                BlockPos landing = from.add(dx * NEO_JUMP_DIST, 0, dz * NEO_JUMP_DIST);
                Vec3d precise = new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
                out.add(new PathNode(landing, precise, current, PathNode.Type.SPRINT_JUMP));
            }
        }

        // --- Door / fence gate interaction (cardinal only) ---
        if (!diagonal && PathingEnvironment.isClosedOpenableDoor(world, target)) {
            BlockPos throughDoor = from.add(dx * 2, 0, dz * 2);
            if (passable(world, throughDoor, pathStart, pathEnd)
                    && passable(world, throughDoor.up(), pathStart, pathEnd)
                    && hasSolidGround(world, throughDoor)) {
                Vec3d precise = new Vec3d(target.getX() + 0.5, from.getY(), target.getZ() + 0.5);
                out.add(new PathNode(target, precise, current, PathNode.Type.INTERACT, target));
            }
        }

        // --- Water-bucket clutch drop (any height beyond normal drop range, cardinal only) ---
        // Only generated when the player has a water bucket in the hotbar.
        if (!diagonal && hasWaterBucket) {
            BlockPos wcLanding = findDropLandingWaterClutch(world, from, target, pathStart, pathEnd);
            // Only use WATER_DROP for falls strictly deeper than what a normal DROP covers
            if (wcLanding != null && (from.getY() - wcLanding.getY() > MAX_FALL_BLOCKS)) {
                Vec3d precise = new Vec3d(wcLanding.getX() + 0.5, wcLanding.getY(), wcLanding.getZ() + 0.5);
                out.add(new PathNode(wcLanding, precise, current, PathNode.Type.WATER_DROP));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Movement validators
    // -----------------------------------------------------------------------

    /**
     * Standard walk: same Y, both feet+head passable, solid ground, no hazard, clear line.
     */
    private static boolean canWalkTo(World world, BlockPos from, BlockPos to,
                                     BlockPos pathStart, BlockPos pathEnd) {
        if (!passable(world, to, pathStart, pathEnd)) return false;
        if (!passable(world, to.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, to)) return false;
        // Reject hazardous destinations and their floors
        if (PathingEnvironment.isHazardous(world, to)) return false;
        if (PathingEnvironment.isHazardous(world, to.down())) return false;
        if (!isPathClear(world, from, to, pathStart, pathEnd)) return false;
        return true;
    }

    /**
     * Bridge: target is passable but has no floor — a block can be placed at the
     * 1-wide gap (target.down()) to let the player walk across at the same Y level.
     */
    private static boolean canBridgeTo(World world, BlockPos target,
                                        BlockPos pathStart, BlockPos pathEnd) {
        if (!passable(world, target, pathStart, pathEnd)) return false;     // can't stand at target
        if (hasSolidGround(world, target)) return false;                    // floor exists — use WALK
        if (!world.getBlockState(target.down()).isAir()) return false;      // gap must be air (not water/lava)
        if (!passable(world, target.up(), pathStart, pathEnd)) return false; // need headroom at destination
        if (PathingEnvironment.isHazardous(world, target)) return false;
        return true;
    }

    /**
     * Jump up 1 block: the ledge (stepBlock) must be solid but NOT a tall obstacle
     * (fences/walls are 1.5 blocks high and cannot be landed on top of).
     */
    private static boolean canJumpTo(World world, BlockPos from, BlockPos stepBlock,
                                     BlockPos jumpLanding, BlockPos pathStart, BlockPos pathEnd) {
        if (jumpLanding.getY() != from.getY() + 1) return false;
        if (!hasSolidGround(world, from)) return false;

        // The step-up ledge must be solid (that's what we're jumping ONTO)
        if (passable(world, stepBlock, pathStart, pathEnd)) return false;

        // Fences and walls are 1.5 blocks tall — cannot land on top of them
        if (PathingEnvironment.isTallObstacle(world, stepBlock)) return false;

        // Clear space at landing: feet + head
        if (!passable(world, jumpLanding, pathStart, pathEnd)) return false;
        if (!passable(world, jumpLanding.up(), pathStart, pathEnd)) return false;

        // Jump arc clearance: 2 blocks above launch position
        if (!passable(world, from.up(), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        // Must land on something solid and safe
        if (!hasSolidGround(world, jumpLanding)) return false;
        if (PathingEnvironment.isHazardous(world, jumpLanding)) return false;

        return true;
    }

    /**
     * Sprint jump: leap over a gap of 2–4 blocks at the same Y level.
     * The gap must exist (at least one intermediate position lacks solid ground).
     * All intermediate positions must be clear at feet+body+head for arc clearance.
     */
    private static boolean canSprintJumpTo(World world, BlockPos from, int dx, int dz, int dist,
                                           BlockPos pathStart, BlockPos pathEnd) {
        // Physics gate: reject if the center-to-center distance exceeds the maximum
        // horizontal reach achievable at full sprint speed.  A 0.5-block tolerance
        // accounts for the player launching slightly past block center.
        if (dist > MAX_SPRINT_JUMP_REACH + 0.5) return false;

        // Need solid ground to launch from
        if (!hasSolidGround(world, from)) return false;

        // Launch clearance — player body + jump arc height
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(3), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, 0, dz * dist);

        // Landing must be passable at feet and head
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, landing)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // At least one intermediate position must lack solid ground (otherwise just walk)
        boolean gapExists = false;
        for (int i = 1; i < dist; i++) {
            if (!hasSolidGround(world, from.add(dx * i, 0, dz * i))) {
                gapExists = true;
                break;
            }
        }
        if (!gapExists) return false;

        // Arc clearance: intermediate positions must be open at feet, body, and arc peak.
        // Also reject any arc position where a fence/wall below bleeds its collision box
        // into mid.up(1) space — passable() misses this because the block at mid.up(1)
        // is air even though the fence physically occludes that height.
        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,        pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1),  pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2),  pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1)))               return false;
        }

        return true;
    }

    /** Sprint jump valid if a block is placed at {@code placedGround} before landing. */
    public static boolean canSprintJumpToWithPlacedGround(World world, BlockPos from, int dx, int dz,
                                                          int dist, BlockPos placedGround,
                                                          BlockPos pathStart, BlockPos pathEnd) {
        if (dist > MAX_SPRINT_JUMP_REACH + 0.5) return false;
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(3), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, 0, dz * dist);
        if (!landing.down().equals(placedGround)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        boolean gapExists = false;
        for (int i = 1; i < dist; i++) {
            if (!hasSolidGround(world, from.add(dx * i, 0, dz * i))) {
                gapExists = true;
                break;
            }
        }
        if (!gapExists) return false;

        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid, pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1))) return false;
        }
        return true;
    }

    /**
     * Sprint jump on flat terrain (sprint-path mode only).
     * No gap required — the player simply leaps forward 2-3 blocks continuously.
     * Still requires arc clearance and a valid landing surface.
     */
    private static boolean canSprintJumpFlat(World world, BlockPos from, int dx, int dz, int dist,
                                             BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, 0, dz * dist);
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, landing)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // Arc clearance over intermediate blocks (fence-top extension check included)
        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1)))              return false;
        }
        return true;
    }

    /**
     * Drop: walk into an open air column and fall to the first solid landing.
     * Returns the landing BlockPos or null.
     */
    private static BlockPos findDropLanding(World world, BlockPos from, BlockPos target,
                                            BlockPos pathStart, BlockPos pathEnd) {
        if (!passable(world, target, pathStart, pathEnd)) return null;
        if (!passable(world, target.up(), pathStart, pathEnd)) return null;
        if (!hasSolidGround(world, from)) return null;   // need solid ground to walk off
        if (hasSolidGround(world, target)) return null;  // normal walk handles this case

        for (int drop = 1; drop <= MAX_FALL_BLOCKS; drop++) {
            BlockPos land = new BlockPos(target.getX(), target.getY() - drop, target.getZ());
            if (!passable(world, land, pathStart, pathEnd)) return null;
            if (!passable(world, land.up(), pathStart, pathEnd)) return null;
            if (PathingEnvironment.isHazardous(world, land)) return null;
            if (PathingEnvironment.isHazardous(world, land.down())) return null;
            if (hasSolidGround(world, land)) return land;
        }
        return null;
    }

    /**
     * Sprint-drop: player runs off a ledge at sprint speed (no jump) and lands
     * {@code hForward} block-columns ahead.  All intermediate columns must be passable
     * (the player sprints through them before the floor disappears).  A physics gate
     * checks that sprint velocity can cover the horizontal distance before the player
     * would have already landed.
     */
    private static BlockPos findSprintDropLanding(World world, BlockPos from, int dx, int dz,
                                                   int hForward, BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return null;
        // Every intermediate column (1 … hForward) must be passable at player level
        for (int i = 1; i <= hForward; i++) {
            BlockPos col = from.add(dx * i, 0, dz * i);
            if (!passable(world, col, pathStart, pathEnd)) return null;
            if (!passable(world, col.up(), pathStart, pathEnd)) return null;
        }
        // The far column must NOT have solid ground at player level (it is the drop-off edge)
        BlockPos farCol = from.add(dx * hForward, 0, dz * hForward);
        if (hasSolidGround(world, farCol)) return null;

        // Find the solid landing below the far column
        for (int drop = 1; drop <= MAX_FALL_BLOCKS; drop++) {
            BlockPos land = new BlockPos(farCol.getX(), farCol.getY() - drop, farCol.getZ());
            if (!passable(world, land, pathStart, pathEnd)) return null;
            if (!passable(world, land.up(), pathStart, pathEnd)) return null;
            if (PathingEnvironment.isHazardous(world, land)) return null;
            if (PathingEnvironment.isHazardous(world, land.down())) return null;
            if (hasSolidGround(world, land)) {
                // Physics gate: at sprint speed can the player cover hForward blocks while
                // falling `drop` blocks?  Simulate sprint-horizontal + gravity.
                double vx = 0.286, vy = 0, py = 0, totalX = 0;
                for (int t = 0; t < 30; t++) {
                    vx += 0.026; totalX += vx; vx *= 0.91;
                    py += vy; vy = (vy - 0.08) * 0.98;
                    if (py <= -drop) break;
                }
                return (totalX >= hForward - 0.5) ? land : null;
            }
        }
        return null;
    }

    /** Vertical ladder/vine/scaffold climbing: same X/Z column, straight up. */
    private static boolean canClimbTo(World world, BlockPos from, BlockPos to,
                                      BlockPos pathStart, BlockPos pathEnd) {
        if (to.getY() != from.getY() + 1) return false;
        if (to.getX() != from.getX() || to.getZ() != from.getZ()) return false;

        if (!PathingEnvironment.isClimbable(world, to) && !PathingEnvironment.isClimbable(world, from)) {
            return false;
        }
        if (!passable(world, to, pathStart, pathEnd)) return false;
        if (!passable(world, to.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, from) && !PathingEnvironment.isClimbable(world, from)) return false;

        return true;
    }

    /**
     * Descend one block on a climbable surface (ladders, vines, scaffolding).
     * The player simply releases all movement keys and gravity does the work;
     * the climbable block must be present at the current OR the next position.
     */
    private static boolean canClimbDown(World world, BlockPos from, BlockPos to,
                                        BlockPos pathStart, BlockPos pathEnd) {
        if (to.getY() != from.getY() - 1) return false;
        if (to.getX() != from.getX() || to.getZ() != from.getZ()) return false;

        // At least one of the two positions must contain a climbable block
        if (!PathingEnvironment.isClimbable(world, from) && !PathingEnvironment.isClimbable(world, to)) {
            return false;
        }
        if (!passable(world, to, pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, to)) return false;

        return true;
    }

    // -----------------------------------------------------------------------
    // New parkour move validators
    // -----------------------------------------------------------------------

    /**
     * Sprint-jump uphill: the player sprint-jumps and lands ONE block higher than
     * the launch position, 1-2 blocks away horizontally.
     *
     * Key use: getting onto fence tops (fence provides solid ground at Y+1.5,
     * so hasSolidGround for Y+1 above a fence returns true).
     */
    private static boolean canSprintJumpUphill(World world, BlockPos from, int dx, int dz,
                                                int dist, BlockPos pathStart, BlockPos pathEnd) {
        // Landing is always exactly +1 Y — reject if a 2-block wall blocks the arc.
        BlockPos landing = from.add(dx * dist, 1, dz * dist);
        BlockPos step = from.add(dx * dist, 0, dz * dist);
        if (!passable(world, step, pathStart, pathEnd)
                && !passable(world, step.up(), pathStart, pathEnd)) {
            return false;
        }
        if (!hasSolidGround(world, from)) return false;
        // Launch clearance: arc peaks ~1.25 blocks above the source
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        // The block at from.Y directly below landing provides the landing surface
        // (could be a fence, solid block, slab top, etc.).
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // Intermediate clearance (at both from.Y and from.Y+1 for the arc)
        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1)))              return false;
        }
        return true;
    }

    /**
     * Sprint-jump downhill: the player sprint-jumps and lands ONE block lower
     * than the launch position, 1-3 blocks away horizontally.
     *
     * Handles downhill parkour gaps where the drop is exactly one block.
     */
    private static boolean canSprintJumpDownhill(World world, BlockPos from, int dx, int dz,
                                                  int dist, BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, -1, dz * dist);
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing,       pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(),  pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // The block at landing.Y (= from.Y-1) must be passable at the source column
        // and at least one intermediate column must lack ground → real downhill jump
        BlockPos targetSameY = from.add(dx * dist, 0, dz * dist);
        if (!passable(world, targetSameY, pathStart, pathEnd)) return false;

        // Intermediate clearance at source Y (the body passes through these)
        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,        pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1),  pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1)))               return false;
        }
        return true;
    }

    /**
     * Sprint-jump landing 2 or 3 blocks LOWER than the launch position.
     * Generalises {@link #canSprintJumpDownhill} to larger height drops; uses the
     * same structure but with a caller-supplied {@code yDrop} and a physics gate.
     */
    private static boolean canSprintJumpDeepDrop(World world, BlockPos from, int dx, int dz,
                                                  int dist, int yDrop,
                                                  BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, -yDrop, dz * dist);
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        BlockPos targetSameY = from.add(dx * dist, 0, dz * dist);
        if (!passable(world, targetSameY, pathStart, pathEnd)) return false;

        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1))) return false;
        }
        // Physics gate: sprint-jump (vy0=0.42) must cover dist blocks while falling yDrop
        double vx = 0.486, vy = 0.42, py = 0, totalX = 0;
        for (int t = 0; t < 40; t++) {
            vx += 0.026; totalX += vx; vx *= 0.91;
            py += vy; vy = (vy - 0.08) * 0.98;
            if (py <= -yDrop && t > 3) break;
        }
        return totalX >= dist - 0.5 && dist <= totalX + 0.8;
    }

    /**
     * Combined sprint-jump then large drop (yDrop ≥ 4).  The player jumps and flies a full
     * ballistic arc, landing far below.  No intermediate-column obstacle check is performed
     * (the player is airborne the whole time).  A physics gate verifies reachability.
     */
    private static boolean canSprintJumpThenDrop(World world, BlockPos from, int dx, int dz,
                                                  int dist, int yDrop,
                                                  BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(3), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, -yDrop, dz * dist);
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // Physics gate: full ballistic arc until py <= -yDrop
        double vx = 0.486, vy = 0.42, py = 0, totalX = 0;
        for (int t = 0; t < 60; t++) {
            vx += 0.026; totalX += vx; vx *= 0.91;
            py += vy; vy = (vy - 0.08) * 0.98;
            if (py <= -yDrop && t > 3) break;
        }
        return totalX >= dist - 0.5 && dist <= totalX + 1.0;
    }

    /**
     * Neo jump: sprint-jump 5 blocks with a solid neo-block at position 1.
     *
     * In real parkour a "neo" (or "neo jump") clips the edge of a wall block
     * at position 1, resetting the Y-velocity and extending horizontal range
     * to ~5 blocks.  The bot generates the SPRINT_JUMP node; the PathFollower
     * executes it with the normal sprint-jump locomotion and relies on the
     * wall geometry to clip the trajectory.
     *
     * Detection criteria:
     *  - Block at pos+1 is solid at feet level (the neo wall)
     *  - Head clearance above the neo block exists (the arc clears it)
     *  - Positions 2-4 have a gap (the actual chasm)
     *  - Landing at pos+5 is valid
     */
    private static boolean canNeoJump(World world, BlockPos from, int dx, int dz,
                                       BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;

        // Launch clearance (sprint-jump peaks at ~1.25 blocks, needs 3 clear above)
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(3), pathStart, pathEnd)) return false;

        // Landing at distance 5
        BlockPos landing = from.add(dx * NEO_JUMP_DIST, 0, dz * NEO_JUMP_DIST);
        if (!passable(world, landing,      pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, landing)) return false;
        if (PathingEnvironment.isHazardous(world, landing)) return false;

        // Neo block at position 1: must be solid (wall to clip off)
        // but clearance above it must exist for the jump arc
        BlockPos neoBlock = from.add(dx, 0, dz);
        if (passable(world, neoBlock, pathStart, pathEnd)) return false;  // no wall → no neo
        if (!passable(world, neoBlock.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, neoBlock.up(2), pathStart, pathEnd)) return false;

        // Positions 2-4: must be clear and contain at least one gap (no ground)
        boolean hasGap = false;
        for (int i = 2; i < NEO_JUMP_DIST; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1)))              return false;
            if (!hasSolidGround(world, mid)) hasGap = true;
        }
        return hasGap;
    }

    /**
     * Generates neighbors reachable from a slime-block bounce landing.
     *
     * Physics model (simplified):
     *  - Fall height H → bounce height ≈ H × 0.6
     *  - Time in air ≈ 2 × sqrt(2 × bounceH / g) where g = 0.08 blocks/tick²
     *  - Sprint horizontal reach ≈ bounceTime × 0.26 blocks/tick
     *
     * Searches same-Y AND elevated landing spots (up to half bounceHeight higher)
     * within the calculated radius.
     */
    private static void addBounceNeighbors(World world, PathNode bounceNode,
                                            BlockPos pathStart, BlockPos pathEnd,
                                            List<PathNode> out) {
        BlockPos slimeSurface = bounceNode.pos;  // air block the player lands in

        // Fall height: distance from the edge (parent) to the slime floor
        double fallHeight = (bounceNode.parent != null)
            ? Math.max(1.0, bounceNode.parent.pos.getY() - (slimeSurface.getY() - 1))
            : 4.0;

        double bounceHeight = fallHeight * 0.6;
        double g = 0.08;
        double vy = Math.sqrt(2.0 * g * bounceHeight);
        double bounceTime = 2.0 * vy / g;
        double bounceReach = Math.min(bounceTime * 0.26, 14.0);  // cap 14 blocks
        int reach = (int) Math.ceil(bounceReach);
        int maxUpStep = (int) Math.max(1, bounceHeight * 0.5);

        for (int bx = -reach; bx <= reach; bx++) {
            for (int bz = -reach; bz <= reach; bz++) {
                if (bx == 0 && bz == 0) continue;
                double dist = Math.sqrt((double) bx * bx + (double) bz * bz);
                if (dist > bounceReach) continue;

                // Same-height landing (most common: bounce and land level with slime surface)
                BlockPos land = slimeSurface.add(bx, 0, bz);
                if (passable(world, land, pathStart, pathEnd)
                        && passable(world, land.up(), pathStart, pathEnd)
                        && hasSolidGround(world, land)
                        && !PathingEnvironment.isHazardous(world, land)) {
                    Vec3d precise = new Vec3d(land.getX() + 0.5, land.getY(), land.getZ() + 0.5);
                    out.add(new PathNode(land, precise, bounceNode, PathNode.Type.WALK));
                }

                // Elevated landing: catch platforms higher than the slime surface
                // (player reaches them on the ascending half of the bounce arc)
                for (int by = 1; by <= maxUpStep; by++) {
                    double reducedReach = bounceReach * (1.0 - (double) by / bounceHeight);
                    if (dist > reducedReach) break;
                    BlockPos landUp = slimeSurface.add(bx, by, bz);
                    if (passable(world, landUp, pathStart, pathEnd)
                            && passable(world, landUp.up(), pathStart, pathEnd)
                            && hasSolidGround(world, landUp)
                            && !PathingEnvironment.isHazardous(world, landUp)) {
                        Vec3d precise = new Vec3d(landUp.getX() + 0.5, landUp.getY(), landUp.getZ() + 0.5);
                        out.add(new PathNode(landUp, precise, bounceNode, PathNode.Type.WALK));
                        break; // take the lowest valid landing at each XZ
                    }
                }
            }
        }
    }

    /**
     * Water-bucket clutch drop: same as {@link #findDropLanding} but allows falls
     * up to {@link #MAX_WATER_FALL_BLOCKS}.  The bot will place a water bucket just
     * before impact to negate fall damage.
     */
    private static BlockPos findDropLandingWaterClutch(World world, BlockPos from, BlockPos target,
                                                       BlockPos pathStart, BlockPos pathEnd) {
        if (!passable(world, target, pathStart, pathEnd)) return null;
        if (!passable(world, target.up(), pathStart, pathEnd)) return null;
        if (!hasSolidGround(world, from)) return null;
        if (hasSolidGround(world, target)) return null; // ordinary walk covers this

        for (int drop = 1; drop <= MAX_WATER_FALL_BLOCKS; drop++) {
            BlockPos land = new BlockPos(target.getX(), target.getY() - drop, target.getZ());
            if (!passable(world, land, pathStart, pathEnd)) return null;
            if (!passable(world, land.up(), pathStart, pathEnd)) return null;
            if (PathingEnvironment.isHazardous(world, land)) return null;
            if (PathingEnvironment.isHazardous(world, land.down())) return null;
            if (hasSolidGround(world, land)) return land;
        }
        return null;
    }

    /**
     * Diagonal corner-cut check for a 0.6-wide player hitbox.
     *
     * During diagonal movement the player's body (±0.3 from centre) overlaps
     * both cardinal-neighbour blocks simultaneously.  If EITHER is solid the
     * physics engine pushes the player back and the bot gets stuck on the corner.
     * Both neighbours must therefore be fully clear before a diagonal step is allowed.
     */
    private static boolean canCutCorner(World world, BlockPos from, int dx, int dz,
                                        BlockPos pathStart, BlockPos pathEnd) {
        BlockPos adjX = from.add(dx, 0, 0);
        BlockPos adjZ = from.add(0, 0, dz);
        boolean xBlocked = !passable(world, adjX, pathStart, pathEnd)
                || !passable(world, adjX.up(), pathStart, pathEnd);
        boolean zBlocked = !passable(world, adjZ, pathStart, pathEnd)
                || !passable(world, adjZ.up(), pathStart, pathEnd);
        // Both neighbours must be clear — one blocked corner clips the 0.6-wide hitbox.
        return !xBlocked && !zBlocked;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Thin wrapper so callers don't have to repeat pathStart/pathEnd everywhere. */
    private static boolean passable(World world, BlockPos pos,
                                    BlockPos pathStart, BlockPos pathEnd) {
        return PathingEnvironment.isPassable(world, pos, pathStart, pathEnd);
    }

    /**
     * True if the block below pos provides solid footing (height >= 0.5 so thin blocks
     * like buttons and rails are excluded but slabs are included).
     */
    private static boolean hasSolidGround(World world, BlockPos pos) {
        BlockPos below = pos.down();
        if (world.getBlockState(below).isAir()) return false;
        return PathingEnvironment.getBlockMaxHeight(world, below) >= 0.5;
    }

    /**
     * True if a fence or wall at pos.down() extends upward into pos.
     *
     * Fences and walls have a collision hitbox 1.5 blocks tall, meaning their top 0.5 blocks
     * protrude into the integer block directly above them.  {@link PathingEnvironment#isPassable}
     * checks the block AT pos (which is air above a fence) and returns true — it cannot see
     * the fence's physical collision shape bleeding into that space.  This helper catches that
     * invisible barrier so arc-clearance checks can reject trajectories that would clip into
     * fence tops even though the block there is technically "air".
     */
    private static boolean fenceExtendsInto(World world, BlockPos pos) {
        BlockPos below = pos.down();
        var state = world.getBlockState(below);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(world, below);
        if (shape.isEmpty()) return false;
        // A fence post extends 1.5 blocks; a slab or full block never exceeds 1.0.
        // Using the actual VoxelShape maxY instead of a block-type whitelist means
        // connected fence panels (which share the same block type but have the same
        // 1.5-block height) are also caught, while isolated posts vs. panels are
        // indistinguishable in shape — and both block arc clearance correctly.
        return shape.getBoundingBox().maxY > 1.0;
    }

    /**
     * Line-of-sight walk check.
     *
     * For diagonal paths we use twice as many sample points so that narrow walls
     * sitting beside (but not exactly on) the Bresenham centre line are caught.
     * At each sample we also check the two "shoulder" blocks that a 0.6-wide
     * player would brush against when hugging a corner.
     */
    /**
     * Walk-level line-of-sight check between two block positions.
     * Delegates to {@link #isHitboxSegmentClear} for client worlds;
     * the server-world branch uses PathLimiters (which has its own corner checks).
     */
    private static boolean isPathClear(World world, BlockPos from, BlockPos to,
                                       BlockPos pathStart, BlockPos pathEnd) {
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return true;
        if (world instanceof net.minecraft.server.world.ServerWorld sw) {
            return PathLimiters.executeBresenhamCheck(sw, from, to, pathStart, pathEnd);
        }
        return isHitboxSegmentClear(world, from, to, pathStart, pathEnd);
    }

    /**
     * Sweeps the full 0.6×1.8 player hitbox along the segment [from → to] and
     * returns false if any block position inside the hitbox is impassable.
     *
     * Samples at 0.25-block intervals (half the hitbox width) so no block column
     * can be skipped.  At every sample the 4 XZ corners (±0.3 from centre) are
     * checked at both the feet block (Y) and the head block (Y+1), giving 8 block
     * lookups per sample — the minimal set that guarantees correct hitbox collision.
     *
     * The anti-cliff guard (solid ground under centre) is also checked every sample
     * so paths across voids are never approved.
     */
    private static boolean isHitboxSegmentClear(World world, BlockPos from, BlockPos to,
                                                BlockPos pathStart, BlockPos pathEnd) {
        double fromCx = from.getX() + 0.5, fromCz = from.getZ() + 0.5;
        double toCx   = to.getX()   + 0.5, toCz   = to.getZ()   + 0.5;
        double segDx = toCx - fromCx, segDz = toCz - fromCz;
        double dist  = Math.sqrt(segDx * segDx + segDz * segDz);
        int    y     = from.getY();

        int steps = (int) Math.ceil(dist / 0.25);
        if (steps < 1) steps = 1;

        // 4 XZ hitbox corners (offset from player centre)
        final double[] ox = {-0.3,  0.3,  0.3, -0.3};
        final double[] oz = {-0.3, -0.3,  0.3,  0.3};

        for (int s = 1; s <= steps; s++) {
            double t  = (double) s / steps;
            double cx = fromCx + segDx * t;
            double cz = fromCz + segDz * t;

            // Anti-cliff: solid ground must exist under the centre column
            BlockPos centre = new BlockPos((int) Math.floor(cx), y, (int) Math.floor(cz));
            if (!hasSolidGround(world, centre)) return false;

            // Full hitbox: all 4 corners × feet + head
            for (int k = 0; k < 4; k++) {
                int bx = (int) Math.floor(cx + ox[k]);
                int bz = (int) Math.floor(cz + oz[k]);
                if (!passable(world, new BlockPos(bx, y,     bz), pathStart, pathEnd)) return false;
                if (!passable(world, new BlockPos(bx, y + 1, bz), pathStart, pathEnd)) return false;
            }
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Post-processing
    // -----------------------------------------------------------------------

    /**
     * Shifts the {@code precisePos} of each WALK/EDGE launch node (the node immediately
     * before a gap-crossing SPRINT_JUMP) from the block center to the trailing side of
     * the launch block.
     *
     * <p>Why: estimated sprint-jump distance ≈ 4.4 blocks; center-to-center for a 4-block
     * gap = 4.0 blocks.  Jumping from centre overshoots the landing block by 0.4 blocks
     * with only 0.1 blocks of safety margin.  Shifting 0.2 blocks toward the trailing edge
     * increases the safety margin to ~0.3 blocks and produces a landing closer to the
     * middle of the landing block.
     *
     * <p>Stride waypoints (gap ≤ 3 blocks centre-to-centre) are left untouched because
     * the player intentionally overshoots them; skip-ahead handles the correction.
     */
    private static List<PathNode> adjustSprintJumpTakeoffPositions(List<PathNode> path) {
        if (path.size() < 2) return path;
        List<PathNode> result = new ArrayList<>(path);

        for (int i = 0; i < path.size() - 1; i++) {
            PathNode curr = path.get(i);
            PathNode next = path.get(i + 1);
            if (next.type != PathNode.Type.SPRINT_JUMP) continue;
            if (curr.type != PathNode.Type.WALK && curr.type != PathNode.Type.EDGE) continue;

            Vec3d launch = curr.getFeetPos();
            Vec3d land   = next.getFeetPos();
            double segDx = land.x - launch.x, segDz = land.z - launch.z;
            double dist  = Math.sqrt(segDx * segDx + segDz * segDz);
            if (dist <= 3.0) continue; // stride waypoint — skip
            if (dist < 0.01) continue;

            // Unit vector TOWARD the gap (leading-edge direction)
            double nx = segDx / dist, nz = segDz / dist;

            // Shift to the leading edge: +0.2 in jump direction puts the node at block+0.70,
            // the maximum safe position (hitbox half-width 0.3 from the block edge).
            // Jumping from the leading edge maximises horizontal distance and mirrors how
            // skilled players run to the edge before leaping.
            double optX = launch.x + nx * 0.2;
            double optZ = launch.z + nz * 0.2;
            optX = Math.max(curr.pos.getX() + 0.30, Math.min(curr.pos.getX() + 0.70, optX));
            optZ = Math.max(curr.pos.getZ() + 0.30, Math.min(curr.pos.getZ() + 0.70, optZ));

            result.set(i, new PathNode(curr.pos,
                    new Vec3d(optX, curr.pos.getY(), optZ),
                    null, curr.type));
        }
        return result;
    }

    private static List<PathNode> markEdgeNodes(List<PathNode> path) {
        if (path.size() < 2) return path;
        List<PathNode> result = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            PathNode node = path.get(i);
            if (i < path.size() - 1
                    && (path.get(i + 1).type == PathNode.Type.DROP
                        || path.get(i + 1).type == PathNode.Type.WATER_DROP
                        || path.get(i + 1).type == PathNode.Type.BOUNCE)
                    && node.type == PathNode.Type.WALK) {
                PathNode edge = new PathNode(node.pos, node.precisePos, node.parent, PathNode.Type.EDGE);
                edge.gCost = node.gCost;
                result.add(edge);
            } else {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * Sub-block precision pass.
     *
     * After simplification, every WALK/EDGE waypoint sits at its block centre
     * (e.g. 3.5, 64, 2.5).  For a path that travels at an arbitrary angle the
     * ideal crossing point is rarely the block centre — it's wherever the
     * straight line from the previous waypoint to the next one pierces the block.
     *
     * This method projects each horizontal node's position onto that ideal line
     * and stores the result in {@code precisePos}.  The integer {@code pos}
     * (BlockPos) is unchanged so collision/passability logic is unaffected; only
     * the movement target used by the follower and renderer moves to the exact
     * sub-block location.
     *
     * Corner nodes (where the line-projection falls outside the block footprint)
     * are clamped to the nearest point on the block edge, which is still the
     * smoothest possible approach to a genuine direction change.
     */
    private static List<PathNode> smoothPrecisePositions(List<PathNode> path) {
        if (path.size() <= 2) return path;
        List<PathNode> result = new ArrayList<>(path.size());
        result.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            PathNode prev = result.get(result.size() - 1);
            PathNode curr = path.get(i);
            PathNode next = path.get(i + 1);

            // Only smooth horizontal WALK/EDGE nodes at the same Y as their neighbours.
            if ((curr.type == PathNode.Type.WALK || curr.type == PathNode.Type.EDGE)
                    && prev.pos.getY() == curr.pos.getY()
                    && curr.pos.getY() == next.pos.getY()) {

                // Current precise positions (already smoothed for prev).
                Vec3d pv = prev.getCenterPos();
                Vec3d nv = next.getCenterPos();
                double cx = curr.pos.getX() + 0.5;
                double cz = curr.pos.getZ() + 0.5;

                double ldx = nv.x - pv.x;
                double ldz = nv.z - pv.z;
                double llen2 = ldx * ldx + ldz * ldz;

                if (llen2 > 0.01) {
                    // Scalar projection of (cx,cz) onto the line from pv
                    double t = ((cx - pv.x) * ldx + (cz - pv.z) * ldz) / llen2;

                    // Only slide the point if it falls inside the segment
                    // (don't extrapolate beyond prev or next)
                    if (t > 0.02 && t < 0.98) {
                        double projX = pv.x + ldx * t;
                        double projZ = pv.z + ldz * t;

                        // Clamp so the player's full 0.6-wide hitbox (±0.3 from centre)
                        // stays within this block.  Nodes at 0.02 or 0.98 would place
                        // the hitbox edge 0.28 blocks into the adjacent block — causing
                        // the player to clip and stop on the edge.
                        projX = Math.max(curr.pos.getX() + 0.30,
                                         Math.min(curr.pos.getX() + 0.70, projX));
                        projZ = Math.max(curr.pos.getZ() + 0.30,
                                         Math.min(curr.pos.getZ() + 0.70, projZ));

                        result.add(new PathNode(curr.pos,
                                new Vec3d(projX, curr.pos.getY(), projZ),
                                null, curr.type));
                        continue;
                    }
                }
            }
            result.add(curr);
        }
        result.add(path.get(path.size() - 1));
        return result;
    }

    /**
     * Sprint post-processing pass.
     *
     * Takes the simplified path and:
     *  1. Converts every horizontal WALK / EDGE node to SPRINT_JUMP so the renderer
     *     shows the lime colour and the follower uses jump locomotion.
     *  2. Inserts evenly-spaced stride waypoints every SPRINT_STRIDE blocks along the
     *     vector between consecutive same-Y nodes — this works at *any* angle, not just
     *     the 8 grid directions, so diagonal segments get proper sprint-jump behaviour.
     *  3. Leaves JUMP, DROP, CLIMB, INTERACT nodes untouched.
     *  4. Deduplicates consecutive nodes at the same BlockPos.
     */
    private static List<PathNode> sprintifyPath(List<PathNode> path) {
        if (path.size() <= 1) return path;

        // Pre-pass: convert 1-block DROP nodes immediately following a horizontal node
        // into SPRINT_JUMP.  A sprint-jumping player naturally clears a 1-block drop
        // with full horizontal momentum — no need for a slow walk-off.
        List<PathNode> preProcessed = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            PathNode curr = path.get(i);
            if (curr.type == PathNode.Type.DROP && i > 0) {
                PathNode prev = path.get(i - 1);
                if (isSameYHorizontal(prev) && curr.pos.getY() == prev.pos.getY() - 1) {
                    preProcessed.add(new PathNode(curr.pos, curr.precisePos, null, PathNode.Type.SPRINT_JUMP));
                    continue;
                }
            }
            preProcessed.add(curr);
        }
        path = preProcessed;

        List<PathNode> result = new ArrayList<>();

        for (int i = 0; i < path.size() - 1; i++) {
            PathNode from = path.get(i);
            PathNode to   = path.get(i + 1);

            // Emit the current node.
            // Special case: if this is a walkable node immediately before a gap-crossing
            // SPRINT_JUMP landing (i.e. `to` is already SPRINT_JUMP from the A* search,
            // not a stride we're about to insert), keep it as WALK so only the landing
            // block is highlighted lime.  Converting it to SPRINT_JUMP produces a
            // misleading "jump here" indicator at the launch block — the player doesn't
            // jump AT this node, they jump THROUGH it to reach `to`.
            boolean isLaunchBeforeGap = (from.type == PathNode.Type.WALK || from.type == PathNode.Type.EDGE)
                    && to.type == PathNode.Type.SPRINT_JUMP;
            result.add(isLaunchBeforeGap ? from : toSprintNode(from, to));

            // Insert stride waypoints between consecutive horizontal nodes.
            // Allow a 1-block height difference — the player sprint-jumps off the ledge
            // and naturally lands on the lower surface.
            if (isSameYHorizontal(from) && isSameYHorizontal(to)
                    && Math.abs(from.pos.getY() - to.pos.getY()) <= 1) {
                double cx0 = from.pos.getX() + 0.5;
                double cz0 = from.pos.getZ() + 0.5;
                double cx1 = to.pos.getX() + 0.5;
                double cz1 = to.pos.getZ() + 0.5;
                double totalDx   = cx1 - cx0;
                double totalDz   = cz1 - cz0;
                double totalDist = Math.sqrt(totalDx * totalDx + totalDz * totalDz);

                // Stride nodes are always placed at the launch height (from.pos.getY()).
                // If the segment goes downhill the player lands naturally after the last stride.
                int strideY = from.pos.getY();
                if (totalDist > SPRINT_STRIDE * 1.4) {
                    int strides = (int) (totalDist / SPRINT_STRIDE);
                    for (int s = 1; s <= strides; s++) {
                        double t = (s * SPRINT_STRIDE) / totalDist;
                        if (t >= 0.95) break; // don't add a point too close to the destination
                        double exactX = cx0 + totalDx * t;
                        double exactZ = cz0 + totalDz * t;
                        int wx = (int) Math.floor(exactX);
                        int wz = (int) Math.floor(exactZ);
                        BlockPos wPos = new BlockPos(wx, strideY, wz);
                        if (!wPos.equals(from.pos) && !wPos.equals(to.pos)) {
                            result.add(new PathNode(wPos,
                                    new Vec3d(exactX, strideY, exactZ),
                                    null, PathNode.Type.SPRINT_JUMP));
                        }
                    }
                }
            }
        }
        // Emit the final node
        result.add(toSprintNode(path.get(path.size() - 1), null));

        // Remove consecutive duplicate positions
        List<PathNode> deduped = new ArrayList<>();
        BlockPos prev = null;
        for (PathNode n : result) {
            if (!n.pos.equals(prev)) { deduped.add(n); prev = n.pos; }
        }
        return deduped;
    }

    /** True for node types that represent horizontal walkable ground. */
    private static boolean isSameYHorizontal(PathNode n) {
        return n.type == PathNode.Type.WALK
            || n.type == PathNode.Type.EDGE
            || n.type == PathNode.Type.SPRINT_JUMP;
    }

    /**
     * Returns a copy of the node with type SPRINT_JUMP if it is a horizontal WALK/EDGE node
     * and the following node can be reached with a sprint-jump (≤1 block up).
     * Vertical moves (JUMP, CLIMB, PILLAR) and 2+ block rises stay as WALK so the
     * follower uses normal walking / single-block jumping instead.
     */
    private static PathNode toSprintNode(PathNode n, PathNode next) {
        if (n.type != PathNode.Type.WALK && n.type != PathNode.Type.EDGE) return n;
        if (next != null) {
            int yDelta = next.pos.getY() - n.pos.getY();
            if (yDelta > 1) return n;
            if (next.type == PathNode.Type.JUMP
                    || next.type == PathNode.Type.CLIMB
                    || next.type == PathNode.Type.PILLAR) {
                return n;
            }
        }
        return new PathNode(n.pos, n.precisePos, null, PathNode.Type.SPRINT_JUMP);
    }

    /**
     * Greedy look-ahead simplification: find the furthest node reachable in a straight
     * line from the current position and jump directly there, skipping all intermediate
     * same-level WALK nodes. Special nodes (JUMP, DROP, CLIMB, INTERACT, SPRINT_JUMP)
     * are never skipped — they are emitted exactly where they appear in the path.
     */
    private static List<PathNode> simplifyPath(World world, List<PathNode> path,
                                               BlockPos pathStart, BlockPos pathEnd) {
        if (path.size() <= 2) return path;

        List<PathNode> simplified = new ArrayList<>();
        simplified.add(path.get(0));

        int cur = 0;
        while (cur < path.size() - 1) {
            // Search ALL remaining nodes (no early break) for the furthest directly reachable one
            int far = cur + 1;
            for (int i = cur + 2; i < path.size(); i++) {
                if (canMoveDirectly(world, path.get(cur), path.get(i), pathStart, pathEnd)) far = i;
            }
            simplified.add(path.get(far));
            cur = far;
        }
        return simplified;
    }

    private static boolean canMoveDirectly(World world, PathNode from, PathNode to,
                                           BlockPos pathStart, BlockPos pathEnd) {
        if (from.type == PathNode.Type.JUMP        || to.type == PathNode.Type.JUMP)        return false;
        if (from.type == PathNode.Type.SPRINT_JUMP || to.type == PathNode.Type.SPRINT_JUMP) return false;
        if (from.type == PathNode.Type.DROP        || to.type == PathNode.Type.DROP)        return false;
        if (from.type == PathNode.Type.WATER_DROP  || to.type == PathNode.Type.WATER_DROP)  return false;
        if (from.type == PathNode.Type.BOUNCE      || to.type == PathNode.Type.BOUNCE)      return false;
        if (from.type == PathNode.Type.CLIMB       || to.type == PathNode.Type.CLIMB)       return false;
        if (from.type == PathNode.Type.INTERACT    || to.type == PathNode.Type.INTERACT)    return false;
        if (from.type == PathNode.Type.PILLAR      || to.type == PathNode.Type.PILLAR)      return false;
        if (from.type == PathNode.Type.BRIDGE      || to.type == PathNode.Type.BRIDGE)      return false;
        if (to.pos.getY() != from.pos.getY()) return false;
        return isPathClear(world, from.pos, to.pos, pathStart, pathEnd);
    }

}
