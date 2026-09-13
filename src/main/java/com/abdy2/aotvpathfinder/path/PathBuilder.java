package com.abdy2.aotvpathfinder.path;

import com.abdy2.aotvpathfinder.ability.CastRules;
import com.abdy2.aotvpathfinder.parkour.PathNode;
import com.abdy2.aotvpathfinder.path.HopType;
import com.abdy2.aotvpathfinder.path.PathHop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

public final class PathBuilder {
    public enum MovementMode {
        HYBRID,
        WALK_ONLY,
        TELEPORT_ONLY
    }

    public enum TeleportMode {
        SHIFT_ONLY,
        HYBRID_TELEPORT,
        JUST_TELEPORT
    }

    private static final int MAX_GRAVITY_DROP = 24;
    /** Within this of the goal, walking is treated as a reasonable way to finish. */
    private static final double WALK_APPROACH_RADIUS = 20.0;
    private static final int JUST_TELEPORT_MIN_AIR_CLEARANCE = 13;
    private static final double JUST_TELEPORT_FINAL_WALK_RADIUS = 3.0;
    private static final int AIR_CHAIN_SAFE_FALL_DROP = 8;
    private static final float WAYPOINT_MAX_YAW_DELTA_DEG = 145.0F;

    private static final List<BlockPos> SHORT_OFFSETS = buildShortOffsets();
    private static final List<BlockPos> LONG_OFFSETS = buildLongOffsets();
    private static final BlockPos[] WALK_OFFSETS = new BlockPos[] {
        new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
        new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
        new BlockPos(1, 0, 1), new BlockPos(1, 0, -1),
        new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1)
    };
    private static final BlockPos[] JUMP_OFFSETS = new BlockPos[] {
        new BlockPos(2, 0, 0), new BlockPos(-2, 0, 0),
        new BlockPos(0, 0, 2), new BlockPos(0, 0, -2)
    };

    /**
     * Wall-clock ceiling for a whole findPath call.
     *
     * <p>The expansion budgets alone do not bound how long a search takes. They sum to roughly
     * 700,000 expansions, and each one considers a shell of candidate offsets several thousand
     * wide, many of which raycast. This runs on the client thread, so the cost is paid as a freeze
     * -- and a failing route rebuilds, paying it again.
     *
     * <p>A time limit bounds that directly, whatever the budgets happen to be. Searching stops when
     * it expires and the best route found so far is used, which is the same thing that happens when
     * a budget runs out. A quarter second is long enough to plan a sensible route across open
     * ground and short enough to read as a hitch rather than a hang.
     */
    private static final long SEARCH_BUDGET_NANOS = 250_000_000L;

    /** When the current findPath call must stop searching. */
    private long deadlineNanos = Long.MAX_VALUE;

    private boolean outOfTime() {
        return System.nanoTime() > deadlineNanos;
    }


    public List<PathHop> findPath(
        LocalPlayer player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        MovementMode mode,
        TeleportMode teleportMode,
        boolean allowAirChain
    ) {
        if (start.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
            return List.of();
        }

        deadlineNanos = System.nanoTime() + SEARCH_BUDGET_NANOS;

        MovementMode resolvedMode = mode == null ? MovementMode.HYBRID : mode;
        TeleportMode resolvedTeleportMode = teleportMode == null ? TeleportMode.HYBRID_TELEPORT : teleportMode;
        int distance = (int) Math.sqrt(start.distSqr(goal));
        SearchResult bestFailed = SearchResult.empty();

        // Walk-only has to exclude this too. The air chain is a teleport strategy, and gating it
        // on the teleport mode alone let it run in walk-only and return a route made entirely of
        // hops -- so asking for walking produced no walking at all.
        // Walk-only is answered entirely by the engine below; none of the teleport strategies
        // apply, and running them first only risks one of them answering instead.
        if (resolvedMode == MovementMode.WALK_ONLY) {
            SearchResult walk = searchPureWalk(player, start, goal, 0);
            return smoothTeleportRoute(player, start, walk.hops(), resolvedMode);
        }

        if (allowAirChain
                && resolvedTeleportMode != TeleportMode.SHIFT_ONLY) {
            SearchResult airChain = searchDirectAirChain(player, start, goal, availableMana, resolvedTeleportMode);
            if (airChain.reachedGoal()) {
                return smoothTeleportRoute(player, start, airChain.hops(), resolvedMode);
            }
            if (!airChain.hops().isEmpty()) {
                // The chain stopped short. It used to be accepted anyway if it had landed within
                // 25 blocks, which meant the last stretch was wherever the chain happened to end
                // and the walk-and-teleport search never ran at all. Walk the remainder instead.
                SearchResult finished = finishOnFoot(player, airChain, goal);
                if (finished != null) {
                    return smoothTeleportRoute(player, start, finished.hops(), resolvedMode);
                }
                // Still worth keeping: if nothing below gets closer, chooseBetter returns this.
                bestFailed = chooseBetter(bestFailed, airChain);
            }
        }

        if (resolvedMode != MovementMode.WALK_ONLY) {
            int[] mixedBudgets = new int[] {
                Math.max(12000, Math.min(45000, distance * 70)),
                Math.max(18000, Math.min(70000, distance * 95)),
                Math.max(26000, Math.min(100000, distance * 125))
            };
            for (int budget : mixedBudgets) {
                if (outOfTime()) break;
                SearchResult mixed = searchOnCustomNodeGraph(player, start, goal, availableMana, true, false, resolvedTeleportMode, budget);
                if (mixed.reachedGoal()) {
                    return smoothTeleportRoute(player, start, mixed.hops(), resolvedMode);
                }
                bestFailed = chooseBetter(bestFailed, mixed);
            }
        }

        if (resolvedMode != MovementMode.TELEPORT_ONLY) {
            // Walking comes from the kinematic engine, and only from it.
            //
            // There used to be a grid pass here first -- the same node graph as the mixed search,
            // just with teleports switched off. Being pure walking too, it answered before the
            // engine was ever reached, so routes were built from grid nodes that carry no
            // instruction while the engine sat unused at the bottom of the list. Whatever the
            // engine is worth, it is worth nothing from there.
            SearchResult parkour = searchPureWalk(player, start, goal, 0);
            if (parkour.reachedGoal()) {
                return smoothTeleportRoute(player, start, parkour.hops(), resolvedMode);
            }
            bestFailed = chooseBetter(bestFailed, parkour);
        }

        return smoothTeleportRoute(player, start, bestFailed.hops(), resolvedMode);
    }

    private SearchResult searchDirectAirChain(
        LocalPlayer player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        TeleportMode teleportMode
    ) {
        List<PathHop> hops = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet(512);
        BlockPos current = start;
        BlockPos previous = null;
        Vec3 smoothedDir = null;
        seen.add(packPos(current));

        double horizontalStartDist = Math.hypot(start.getX() - goal.getX(), start.getZ() - goal.getZ());
        int worldCeiling = player.level().getMinY() + player.level().getHeight() - 15;
        int cruiseLift = Math.max(54, Math.min(520, (int) (horizontalStartDist * 1.6)));
        int cruiseY = Math.min(worldCeiling, Math.max(start.getY(), goal.getY()) + cruiseLift);
        if (goal.getY() - start.getY() > 20) {
            cruiseY = Math.min(worldCeiling, cruiseY + Math.min(300, goal.getY() - start.getY()));
        }
        int maxHopsByMana = availableMana > 0 ? Math.max(1, availableMana / CastRules.TRANSMISSION_MANA) : 200;
        int maxHops = Math.min(420, Math.max(80, maxHopsByMana));
        double bestDistSq = current.distSqr(goal);
        BlockPos bestPos = current;

        for (int i = 0; i < maxHops; i++) {
            if (outOfTime()) {
                break;
            }
            if (current.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
                return new SearchResult(hops, true, 0.0);
            }

            BlockPos next = pickDirectAirChainStep(player, current, goal, teleportMode, seen, previous, smoothedDir, cruiseY);
            if (next == null) {
                break;
            }

            hops.add(PathHop.of(next, HopType.NORMAL, CastRules.TRANSMISSION_MANA));
            Vec3 hopVec = Vec3.atCenterOf(next).subtract(Vec3.atCenterOf(current));
            double hopLen = Math.sqrt(hopVec.x * hopVec.x + hopVec.y * hopVec.y + hopVec.z * hopVec.z);
            if (hopLen > 0.001) {
                Vec3 hopDir = hopVec.scale(1.0 / hopLen);
                if (smoothedDir == null) {
                    smoothedDir = hopDir;
                } else {
                    Vec3 blended = smoothedDir.scale(0.75).add(hopDir.scale(0.25));
                    double blendedLen = Math.sqrt(blended.x * blended.x + blended.y * blended.y + blended.z * blended.z);
                    smoothedDir = blendedLen > 0.001 ? blended.scale(1.0 / blendedLen) : smoothedDir;
                }
            }

            previous = current;
            current = next;
            seen.add(packPos(current));

            double distSq = current.distSqr(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestPos = current;
            }
        }

        BlockPos safeHandoff = settleByGravityWithLimit(player, bestPos, AIR_CHAIN_SAFE_FALL_DROP);
        if (safeHandoff == null) {
            safeHandoff = settleByGravityWithLimit(player, current, AIR_CHAIN_SAFE_FALL_DROP);
        }
        if (safeHandoff == null) {
            safeHandoff = bestPos;
        }

        SearchResult walkFinish = searchPureWalk(player, safeHandoff, goal, 28000);
        if (walkFinish.reachedGoal() || !walkFinish.hops().isEmpty()) {
            List<PathHop> combined = new ArrayList<>(hops);
            combined.addAll(walkFinish.hops());
            return new SearchResult(
                combined,
                walkFinish.reachedGoal(),
                Math.min(bestDistSq, walkFinish.bestDistanceSq())
            );
        }

        return new SearchResult(hops, false, bestDistSq);
    }

    private BlockPos pickDirectAirChainStep(
        LocalPlayer player,
        BlockPos from,
        BlockPos goal,
        TeleportMode teleportMode,
        LongOpenHashSet seen,
        BlockPos previousFrom,
        Vec3 lockedDirection,
        int cruiseY
    ) {
        double fromDist = Math.sqrt(from.distSqr(goal));
        boolean blockedToGoal = !hasTeleportCorridorClear(player, from, goal);
        Vec3 toGoal = Vec3.atCenterOf(goal).subtract(Vec3.atCenterOf(from));
        Vec3 goalDir = toGoal.lengthSqr() > 0.001 ? toGoal.normalize() : new Vec3(0.0, 0.0, 0.0);
        double horizontalDist = Math.hypot(from.getX() - goal.getX(), from.getZ() - goal.getZ());
        boolean hasReachedCruise = from.getY() >= cruiseY - 6;
        boolean descendPhase = horizontalDist <= 26.0 || (hasReachedCruise && from.getY() > goal.getY() + 10);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos offset : SHORT_OFFSETS) {

            BlockPos candidate = from.offset(offset);
            if (seen.contains(packPos(candidate))) {
                continue;
            }
            if (descendPhase && candidate.getY() >= from.getY()) {
                continue;
            }

            if (!isPassableForPlayer(player, candidate) || !isPassableForPlayer(player, candidate.above())) {
                continue;
            }

            if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, candidate, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                continue;
            }

            if (!hasTeleportCorridorClear(player, from, candidate)) {
                continue;
            }

            double candidateDist = Math.sqrt(candidate.distSqr(goal));
            if (descendPhase && from.getY() - goal.getY() > 4 && offset.getY() > -2) {
                continue;
            }
            boolean climbPhase = !descendPhase && from.getY() + 6 < cruiseY;
            if (climbPhase && offset.getY() <= 0) {
                continue;
            }

            if (blockedToGoal && offset.getY() >= 2) {
                if (candidateDist > fromDist + 28.0) {
                    continue;
                }
            } else if (climbPhase && offset.getY() > 0) {
                if (candidateDist > fromDist + 28.0) {
                    continue;
                }
            } else if (candidateDist > fromDist + 0.8) {
                continue;
            }

            Vec3 step = Vec3.atCenterOf(candidate).subtract(Vec3.atCenterOf(from));
            double stepLen = Math.sqrt(step.x * step.x + step.y * step.y + step.z * step.z);
            Vec3 stepDir = stepLen > 0.001 ? step.scale(1.0 / stepLen) : new Vec3(0.0, 0.0, 0.0);
            double alignment = step.lengthSqr() > 0.001 ? goalDir.dot(stepDir) : 0.0;

            double lockPenalty = 0.0;
            if (lockedDirection != null && stepLen > 0.001 && !descendPhase) {
                double lockAlign = lockedDirection.dot(stepDir);
                lockPenalty = Math.max(0.0, 1.0 - lockAlign) * 7.5;
                if (lockAlign < 0.97 && candidateDist > fromDist - 0.55) {
                    continue;
                }
            }

            double lateralPenalty = Math.max(0.0, 1.0 - alignment) * 2.4;

            double turnPenalty = 0.0;
            if (previousFrom != null) {
                Vec3 prevStep = Vec3.atCenterOf(from).subtract(Vec3.atCenterOf(previousFrom));
                double prevLen = Math.sqrt(prevStep.x * prevStep.x + prevStep.y * prevStep.y + prevStep.z * prevStep.z);
                if (prevLen > 0.001 && stepLen > 0.001) {
                    Vec3 prevDir = prevStep.scale(1.0 / prevLen);
                    double continuity = prevDir.dot(stepDir);
                    turnPenalty = Math.max(0.0, 1.0 - continuity) * 2.2;
                }
            }

            double score = candidateDist - (alignment * 4.35) - Math.max(0.0, offset.getY()) * 0.10 + lateralPenalty + turnPenalty + lockPenalty;
            score += Math.abs(offset.getX()) * 0.015 + Math.abs(offset.getZ()) * 0.015;
            score += (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.35;

            if (!descendPhase) {
                score += Math.max(0, cruiseY - candidate.getY()) * 0.22;
                if (offset.getY() < 0) {
                    score += 2.4;
                }
                if (offset.getY() > 0) {
                    score -= Math.min(4.6, offset.getY() * 0.7);
                }
            } else {
                score += Math.abs(candidate.getY() - goal.getY()) * 0.08;
                double lateral = Math.abs(candidate.getX() - from.getX()) + Math.abs(candidate.getZ() - from.getZ());
                score += lateral * 0.18;
                if (offset.getY() < 0) {
                    score -= Math.min(2.8, Math.abs(offset.getY()) * 0.48);
                }
            }

            if (blockedToGoal && offset.getY() > 0) {
                score -= Math.min(4.5, offset.getY() * 0.58);
            }

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }


        // Climbing is an escape from an obstacle partway along a route: get above it, carry on.
        // It is the wrong move once we are close and descending. The goal is below us, so going
        // up cannot bring us nearer -- and from the higher position the descend filters reject
        // every downward step again, so the next stuck step climbs once more. That loop is how a
        // route which crossed the map cleanly ends as a tower of hops stacked above the goal.
        // Stopping here instead leaves the final approach to be walked or replanned, which is
        // what should have been handling an enclosed goal in the first place.
        if (best == null && blockedToGoal && !descendPhase) {
            for (int dy = 12; dy >= 6; dy--) {
                BlockPos climb = from.above(dy);
                if (seen.contains(packPos(climb))) {
                    continue;
                }
                if (!isPassableForPlayer(player, climb) || !isPassableForPlayer(player, climb.above())) {
                    continue;
                }
                if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, climb, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                    continue;
                }
                if (!hasTeleportCorridorClear(player, from, climb)) {
                    continue;
                }
                best = climb;
                break;
            }
        }
        return best;
    }

    private SearchResult searchOnCustomNodeGraph(
        LocalPlayer player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        boolean includeTeleports,
        boolean allowAirNormalTeleports,
        TeleportMode teleportMode,
        int maxExpansions
    ) {
        int graphNodeBudget = includeTeleports
            ? Math.max(1200, Math.min(7000, maxExpansions / 4))
            : Math.max(2500, Math.min(16000, maxExpansions / 2));

        Long2ObjectOpenHashMap<GraphNode> graph = buildCustomNodeGraph(player, start, goal, includeTeleports, allowAirNormalTeleports, teleportMode, maxExpansions, graphNodeBudget);

        long startPacked = packPos(start);
        GraphNode startNode = graph.get(startPacked);
        if (startNode == null) {
            return SearchResult.empty();
        }

        PrimitiveMinHeap open = new PrimitiveMinHeap(Math.min(4096, graphNodeBudget));
        Long2ObjectOpenHashMap<SearchNode> visited = new Long2ObjectOpenHashMap<>(graphNodeBudget);
        LongOpenHashSet closed = new LongOpenHashSet(graphNodeBudget);

        SearchNode first = new SearchNode(startNode, null, 0.0, heuristicWithStart(start, goal, start), 0, HopType.WALK, 0, startNode.pos, MoveKind.WALK);
        open.insertOrUpdate(startPacked, first.fScore);
        visited.put(startPacked, first);

        SearchNode best = first;
        double bestDistSq = start.distSqr(goal);

        int expansions = 0;
        while (!open.isEmpty() && expansions < maxExpansions) {
            if ((expansions & 0xFF) == 0 && outOfTime()) {
                break;
            }
            long currentPacked = open.extractMin();
            if (closed.contains(currentPacked)) {
                continue;
            }
            closed.add(currentPacked);

            SearchNode current = visited.get(currentPacked);
            if (current == null) continue;
            expansions++;

            double distSq = current.node.pos.distSqr(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = current;
            }

            if (current.node.pos.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
                return new SearchResult(backtrack(current), true, 0.0);
            }

            for (GraphEdge edge : current.node.edges) {
                long edgePacked = packPos(edge.to.pos);
                if (closed.contains(edgePacked)) {
                    continue;
                }

                int nextManaSpent = current.manaSpent + edge.manaCost;
                if (availableMana > 0 && nextManaSpent > availableMana) {
                    continue;
                }

                double transitionPenalty = 0.0;
                if (current.type == HopType.WALK && edge.type != HopType.WALK) {
                    if (!hasAdequateHorizontalClearance(player, current.node.pos)) {
                        transitionPenalty = 25.0;
                    }
                }
                double nextG = current.gScore + edge.travelCost + transitionPenalty;
                SearchNode known = visited.get(edgePacked);
                if (known != null && nextG >= known.gScore) {
                    continue;
                }

                double nextF = nextG + heuristicWithStart(edge.to.pos, goal, start);
                SearchNode next = new SearchNode(
                    edge.to,
                    current,
                    nextG,
                    nextF,
                    nextManaSpent,
                    edge.type,
                    edge.manaCost,
                    edge.target(),
                    edge.kind()
                );

                visited.put(edgePacked, next);
                open.insertOrUpdate(edgePacked, nextF);
            }
        }

        return new SearchResult(backtrack(best), false, bestDistSq);
    }

    private Long2ObjectOpenHashMap<GraphNode> buildCustomNodeGraph(
        LocalPlayer player,
        BlockPos start,
        BlockPos goal,
        boolean includeTeleports,
        boolean allowAirNormalTeleports,
        TeleportMode teleportMode,
        int maxExpansions,
        int maxNodes
    ) {
        Long2ObjectOpenHashMap<GraphNode> graph = new Long2ObjectOpenHashMap<>(maxNodes);
        PriorityQueue<GraphNode> queue = new PriorityQueue<>(
            Comparator.comparingDouble((GraphNode n) -> n.pos.distSqr(goal))
        );
        LongOpenHashSet expanded = new LongOpenHashSet(maxNodes);

        GraphNode startNode = new GraphNode(start);
        graph.put(packPos(start), startNode);
        queue.add(startNode);

        int expansions = 0;
        while (!queue.isEmpty() && expansions < maxExpansions && graph.size() < maxNodes) {
            if ((expansions & 0xFF) == 0 && outOfTime()) {
                break;
            }
            GraphNode current = queue.poll();
            if (!expanded.add(packPos(current.pos))) {
                continue;
            }
            expansions++;

            for (Neighbor neighbor : neighbors(player, current.pos, start, includeTeleports, allowAirNormalTeleports, teleportMode, goal)) {
                long neighborPacked = packPos(neighbor.pos);
                GraphNode to = graph.get(neighborPacked);
                boolean isNew = false;
                if (to == null) {
                    to = new GraphNode(neighbor.pos);
                    graph.put(neighborPacked, to);
                    isNew = true;
                }

                current.edges.add(new GraphEdge(to, neighbor.target(), neighbor.type, neighbor.manaCost,
                    neighbor.travelCost, neighbor.kind()));

                if (isNew && shouldExpandNode(start, goal, to.pos, includeTeleports)) {
                    queue.add(to);
                }
            }
        }

        long goalPacked = packPos(goal);
        if (!graph.containsKey(goalPacked) && isSafeStanding(player, goal)) {
            GraphNode goalNode = new GraphNode(goal);
            graph.put(goalPacked, goalNode);
            for (BlockPos walkOffset : WALK_OFFSETS) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos neighborPos = goal.offset(walkOffset).offset(0, dy, 0);
                    GraphNode neighborNode = graph.get(packPos(neighborPos));
                    if (neighborNode != null && isWalkTransitionValid(player, neighborPos, goal)) {
                        neighborNode.edges.add(new GraphEdge(goalNode, goalNode.pos, HopType.WALK, 0, 1.35, MoveKind.WALK));
                    }
                }
            }
        }

        return graph;
    }

    private boolean shouldExpandNode(BlockPos start, BlockPos goal, BlockPos candidate, boolean includeTeleports) {
        double startToGoal = Math.sqrt(start.distSqr(goal));
        double startToCandidate = Math.sqrt(start.distSqr(candidate));
        double candidateToGoal = Math.sqrt(candidate.distSqr(goal));
        double slack = includeTeleports ? 70.0 : 50.0;
        return startToCandidate + candidateToGoal <= startToGoal + slack;
    }

    private Collection<Neighbor> neighbors(LocalPlayer player, BlockPos from, BlockPos start, boolean includeTeleports, boolean allowAirNormalTeleports, TeleportMode teleportMode, BlockPos goal) {
        List<Neighbor> out = new ArrayList<>(SHORT_OFFSETS.size() + LONG_OFFSETS.size() + 16);
        double fromGoalSq = from.distSqr(goal);
        float fromYaw = from.equals(start) ? player.getYRot() : yawTo(start, from);

        double gx = goal.getX() - from.getX();
        double gz = goal.getZ() - from.getZ();
        double gHorizLen = Math.sqrt(gx * gx + gz * gz);
        double goalDirX = gHorizLen > 0.001 ? gx / gHorizLen : 0.0;
        double goalDirZ = gHorizLen > 0.001 ? gz / gHorizLen : 0.0;

        if (includeTeleports) {
            boolean allowNormal = teleportMode != TeleportMode.SHIFT_ONLY;
            boolean allowShift = teleportMode != TeleportMode.JUST_TELEPORT;

            boolean viableLaunchPos = hasAdequateHorizontalClearance(player, from);
            if (!viableLaunchPos) {
                allowNormal = false;
                allowShift = false;
            }

            if (allowNormal) {
                for (BlockPos offset : SHORT_OFFSETS) {
                    if (teleportMode == TeleportMode.JUST_TELEPORT && offset.getY() < 2) {
                        continue;
                    }

                    BlockPos aimPoint = from.offset(offset);
                    float yawDelta = Math.abs(wrapDegrees(yawTo(from, aimPoint) - fromYaw));
                    if (yawDelta > WAYPOINT_MAX_YAW_DELTA_DEG) {
                        continue;
                    }
                    if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, aimPoint, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                        continue;
                    }
                    if (!hasTeleportCorridorClear(player, from, aimPoint)) {
                        continue;
                    }

                    BlockPos landing = settleByGravity(player, aimPoint);
                    if (landing == null || !hasTeleportCorridorClear(player, from, landing)) {
                        if (allowAirNormalTeleports && offset.getY() >= -1 && isAirWaypointValid(player, from, aimPoint) && (teleportMode != TeleportMode.JUST_TELEPORT || hasVerticalClearance(player, aimPoint, JUST_TELEPORT_MIN_AIR_CLEARANCE))) {
                            double turnPenalty = yawDelta / 90.0;
                            out.add(new Neighbor(aimPoint, HopType.NORMAL, CastRules.TRANSMISSION_MANA, 2.1 + turnPenalty));
                        }
                        continue;
                    }

                    double gravityPenalty = Math.max(0, aimPoint.getY() - landing.getY()) * 0.03;
                    double landingGoalSq = landing.distSqr(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
                        continue;
                    }
                    if (!isWaypointCastStable(player, from, landing)) {
                        continue;
                    }
                    if (!hasAdequateHorizontalClearance(player, landing)) {
                        continue;
                    }
                    double hxN = landing.getX() - from.getX();
                    double hzN = landing.getZ() - from.getZ();
                    double hLenN = Math.sqrt(hxN * hxN + hzN * hzN);
                    double horizAlignN = hLenN > 0.001 ? (goalDirX * hxN + goalDirZ * hzN) / hLenN : 1.0;
                    double straightPenaltyN = Math.max(0.0, 1.0 - horizAlignN) * 1.8;
                    double turnPenalty = yawDelta / 90.0;
                    double dxN = landing.getX() - from.getX();
                    double dyN = landing.getY() - from.getY();
                    double dzN = landing.getZ() - from.getZ();
                    double actualDistN = Math.sqrt(dxN * dxN + dyN * dyN + dzN * dzN);
                    double longHopBonusN = Math.max(0.0, (actualDistN / CastRules.TRANSMISSION_RANGE - 0.75) * 0.6);
                    double travelCostN = 1.85 + gravityPenalty + turnPenalty + straightPenaltyN - longHopBonusN
                        + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.15;
                    out.add(new Neighbor(landing, HopType.NORMAL, CastRules.TRANSMISSION_MANA, travelCostN));
                }
            }

            if (allowShift) {
                for (BlockPos offset : LONG_OFFSETS) {
                    BlockPos aimPoint = from.offset(offset);
                    if (!hasTeleportCorridorClear(player, from, aimPoint)) {
                        continue;
                    }
                    float yawDelta = Math.abs(wrapDegrees(yawTo(from, aimPoint) - fromYaw));
                    if (yawDelta > 155.0F) {
                        continue;
                    }

                    BlockPos landing = settleByGravity(player, aimPoint);
                    if (landing == null || !hasTeleportCorridorClear(player, from, landing)) {
                        continue;
                    }

                    double gravityPenalty = Math.max(0, aimPoint.getY() - landing.getY()) * 0.03;
                    double landingGoalSq = landing.distSqr(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
                        continue;
                    }
                    if (!isWaypointCastStable(player, from, landing)) {
                        continue;
                    }
                    double hxS = landing.getX() - from.getX();
                    double hzS = landing.getZ() - from.getZ();
                    double hLenS = Math.sqrt(hxS * hxS + hzS * hzS);
                    double horizAlignS = hLenS > 0.001 ? (goalDirX * hxS + goalDirZ * hzS) / hLenS : 1.0;
                    double straightPenaltyS = Math.max(0.0, 1.0 - horizAlignS) * 1.8;
                    double dxS = landing.getX() - from.getX();
                    double dyS = landing.getY() - from.getY();
                    double dzS = landing.getZ() - from.getZ();
                    double actualDistS = Math.sqrt(dxS * dxS + dyS * dyS + dzS * dzS);
                    double longHopBonusS = Math.max(0.0, (actualDistS / CastRules.ETHERWARP_RANGE - 0.80) * 0.4);
                    double travelCostS = 2.5 + gravityPenalty + (yawDelta / 120.0) + straightPenaltyS - longHopBonusS
                        + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.15;
                    out.add(new Neighbor(landing, HopType.SHIFT, CastRules.ETHERWARP_MANA, travelCostS));
                }
            }
        }

        boolean hasTeleportExits = !out.isEmpty();
        // Walking is slower per block than teleporting and is priced above it deliberately. At 8.0
        // against a hop's ~1.85 for up to twelve blocks the gap was around fifty to one, which is
        // far more than the real difference in time and meant walking never won even where it
        // plainly should have. Nearer the goal the balance shifts again: hops need a valid landing
        // and precision starts to matter more than speed, so the final approach is priced to let
        // walking compete.
        double walkTravelCost;
        if (!includeTeleports) {
            walkTravelCost = 1.35;
        } else if (!hasTeleportExits) {
            walkTravelCost = 1.5;
        } else {
            walkTravelCost = from.closerThan(goal, WALK_APPROACH_RADIUS) ? 2.0 : 4.0;
        }
        for (BlockPos walkOffset : WALK_OFFSETS) {
            BlockPos base = from.offset(walkOffset);
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = base.offset(0, y, 0);
                if (!isWalkTransitionValid(player, from, candidate)) {
                    continue;
                }
                if (teleportMode == TeleportMode.JUST_TELEPORT && !candidate.closerThan(goal, JUST_TELEPORT_FINAL_WALK_RADIUS)) {
                    continue;
                }
                out.add(new Neighbor(candidate, HopType.WALK, 0, walkTravelCost + (y > 0 ? 0.15 : 0.0)));
            }
        }

        for (BlockPos jumpOffset : JUMP_OFFSETS) {
            BlockPos dest = from.offset(jumpOffset);
            BlockPos mid  = new BlockPos(
                (from.getX() + dest.getX()) / 2,
                from.getY(),
                (from.getZ() + dest.getZ()) / 2
            );
            if (!isChunkLoaded(player, mid) || !isChunkLoaded(player, dest)) continue;
            if (isWalkPassable(player, mid)) continue;
            if (!isWalkPassable(player, mid.above()) || !isWalkPassable(player, mid.above(2))) continue;
            if (!isWalkSafeStanding(player, dest)) continue;
            if (dest.getY() != from.getY()) continue;
            if (teleportMode == TeleportMode.JUST_TELEPORT && !dest.closerThan(goal, JUST_TELEPORT_FINAL_WALK_RADIUS)) continue;
            // Came from JUMP_OFFSETS and had its arc validated: this is a jump, and saying so
            // here saves the executor guessing at it later.
            out.add(new Neighbor(dest, HopType.WALK, 0, walkTravelCost * 2.0 + 0.2, MoveKind.JUMP));
        }

        if (includeTeleports && teleportMode != TeleportMode.SHIFT_ONLY) {
            for (BlockPos walkOffset : WALK_OFFSETS) {
                BlockPos edge = from.offset(walkOffset);
                if (!isPassableForPlayer(player, edge) || !isPassableForPlayer(player, edge.above()) || isSafeStanding(player, edge)) {
                    continue;
                }

                for (int drop = 2; drop <= 8; drop++) {
                    BlockPos landing = edge.below(drop);
                    if (!isSafeStanding(player, landing)) {
                        continue;
                    }
                    boolean columnClear = true;
                    for (int d = 1; d < drop; d++) {
                        if (!isPassableForPlayer(player, edge.below(d))) {
                            columnClear = false;
                            break;
                        }
                    }
                    if (!columnClear) continue;
                    if (!hasTeleportCorridorClear(player, from, landing)) {
                        continue;
                    }
                    out.add(new Neighbor(landing, HopType.NORMAL, CastRules.TRANSMISSION_MANA, 2.0 + drop * 0.08));
                    break;
                }
            }
        }

        return out;
    }

    private boolean isWaypointCastStable(LocalPlayer player, BlockPos from, BlockPos landing) {
        // Same eye and same aim point the executor will use, so a waypoint judged stable here is
        // stable there too.
        Vec3 fromEye = CastRules.eyeIn(from);
        return rayClear(player, fromEye, CastRules.aimPoint(landing, fromEye));
    }

    private boolean hasAdequateHorizontalClearance(LocalPlayer player, BlockPos pos) {
        int open = 0;
        BlockPos[] adj = {
            pos.offset(1, 0, 0), pos.offset(-1, 0, 0),
            pos.offset(0, 0, 1), pos.offset(0, 0, -1)
        };
        for (BlockPos a : adj) {
            if (isWalkPassable(player, a) && isWalkPassable(player, a.above())) {
                open++;
            }
        }
        return open >= 2;
    }

    private boolean rayClear(LocalPlayer player, Vec3 start, Vec3 end) {
        return CastRules.rayReaches(player.level(), player, start, end);
    }

    private float yawTo(BlockPos from, BlockPos to) {
        Vec3 start = CastRules.eyeIn(from);
        Vec3 d = CastRules.aimPoint(to, start).subtract(start);
        return (float) (Math.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0F;
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private List<PathHop> smoothTeleportRoute(
        LocalPlayer player,
        BlockPos start,
        List<PathHop> input,
        MovementMode mode
    ) {
        if (input == null || input.size() < 3) {
            return input;
        }
        List<PathHop> out = new ArrayList<>();
        BlockPos current = start;
        // Where the route is heading. The goal itself is not passed in, and the last landing is
        // the same thing for this purpose.
        BlockPos routeEnd = input.get(input.size() - 1).landing();
        int i = 0;
        while (i < input.size()) {
            PathHop hop = input.get(i);
            if (hop.type() == HopType.WALK) {
                // Folding walking into a hop is not available when hops are not: this runs on
                // every route including the walk-only ones, and would otherwise put teleports
                // into a route that asked for none.
                if (mode == MovementMode.WALK_ONLY) {
                    out.add(hop);
                    current = hop.landing();
                    i++;
                    continue;
                }
                // Runs of walking get folded into a single hop, which is a fair trade while
                // covering ground -- one cast beats twelve steps. It is the wrong trade on the
                // final approach, where a hop needs a valid landing and overshooting costs more
                // than the steps it saved. Without this the walking the planner chose was
                // converted straight back into teleports, which is why routes came out as pure
                // Aspect of the Void however they had been planned.
                if (current.closerThan(routeEnd, WALK_APPROACH_RADIUS)) {
                    out.add(hop);
                    current = hop.landing();
                    i++;
                    continue;
                }
                int maxRange = CastRules.TRANSMISSION_RANGE;
                int scanLimit = Math.min(input.size() - 1, i + maxRange + 6);
                int skipTo = -1;
                for (int j = scanLimit; j > i; j--) {
                    BlockPos dest = input.get(j).landing();
                    double distSq = current.distSqr(dest);
                    if (distSq < 16.0 || distSq > (double) (maxRange * maxRange)) {
                        continue;
                    }
                    if (!hasTeleportCorridorClear(player, current, dest)) {
                        continue;
                    }
                    if (!isWaypointCastStable(player, current, dest)) {
                        continue;
                    }
                    skipTo = j;
                    break;
                }
                if (skipTo > i) {
                    BlockPos dest = input.get(skipTo).landing();
                    out.add(PathHop.of(dest, HopType.NORMAL, CastRules.TRANSMISSION_MANA));
                    current = dest;
                    i = skipTo + 1;
                    continue;
                }
                out.add(hop);
                current = hop.landing();
                i++;
                continue;
            }

            int best = i;
            // Collapsing consecutive hops must never produce one longer than the ability can
            // actually cast. Without this the scan merges up to six hops on a clear corridor
            // alone, so six transmission hops of ~12 blocks become a single ~72 block hop that
            // nothing can perform, and the route stalls on its own first node.
            int mergeRange = (hop.type() == HopType.SHIFT)
                ? CastRules.planRange(HopType.SHIFT)
                : CastRules.TRANSMISSION_RANGE;
            double mergeRangeSq = (double) mergeRange * mergeRange;
            for (int j = Math.min(i + 6, input.size() - 1); j > i; j--) {
                PathHop candidate = input.get(j);
                if (candidate.type() != hop.type()) {
                    continue;
                }
                if (current.distSqr(candidate.landing()) > mergeRangeSq) {
                    continue;
                }
                if (!hasTeleportCorridorClear(player, current, candidate.landing())) {
                    continue;
                }
                if (!isWaypointCastStable(player, current, candidate.landing())) {
                    continue;
                }
                best = j;
                break;
            }
            PathHop selected = input.get(best);
            if (selected.type() != HopType.WALK
                    && (!hasTeleportCorridorClear(player, current, selected.landing())
                        || !isWaypointCastStable(player, current, selected.landing())
                        || !hasAdequateHorizontalClearance(player, current))) {
                BlockPos launchPos = findViableLaunchPosition(player, current, selected.landing(), input, best);
                if (launchPos != null && !launchPos.equals(current)) {
                    List<BlockPos> walkChain = buildShortWalkChain(player, current, launchPos);
                    for (BlockPos step : walkChain) {
                        out.add(PathHop.of(step, HopType.WALK, 0));
                    }
                    out.add(PathHop.of(selected.landing(), selected.type(), selected.manaCost()));
                    current = selected.landing();
                    i = best + 1;
                    continue;
                }
            }
            out.add(PathHop.of(selected.landing(), selected.type(), selected.manaCost()));
            current = selected.landing();
            i = best + 1;
        }
        return out;
    }

    private boolean isAirWaypointValid(LocalPlayer player, BlockPos from, BlockPos pos) {
        if (!hasTeleportCorridorClear(player, from, pos)) {
            return false;
        }
        return isPassableForPlayer(player, pos) && isPassableForPlayer(player, pos.above());
    }

    private BlockPos findViableLaunchPosition(LocalPlayer player, BlockPos current, BlockPos teleportDest, List<PathHop> input, int teleportIndex) {
        for (int j = teleportIndex - 1; j >= Math.max(0, teleportIndex - 8); j--) {
            PathHop h = input.get(j);
            if (h.type() != HopType.WALK) break;
            BlockPos candidate = h.landing();
            if (!hasAdequateHorizontalClearance(player, candidate)) continue;
            if (!hasTeleportCorridorClear(player, candidate, teleportDest)) continue;
            if (!isWaypointCastStable(player, candidate, teleportDest)) continue;
            return candidate;
        }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        BlockPos bestLateral = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dist = 1; dist <= 3; dist++) {
            for (int[] d : dirs) {
                BlockPos candidate = current.offset(d[0] * dist, 0, d[1] * dist);
                if (!isWalkSafeStanding(player, candidate)) continue;
                if (!hasAdequateHorizontalClearance(player, candidate)) continue;
                if (!hasTeleportCorridorClear(player, candidate, teleportDest)) continue;
                if (!isWaypointCastStable(player, candidate, teleportDest)) continue;
                double dSq = candidate.distSqr(current);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestLateral = candidate;
                }
            }
            if (bestLateral != null) return bestLateral;
        }
        return bestLateral;
    }

    private boolean hasTeleportCorridorClear(LocalPlayer player, BlockPos from, BlockPos to) {
        if (!isRayClear(player, from, to, 1.05)) return false;
        if (!isRayClear(player, from, to, 1.62)) return false;

        // Test the ray that will actually be cast, not just ones near it. The two above run at
        // fixed heights through block centres, which is a reasonable approximation of a clear
        // corridor but is not the line the executor aims along. It aims from the eye at the
        // target's aim point, and a hop can pass these checks while that line is obstructed --
        // which reads, correctly but uselessly, as the planner promising a hop the executor then
        // refuses. Checking the real line here means a hop that survives planning can be cast.
        Vec3 eye = CastRules.eyeIn(from);
        if (!CastRules.rayReaches(player.level(), player, eye, CastRules.aimPoint(to, eye))) {
            return false;
        }

        if (hasAdjacentSolid(player, from)) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizLen = Math.sqrt(dx * dx + dz * dz);
            if (horizLen > 0.001) {
                double px = -dz / horizLen * 0.25;
                double pz =  dx / horizLen * 0.25;
                Vec3 toCenter = CastRules.aimPoint(to, CastRules.eyeIn(from));
                Vec3 edgeA = new Vec3(from.getX() + 0.5 + px, from.getY() + CastRules.EYE_HEIGHT, from.getZ() + 0.5 + pz);
                Vec3 edgeB = new Vec3(from.getX() + 0.5 - px, from.getY() + CastRules.EYE_HEIGHT, from.getZ() + 0.5 - pz);
                if (!rayClear(player, edgeA, toCenter)) return false;
                if (!rayClear(player, edgeB, toCenter)) return false;
            }
        }
        return true;
    }

    private boolean hasAdjacentSolid(LocalPlayer player, BlockPos pos) {
        BlockPos head = pos.above();
        BlockPos[] check = {
            pos.north(), pos.south(), pos.east(), pos.west(),
            head.north(), head.south(), head.east(), head.west()
        };
        for (BlockPos adj : check) {
            if (!isChunkLoaded(player, adj)) continue;
            if (!isPassableForPlayer(player, adj)) return true;
        }
        return false;
    }

    private List<BlockPos> buildShortWalkChain(LocalPlayer player, BlockPos from, BlockPos to) {
        List<BlockPos> chain = new ArrayList<>();
        BlockPos cursor = from;
        for (int step = 0; step < 6; step++) {
            if (cursor.equals(to)) break;
            int dx = Integer.signum(to.getX() - cursor.getX());
            int dz = Integer.signum(to.getZ() - cursor.getZ());

            BlockPos[] tries = (dx != 0 && dz != 0)
                ? new BlockPos[]{ cursor.offset(dx, 0, dz), cursor.offset(dx, 0, 0), cursor.offset(0, 0, dz) }
                : new BlockPos[]{ cursor.offset(dx, 0, dz) };

            boolean moved = false;
            for (BlockPos next : tries) {
                if (isWalkSafeStanding(player, next)) {
                    chain.add(next);
                    cursor = next;
                    moved = true;
                    break;
                }
            }
            if (!moved) break;
        }
        if (!cursor.equals(to) && isWalkSafeStanding(player, to)) {
            chain.add(to);
        }
        return chain;
    }

    private boolean isRayClear(LocalPlayer player, BlockPos from, BlockPos to, double yOffset) {
        if (!isChunkLoaded(player, to)) return false;
        Vec3 start = new Vec3(from.getX() + 0.5, from.getY() + yOffset, from.getZ() + 0.5);
        Vec3 end = new Vec3(to.getX() + 0.5, to.getY() + yOffset, to.getZ() + 0.5);
        return CastRules.rayReaches(player.level(), player, start, end);
    }

    private BlockPos settleByGravity(LocalPlayer player, BlockPos start) {
        return settleByGravityWithLimit(player, start, MAX_GRAVITY_DROP);
    }

    private BlockPos settleByGravityWithLimit(LocalPlayer player, BlockPos start, int maxDrop) {
        BlockPos cursor = start;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (isSafeStanding(player, cursor)) {
                return cursor;
            }
            if (!isPassableForPlayer(player, cursor) || !isPassableForPlayer(player, cursor.above())) {
                return null;
            }
            cursor = cursor.below();
        }
        return null;
    }

    private boolean isChunkLoaded(LocalPlayer player, BlockPos pos) {
        return player.level().isLoaded(pos);
    }

    private boolean isPassableForPlayer(LocalPlayer player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        return player.level().getBlockState(pos).isAir();
    }

    private boolean isWalkPassable(LocalPlayer player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        return player.level().getBlockState(pos)
            .getCollisionShape(player.level(), pos)
            .isEmpty();
    }

    private boolean isWalkSafeStanding(LocalPlayer player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        BlockState feet = player.level().getBlockState(pos);
        BlockState head = player.level().getBlockState(pos.above());
        BlockState below = player.level().getBlockState(pos.below());
        return feet.getCollisionShape(player.level(), pos).isEmpty()
            && head.getCollisionShape(player.level(), pos.above()).isEmpty()
            && below.isSolid();
    }

    private boolean hasVerticalClearance(LocalPlayer player, BlockPos base, int requiredAirBlocks) {
        for (int i = 0; i < requiredAirBlocks; i++) {
            if (!isPassableForPlayer(player, base.above(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isWalkTransitionValid(LocalPlayer player, BlockPos from, BlockPos to) {
        if (!isWalkSafeStanding(player, to)) {
            return false;
        }

        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = to.getY() - from.getY();
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || Math.abs(dy) > 1) {
            return false;
        }

        if (dy > 0) {
            if (!player.level().getBlockState(from.above(2)).isAir()) {
                return false;
            }
            if (!hasJumpArcClear(player, from, to)) {
                return false;
            }
        }

        if (dx != 0 && dz != 0) {
            BlockPos sideX = from.offset(dx, 0, 0);
            BlockPos sideZ = from.offset(0, 0, dz);
            boolean sideXClear = isWalkPassable(player, sideX) && isWalkPassable(player, sideX.above());
            boolean sideZClear = isWalkPassable(player, sideZ) && isWalkPassable(player, sideZ.above());

            if (dy > 0) {
                if (!sideXClear && !sideZClear) {
                    return false;
                }
            } else {
                if (!sideXClear || !sideZClear) {
                    return false;
                }
            }
        }

        return hasWalkCorridorClear(player, from, to);
    }

    private boolean hasWalkCorridorClear(LocalPlayer player, BlockPos from, BlockPos to) {
        Vec3 fromFeet = Vec3.atCenterOf(from).add(0.0, 0.05, 0.0);
        Vec3 toFeet = Vec3.atCenterOf(to).add(0.0, 0.05, 0.0);
        Vec3 fromHead = Vec3.atCenterOf(from).add(0.0, 1.05, 0.0);
        Vec3 toHead = Vec3.atCenterOf(to).add(0.0, 1.05, 0.0);

        HitResult feetHit = player.level().clip(new ClipContext(
            fromFeet, toFeet, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (feetHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        HitResult headHit = player.level().clip(new ClipContext(
            fromHead, toHead, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return headHit.getType() == HitResult.Type.MISS;
    }

    private boolean hasJumpArcClear(LocalPlayer player, BlockPos from, BlockPos to) {
        Vec3 upStart = Vec3.atCenterOf(from).add(0.0, 0.05, 0.0);
        Vec3 upEnd = upStart.add(0.0, 1.0, 0.0);
        HitResult vertical = player.level().clip(new ClipContext(
            upStart, upEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (vertical.getType() != HitResult.Type.MISS) {
            return false;
        }

        Vec3 hStart = Vec3.atCenterOf(from).add(0.0, 1.05, 0.0);
        Vec3 hEnd = Vec3.atCenterOf(to).add(0.0, 1.05, 0.0);
        HitResult horizontal = player.level().clip(new ClipContext(
            hStart, hEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        return horizontal.getType() == HitResult.Type.MISS;
    }

    private boolean isSafeStanding(LocalPlayer player, BlockPos pos) {
        return CastRules.isStandable(player.level(), pos);
    }

    private double heuristicWithStart(BlockPos current, BlockPos goal, BlockPos start) {
        int dx = Math.abs(current.getX() - goal.getX());
        int dz = Math.abs(current.getZ() - goal.getZ());
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        double octileXZ = (D2_CONST - 1.0) * min + max;

        double perpXZ = 0.0;
        if (start != null) {
            double lx = goal.getX() - start.getX();
            double lz = goal.getZ() - start.getZ();
            double lineSq = lx * lx + lz * lz;
            if (lineSq > 1e-9) {
                double tox = current.getX() - start.getX();
                double toz = current.getZ() - start.getZ();
                double cross = tox * lz - toz * lx;
                perpXZ = Math.abs(cross) / Math.sqrt(lineSq);
            }
        }

        double heightPenalty = Math.abs(current.getY() - goal.getY()) * 0.15;

        return (octileXZ + perpXZ * 0.5 + heightPenalty) / CastRules.ETHERWARP_RANGE;
    }

    private static final double D2_CONST = Math.sqrt(2.0);

    private List<PathHop> backtrack(SearchNode node) {
        List<PathHop> reversed = new ArrayList<>();
        SearchNode cursor = node;
        while (cursor != null && cursor.parent != null) {
            reversed.add(PathHop.settled(cursor.node.pos, cursor.target, cursor.type, cursor.manaCost, cursor.kind));
            cursor = cursor.parent;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * Completes a route that stopped short by walking the rest of the way.
     *
     * <p>Teleporting covers ground far faster than walking, so a route is mostly hops. The last
     * stretch is different: hops need a valid landing and enough clearance, and near a goal tucked
     * against terrain there often is not one. Walking has no such requirement, and a short walk
     * costs less time than a hop that overshoots and has to be recovered from.
     *
     * @return the combined route, or null if the remainder could not be walked
     */
    private SearchResult finishOnFoot(LocalPlayer player, SearchResult partial, BlockPos goal) {
        List<PathHop> hops = partial.hops();
        if (hops.isEmpty()) {
            return null;
        }
        BlockPos end = hops.get(hops.size() - 1).landing();
        if (end.closerThan(goal, CastRules.GOAL_REACHED_RADIUS)) {
            return new SearchResult(hops, true, 0.0);
        }

        // Budget scaled to the gap: this is a short approach, not a fresh search across the world.
        int gap = (int) Math.sqrt(end.distSqr(goal));
        SearchResult walk = searchPureWalk(player, end, goal, Math.max(8000, Math.min(60000, gap * 400)));
        if (!walk.reachedGoal() || walk.hops().isEmpty()) {
            return null;
        }

        List<PathHop> combined = new ArrayList<>(hops.size() + walk.hops().size());
        combined.addAll(hops);
        combined.addAll(walk.hops());
        return new SearchResult(combined, true, 0.0);
    }


    /**
     * Plans a walk with the kinematic parkour engine.
     *
     * <p>This is where the walking half of a route now comes from. The grid search it replaces
     * produced positions and left the executor to work out how to travel between them; this
     * produces positions that say how, which is the difference the whole mod was missing.
     */
    /** The engine's own nodes for the walk most recently planned, for the follower to perform. */
    private List<PathNode> lastWalkNodes = List.of();

    /** The engine nodes behind the walking in the route just returned, empty if none. */
    public List<PathNode> lastWalkNodes() {
        return lastWalkNodes;
    }

    private SearchResult searchPureWalk(LocalPlayer player, BlockPos start, BlockPos goal, int maxExpansions) {
        ParkourWalkPlanner.Result walk = ParkourWalkPlanner.plan(player, start, goal, true);
        lastWalkNodes = walk.nodes();
        if (walk.hops().isEmpty()) {
            return SearchResult.empty();
        }
        BlockPos end = walk.hops().get(walk.hops().size() - 1).landing();
        return new SearchResult(walk.hops(), walk.reachedGoal(), end.distSqr(goal));
    }

    private SearchResult chooseBetter(SearchResult a, SearchResult b) {
        if (b.reachedGoal() && !a.reachedGoal()) {
            return b;
        }
        if (a.reachedGoal() && !b.reachedGoal()) {
            return a;
        }
        if (b.bestDistanceSq() < a.bestDistanceSq()) {
            return b;
        }
        if (a.hops().isEmpty() && !b.hops().isEmpty()) {
            return b;
        }
        return a;
    }

    private static List<BlockPos> buildShortOffsets() {
        List<BlockPos> out = new ArrayList<>();
        int max = CastRules.TRANSMISSION_RANGE;
        for (int dx = -max; dx <= max; dx++) {
            for (int dz = -max; dz <= max; dz++) {
                for (int dy = -20; dy <= 38; dy++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist < 4 || dist > max) {
                        continue;
                    }
                    out.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        out.sort(Comparator.comparingInt(p -> p.getX() * p.getX() + p.getY() * p.getY() + p.getZ() * p.getZ()));
        return out;
    }

    private static List<BlockPos> buildLongOffsets() {
        List<BlockPos> out = new ArrayList<>();
        // Plan range, not true range: a candidate generated at exactly ETHERWARP_RANGE is only
        // castable when the player happens to stand on the near edge of their block.
        int max = CastRules.planRange(HopType.SHIFT);
        List<Integer> distances = new ArrayList<>();
        for (int d = CastRules.TRANSMISSION_RANGE + 1; d <= max; d += 4) {
            distances.add(d);
        }
        if (!distances.contains(max)) {
            distances.add(max);
        }

        for (int pitchDeg : new int[] { -30, -15, 0, 15, 30 }) {
            float pitch = (float) Math.toRadians(pitchDeg);
            double cp = Math.cos(pitch);
            double sp = Math.sin(pitch);

            for (int yawDeg = 0; yawDeg < 360; yawDeg += 15) {
                float yaw = (float) Math.toRadians(yawDeg);
                double cy = Math.cos(yaw);
                double sy = Math.sin(yaw);

                Vec3 unit = new Vec3(-sy * cp, -sp, cy * cp);
                for (int distance : distances) {
                    BlockPos offset = BlockPos.containing(unit.scale(distance));
                    if (offset.distManhattan(BlockPos.ZERO) < CastRules.TRANSMISSION_RANGE + 2) {
                        continue;
                    }
                    out.add(offset);
                }
            }
        }

        return out.stream().distinct().toList();
    }

    private static final long MASK_Y = 0xFFFL;
    private static final long MASK_XZ = 0x3FFFFFFL;
    private static final int SHIFT_Z = 12;
    private static final int SHIFT_X = 38;

    private static long packPos(BlockPos pos) {
        return ((long) pos.getX() & MASK_XZ) << SHIFT_X |
               ((long) pos.getZ() & MASK_XZ) << SHIFT_Z |
               ((long) pos.getY() & MASK_Y);
    }

    /**
     * A candidate step.
     *
     * <p>{@code target} is the block the ability is aimed at and {@code pos} is where the player
     * ends up. For a transmission hop that settles under gravity these differ, sometimes by many
     * blocks, and both are needed: the landing decides where the route goes next, the target
     * decides where to aim. Keeping only the landing meant the executor re-derived the target from
     * it and aimed somewhere the planner never checked.
     */
    private record Neighbor(BlockPos pos, BlockPos target, HopType type, int manaCost,
                            double travelCost, MoveKind kind) {
        Neighbor(BlockPos pos, HopType type, int manaCost, double travelCost) {
            this(pos, PathHop.defaultTarget(pos, type), type, manaCost, travelCost, MoveKind.WALK);
        }

        Neighbor(BlockPos pos, HopType type, int manaCost, double travelCost, MoveKind kind) {
            this(pos, PathHop.defaultTarget(pos, type), type, manaCost, travelCost, kind);
        }
    }
    private record GraphEdge(GraphNode to, BlockPos target, HopType type, int manaCost,
                             double travelCost, MoveKind kind) {}
    private record SearchResult(List<PathHop> hops, boolean reachedGoal, double bestDistanceSq) {
        private static SearchResult empty() {
            return new SearchResult(Collections.emptyList(), false, Double.POSITIVE_INFINITY);
        }
    }

    private static final class GraphNode {
        private final BlockPos pos;
        private final List<GraphEdge> edges = new ArrayList<>();

        private GraphNode(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final class SearchNode {
        private final GraphNode node;
        private final SearchNode parent;
        private final double gScore;
        private final double fScore;
        private final int manaSpent;
        private final HopType type;
        private final int manaCost;
        /** Block aimed at to make this step, which is not always where it landed. */
        private final BlockPos target;
        /** How the step has to be performed. */
        private final MoveKind kind;

        private SearchNode(
            GraphNode node,
            SearchNode parent,
            double gScore,
            double fScore,
            int manaSpent,
            HopType type,
            int manaCost,
            BlockPos target,
            MoveKind kind
        ) {
            this.node = node;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
            this.manaSpent = manaSpent;
            this.type = type;
            this.target = target;
            this.kind = kind;
            this.manaCost = manaCost;
        }
    }
}
