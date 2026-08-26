package net.netherite.tutorialmod;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.netherite.tutorialmod.client.TessellatorRenderer;
import net.netherite.tutorialmod.pathfinder.PathfinderEngine;
import net.netherite.tutorialmod.pathfinder.PathCompressor;
import java.util.List;

public class PreviewManager {

    /**
     * Computes and renders a path preview. Returns {@code true} if a valid path was
     * found that actually reaches the target, {@code false} if unreachable.
     */
    public static boolean startPreview(ServerWorld world, ServerPlayerEntity player, int targetX, int targetY, int targetZ) {
        if (world == null || player == null) return false;

        BlockPos startPos = player.getBlockPos();
        BlockPos endPos = new BlockPos(targetX, targetY, targetZ);

        List<PathNode> calculatedNodes = PathfinderEngine.findNodePath(world, startPos, endPos);

        if (calculatedNodes.isEmpty()) {
            player.sendMessage(Text.literal("§cNo path found to (" + targetX + ", " + targetY + ", " + targetZ + ")."), false);
            return false;
        }

        // Verify the path actually reaches the goal (last node within 1 block of target)
        PathNode lastNode = calculatedNodes.get(calculatedNodes.size() - 1);
        if (lastNode.pos.getSquaredDistance(endPos) > 2.0) {
            player.sendMessage(Text.literal("§cTarget unreachable — closest approach is ("
                    + lastNode.pos.getX() + ", " + lastNode.pos.getY() + ", " + lastNode.pos.getZ() + ")."), false);
            return false;
        }

        List<PathNode> compressedNodes = PathCompressor.compressPath(world, calculatedNodes, startPos, endPos);
        TessellatorRenderer.setPath(compressedNodes);
        LineRenderer.setPath(world, compressedNodes);
        return true;
    }

    public static void stopPreview() {
        LineRenderer.clearPath();
    }
}