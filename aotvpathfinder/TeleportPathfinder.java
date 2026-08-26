package com.abdy2.aotvpathfinder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class TeleportPathfinder {
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
    // 2-block cardinal jump-over offsets (same Y, one solid block in between).
    private static final BlockPos[] JUMP_OFFSETS = new BlockPos[] {
        new BlockPos(2, 0, 0), new BlockPos(-2, 0, 0),
        new BlockPos(0, 0, 2), new BlockPos(0, 0, -2)
    };

    private final AotvWalkPathfinder walkPathfinder = new AotvWalkPathfinder();

    public List<TeleportHop> findPath(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        MovementMode mode,
        TeleportMode teleportMode,
        boolean allowAirChain
    ) {
        if (start.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
            return List.of();
        }

        MovementMode resolvedMode = mode == null ? MovementMode.HYBRID : mode;
        TeleportMode resolvedTeleportMode = teleportMode == null ? TeleportMode.HYBRID_TELEPORT : teleportMode;
        int distance = (int) Math.sqrt(start.getSquaredDistance(goal));
        SearchResult bestFailed = SearchResult.empty();

        if (allowAirChain && resolvedTeleportMode != TeleportMode.SHIFT_ONLY) {
            SearchResult airChain = searchDirectAirChain(player, start, goal, availableMana, resolvedTeleportMode);
            if (airChain.reachedGoal()) {
                return smoothTeleportRoute(player, start, airChain.hops());
            }
            // Also accept the chain when it made substantial progress — the live AI
            // will replan from the new position for the remaining distance.
            // "Progress" = got within 25 blocks absolute OR covered ≥ 80 % of the way.
            if (!airChain.hops().isEmpty()) {
                double startDistSq = start.getSquaredDistance(goal);
                boolean nearGoal     = airChain.bestDistanceSq() < 25.0 * 25.0;
                boolean mostlyClosed = airChain.bestDistanceSq() < startDistSq * 0.20;
                if (nearGoal || mostlyClosed) {
                    return smoothTeleportRoute(player, start, airChain.hops());
                }
            }
        }

        if (resolvedMode != MovementMode.WALK_ONLY) {
            int[] mixedBudgets = new int[] {
                Math.max(12000, Math.min(45000, distance * 70)),
                Math.max(18000, Math.min(70000, distance * 95)),
                Math.max(26000, Math.min(100000, distance * 125))
            };
            for (int budget : mixedBudgets) {
                SearchResult mixed = searchOnCustomNodeGraph(player, start, goal, availableMana, true, false, resolvedTeleportMode, budget);
                if (mixed.reachedGoal()) {
                    return smoothTeleportRoute(player, start, mixed.hops());
                }
                bestFailed = chooseBetter(bestFailed, mixed);
            }
        }

        if (resolvedMode != MovementMode.TELEPORT_ONLY) {
            int[] walkBudgets = new int[] {
                Math.max(15000, Math.min(70000, distance * 90)),
                Math.max(26000, Math.min(120000, distance * 150))
            };
            for (int budget : walkBudgets) {
                SearchResult walkGraph = searchOnCustomNodeGraph(player, start, goal, -1, false, false, resolvedTeleportMode, budget);
                if (walkGraph.reachedGoal()) {
                    return smoothTeleportRoute(player, start, walkGraph.hops());
                }
                bestFailed = chooseBetter(bestFailed, walkGraph);
            }

            int[] pureWalkBudgets = new int[] {
                Math.max(30000, Math.min(120000, distance * 140)),
                Math.max(50000, Math.min(180000, distance * 200))
            };
            for (int budget : pureWalkBudgets) {
                SearchResult pureWalk = searchPureWalk(player, start, goal, budget);
                if (pureWalk.reachedGoal()) {
                    return smoothTeleportRoute(player, start, pureWalk.hops());
                }
                bestFailed = chooseBetter(bestFailed, pureWalk);
            }
        }

        return smoothTeleportRoute(player, start, bestFailed.hops());
    }

    private SearchResult searchDirectAirChain(
        ClientPlayerEntity player,
        BlockPos start,
        BlockPos goal,
        int availableMana,
        TeleportMode teleportMode
    ) {
        List<TeleportHop> hops = new ArrayList<>();
        LongOpenHashSet seen = new LongOpenHashSet(512);
        BlockPos current = start;
        BlockPos previous = null;
        Vec3d smoothedDir = null;
        seen.add(packPos(current));

        double horizontalStartDist = Math.hypot(start.getX() - goal.getX(), start.getZ() - goal.getZ());
        // Hard ceiling: never plan hops above the world build limit.
        // getTopY() is exclusive (e.g. 320 for overworld) — leave a 15-block safety gap.
        // HeightLimitView: getBottomY() + getHeight() gives the exclusive top Y.
        // Subtract 15 to leave a safety gap below the world ceiling.
        int worldCeiling = player.getEntityWorld().getBottomY() + player.getEntityWorld().getHeight() - 15;
        // Scale cruise altitude generously — the air chain clears terrain by arcing above it.
        int cruiseLift = Math.max(54, Math.min(520, (int) (horizontalStartDist * 1.6)));
        int cruiseY = Math.min(worldCeiling, Math.max(start.getY(), goal.getY()) + cruiseLift);
        if (goal.getY() - start.getY() > 20) {
            // When the goal itself is much higher, add extra altitude above it.
            cruiseY = Math.min(worldCeiling, cruiseY + Math.min(300, goal.getY() - start.getY()));
        }
        int maxHopsByMana = availableMana > 0 ? Math.max(1, availableMana / AotvConfig.TRANSMISSION_MANA) : 200;
        // 80 hops minimum — below that the chain can't complete the climb + traverse + descent arc.
        int maxHops = Math.min(420, Math.max(80, maxHopsByMana));
        double bestDistSq = current.getSquaredDistance(goal);
        BlockPos bestPos = current;

        for (int i = 0; i < maxHops; i++) {
            if (current.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                return new SearchResult(hops, true, 0.0);
            }

            BlockPos next = pickDirectAirChainStep(player, current, goal, teleportMode, seen, previous, smoothedDir, cruiseY);
            if (next == null) {
                break;
            }

            hops.add(new TeleportHop(next, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA));
            Vec3d hopVec = Vec3d.ofCenter(next).subtract(Vec3d.ofCenter(current));
            double hopLen = Math.sqrt(hopVec.x * hopVec.x + hopVec.y * hopVec.y + hopVec.z * hopVec.z);
            if (hopLen > 0.001) {
                Vec3d hopDir = hopVec.multiply(1.0 / hopLen);
                if (smoothedDir == null) {
                    smoothedDir = hopDir;
                } else {
                    Vec3d blended = smoothedDir.multiply(0.75).add(hopDir.multiply(0.25));
                    double blendedLen = Math.sqrt(blended.x * blended.x + blended.y * blended.y + blended.z * blended.z);
                    smoothedDir = blendedLen > 0.001 ? blended.multiply(1.0 / blendedLen) : smoothedDir;
                }
            }

            previous = current;
            current = next;
            seen.add(packPos(current));

            double distSq = current.getSquaredDistance(goal);
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
            List<TeleportHop> combined = new ArrayList<>(hops);
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
        ClientPlayerEntity player,
        BlockPos from,
        BlockPos goal,
        TeleportMode teleportMode,
        LongOpenHashSet seen,
        BlockPos previousFrom,
        Vec3d lockedDirection,
        int cruiseY
    ) {
        double fromDist = Math.sqrt(from.getSquaredDistance(goal));
        boolean blockedToGoal = !hasTeleportCorridorClear(player, from, goal);
        Vec3d toGoal = Vec3d.ofCenter(goal).subtract(Vec3d.ofCenter(from));
        Vec3d goalDir = toGoal.lengthSquared() > 0.001 ? toGoal.normalize() : new Vec3d(0.0, 0.0, 0.0);
        double horizontalDist = Math.hypot(from.getX() - goal.getX(), from.getZ() - goal.getZ());
        // Only enter descent once we have actually reached the cruise altitude.
        // Without this guard the chain enters descendPhase the moment it rises above goal.Y + 10,
        // which immediately kills any high-altitude arc before it can clear terrain.
        boolean hasReachedCruise = from.getY() >= cruiseY - 6;
        boolean descendPhase = horizontalDist <= 26.0 || (hasReachedCruise && from.getY() > goal.getY() + 10);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (BlockPos offset : SHORT_OFFSETS) {

            BlockPos candidate = from.add(offset);
            if (seen.contains(packPos(candidate))) {
                continue;
            }
            if (descendPhase && candidate.getY() >= from.getY()) {
                continue;
            }

            if (!isPassableForPlayer(player, candidate) || !isPassableForPlayer(player, candidate.up())) {
                continue;
            }

            if (teleportMode == TeleportMode.JUST_TELEPORT && !hasVerticalClearance(player, candidate, JUST_TELEPORT_MIN_AIR_CLEARANCE)) {
                continue;
            }

            if (!hasTeleportCorridorClear(player, from, candidate)) {
                continue;
            }

            double candidateDist = Math.sqrt(candidate.getSquaredDistance(goal));
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

            Vec3d step = Vec3d.ofCenter(candidate).subtract(Vec3d.ofCenter(from));
            double stepLen = Math.sqrt(step.x * step.x + step.y * step.y + step.z * step.z);
            Vec3d stepDir = stepLen > 0.001 ? step.multiply(1.0 / stepLen) : new Vec3d(0.0, 0.0, 0.0);
            double alignment = step.lengthSquared() > 0.001 ? goalDir.dotProduct(stepDir) : 0.0;

            double lockPenalty = 0.0;
            if (lockedDirection != null && stepLen > 0.001 && !descendPhase) {
                double lockAlign = lockedDirection.dotProduct(stepDir);
                lockPenalty = Math.max(0.0, 1.0 - lockAlign) * 7.5;
                // Keep heading fixed unless a turn is truly needed.
                if (lockAlign < 0.97 && candidateDist > fromDist - 0.55) {
                    continue;
                }
            }

            double lateralPenalty = Math.max(0.0, 1.0 - alignment) * 2.4;

            double turnPenalty = 0.0;
            if (previousFrom != null) {
                Vec3d prevStep = Vec3d.ofCenter(from).subtract(Vec3d.ofCenter(previousFrom));
                double prevLen = Math.sqrt(prevStep.x * prevStep.x + prevStep.y * prevStep.y + prevStep.z * prevStep.z);
                if (prevLen > 0.001 && stepLen > 0.001) {
                    Vec3d prevDir = prevStep.multiply(1.0 / prevLen);
                    double continuity = prevDir.dotProduct(stepDir);
                    turnPenalty = Math.max(0.0, 1.0 - continuity) * 2.2;
                }
            }

            double score = candidateDist - (alignment * 4.35) - Math.max(0.0, offset.getY()) * 0.10 + lateralPenalty + turnPenalty + lockPenalty;
            // Forward preference: reward straighter continuation to reduce oscillation.
            score += Math.abs(offset.getX()) * 0.015 + Math.abs(offset.getZ()) * 0.015;
            score += (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.35;

            if (!descendPhase) {
                // Keep the chain airborne: prefer climbing to a cruise altitude before descending.
                score += Math.max(0, cruiseY - candidate.getY()) * 0.22;
                if (offset.getY() < 0) {
                    score += 2.4;
                }
                if (offset.getY() > 0) {
                    score -= Math.min(4.6, offset.getY() * 0.7);
                }
            } else {
                // Vertical-first descent: prefer straight-down chains before lateral adjustments.
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


        if (best == null && blockedToGoal) {
            // Emergency climb when boxed by walls/mountains: go straight up first.
            for (int dy = 12; dy >= 6; dy--) {
                BlockPos climb = from.up(dy);
                if (seen.contains(packPos(climb))) {
                    continue;
                }
                if (!isPassableForPlayer(player, climb) || !isPassableForPlayer(player, climb.up())) {
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
        }        return best;
    }
    private SearchResult searchOnCustomNodeGraph(
        ClientPlayerEntity player,
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

        SearchNode first = new SearchNode(startNode, null, 0.0, heuristicWithStart(start, goal, start), 0, TeleportHop.HopType.WALK, 0);
        open.insertOrUpdate(startPacked, first.fScore);
        visited.put(startPacked, first);

        SearchNode best = first;
        double bestDistSq = start.getSquaredDistance(goal);

        int expansions = 0;
        while (!open.isEmpty() && expansions < maxExpansions) {
            long currentPacked = open.extractMin();
            if (closed.contains(currentPacked)) {
                continue;
            }
            closed.add(currentPacked);

            SearchNode current = visited.get(currentPacked);
            if (current == null) continue;
            expansions++;

            double distSq = current.node.pos.getSquaredDistance(goal);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = current;
            }

            if (current.node.pos.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
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
                if (current.type == TeleportHop.HopType.WALK && edge.type != TeleportHop.HopType.WALK) {
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
                    edge.manaCost
                );

                visited.put(edgePacked, next);
                open.insertOrUpdate(edgePacked, nextF);
            }
        }

        return new SearchResult(backtrack(best), false, bestDistSq);
    }

    private Long2ObjectOpenHashMap<GraphNode> buildCustomNodeGraph(
        ClientPlayerEntity player,
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
            Comparator.comparingDouble((GraphNode n) -> n.pos.getSquaredDistance(goal))
        );
        LongOpenHashSet expanded = new LongOpenHashSet(maxNodes);

        GraphNode startNode = new GraphNode(start);
        graph.put(packPos(start), startNode);
        queue.add(startNode);

        int expansions = 0;
        while (!queue.isEmpty() && expansions < maxExpansions && graph.size() < maxNodes) {
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

                current.edges.add(new GraphEdge(to, neighbor.type, neighbor.manaCost, neighbor.travelCost));

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
                    BlockPos neighborPos = goal.add(walkOffset).add(0, dy, 0);
                    GraphNode neighborNode = graph.get(packPos(neighborPos));
                    if (neighborNode != null && isWalkTransitionValid(player, neighborPos, goal)) {
                        neighborNode.edges.add(new GraphEdge(goalNode, TeleportHop.HopType.WALK, 0, 1.35));
                    }
                }
            }
        }

        return graph;
    }

    private boolean shouldExpandNode(BlockPos start, BlockPos goal, BlockPos candidate, boolean includeTeleports) {
        double startToGoal = Math.sqrt(start.getSquaredDistance(goal));
        double startToCandidate = Math.sqrt(start.getSquaredDistance(candidate));
        double candidateToGoal = Math.sqrt(candidate.getSquaredDistance(goal));
        double slack = includeTeleports ? 70.0 : 50.0;
        return startToCandidate + candidateToGoal <= startToGoal + slack;
    }

    private Collection<Neighbor> neighbors(ClientPlayerEntity player, BlockPos from, BlockPos start, boolean includeTeleports, boolean allowAirNormalTeleports, TeleportMode teleportMode, BlockPos goal) {
        List<Neighbor> out = new ArrayList<>(SHORT_OFFSETS.size() + LONG_OFFSETS.size() + 16);
        double fromGoalSq = from.getSquaredDistance(goal);
        // For the starting node use the player's actual facing; for every subsequent node
        // the player has already flicked to aim at this position, so their effective facing
        // is the direction they just traveled (start → from).
        float fromYaw = from.equals(start) ? player.getYaw() : yawTo(start, from);

        // Horizontal goal direction for straight-path bias.
        // Only the XZ plane matters — going vertically to clear terrain is not penalised.
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

                    BlockPos aimPoint = from.add(offset);
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
                            out.add(new Neighbor(aimPoint, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, 2.1 + turnPenalty));
                        }
                        continue;
                    }

                    double gravityPenalty = Math.max(0, aimPoint.getY() - landing.getY()) * 0.03;
                    double landingGoalSq = landing.getSquaredDistance(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
                        continue;
                    }
                    if (!isWaypointCastStable(player, from, landing)) {
                        continue;
                    }
                    // Reject landings squeezed into 1-block gaps or clipped inside geometry —
                    // the player would be unable to see / reach the next waypoint from there.
                    if (!hasAdequateHorizontalClearance(player, landing)) {
                        continue;
                    }
                    // Penalise lateral deviation from the goal direction so the planner prefers
                    // going straight through/over obstacles rather than routing around them.
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
                    double longHopBonusN = Math.max(0.0, (actualDistN / AotvConfig.TRANSMISSION_RANGE - 0.75) * 0.6);
                    double travelCostN = 1.85 + gravityPenalty + turnPenalty + straightPenaltyN - longHopBonusN
                        + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.15;
                    out.add(new Neighbor(landing, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, travelCostN));
                }
            }

            if (allowShift) {
                for (BlockPos offset : LONG_OFFSETS) {
                    BlockPos aimPoint = from.add(offset);
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
                    double landingGoalSq = landing.getSquaredDistance(goal);
                    if (landingGoalSq > fromGoalSq + 200.0 && !landing.isWithinDistance(goal, AotvConfig.GOAL_REACHED_RADIUS)) {
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
                    double longHopBonusS = Math.max(0.0, (actualDistS / AotvConfig.ETHERWARP_RANGE - 0.80) * 0.4);
                    double travelCostS = 2.5 + gravityPenalty + (yawDelta / 120.0) + straightPenaltyS - longHopBonusS
                        + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.15;
                    out.add(new Neighbor(landing, TeleportHop.HopType.SHIFT, AotvConfig.ETHERWARP_MANA, travelCostS));
                }
            }
        }

        // Context-aware walk cost:
        //   • Teleport exits exist at this node → keep walk expensive (5.0) so the planner
        //     strongly prefers teleporting whenever it is physically possible.
        //   • No teleport exits at this node (tunnel, enclosed corridor, solid obstacle) →
        //     drop walk cost to bridge cost (1.5) so the planner walks through the obstacle
        //     and resumes teleporting on the other side, rather than routing a long detour around it.
        boolean hasTeleportExits = !out.isEmpty();  // 'out' holds only teleport moves at this point
        // 8.0 per step when teleports are reachable: 4× the per-block teleport cost so the
        // planner strongly prefers teleporting, but a 2-3 step walk around a solid obstacle
        // is still cheaper than a 9+ hop teleport detour.  At 15.0 the A* spent its entire
        // expansion budget chasing long teleport detours and never found the short walk-around,
        // causing paths to fail completely when a single block blocked all teleport directions.
        // 1.5 per step when no teleports exist (tunnel / enclosed space) so the planner
        // bridges through the obstacle and resumes teleporting on the far side.
        double walkTravelCost = includeTeleports
            ? (hasTeleportExits ? 8.0 : 1.5)
            : 1.35;
        for (BlockPos walkOffset : WALK_OFFSETS) {
            BlockPos base = from.add(walkOffset);
            for (int y = -1; y <= 1; y++) {
                BlockPos candidate = base.add(0, y, 0);
                if (!isWalkTransitionValid(player, from, candidate)) {
                    continue;
                }
                if (teleportMode == TeleportMode.JUST_TELEPORT && !candidate.isWithinDistance(goal, JUST_TELEPORT_FINAL_WALK_RADIUS)) {
                    continue;
                }
                out.add(new Neighbor(candidate, TeleportHop.HopType.WALK, 0, walkTravelCost + (y > 0 ? 0.15 : 0.0)));
            }
        }

        // Jump-over walk: 2-block cardinal jump clearing a 1-block solid obstacle.
        // Same conditions as AotvWalkPathfinder: mid has collision, two blocks above are
        // clear for the arc, and the landing is safe at the same floor level.
        for (BlockPos jumpOffset : JUMP_OFFSETS) {
            BlockPos dest = from.add(jumpOffset);
            BlockPos mid  = new BlockPos(
                (from.getX() + dest.getX()) / 2,
                from.getY(),
                (from.getZ() + dest.getZ()) / 2
            );
            if (!isChunkLoaded(player, mid) || !isChunkLoaded(player, dest)) continue;
            if (isWalkPassable(player, mid)) continue;                            // no obstacle to jump
            if (!isWalkPassable(player, mid.up()) || !isWalkPassable(player, mid.up(2))) continue; // arc blocked
            if (!isWalkSafeStanding(player, dest)) continue;                      // bad landing
            if (dest.getY() != from.getY()) continue;                             // must be same floor level
            if (teleportMode == TeleportMode.JUST_TELEPORT && !dest.isWithinDistance(goal, JUST_TELEPORT_FINAL_WALK_RADIUS)) continue;
            out.add(new Neighbor(dest, TeleportHop.HopType.WALK, 0, walkTravelCost * 2.0 + 0.2));
        }

        if (includeTeleports && teleportMode != TeleportMode.SHIFT_ONLY) {
            for (BlockPos walkOffset : WALK_OFFSETS) {
                BlockPos edge = from.add(walkOffset);
                // The player walks one block into the edge to reach the drop.
                // Both feet-level (edge) and head-level (edge.up()) must be open air so
                // the player doesn't clip into a block when stepping off the ledge.
                if (!isPassableForPlayer(player, edge) || !isPassableForPlayer(player, edge.up()) || isSafeStanding(player, edge)) {
                    continue;
                }

                for (int drop = 2; drop <= 8; drop++) {
                    BlockPos landing = edge.down(drop);
                    if (!isSafeStanding(player, landing)) {
                        continue;
                    }
                    // Verify every block in the falling column between the edge step and
                    // the landing is passable.  isSafeStanding only checks landing.up()
                    // (one above the floor); for drops ≥ 3 the intermediate blocks go
                    // unchecked and can silently stop the player mid-fall.
                    boolean columnClear = true;
                    for (int d = 1; d < drop; d++) {
                        if (!isPassableForPlayer(player, edge.down(d))) {
                            columnClear = false;
                            break;
                        }
                    }
                    if (!columnClear) continue;
                    if (!hasTeleportCorridorClear(player, from, landing)) {
                        continue;
                    }
                    out.add(new Neighbor(landing, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA, 2.0 + drop * 0.08));
                    break;
                }
            }
        }

        return out;
    }

    private boolean isWaypointCastStable(ClientPlayerEntity player, BlockPos from, BlockPos landing) {
        // Simple eye-to-target ray: player eye → centre of the landing block at torso height.
        // The nudge/jitter variant was over-rejecting valid positions because the lateral
        // jitter (±0.08 in X only) didn't represent the actual AOTV cast geometry.
        Vec3d fromEye = new Vec3d(from.getX() + 0.5, from.getY() + 1.62, from.getZ() + 0.5);
        Vec3d target  = new Vec3d(landing.getX() + 0.5, landing.getY() + 0.92, landing.getZ() + 0.5);
        return rayClear(player, fromEye, target);
    }

    /**
     * Returns true when the teleport landing has at least 2 open cardinal neighbours at
     * both feet and head level.  Nodes that sit in 1-block-wide gaps or slightly inside
     * blocks fail this check and are excluded from the graph, preventing the pathfinder
     * from building paths the player physically cannot follow.
     */
    private boolean hasAdequateHorizontalClearance(ClientPlayerEntity player, BlockPos pos) {
        int open = 0;
        BlockPos[] adj = {
            pos.add(1, 0, 0), pos.add(-1, 0, 0),
            pos.add(0, 0, 1), pos.add(0, 0, -1)
        };
        for (BlockPos a : adj) {
            if (isWalkPassable(player, a) && isWalkPassable(player, a.up())) {
                open++;
            }
        }
        return open >= 2;
    }

    private boolean rayClear(ClientPlayerEntity player, Vec3d start, Vec3d end) {
        // COLLIDER-only: AOTV targeting uses collision shapes, not outline shapes.
        // The OUTLINE check was incorrectly rejecting casts when the aim ray grazed the
        // edge of an adjacent block (e.g. a single wall block next to the player).
        // Grass, flowers, and other non-solid decorators have empty collision shapes and
        // do not block AOTV, so they must not be caught here.
        HitResult colliderHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return colliderHit.getType() == HitResult.Type.MISS;
    }

    private float yawTo(BlockPos from, BlockPos to) {
        Vec3d start = new Vec3d(from.getX() + 0.5, from.getY() + 1.62, from.getZ() + 0.5);
        Vec3d end = new Vec3d(to.getX() + 0.5, to.getY() + 0.92, to.getZ() + 0.5);
        Vec3d d = end.subtract(start);
        return (float) (Math.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90.0F;
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private List<TeleportHop> smoothTeleportRoute(ClientPlayerEntity player, BlockPos start, List<TeleportHop> input) {
        if (input == null || input.size() < 3) {
            return input;
        }
        List<TeleportHop> out = new ArrayList<>();
        BlockPos current = start;
        int i = 0;
        while (i < input.size()) {
            TeleportHop hop = input.get(i);
            if (hop.type() == TeleportHop.HopType.WALK) {
                // ── Walk→teleport skip optimisation ──────────────────────────
                // After bridging through an obstacle by walking, check whether we can now
                // teleport to a position further along the walk segment. Scan backward from
                // the furthest look-ahead so we always take the longest possible teleport skip.
                int maxRange = AotvConfig.TRANSMISSION_RANGE;
                int scanLimit = Math.min(input.size() - 1, i + maxRange + 6);
                int skipTo = -1;
                for (int j = scanLimit; j > i; j--) {
                    BlockPos dest = input.get(j).landing();
                    double distSq = current.getSquaredDistance(dest);
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
                    out.add(new TeleportHop(dest, TeleportHop.HopType.NORMAL, AotvConfig.TRANSMISSION_MANA));
                    current = dest;
                    i = skipTo + 1;
                    continue;
                }
                // No teleport available from here — take the walk step.
                out.add(hop);
                current = hop.landing();
                i++;
                continue;
            }

            // Teleport hop: try to skip up to 6 redundant same-type hops ahead.
            int best = i;
            for (int j = Math.min(i + 6, input.size() - 1); j > i; j--) {
                TeleportHop candidate = input.get(j);
                if (candidate.type() != hop.type()) {
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
            TeleportHop selected = input.get(best);
            if (selected.type() != TeleportHop.HopType.WALK
                    && (!hasTeleportCorridorClear(player, current, selected.landing())
                        || !isWaypointCastStable(player, current, selected.landing())
                        || !hasAdequateHorizontalClearance(player, current))) {
                BlockPos launchPos = findViableLaunchPosition(player, current, selected.landing(), input, best);
                if (launchPos != null && !launchPos.equals(current)) {
                    List<BlockPos> walkChain = buildShortWalkChain(player, current, launchPos);
                    for (BlockPos step : walkChain) {
                        out.add(new TeleportHop(step, TeleportHop.HopType.WALK, 0));
                    }
                    out.add(new TeleportHop(selected.landing(), selected.type(), selected.manaCost()));
                    current = selected.landing();
                    i = best + 1;
                    continue;
                }
            }
            out.add(new TeleportHop(selected.landing(), selected.type(), selected.manaCost()));
            current = selected.landing();
            i = best + 1;
        }
        return out;
    }

    private boolean isAirWaypointValid(ClientPlayerEntity player, BlockPos from, BlockPos pos) {
        if (!hasTeleportCorridorClear(player, from, pos)) {
            return false;
        }
        return isPassableForPlayer(player, pos) && isPassableForPlayer(player, pos.up());
    }

    private BlockPos findViableLaunchPosition(ClientPlayerEntity player, BlockPos current, BlockPos teleportDest, List<TeleportHop> input, int teleportIndex) {
        // Strategy 1: scan forward along input walk hops — the player can walk
        // further past the cramped handoff to find open ground.
        for (int j = teleportIndex - 1; j >= Math.max(0, teleportIndex - 8); j--) {
            TeleportHop h = input.get(j);
            if (h.type() != TeleportHop.HopType.WALK) break;
            BlockPos candidate = h.landing();
            if (!hasAdequateHorizontalClearance(player, candidate)) continue;
            if (!hasTeleportCorridorClear(player, candidate, teleportDest)) continue;
            if (!isWaypointCastStable(player, candidate, teleportDest)) continue;
            return candidate;
        }

        // Strategy 2: wide lateral search (8 directions, up to 3 blocks) around current.
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        BlockPos bestLateral = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dist = 1; dist <= 3; dist++) {
            for (int[] d : dirs) {
                BlockPos candidate = current.add(d[0] * dist, 0, d[1] * dist);
                if (!isWalkSafeStanding(player, candidate)) continue;
                if (!hasAdequateHorizontalClearance(player, candidate)) continue;
                if (!hasTeleportCorridorClear(player, candidate, teleportDest)) continue;
                if (!isWaypointCastStable(player, candidate, teleportDest)) continue;
                double dSq = candidate.getSquaredDistance(current);
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    bestLateral = candidate;
                }
            }
            if (bestLateral != null) return bestLateral;
        }
        return bestLateral;
    }

    private boolean hasTeleportCorridorClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        // Check at upper-torso (1.05) and head (1.62) height.
        // The 1.05 offset is slightly above the block surface (Y+1.0) so the lower ray
        // clears small ground-level edges that previously clipped at 0.92.
        if (!isRayClear(player, from, to, 1.05)) return false;
        if (!isRayClear(player, from, to, 1.62)) return false;

        // Boundary-trap detection: the player body is 0.6m wide (±0.3 from centre).
        // A wall exactly one block away is only 0.2m from the body edge, so the
        // centre ray passes cleanly while the real aim ray clips the wall in-game.
        // When the landing position is adjacent to any solid block, also check two
        // rays offset ±0.25 perpendicular to the aim direction at head height.
        if (hasAdjacentSolid(player, from)) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double horizLen = Math.sqrt(dx * dx + dz * dz);
            if (horizLen > 0.001) {
                double px = -dz / horizLen * 0.25;
                double pz =  dx / horizLen * 0.25;
                Vec3d toCenter = new Vec3d(to.getX() + 0.5, to.getY() + 0.92, to.getZ() + 0.5);
                Vec3d edgeA = new Vec3d(from.getX() + 0.5 + px, from.getY() + 1.62, from.getZ() + 0.5 + pz);
                Vec3d edgeB = new Vec3d(from.getX() + 0.5 - px, from.getY() + 1.62, from.getZ() + 0.5 - pz);
                if (!rayClear(player, edgeA, toCenter)) return false;
                if (!rayClear(player, edgeB, toCenter)) return false;
            }
        }
        return true;
    }

    // Returns true when any cardinal neighbour at feet or head height is a solid block,
    // meaning the player is against a wall and boundary-trap checks are needed.
    private boolean hasAdjacentSolid(ClientPlayerEntity player, BlockPos pos) {
        BlockPos head = pos.up();
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

    private List<BlockPos> buildShortWalkChain(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        List<BlockPos> chain = new ArrayList<>();
        BlockPos cursor = from;
        for (int step = 0; step < 6; step++) {
            if (cursor.equals(to)) break;
            int dx = Integer.signum(to.getX() - cursor.getX());
            int dz = Integer.signum(to.getZ() - cursor.getZ());

            BlockPos[] tries = (dx != 0 && dz != 0)
                ? new BlockPos[]{ cursor.add(dx, 0, dz), cursor.add(dx, 0, 0), cursor.add(0, 0, dz) }
                : new BlockPos[]{ cursor.add(dx, 0, dz) };

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

    private boolean isRayClear(ClientPlayerEntity player, BlockPos from, BlockPos to, double yOffset) {
        // If the destination chunk is not loaded the raycast passes through it as if it
        // were empty air — block that case so we never target unloaded terrain.
        if (!isChunkLoaded(player, to)) return false;
        Vec3d start = new Vec3d(from.getX() + 0.5, from.getY() + yOffset, from.getZ() + 0.5);
        Vec3d end = new Vec3d(to.getX() + 0.5, to.getY() + yOffset, to.getZ() + 0.5);

        HitResult colliderHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        // OUTLINE catches hollow blocks (stairs, fences, glass panes) whose collision
        // shape has gaps that COLLIDER rays slip through, but which AOTV cannot actually
        // pass through.  Ignore OUTLINE hits very close to the source — those are blocks
        // adjacent to the player that don't obstruct the cast — and hits very close to
        // the destination to avoid false-positives from the landing block itself.
        HitResult outlineHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            end,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (outlineHit.getType() != HitResult.Type.MISS) {
            double hitFromStartSq = outlineHit.getPos().squaredDistanceTo(start);
            double hitFromEndSq   = outlineHit.getPos().squaredDistanceTo(end);
            // > 1.5 blocks from source AND > 1.0 block from destination
            if (hitFromStartSq > 2.25 && hitFromEndSq > 1.0) {
                return false;
            }
        }
        return true;
    }
    private BlockPos settleByGravity(ClientPlayerEntity player, BlockPos start) {
        return settleByGravityWithLimit(player, start, MAX_GRAVITY_DROP);
    }

    private BlockPos settleByGravityWithLimit(ClientPlayerEntity player, BlockPos start, int maxDrop) {
        BlockPos cursor = start;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (isSafeStanding(player, cursor)) {
                return cursor;
            }
            if (!isPassableForPlayer(player, cursor) || !isPassableForPlayer(player, cursor.up())) {
                return null;
            }
            cursor = cursor.down();
        }
        return null;
    }

    private boolean isChunkLoaded(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private boolean isPassableForPlayer(ClientPlayerEntity player, BlockPos pos) {
        // Unloaded chunks report every block as AIR — treat them as solid so the
        // pathfinder never punches through terrain that the client hasn't received yet.
        if (!isChunkLoaded(player, pos)) return false;
        return player.getEntityWorld().getBlockState(pos).isAir();
    }

    private boolean isWalkPassable(ClientPlayerEntity player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        return player.getEntityWorld().getBlockState(pos)
            .getCollisionShape(player.getEntityWorld(), pos)
            .isEmpty();
    }

    private boolean isWalkSafeStanding(ClientPlayerEntity player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        BlockState feet = player.getEntityWorld().getBlockState(pos);
        BlockState head = player.getEntityWorld().getBlockState(pos.up());
        BlockState below = player.getEntityWorld().getBlockState(pos.down());
        return feet.getCollisionShape(player.getEntityWorld(), pos).isEmpty()
            && head.getCollisionShape(player.getEntityWorld(), pos.up()).isEmpty()
            && below.isSolidBlock(player.getEntityWorld(), pos.down());
    }
    private boolean hasVerticalClearance(ClientPlayerEntity player, BlockPos base, int requiredAirBlocks) {
        for (int i = 0; i < requiredAirBlocks; i++) {
            if (!isPassableForPlayer(player, base.up(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isWalkTransitionValid(ClientPlayerEntity player, BlockPos from, BlockPos to) {
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
            if (!player.getEntityWorld().getBlockState(from.up(2)).isAir()) {
                return false;
            }
            if (!hasJumpArcClear(player, from, to)) {
                return false;
            }
        }

        if (dx != 0 && dz != 0) {
            BlockPos sideX = from.add(dx, 0, 0);
            BlockPos sideZ = from.add(0, 0, dz);
            boolean sideXClear = isWalkPassable(player, sideX) && isWalkPassable(player, sideX.up());
            boolean sideZClear = isWalkPassable(player, sideZ) && isWalkPassable(player, sideZ.up());

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

    private boolean hasWalkCorridorClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        Vec3d fromFeet = Vec3d.ofCenter(from).add(0.0, 0.05, 0.0);
        Vec3d toFeet = Vec3d.ofCenter(to).add(0.0, 0.05, 0.0);
        Vec3d fromHead = Vec3d.ofCenter(from).add(0.0, 1.05, 0.0);
        Vec3d toHead = Vec3d.ofCenter(to).add(0.0, 1.05, 0.0);

        HitResult feetHit = player.getEntityWorld().raycast(new RaycastContext(
            fromFeet,
            toFeet,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (feetHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        HitResult headHit = player.getEntityWorld().raycast(new RaycastContext(
            fromHead,
            toHead,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return headHit.getType() == HitResult.Type.MISS;
    }

    private boolean hasJumpArcClear(ClientPlayerEntity player, BlockPos from, BlockPos to) {
        Vec3d upStart = Vec3d.ofCenter(from).add(0.0, 0.05, 0.0);
        Vec3d upEnd = upStart.add(0.0, 1.0, 0.0);
        HitResult vertical = player.getEntityWorld().raycast(new RaycastContext(
            upStart,
            upEnd,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (vertical.getType() != HitResult.Type.MISS) {
            return false;
        }

        Vec3d hStart = Vec3d.ofCenter(from).add(0.0, 1.05, 0.0);
        Vec3d hEnd = Vec3d.ofCenter(to).add(0.0, 1.05, 0.0);
        HitResult horizontal = player.getEntityWorld().raycast(new RaycastContext(
            hStart,
            hEnd,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return horizontal.getType() == HitResult.Type.MISS;
    }

    private boolean isSafeStanding(ClientPlayerEntity player, BlockPos pos) {
        if (!isChunkLoaded(player, pos)) return false;
        BlockState feet = player.getEntityWorld().getBlockState(pos);
        BlockState head = player.getEntityWorld().getBlockState(pos.up());
        BlockState below = player.getEntityWorld().getBlockState(pos.down());
        return feet.isAir() && head.isAir() && below.isSolidBlock(player.getEntityWorld(), pos.down());
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

        return (octileXZ + perpXZ * 0.5 + heightPenalty) / AotvConfig.ETHERWARP_RANGE;
    }

    private static final double D2_CONST = Math.sqrt(2.0);

    private List<TeleportHop> backtrack(SearchNode node) {
        List<TeleportHop> reversed = new ArrayList<>();
        SearchNode cursor = node;
        while (cursor != null && cursor.parent != null) {
            reversed.add(new TeleportHop(cursor.node.pos, cursor.type, cursor.manaCost));
            cursor = cursor.parent;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private SearchResult searchPureWalk(ClientPlayerEntity player, BlockPos start, BlockPos goal, int maxExpansions) {
        AotvWalkPathfinder.Result walk = walkPathfinder.findPath(
            player,
            start,
            goal,
            maxExpansions,
            AotvConfig.GOAL_REACHED_RADIUS
        );

        List<TeleportHop> hops = new ArrayList<>(walk.path().size());
        for (WalkPathNode node : walk.path()) {
            hops.add(new TeleportHop(node.pos, TeleportHop.HopType.WALK, 0));
        }

        return new SearchResult(hops, walk.reachedGoal(), walk.bestDistanceSq());
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
        int max = AotvConfig.TRANSMISSION_RANGE;
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
        // Evaluate closer offsets first so expensive corridor checks on far
        // candidates are skipped when a nearer valid hop is already found.
        out.sort(Comparator.comparingInt(p -> p.getX() * p.getX() + p.getY() * p.getY() + p.getZ() * p.getZ()));
        return out;
    }

    private static List<BlockPos> buildLongOffsets() {
        List<BlockPos> out = new ArrayList<>();
        int max = AotvConfig.ETHERWARP_RANGE;
        List<Integer> distances = new ArrayList<>();
        for (int d = AotvConfig.TRANSMISSION_RANGE + 1; d <= max; d += 4) {
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

                Vec3d unit = new Vec3d(-sy * cp, -sp, cy * cp);
                for (int distance : distances) {
                    BlockPos offset = BlockPos.ofFloored(unit.multiply(distance));
                    if (offset.getManhattanDistance(BlockPos.ORIGIN) < AotvConfig.TRANSMISSION_RANGE + 2) {
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

    private record Neighbor(BlockPos pos, TeleportHop.HopType type, int manaCost, double travelCost) {}
    private record GraphEdge(GraphNode to, TeleportHop.HopType type, int manaCost, double travelCost) {}
    private record SearchResult(List<TeleportHop> hops, boolean reachedGoal, double bestDistanceSq) {
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
        private final TeleportHop.HopType type;
        private final int manaCost;

        private SearchNode(
            GraphNode node,
            SearchNode parent,
            double gScore,
            double fScore,
            int manaSpent,
            TeleportHop.HopType type,
            int manaCost
        ) {
            this.node = node;
            this.parent = parent;
            this.gScore = gScore;
            this.fScore = fScore;
            this.manaSpent = manaSpent;
            this.type = type;
            this.manaCost = manaCost;
        }
    }
}
































