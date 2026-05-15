package com.abdy2.aotvpathfinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

final class AotvWalkPathfinder {

    private static final double D1 = 1.0;
    private static final double D2 = Math.sqrt(2.0);
    private static final double D3 = Math.sqrt(3.0);
    private static final double DEFAULT_JUMP_HEIGHT = 1.125;
    private static final double TIE_BREAKER_WEIGHT = 1e-6;

    private static final int[][] NEIGHBOR_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
        {0, 1, 0}, {0, -1, 0},
        {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}
    };

    Result findPath(ClientPlayerEntity player, BlockPos start, BlockPos goal, int maxIterations, double goalRadius) {
        World world = player.getEntityWorld();
        long startPacked = packPos(start.getX(), start.getY(), start.getZ());
        long goalPacked = packPos(goal.getX(), goal.getY(), goal.getZ());

        Long2ObjectOpenHashMap<WalkNode> nodeMap = new Long2ObjectOpenHashMap<>(1024);
        LongOpenHashSet closedSet = new LongOpenHashSet(1024);
        PrimitiveMinHeap openSet = new PrimitiveMinHeap(1024);

        WalkNode startNode = new WalkNode(start.getX(), start.getY(), start.getZ(), 0);
        startNode.gCost = 0.0;
        startNode.heuristic = weightedHeuristic(
            start.getX(), start.getY(), start.getZ(),
            goal.getX(), goal.getY(), goal.getZ(),
            start.getX(), start.getY(), start.getZ());

        nodeMap.put(startPacked, startNode);
        openSet.insertOrUpdate(startPacked, startNode.fCost());

        WalkNode bestFallback = startNode;
        double bestFallbackH = startNode.heuristic;
        int iterations = 0;

        while (!openSet.isEmpty() && iterations < maxIterations) {
            iterations++;

            long currentPacked = openSet.extractMin();
            WalkNode current = nodeMap.get(currentPacked);
            if (current == null) continue;

            closedSet.add(currentPacked);

            if (current.heuristic < bestFallbackH) {
                bestFallbackH = current.heuristic;
                bestFallback = current;
            }

            double distSq = squaredDistance(current.x, current.y, current.z, goal.getX(), goal.getY(), goal.getZ());
            if (distSq <= goalRadius * goalRadius) {
                return buildResult(current, nodeMap, true);
            }

            for (int[] offset : NEIGHBOR_OFFSETS) {
                int nx = current.x + offset[0];
                int ny = current.y + offset[1];
                int nz = current.z + offset[2];
                long neighborPacked = packPos(nx, ny, nz);

                if (closedSet.contains(neighborPacked)) continue;

                if (!isChunkLoaded(world, nx, nz)) continue;

                NavPoint navCurrent = getNavPoint(world, current.x, current.y, current.z);
                NavPoint navNeighbor = getNavPoint(world, nx, ny, nz);

                if (!navNeighbor.traversable) continue;

                int dx = offset[0];
                int dy = offset[1];
                int dz = offset[2];

                if (!isTransitionValid(world, navCurrent, navNeighbor, current.x, current.y, current.z, dx, dy, dz)) {
                    continue;
                }

                double transitionCost = calculateTransitionCost(current.x, current.y, current.z, nx, ny, nz);
                double additionalCost = calculateAdditionalCost(world, nodeMap, current, navCurrent, navNeighbor, nx, ny, nz);
                double newG = current.gCost + transitionCost + additionalCost;

                WalkNode existing = nodeMap.get(neighborPacked);
                if (existing != null) {
                    if (newG + Math.ulp(Math.max(Math.abs(newG), Math.abs(existing.gCost))) >= existing.gCost) {
                        continue;
                    }
                    existing.parentPacked = currentPacked;
                    existing.gCost = newG;
                    existing.depth = current.depth + 1;
                    openSet.insertOrUpdate(neighborPacked, heapKey(existing));
                } else {
                    WalkNode neighbor = new WalkNode(nx, ny, nz, current.depth + 1);
                    neighbor.parentPacked = currentPacked;
                    neighbor.gCost = newG;
                    neighbor.heuristic = weightedHeuristic(
                        nx, ny, nz,
                        goal.getX(), goal.getY(), goal.getZ(),
                        start.getX(), start.getY(), start.getZ());
                    nodeMap.put(neighborPacked, neighbor);
                    openSet.insertOrUpdate(neighborPacked, heapKey(neighbor));
                }
            }
        }

        return buildResult(bestFallback, nodeMap, false);
    }

    private boolean isTransitionValid(World world, NavPoint from, NavPoint to,
                                       int fromX, int fromY, int fromZ, int dx, int dy, int dz) {
        if (dy > 0 && (to.floorLevel - from.floorLevel) > DEFAULT_JUMP_HEIGHT) {
            return false;
        }

        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            NavPoint corner1 = getNavPoint(world, fromX + dx, fromY, fromZ);
            NavPoint corner2 = getNavPoint(world, fromX, fromY, fromZ + dz);
            if (!corner1.traversable || !corner2.traversable) return false;
        }

        double floorDy = to.floorLevel - from.floorLevel;
        if (floorDy < -0.5) {
            return true;
        }
        if (floorDy > 0.5) {
            return from.hasFloor || to.climbable;
        }
        return to.hasFloor || from.hasFloor || to.climbable || from.climbable;
    }

    private double calculateTransitionCost(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        double dx = (toX + 0.5) - (fromX + 0.5);
        double dy = (toY + 0.5) - (fromY + 0.5);
        double dz = (toZ + 0.5) - (fromZ + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double calculateAdditionalCost(World world, Long2ObjectOpenHashMap<WalkNode> nodeMap,
                                            WalkNode current, NavPoint navFrom, NavPoint navTo,
                                            int nx, int ny, int nz) {
        double cost = 0.0;

        double floorDy = navTo.floorLevel - navFrom.floorLevel;
        if (floorDy > 0.1) {
            cost += 0.5 * floorDy;
        } else if (floorDy < -0.1) {
            cost += 0.1 * Math.abs(floorDy);
        }

        BlockPos blockPos = new BlockPos(nx, ny, nz);
        double crampedPenalty = 0.0;
        for (int i = 2; i <= 3; i++) {
            if (world.getBlockState(blockPos.up(i)).isOpaque()) {
                crampedPenalty += 0.1 / i;
            }
        }
        if (world.getBlockState(blockPos.west()).isOpaque() ||
            world.getBlockState(blockPos.east()).isOpaque() ||
            world.getBlockState(blockPos.north()).isOpaque() ||
            world.getBlockState(blockPos.south()).isOpaque()) {
            crampedPenalty += 0.05;
        }
        cost += crampedPenalty;

        if (current.parentPacked != -1L) {
            WalkNode grandparent = nodeMap.get(current.parentPacked);
            if (grandparent != null) {
                double v1x = current.x - grandparent.x;
                double v1z = current.z - grandparent.z;
                double v2x = nx - current.x;
                double v2z = nz - current.z;
                double mag1 = Math.sqrt(v1x * v1x + v1z * v1z);
                double mag2 = Math.sqrt(v2x * v2x + v2z * v2z);
                if (mag1 > 0.1 && mag2 > 0.1) {
                    double dot = (v1x * v2x + v1z * v2z) / (mag1 * mag2);
                    if (dot < 0.99) {
                        cost += 0.05;
                    }
                }
            }
        }

        return cost;
    }

    private static final double OCTILE_WEIGHT = 1.0;
    private static final double PERPENDICULAR_WEIGHT = 0.6;
    private static final double HEIGHT_WEIGHT = 0.3;

    private double weightedHeuristic(int cx, int cy, int cz, int tx, int ty, int tz, int sx, int sy, int sz) {
        int dx = Math.abs(cx - tx);
        int dy = Math.abs(cy - ty);
        int dz = Math.abs(cz - tz);
        int min = Math.min(dx, Math.min(dy, dz));
        int max = Math.max(dx, Math.max(dy, dz));
        int mid = dx + dy + dz - min - max;
        double octile = (D3 - D2) * min + (D2 - D1) * mid + D1 * max;

        double perpendicular = perpendicularDistance(cx, cy, cz, sx, sy, sz, tx, ty, tz);

        double height = Math.abs(cy - ty);

        return octile * OCTILE_WEIGHT + perpendicular * PERPENDICULAR_WEIGHT + height * HEIGHT_WEIGHT;
    }

    private static double perpendicularDistance(int cx, int cy, int cz, int sx, int sy, int sz, int tx, int ty, int tz) {
        double lx = tx - sx;
        double ly = ty - sy;
        double lz = tz - sz;
        double lineSq = lx * lx + ly * ly + lz * lz;
        if (lineSq < 1e-9) {
            double ddx = cx - sx;
            double ddy = cy - sy;
            double ddz = cz - sz;
            return Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
        }
        double tox = cx - sx;
        double toy = cy - sy;
        double toz = cz - sz;
        double crossX = toy * lz - toz * ly;
        double crossY = toz * lx - tox * lz;
        double crossZ = tox * ly - toy * lx;
        return Math.sqrt((crossX * crossX + crossY * crossY + crossZ * crossZ) / lineSq);
    }

    private double heapKey(WalkNode node) {
        double f = node.fCost();
        double h = node.heuristic;
        double tieBreaker = TIE_BREAKER_WEIGHT * (h / (Math.abs(f) + 1.0));
        double key = f - tieBreaker;
        if (Double.isNaN(key) || Double.isInfinite(key)) return f;
        return key;
    }

    private Result buildResult(WalkNode endNode, Long2ObjectOpenHashMap<WalkNode> nodeMap, boolean reached) {
        List<BlockPos> rawPath = new ArrayList<>();
        WalkNode cursor = endNode;
        while (cursor != null) {
            rawPath.add(new BlockPos(cursor.x, cursor.y, cursor.z));
            if (cursor.parentPacked == -1L) break;
            cursor = nodeMap.get(cursor.parentPacked);
        }
        Collections.reverse(rawPath);

        List<BlockPos> simplified = simplifyPath(rawPath);

        double bestDistSq = reached ? 0.0 : squaredDistance(
            endNode.x, endNode.y, endNode.z,
            rawPath.isEmpty() ? 0 : rawPath.get(0).getX(),
            rawPath.isEmpty() ? 0 : rawPath.get(0).getY(),
            rawPath.isEmpty() ? 0 : rawPath.get(0).getZ());

        if (!reached && !rawPath.isEmpty()) {
            BlockPos last = rawPath.get(rawPath.size() - 1);
            bestDistSq = squaredDistance(last.getX(), last.getY(), last.getZ(),
                endNode.x, endNode.y, endNode.z);
        }

        if (simplified.size() > 1) {
            simplified.remove(0);
        }

        return new Result(simplified, reached, reached ? 0.0 : bestDistSq);
    }

    private List<BlockPos> simplifyPath(List<BlockPos> path) {
        if (path.size() <= 2) return new ArrayList<>(path);

        List<BlockPos> simplified = new ArrayList<>(path.size());
        simplified.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos prev = simplified.get(simplified.size() - 1);
            BlockPos curr = path.get(i);
            BlockPos next = path.get(i + 1);

            int prevDx = clamp1(curr.getX() - prev.getX());
            int prevDy = clamp1(curr.getY() - prev.getY());
            int prevDz = clamp1(curr.getZ() - prev.getZ());
            int nextDx = clamp1(next.getX() - curr.getX());
            int nextDy = clamp1(next.getY() - curr.getY());
            int nextDz = clamp1(next.getZ() - curr.getZ());

            if (prevDx == nextDx && prevDy == nextDy && prevDz == nextDz) {
                continue;
            }
            simplified.add(curr);
        }
        simplified.add(path.get(path.size() - 1));
        return simplified;
    }

    private static int clamp1(int v) {
        return Math.max(-1, Math.min(1, v));
    }

    // ── Navigation point evaluation ──────────────────────────────────────────────

    private NavPoint getNavPoint(World world, int x, int y, int z) {
        if (!isChunkLoaded(world, x, z)) {
            return NavPoint.BLOCKED;
        }

        BlockPos pos = new BlockPos(x, y, z);
        BlockState feet = world.getBlockState(pos);
        BlockState head = world.getBlockState(pos.up());
        BlockState below = world.getBlockState(pos.down());

        boolean feetPass = canWalkThrough(world, feet, pos);
        boolean headPass = canWalkThrough(world, head, pos.up());
        boolean traversable = feetPass && headPass;
        boolean hasFloor = canWalkOn(world, below, pos.down());
        double floorLevel = calculateFloorLevel(world, pos);
        boolean climbable = feet.getBlock() instanceof LadderBlock || feet.getBlock() instanceof VineBlock;

        return new NavPoint(traversable, hasFloor, floorLevel, climbable);
    }

    private boolean canWalkThrough(World world, BlockState state, BlockPos pos) {
        if (state.isAir()) return true;

        if (state.isIn(BlockTags.TRAPDOORS)) return true;

        Block block = state.getBlock();

        if (block instanceof DoorBlock door) {
            return state.get(DoorBlock.OPEN) || door.getBlockSetType().canOpenByHand();
        }
        if (block instanceof FenceGateBlock) {
            return state.get(FenceGateBlock.OPEN);
        }

        if (state.isIn(BlockTags.FENCES) || state.isIn(BlockTags.WALLS)) {
            return false;
        }

        if (state.isIn(BlockTags.RAILS)) return true;

        if (state.getCollisionShape(world, pos).isEmpty()) return true;

        return !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.WATER);
    }

    private boolean canWalkOn(World world, BlockState state, BlockPos pos) {
        Block block = state.getBlock();

        if (!state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }

        return block instanceof LadderBlock ||
               block instanceof VineBlock ||
               block instanceof StairsBlock ||
               block instanceof SlabBlock;
    }

    private double calculateFloorLevel(World world, BlockPos pos) {
        if (!world.getFluidState(pos).isEmpty() && world.getFluidState(pos).isIn(FluidTags.WATER)) {
            return pos.getY() + 0.5;
        }

        BlockPos belowPos = pos.down();
        BlockState below = world.getBlockState(belowPos);
        var shape = below.getCollisionShape(world, belowPos);
        if (shape.isEmpty()) {
            return belowPos.getY();
        }
        return belowPos.getY() + shape.getMax(Direction.Axis.Y);
    }

    // ── Utilities ────────────────────────────────────────────────────────────────

    private static boolean isChunkLoaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    private static double squaredDistance(int x1, int y1, int z1, int x2, int y2, int z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private static final long MASK_Y = 0xFFFL;
    private static final long MASK_XZ = 0x3FFFFFFL;
    private static final int SHIFT_Z = 12;
    private static final int SHIFT_X = 38;

    private static long packPos(int x, int y, int z) {
        return ((long) x & MASK_XZ) << SHIFT_X |
               ((long) z & MASK_XZ) << SHIFT_Z |
               ((long) y & MASK_Y);
    }

    // ── Inner types ──────────────────────────────────────────────────────────────

    record Result(List<BlockPos> path, boolean reachedGoal, double bestDistanceSq) {}

    private static final class WalkNode {
        final int x, y, z;
        int depth;
        double gCost;
        double heuristic;
        long parentPacked = -1L;

        WalkNode(int x, int y, int z, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.depth = depth;
        }

        double fCost() {
            return gCost + heuristic;
        }
    }

    private record NavPoint(boolean traversable, boolean hasFloor, double floorLevel, boolean climbable) {
        static final NavPoint BLOCKED = new NavPoint(false, false, 0.0, false);
    }

}
