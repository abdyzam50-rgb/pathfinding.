package net.netherite.tutorialmod.pathfinder;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.netherite.tutorialmod.PathNode;
import java.util.ArrayList;
import java.util.List;

public class PathCompressor {

    public static List<PathNode> compressPath(ServerWorld world, List<PathNode> fullPath, BlockPos start, BlockPos end) {
        if (fullPath.size() <= 2) return fullPath;

        List<PathNode> compressed = new ArrayList<>();
        compressed.add(fullPath.get(0));

        int index = 0;
        while (index < fullPath.size() - 1) {
            int nextKeyIndex = index + 1;
            List<BlockPos> arcPoints = new ArrayList<>();
            PathNode currentNode = fullPath.get(index);
            PathNode nextNode = fullPath.get(nextKeyIndex);

            // Preserve explicit jump/drop/sprint-jump/edge actions — never compress over them.
            // SPRINT_JUMP nodes carry their own ballistic arc that the renderer traces;
            // skipping them would erase gap-crossing nodes the follower needs to jump from.
            if (nextNode.type == PathNode.Type.JUMP
                    || nextNode.type == PathNode.Type.DROP
                    || nextNode.type == PathNode.Type.EDGE
                    || nextNode.type == PathNode.Type.SPRINT_JUMP) {
                compressed.add(nextNode);
                index = nextKeyIndex;
                continue;
            }

            boolean isDropTransition = currentNode.type == PathNode.Type.DROP;

            for (int i = index + 2; i < fullPath.size(); i++) {
                PathNode checkNode = fullPath.get(i);

                if (checkNode.type == PathNode.Type.JUMP
                        || checkNode.type == PathNode.Type.DROP
                        || checkNode.type == PathNode.Type.EDGE
                        || checkNode.type == PathNode.Type.SPRINT_JUMP) {
                    break;
                }

                List<BlockPos> simulatedArc = PathPhysics.getTrajectoryIfValid(
                        world, currentNode.pos, checkNode.pos, start, end, isDropTransition
                );

                if (simulatedArc != null) {
                    nextKeyIndex = i;
                    arcPoints = simulatedArc;

                    // Force-lock the precise physics curve for drops
                    if (isDropTransition) {
                        break;
                    }
                } else {
                    break;
                }
            }

            // Map the physical fall arc directly into the path instead of flat shortcuts
            if (!arcPoints.isEmpty() && isDropTransition) {
                for (BlockPos arcPos : arcPoints) {
                    compressed.add(new PathNode(arcPos, compressed.get(compressed.size() - 1), PathNode.Type.WALK));
                }
            } else {
                compressed.add(fullPath.get(nextKeyIndex));
            }

            index = nextKeyIndex;
        }

        PathNode finalTargetNode = fullPath.get(fullPath.size() - 1);
        if (!compressed.get(compressed.size() - 1).pos.equals(finalTargetNode.pos)) {
            compressed.add(finalTargetNode);
        }

        // --- POST-COMPRESSION VALIDATION PASS ---
        List<PathNode> validatedPath = new ArrayList<>();
        validatedPath.add(compressed.get(0));

        for (int i = 0; i < compressed.size() - 1; i++) {
            PathNode current = compressed.get(i);
            PathNode next = compressed.get(i + 1);

            if (next.type == PathNode.Type.JUMP
                    || next.type == PathNode.Type.DROP
                    || next.type == PathNode.Type.EDGE
                    || next.type == PathNode.Type.SPRINT_JUMP) {
                validatedPath.add(next);
                continue;
            }

            boolean segmentClear;
            if (next.pos.getY() < current.pos.getY()) {
                segmentClear = PathPhysics.getTrajectoryIfValid(world, current.pos, next.pos, start, end, true) != null;
            } else {
                segmentClear = PathLimiters.executeBresenhamCheck(world, current.pos, next.pos, start, end);
            }

            if (segmentClear) {
                validatedPath.add(next);
            } else {
                int rawStartIndex = findNodeIndexInList(fullPath, current.pos);
                int rawEndIndex = findNodeIndexInList(fullPath, next.pos);

                if (rawStartIndex != -1 && rawEndIndex != -1 && rawStartIndex < rawEndIndex) {
                    for (int j = rawStartIndex + 1; j <= rawEndIndex; j++) {
                        validatedPath.add(fullPath.get(j));
                    }
                } else {
                    validatedPath.add(next);
                }
            }
        }

        return validatedPath;
    }

    private static int findNodeIndexInList(List<PathNode> list, BlockPos pos) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).pos.equals(pos)) return i;
        }
        return -1;
    }
}