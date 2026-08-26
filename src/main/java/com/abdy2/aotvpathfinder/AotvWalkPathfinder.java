package com.abdy2.aotvpathfinder;

import java.util.*;

import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

/**
 * Walk-segment pathfinder used by TeleportPathfinder for non-teleport movement.
 *
 * Algorithm: A* with Theta* any-angle shortcutting, sprint-jump gap crossing
 * (2–4 blocks), ledge drops (up to 4 blocks), climb nodes (ladders/vines),
 * and sub-block position smoothing for natural-feeling paths.
 */
final class AotvWalkPathfinder {

    // Physics: sprint-jump horizontal reach at full sprint speed
    private static final int    MAX_FALL_BLOCKS       = 4;
    private static final int    MAX_SPRINT_JUMP        = 4;
    private static final double MAX_SPRINT_JUMP_REACH  = computeMaxSprintJumpReach();

    // 8-direction horizontal movement (cardinal + diagonal)
    private static final int[][] DIRS = {
        {1,0},{-1,0},{0,1},{0,-1},
        {1,1},{-1,1},{1,-1},{-1,-1}
    };

    // ±1 % heuristic jitter for tie-breaking (humanizes repeated runs)
    private static final Random JITTER = new Random();

    // ── Public entry point ────────────────────────────────────────────────────

    Result findPath(ClientPlayerEntity player, BlockPos start, BlockPos goal,
                    int maxIterations, double goalRadius) {
        World world = player.getEntityWorld();
        start = resolveValidStart(world, start);

        PriorityQueue<WalkPathNode> open = new PriorityQueue<>(
            Comparator.comparingDouble(n -> n.gCost + heuristic(n.pos, goal)));
        Map<BlockPos, WalkPathNode> openMap = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        WalkPathNode startNode = new WalkPathNode(start,
            new Vec3d(start.getX() + 0.5, start.getY(), start.getZ() + 0.5),
            null, WalkPathNode.Type.WALK);
        open.add(startNode);
        openMap.put(start, startNode);

        WalkPathNode bestFallback = startNode;
        double bestFallbackH = heuristic(start, goal);
        double radiusSq = goalRadius * goalRadius;
        int expanded = 0;
        WalkPathNode found = null;

        while (!open.isEmpty() && expanded < maxIterations) {
            WalkPathNode current = open.poll();
            // Lazy deletion: skip stale entries superseded by a cheaper path
            WalkPathNode canon = openMap.get(current.pos);
            if (canon != null && current.gCost > canon.gCost + 1e-9) continue;
            openMap.remove(current.pos);
            closed.add(current.pos);
            expanded++;

            double h = heuristic(current.pos, goal);
            if (h < bestFallbackH) { bestFallbackH = h; bestFallback = current; }

            double dx = current.pos.getX() - goal.getX();
            double dy = current.pos.getY() - goal.getY();
            double dz = current.pos.getZ() - goal.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                found = current;
                break;
            }

            for (WalkPathNode raw : getNeighbors(world, current, start, goal)) {
                if (closed.contains(raw.pos)) continue;

                double newG = current.gCost + moveCost(current, raw);

                // Theta*: if grandparent has line-of-sight to this neighbour, skip
                // current and connect directly — produces any-angle straight paths.
                // Only applies to flat WALK→WALK transitions at the same Y.
                WalkPathNode effParent = current;
                if (current.parent != null
                        && raw.type == WalkPathNode.Type.WALK
                        && current.type == WalkPathNode.Type.WALK
                        && raw.pos.getY() == current.parent.pos.getY()
                        && isPathClear(world, current.parent.pos, raw.pos, start, goal)) {
                    int tdx = raw.pos.getX() - current.parent.pos.getX();
                    int tdz = raw.pos.getZ() - current.parent.pos.getZ();
                    double thetaG = current.parent.gCost
                        + Math.sqrt((double) tdx * tdx + (double) tdz * tdz);
                    if (thetaG < newG) {
                        effParent = current.parent;
                        newG = thetaG;
                    }
                }

                WalkPathNode neighbor;
                if (effParent == current) {
                    neighbor = raw;
                } else {
                    neighbor = new WalkPathNode(raw.pos, raw.precisePos, effParent, raw.type);
                }

                WalkPathNode existing = openMap.get(neighbor.pos);
                if (existing == null || newG < existing.gCost - 1e-9) {
                    neighbor.gCost = newG;
                    neighbor.parent = effParent;
                    open.add(neighbor);
                    openMap.put(neighbor.pos, neighbor);
                }
            }
        }

