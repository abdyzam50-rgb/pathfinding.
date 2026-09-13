package com.abdy2.aotvpathfinder.path;

import java.util.ArrayList;
import java.util.List;

import com.abdy2.aotvpathfinder.parkour.PathNode;
import com.abdy2.aotvpathfinder.parkour.PathfinderEngine;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Plans the walking parts of a route using the kinematic parkour engine.
 *
 * <p>Replaces the grid walk search. The difference that matters is not that it finds better routes
 * — it is that its nodes say how to be performed. The engine simulates real movement physics and
 * labels every node with the action that produced it, so a jump is a jump because the planner
 * decided it while validating the trajectory, not because the executor guessed from block heights
 * afterwards. Every walking bug worth the name in this mod came from that gap.
 *
 * <p>This class is only the boundary: it calls the engine and translates its nodes into the route
 * steps the rest of the mod already understands, carrying the action and any block the step has to
 * place or use.
 */
public final class ParkourWalkPlanner {
    private ParkourWalkPlanner() {}

    /** The result of planning a walk, and whether it actually got there. */
    public record Result(List<PathHop> hops, boolean reachedGoal) {
        static Result none() {
            return new Result(List.of(), false);
        }
    }

    /**
     * Walks from {@code start} to {@code goal}.
     *
     * @param allowParkour whether jumps, drops and placements may be used, or only plain walking
     */
    public static Result plan(LocalPlayer player, BlockPos start, BlockPos goal, boolean allowParkour) {
        if (player == null || player.level() == null) {
            return Result.none();
        }
        List<PathNode> nodes;
        try {
            nodes = allowParkour
                ? PathfinderEngine.findSprintNodePath(player.level(), start, goal)
                : PathfinderEngine.findNodePath(player.level(), start, goal);
        } catch (Exception e) {
            // The engine is large and world-dependent; a failure to plan must not take a run with
            // it. An empty result reads the same as finding nothing, which callers already handle.
            return Result.none();
        }
        if (nodes == null || nodes.isEmpty()) {
            return Result.none();
        }

        List<PathHop> hops = new ArrayList<>(nodes.size());
        for (PathNode node : nodes) {
            MoveKind kind = kindOf(node.type);
            hops.add(kind.needsItem()
                ? PathHop.acting(node.pos, kind, node.interactPos)
                : PathHop.of(node.pos, HopType.WALK, 0, kind));
        }

        BlockPos end = hops.get(hops.size() - 1).landing();
        return new Result(hops, end.closerThan(goal, 1.8));
    }

    /**
     * Translates the engine's node type into the route's own vocabulary.
     *
     * <p>One to one on purpose. Folding several kinds together here would discard the reason for
     * using this planner, since the executor needs to tell a bridge from a bounce to perform either.
     */
    private static MoveKind kindOf(PathNode.Type type) {
        return switch (type) {
            case WALK -> MoveKind.WALK;
            case JUMP -> MoveKind.JUMP;
            case SPRINT_JUMP -> MoveKind.SPRINT_JUMP;
            case BOOST_PLACE -> MoveKind.BOOST_PLACE;
            case DROP -> MoveKind.DROP;
            case WATER_DROP -> MoveKind.WATER_DROP;
            case BOUNCE -> MoveKind.BOUNCE;
            case CLIMB -> MoveKind.CLIMB;
            case EDGE -> MoveKind.EDGE;
            case INTERACT -> MoveKind.INTERACT;
            case PILLAR -> MoveKind.PILLAR;
            case BRIDGE -> MoveKind.BRIDGE;
        };
    }
}
