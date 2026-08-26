package net.netherite.tutorialmod;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public class MinecraftPathfinder {

    private static final int MAX_SEARCH_NODES = 5000;
    private static final int MAX_DROP_DISTANCE = 4;

    public static List<PathNode> findNodePath(ServerWorld world, BlockPos start, BlockPos end) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(node ->
                node.gCost + Math.sqrt(node.pos.getSquaredDistance(end))));
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, PathNode> openMap = new HashMap<>();

        PathNode startNode = new PathNode(start, null, PathNode.Type.WALK);
        openSet.add(startNode);
        openMap.put(start, startNode);

        PathNode targetReachedNode = null;
        int searchedNodes = 0;

        BlockPos[] horizontalDirs = {
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
        };

        while (!openSet.isEmpty() && searchedNodes < MAX_SEARCH_NODES) {
            PathNode current = openSet.poll();
            openMap.remove(current.pos);
            closedSet.add(current.pos);
            searchedNodes++;

            if (current.pos.equals(end) || current.pos.getSquaredDistance(end) <= 1) {
                targetReachedNode = current;
                break;
            }

            for (BlockPos dir : horizontalDirs) {
                BlockPos neighborPos = current.pos.add(dir);
                if (closedSet.contains(neighborPos)) continue;

                List<PathNode> validMoves = getValidMovesForDirection(world, current, neighborPos, start, end);

                for (PathNode neighborNode : validMoves) {
                    if (closedSet.contains(neighborNode.pos)) continue;

                    if (!openMap.containsKey(neighborNode.pos)) {
                        openSet.add(neighborNode);
                        openMap.put(neighborNode.pos, neighborNode);
                    } else if (neighborNode.gCost < openMap.get(neighborNode.pos).gCost) {
                        openSet.remove(openMap.get(neighborNode.pos));
                        openSet.add(neighborNode);
                        openMap.put(neighborNode.pos, neighborNode);
                    }
                }
            }
        }

        List<PathNode> path = new ArrayList<>();
        if (targetReachedNode != null) {
            PathNode current = targetReachedNode;
            while (current != null) {
                path.add(0, current);
                current = current.parent;
            }
        }
        return path;
    }

    private static List<PathNode> getValidMovesForDirection(ServerWorld world, PathNode current, BlockPos target, BlockPos start, BlockPos end) {
        List<PathNode> moves = new ArrayList<>();

        double currentHeight = PathingEnvironment.getBlockMaxHeight(world, current.pos.down());
        double targetHeight  = PathingEnvironment.getBlockMaxHeight(world, target.down());

        // --- 1. Standard walk (same level or very slight height difference) ---
        if (PathingEnvironment.isPassable(world, target, start, end)
                && PathingEnvironment.isPassable(world, target.up(), start, end)
                && PathingEnvironment.hasSolidGround(world, target)) {

            if (Math.abs(targetHeight - currentHeight) <= 0.6) {
                moves.add(new PathNode(target, current, PathNode.Type.WALK));
            }
        }

        // --- 2. Jump step-up (target block is solid, jump onto block above it) ---
        // target must NOT be passable (i.e. solid) so we jump ONTO it
        boolean targetIsSolid = !PathingEnvironment.isPassable(world, target, start, end);
        BlockPos upStep = target.up();
        if (targetIsSolid
                && PathingEnvironment.isPassable(world, upStep, start, end)
                && PathingEnvironment.isPassable(world, upStep.up(), start, end)
                && PathingEnvironment.isPassable(world, current.pos.up(), start, end)) {

            BlockPos groundBelowCurrent = current.pos.down();
            // AND (not OR): block must exist AND have a positive height
            boolean standingOnGround = !world.getBlockState(groundBelowCurrent).isAir()
                    && PathingEnvironment.getBlockMaxHeight(world, groundBelowCurrent) > 0.0;

            if (standingOnGround) {
                double stepHeight = PathingEnvironment.getBlockMaxHeight(world, target);
                if (stepHeight - currentHeight <= 1.25) {
                    moves.add(new PathNode(upStep, current, PathNode.Type.JUMP));
                }
            }
        }

        // --- 3. Drop: walk into air and fall to landing spot ---
        // Only allowed when the current node has solid ground (no falling from mid-air)
        if (PathingEnvironment.isPassable(world, target, start, end)
                && PathingEnvironment.isPassable(world, target.up(), start, end)
                && PathingEnvironment.hasSolidGround(world, current.pos)
                && !PathingEnvironment.hasSolidGround(world, target)) {

            for (int drop = 1; drop <= MAX_DROP_DISTANCE; drop++) {
                BlockPos landingPos = new BlockPos(target.getX(), target.getY() - drop, target.getZ());

                if (!PathingEnvironment.isPassable(world, landingPos, start, end)) break;
                if (!PathingEnvironment.isPassable(world, landingPos.up(), start, end)) break;

                if (PathingEnvironment.hasSolidGround(world, landingPos)) {
                    moves.add(new PathNode(landingPos, current, PathNode.Type.DROP));
                    break;
                }
            }
        }

        return moves;
    }

    public static List<PathNode> compressPath(ServerWorld world, List<PathNode> fullPath, BlockPos start, BlockPos end) {
        if (fullPath.size() <= 2) return fullPath;

        List<PathNode> compressed = new ArrayList<>();
        compressed.add(fullPath.get(0));

        int index = 0;
        while (index < fullPath.size() - 1) {
            int nextKeyIndex = index + 1;

            for (int i = index + 2; i < fullPath.size(); i++) {
                PathNode checking  = fullPath.get(i);
                PathNode previous  = fullPath.get(i - 1);

                // Never compress across JUMP, DROP, or INTERACT nodes
                if (checking.type == PathNode.Type.JUMP
                        || checking.type == PathNode.Type.DROP
                        || checking.type == PathNode.Type.INTERACT) break;

                // Stop compression when height drops (active fall)
                if (checking.pos.getY() < previous.pos.getY()) break;

                if (hasLineOfSight(world, fullPath.get(index).pos, checking.pos, start, end)) {
                    nextKeyIndex = i;
                } else {
                    break;
                }
            }

            compressed.add(fullPath.get(nextKeyIndex));
            index = nextKeyIndex;
        }

        return compressed;
    }

    private static boolean hasLineOfSight(ServerWorld world, BlockPos p1, BlockPos p2, BlockPos start, BlockPos end) {
        int x1 = p1.getX(), y1 = p1.getY(), z1 = p1.getZ();
        int x2 = p2.getX(), y2 = p2.getY(), z2 = p2.getZ();

        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);
        int xs = (x1 < x2) ? 1 : -1;
        int ys = (y1 < y2) ? 1 : -1;
        int zs = (z1 < z2) ? 1 : -1;

        int x = x1, y = y1, z = z1;

        if (dx >= dy && dx >= dz) {
            int e1 = 2 * dy - dx, e2 = 2 * dz - dx;
            while (x != x2) {
                x += xs;
                if (e1 >= 0) { y += ys; e1 -= 2 * dx; }
                if (e2 >= 0) { z += zs; e2 -= 2 * dx; }
                e1 += 2 * dy; e2 += 2 * dz;
                if (!PathingEnvironment.isPassable(world, new BlockPos(x, y, z), start, end)) return false;
                if (!PathingEnvironment.isPassable(world, new BlockPos(x, y + 1, z), start, end)) return false;
            }
        } else if (dy >= dx && dy >= dz) {
            int e1 = 2 * dx - dy, e2 = 2 * dz - dy;
            while (y != y2) {
                y += ys;
                if (e1 >= 0) { x += xs; e1 -= 2 * dy; }
                if (e2 >= 0) { z += zs; e2 -= 2 * dy; }
                e1 += 2 * dx; e2 += 2 * dz;
                if (!PathingEnvironment.isPassable(world, new BlockPos(x, y, z), start, end)) return false;
            }
        } else {
            int e1 = 2 * dy - dz, e2 = 2 * dx - dz;
            while (z != z2) {
                z += zs;
                if (e1 >= 0) { y += ys; e1 -= 2 * dz; }
                if (e2 >= 0) { x += xs; e2 -= 2 * dx; }
                e1 += 2 * dy; e2 += 2 * dx;
                if (!PathingEnvironment.isPassable(world, new BlockPos(x, y, z), start, end)) return false;
                if (!PathingEnvironment.isPassable(world, new BlockPos(x, y + 1, z), start, end)) return false;
            }
        }
        return true;
    }
}