        if (found != null) {
            List<WalkPathNode> path = buildPath(found);
            path = markEdgeNodes(path);
            path = simplifyPath(world, path, start, goal);
            path = smoothPrecisePositions(path);
            path = adjustSprintJumpTakeoffPositions(path);
            return new Result(path, true, 0.0);
        }

        List<WalkPathNode> partial = buildPath(bestFallback);
        double dx = bestFallback.pos.getX() - goal.getX();
        double dy = bestFallback.pos.getY() - goal.getY();
        double dz = bestFallback.pos.getZ() - goal.getZ();
        return new Result(partial, false, dx * dx + dy * dy + dz * dz);
    }

    // ── Neighbor generation ───────────────────────────────────────────────────

    private List<WalkPathNode> getNeighbors(World world, WalkPathNode current,
                                            BlockPos pathStart, BlockPos pathEnd) {
        List<WalkPathNode> out = new ArrayList<>();
        BlockPos pos = current.pos;

        for (int[] dir : DIRS) {
            addHorizontalOptions(world, current, dir[0], dir[1], pathStart, pathEnd, out);
        }

        // Climb up (ladder / vine)
        BlockPos climbUp = pos.up();
        if (canClimbTo(world, pos, climbUp, pathStart, pathEnd)) {
            out.add(new WalkPathNode(climbUp,
                new Vec3d(pos.getX() + 0.5, climbUp.getY(), pos.getZ() + 0.5),
                current, WalkPathNode.Type.CLIMB));
        }

        // Climb down (ladder / vine)
        BlockPos climbDown = pos.down();
        if (canClimbDown(world, pos, climbDown, pathStart, pathEnd)) {
            out.add(new WalkPathNode(climbDown,
                new Vec3d(pos.getX() + 0.5, climbDown.getY(), pos.getZ() + 0.5),
                current, WalkPathNode.Type.CLIMB));
        }

        return out;
    }

    private void addHorizontalOptions(World world, WalkPathNode current, int dx, int dz,
                                      BlockPos pathStart, BlockPos pathEnd,
                                      List<WalkPathNode> out) {
        BlockPos from = current.pos;
        boolean diagonal = (dx != 0 && dz != 0);
        BlockPos target = from.add(dx, 0, dz);

        if (diagonal && !canCutCorner(world, from, dx, dz, pathStart, pathEnd)) return;

        // Walk (same Y)
        if (canWalkTo(world, from, target, pathStart, pathEnd)) {
            out.add(new WalkPathNode(target,
                new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
                current, WalkPathNode.Type.WALK));
        }

        // Jump up 1 block
        BlockPos jumpLanding = target.up();
        if (canJumpTo(world, from, target, jumpLanding, pathStart, pathEnd)) {
            out.add(new WalkPathNode(jumpLanding,
                new Vec3d(target.getX() + 0.5, jumpLanding.getY(), target.getZ() + 0.5),
                current, WalkPathNode.Type.JUMP));
        }

        // Drop (walk off edge) — cardinal only
        if (!diagonal) {
            BlockPos landing = findDropLanding(world, from, target, pathStart, pathEnd);
            if (landing != null) {
                out.add(new WalkPathNode(landing,
                    new Vec3d(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5),
                    current, WalkPathNode.Type.DROP));
            }
        }

        // Sprint-jump over gap — cardinal only
        if (!diagonal) {
            // Same-Y gap (2–MAX_SPRINT_JUMP blocks)
            for (int dist = 2; dist <= MAX_SPRINT_JUMP; dist++) {
                if (canSprintJumpTo(world, from, dx, dz, dist, pathStart, pathEnd)) {
                    BlockPos land = from.add(dx * dist, 0, dz * dist);
                    out.add(new WalkPathNode(land,
                        new Vec3d(land.getX() + 0.5, land.getY(), land.getZ() + 0.5),
                        current, WalkPathNode.Type.SPRINT_JUMP));
                    break;
                }
            }

            // Sprint-jump uphill (+1 Y, dist 1–2)
            for (int dist = 1; dist <= 2; dist++) {
                if (canSprintJumpUphill(world, from, dx, dz, dist, pathStart, pathEnd)) {
                    BlockPos land = from.add(dx * dist, 1, dz * dist);
                    out.add(new WalkPathNode(land,
                        new Vec3d(land.getX() + 0.5, land.getY(), land.getZ() + 0.5),
                        current, WalkPathNode.Type.SPRINT_JUMP));
                    break;
                }
            }

            // Sprint-jump downhill (-1 Y, dist 1–3)
            for (int dist = 1; dist <= 3; dist++) {
                if (canSprintJumpDownhill(world, from, dx, dz, dist, pathStart, pathEnd)) {
                    BlockPos land = from.add(dx * dist, -1, dz * dist);
                    out.add(new WalkPathNode(land,
                        new Vec3d(land.getX() + 0.5, land.getY(), land.getZ() + 0.5),
                        current, WalkPathNode.Type.SPRINT_JUMP));
                    break;
                }
            }
        }
    }

    // ── Movement validators ───────────────────────────────────────────────────

    private boolean canWalkTo(World world, BlockPos from, BlockPos to,
                               BlockPos pathStart, BlockPos pathEnd) {
        if (!isPassable(world, to, pathStart, pathEnd)) return false;
        if (!isPassable(world, to.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, to)) return false;
        if (isHazardous(world, to) || isHazardous(world, to.down())) return false;
        if (!isPathClear(world, from, to, pathStart, pathEnd)) return false;
        return true;
    }

    private boolean canJumpTo(World world, BlockPos from, BlockPos stepBlock,
                               BlockPos jumpLanding, BlockPos pathStart, BlockPos pathEnd) {
        if (jumpLanding.getY() != from.getY() + 1) return false;
        if (!hasSolidGround(world, from)) return false;
        if (passable(world, stepBlock, pathStart, pathEnd)) return false; // no ledge to jump onto
        if (isTallObstacle(world, stepBlock)) return false; // fence top — can't land on
        if (!passable(world, jumpLanding, pathStart, pathEnd)) return false;
        if (!passable(world, jumpLanding.up(), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, jumpLanding)) return false;
        if (isHazardous(world, jumpLanding)) return false;
        return true;
    }

    private boolean canSprintJumpTo(World world, BlockPos from, int dx, int dz, int dist,
                                    BlockPos pathStart, BlockPos pathEnd) {
        if (dist > MAX_SPRINT_JUMP_REACH + 0.5) return false;
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(3), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, 0, dz * dist);
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (!hasSolidGround(world, landing)) return false;
        if (isHazardous(world, landing)) return false;

        boolean gapExists = false;
        for (int i = 1; i < dist; i++) {
            if (!hasSolidGround(world, from.add(dx * i, 0, dz * i))) {
                gapExists = true;
                break;
            }
        }
        if (!gapExists) return false;

        // Arc clearance through intermediate positions + fence-top check
        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1))) return false;
        }
        return true;
    }

    private boolean canSprintJumpUphill(World world, BlockPos from, int dx, int dz, int dist,
                                        BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, 1, dz * dist);
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (isHazardous(world, landing)) return false;

        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(2), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1))) return false;
        }
        return true;
    }

    private boolean canSprintJumpDownhill(World world, BlockPos from, int dx, int dz, int dist,
                                          BlockPos pathStart, BlockPos pathEnd) {
        if (!hasSolidGround(world, from)) return false;
        if (!passable(world, from.up(1), pathStart, pathEnd)) return false;
        if (!passable(world, from.up(2), pathStart, pathEnd)) return false;

        BlockPos landing = from.add(dx * dist, -1, dz * dist);
        if (!hasSolidGround(world, landing)) return false;
        if (!passable(world, landing, pathStart, pathEnd)) return false;
        if (!passable(world, landing.up(), pathStart, pathEnd)) return false;
        if (isHazardous(world, landing)) return false;
        if (!passable(world, from.add(dx * dist, 0, dz * dist), pathStart, pathEnd)) return false;

        for (int i = 1; i < dist; i++) {
            BlockPos mid = from.add(dx * i, 0, dz * i);
            if (!passable(world, mid,       pathStart, pathEnd)) return false;
            if (!passable(world, mid.up(1), pathStart, pathEnd)) return false;
            if (fenceExtendsInto(world, mid.up(1))) return false;
        }
        return true;
    }

    private BlockPos findDropLanding(World world, BlockPos from, BlockPos target,
                                     BlockPos pathStart, BlockPos pathEnd) {
        if (!passable(world, target, pathStart, pathEnd)) return null;
        if (!passable(world, target.up(), pathStart, pathEnd)) return null;
        if (!hasSolidGround(world, from)) return null;
        if (hasSolidGround(world, target)) return null; // walk handles this

        for (int drop = 1; drop <= MAX_FALL_BLOCKS; drop++) {
            BlockPos land = new BlockPos(target.getX(), target.getY() - drop, target.getZ());
            if (!passable(world, land, pathStart, pathEnd)) return null;
            if (!passable(world, land.up(), pathStart, pathEnd)) return null;
            if (isHazardous(world, land) || isHazardous(world, land.down())) return null;
            if (hasSolidGround(world, land)) return land;
        }
        return null;
    }

    private boolean canClimbTo(World world, BlockPos from, BlockPos to,
                                BlockPos pathStart, BlockPos pathEnd) {
        if (to.getY() != from.getY() + 1) return false;
        if (to.getX() != from.getX() || to.getZ() != from.getZ()) return false;
        if (!isClimbable(world, to) && !isClimbable(world, from)) return false;
        if (!passable(world, to, pathStart, pathEnd)) return false;
        if (!passable(world, to.up(), pathStart, pathEnd)) return false;
        return true;
    }

    private boolean canClimbDown(World world, BlockPos from, BlockPos to,
                                  BlockPos pathStart, BlockPos pathEnd) {
        if (to.getY() != from.getY() - 1) return false;
        if (to.getX() != from.getX() || to.getZ() != from.getZ()) return false;
        if (!isClimbable(world, from) && !isClimbable(world, to)) return false;
        if (!passable(world, to, pathStart, pathEnd)) return false;
        if (isHazardous(world, to)) return false;
        return true;
    }

    private boolean canCutCorner(World world, BlockPos from, int dx, int dz,
                                  BlockPos pathStart, BlockPos pathEnd) {
        BlockPos adjX = from.add(dx, 0, 0);
        BlockPos adjZ = from.add(0, 0, dz);
        boolean xBlocked = !passable(world, adjX, pathStart, pathEnd)
                        || !passable(world, adjX.up(), pathStart, pathEnd);
        boolean zBlocked = !passable(world, adjZ, pathStart, pathEnd)
                        || !passable(world, adjZ.up(), pathStart, pathEnd);
        return !xBlocked && !zBlocked;
    }

    // ── Block environment queries ─────────────────────────────────────────────

    private boolean isPassable(World world, BlockPos pos, BlockPos pathStart, BlockPos pathEnd) {
        if (pos.equals(pathStart) || pos.equals(pathEnd) || pos.equals(pathEnd.up())) return true;
        return passable(world, pos, pathStart, pathEnd);
    }

    private static boolean passable(World world, BlockPos pos, BlockPos pathStart, BlockPos pathEnd) {
        if (pos.equals(pathStart) || pos.equals(pathEnd) || pos.equals(pathEnd.up())) return true;
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = world.getBlockState(pos);

        if (isClimbableState(state)) return true;

        Block block = state.getBlock();
        if (block instanceof FenceBlock || block instanceof WallBlock) return false;
        if (block instanceof FenceGateBlock) return state.get(FenceGateBlock.OPEN);
        if (block instanceof DoorBlock) {
            return state.get(DoorBlock.OPEN) || !state.getBlock().getTranslationKey().contains("iron");
        }
        if (block instanceof TrapdoorBlock) return true;

        if (state.isIn(BlockTags.RAILS)) return true;
        if (block instanceof CarpetBlock || block instanceof AbstractPressurePlateBlock) return true;

        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return true;

        // Passable through water (can swim)
        return !state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.WATER);
    }

    private static boolean hasSolidGround(World world, BlockPos pos) {
        BlockPos below = pos.down();
        if (!world.isChunkLoaded(below.getX() >> 4, below.getZ() >> 4)) return false;
        BlockState state = world.getBlockState(below);
        if (state.isAir()) return false;
        if (isClimbableState(state)) return true;
        Block b = state.getBlock();
        if (b instanceof LadderBlock || b instanceof VineBlock) return true;
        if (b instanceof SlabBlock) {
            SlabType t = state.get(SlabBlock.TYPE);
            return t == SlabType.BOTTOM || t == SlabType.DOUBLE;
        }
        VoxelShape shape = state.getCollisionShape(world, below);
        if (shape.isEmpty()) return false;
        return shape.getMax(Direction.Axis.Y) >= 0.5;
    }

    private static boolean isHazardous(World world, BlockPos pos) {
        if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        BlockState state = world.getBlockState(pos);
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        Block b = state.getBlock();
        return b instanceof AbstractFireBlock || b instanceof CactusBlock;
    }

    private static boolean isTallObstacle(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock;
    }

    private static boolean isClimbable(World world, BlockPos pos) {
        return isClimbableState(world.getBlockState(pos));
    }

    private static boolean isClimbableState(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE)
            || state.getBlock() instanceof LadderBlock
            || state.getBlock() instanceof VineBlock
            || state.getBlock() instanceof ScaffoldingBlock;
    }

    private static double getBlockMaxHeight(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return 0.0;
        if (state.getBlock() instanceof SlabBlock) {
            return switch (state.get(SlabBlock.TYPE)) {
                case BOTTOM -> 0.5;
                default     -> 1.0;
            };
        }
        if (state.getBlock() instanceof FenceBlock || state.getBlock() instanceof WallBlock) return 1.5;
        VoxelShape shape = state.getCollisionShape(world, pos);
        return shape.isEmpty() ? 0.0 : shape.getMax(Direction.Axis.Y);
    }

    /** True when the block below pos is a fence/wall whose collision box bleeds into pos. */
    private static boolean fenceExtendsInto(World world, BlockPos pos) {
        BlockPos below = pos.down();
        BlockState state = world.getBlockState(below);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(world, below);
        return !shape.isEmpty() && shape.getBoundingBox().maxY > 1.0;
    }

    // ── Line-of-sight check ───────────────────────────────────────────────────

    /**
     * Sweeps the player's 0.6×1.8 hitbox along [from→to] at walk level.
     * Samples every 0.25 blocks; checks 4 XZ corners × feet + head at each step.
     * Also enforces solid ground under the centre column to reject cliff paths.
     */
    private static boolean isPathClear(World world, BlockPos from, BlockPos to,
                                       BlockPos pathStart, BlockPos pathEnd) {
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return true;
        double fromCx = from.getX() + 0.5, fromCz = from.getZ() + 0.5;
        double toCx   = to.getX()   + 0.5, toCz   = to.getZ()   + 0.5;
        double segDx = toCx - fromCx, segDz = toCz - fromCz;
        double dist  = Math.sqrt(segDx * segDx + segDz * segDz);
        int y = from.getY();

        int steps = Math.max(1, (int) Math.ceil(dist / 0.25));
        final double[] ox = {-0.3,  0.3,  0.3, -0.3};
        final double[] oz = {-0.3, -0.3,  0.3,  0.3};

        for (int s = 1; s <= steps; s++) {
            double t  = (double) s / steps;
            double cx = fromCx + segDx * t;
            double cz = fromCz + segDz * t;

            BlockPos centre = new BlockPos((int) Math.floor(cx), y, (int) Math.floor(cz));
            if (!hasSolidGround(world, centre)) return false;

            for (int k = 0; k < 4; k++) {
                int bx = (int) Math.floor(cx + ox[k]);
                int bz = (int) Math.floor(cz + oz[k]);
                if (!passable(world, new BlockPos(bx, y,     bz), pathStart, pathEnd)) return false;
                if (!passable(world, new BlockPos(bx, y + 1, bz), pathStart, pathEnd)) return false;
            }
        }
        return true;
    }

    // ── Start-position resolution ─────────────────────────────────────────────

    /**
     * If the start block is inside a solid or otherwise invalid, scan nearby blocks
     * at the same Y and return the nearest passable, grounded one.
     */
    private static BlockPos resolveValidStart(World world, BlockPos candidate) {
        if (world.getBlockState(candidate).isAir()
                && world.getBlockState(candidate.up()).isAir()
                && hasSolidGround(world, candidate)) {
            return candidate;
        }
        double cx = candidate.getX() + 0.5;
        double cz = candidate.getZ() + 0.5;
        BlockPos best = candidate;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos nb = candidate.add(dx, 0, dz);
                if (world.getBlockState(nb).isAir()
                        && world.getBlockState(nb.up()).isAir()
                        && hasSolidGround(world, nb)) {
                    double d = (cx - (nb.getX() + 0.5)) * (cx - (nb.getX() + 0.5))
                             + (cz - (nb.getZ() + 0.5)) * (cz - (nb.getZ() + 0.5));
                    if (d < bestDist) { bestDist = d; best = nb; }
                }
            }
        }
        return best;
    }

    // ── A* helpers ───────────────────────────────────────────────────────────

    private static double heuristic(BlockPos pos, BlockPos end) {
        double dx = pos.getX() - end.getX();
        double dy = pos.getY() - end.getY();
        double dz = pos.getZ() - end.getZ();
        double base = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return base * (1.0 + (JITTER.nextDouble() - 0.5) * 0.02);
    }

    private static double moveCost(WalkPathNode from, WalkPathNode to) {
        double dx    = to.pos.getX() - from.pos.getX();
        double dz    = to.pos.getZ() - from.pos.getZ();
        double dy    = to.pos.getY() - from.pos.getY();
        double hdist = Math.sqrt(dx * dx + dz * dz);
        return switch (to.type) {
            case JUMP        -> hdist + 0.4;
            case SPRINT_JUMP -> hdist + 0.2;
            case DROP        -> hdist + Math.abs(dy) * 0.1;
            case CLIMB       -> hdist + Math.abs(dy) * 1.5;
            default          -> hdist;
        };
    }

    // ── Post-processing ───────────────────────────────────────────────────────

    private static List<WalkPathNode> buildPath(WalkPathNode end) {
        List<WalkPathNode> path = new ArrayList<>();
        WalkPathNode cur = end;
        while (cur != null) { path.add(cur); cur = cur.parent; }
        Collections.reverse(path);
        return path;
    }

    /** Greedy look-ahead simplification (Theta*): jump straight to the furthest
     *  reachable same-Y WALK node, skipping all intermediate ones. */
    private static List<WalkPathNode> simplifyPath(World world, List<WalkPathNode> path,
                                                    BlockPos pathStart, BlockPos pathEnd) {
        if (path.size() <= 2) return path;
        List<WalkPathNode> out = new ArrayList<>();
        out.add(path.get(0));
        int cur = 0;
        while (cur < path.size() - 1) {
            int far = cur + 1;
            for (int i = cur + 2; i < path.size(); i++) {
                if (canMoveDirectly(world, path.get(cur), path.get(i), pathStart, pathEnd)) far = i;
            }
            out.add(path.get(far));
            cur = far;
        }
        return out;
    }

    private static boolean canMoveDirectly(World world, WalkPathNode from, WalkPathNode to,
                                            BlockPos pathStart, BlockPos pathEnd) {
        if (from.type != WalkPathNode.Type.WALK && from.type != WalkPathNode.Type.EDGE) return false;
        if (to.type   != WalkPathNode.Type.WALK && to.type   != WalkPathNode.Type.EDGE) return false;
        if (to.pos.getY() != from.pos.getY()) return false;
        return isPathClear(world, from.pos, to.pos, pathStart, pathEnd);
    }

    /** Mark the node immediately before a DROP as EDGE for visual distinction. */
    private static List<WalkPathNode> markEdgeNodes(List<WalkPathNode> path) {
        if (path.size() < 2) return path;
        List<WalkPathNode> out = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            WalkPathNode node = path.get(i);
            if (i < path.size() - 1
                    && path.get(i + 1).type == WalkPathNode.Type.DROP
                    && node.type == WalkPathNode.Type.WALK) {
                WalkPathNode edge = new WalkPathNode(node.pos, node.precisePos, node.parent, WalkPathNode.Type.EDGE);
                edge.gCost = node.gCost;
                out.add(edge);
            } else {
                out.add(node);
            }
        }
        return out;
    }

    /**
     * Projects each flat WALK/EDGE waypoint onto the line from its predecessor to
     * its successor, then clamps to the block's ±0.3 safe zone.  The integer BlockPos
     * stays unchanged; only precisePos (used for movement targeting) moves to the
     * sub-block ideal crossing point.
     */
    private static List<WalkPathNode> smoothPrecisePositions(List<WalkPathNode> path) {
        if (path.size() <= 2) return path;
        List<WalkPathNode> out = new ArrayList<>(path.size());
        out.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            WalkPathNode prev = out.get(out.size() - 1);
            WalkPathNode curr = path.get(i);
            WalkPathNode next = path.get(i + 1);

            if ((curr.type == WalkPathNode.Type.WALK || curr.type == WalkPathNode.Type.EDGE)
                    && prev.pos.getY() == curr.pos.getY()
                    && curr.pos.getY() == next.pos.getY()) {

                Vec3d pv = prev.precisePos, nv = next.precisePos;
                double ldx = nv.x - pv.x, ldz = nv.z - pv.z;
                double llen2 = ldx * ldx + ldz * ldz;

                if (llen2 > 0.01) {
                    double cx = curr.pos.getX() + 0.5, cz = curr.pos.getZ() + 0.5;
                    double t = ((cx - pv.x) * ldx + (cz - pv.z) * ldz) / llen2;

                    if (t > 0.02 && t < 0.98) {
                        double projX = Math.max(curr.pos.getX() + 0.30,
                                       Math.min(curr.pos.getX() + 0.70, pv.x + ldx * t));
                        double projZ = Math.max(curr.pos.getZ() + 0.30,
                                       Math.min(curr.pos.getZ() + 0.70, pv.z + ldz * t));
                        WalkPathNode smoothed = new WalkPathNode(curr.pos,
                                new Vec3d(projX, curr.pos.getY(), projZ),
                                null, curr.type);
                        smoothed.gCost = curr.gCost;
                        out.add(smoothed);
                        continue;
                    }
                }
            }
            out.add(curr);
        }
        out.add(path.get(path.size() - 1));
        return out;
    }

    /**
     * Shifts WALK/EDGE launch nodes immediately before a SPRINT_JUMP toward the
     * leading edge of their block (+0.2 in jump direction), increasing arc distance
     * and keeping the landing closer to the centre of the target block.
     */
    private static List<WalkPathNode> adjustSprintJumpTakeoffPositions(List<WalkPathNode> path) {
        if (path.size() < 2) return path;
        List<WalkPathNode> out = new ArrayList<>(path);
        for (int i = 0; i < path.size() - 1; i++) {
            WalkPathNode curr = path.get(i);
            WalkPathNode next = path.get(i + 1);
            if (next.type != WalkPathNode.Type.SPRINT_JUMP) continue;
            if (curr.type != WalkPathNode.Type.WALK && curr.type != WalkPathNode.Type.EDGE) continue;

            Vec3d launch = curr.precisePos, land = next.precisePos;
            double segDx = land.x - launch.x, segDz = land.z - launch.z;
            double dist = Math.sqrt(segDx * segDx + segDz * segDz);
            if (dist <= 3.0 || dist < 0.01) continue; // skip stride waypoints

            double nx = segDx / dist, nz = segDz / dist;
            double optX = Math.max(curr.pos.getX() + 0.30,
                          Math.min(curr.pos.getX() + 0.70, launch.x + nx * 0.2));
            double optZ = Math.max(curr.pos.getZ() + 0.30,
                          Math.min(curr.pos.getZ() + 0.70, launch.z + nz * 0.2));

            WalkPathNode shifted = new WalkPathNode(curr.pos,
                    new Vec3d(optX, curr.pos.getY(), optZ), null, curr.type);
            shifted.gCost = curr.gCost;
            out.set(i, shifted);
        }
        return out;
    }

    // ── Physics constant ──────────────────────────────────────────────────────

    private static double computeMaxSprintJumpReach() {
        double vx = 0.486, vy = 0.42, py = 0, totalX = 0;
        for (int t = 0; t < 40; t++) {
            vx += 0.026; totalX += vx; vx *= 0.91;
            py += vy; vy = (vy - 0.08) * 0.98;
            if (py <= 0 && t > 3) break;
        }
        return totalX;
    }

    // ── Result type ───────────────────────────────────────────────────────────

    record Result(List<WalkPathNode> path, boolean reachedGoal, double bestDistanceSq) {}
}
