package net.netherite.tutorialmod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.netherite.tutorialmod.client.TessellatorRenderer;
import net.netherite.tutorialmod.pathfinder.PathfinderEngine;
import net.minecraft.item.Items;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /preview <x> <y> <z> — compute and render a walkable path preview
            dispatcher.register(CommandManager.literal("preview")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> {
                                                ServerCommandSource source = context.getSource();
                                                ServerPlayerEntity player = source.getPlayer();

                                                if (player == null) {
                                                    source.sendError(Text.literal("Only players can execute this command."));
                                                    return 0;
                                                }

                                                int targetX = IntegerArgumentType.getInteger(context, "x");
                                                int targetY = IntegerArgumentType.getInteger(context, "y");
                                                int targetZ = IntegerArgumentType.getInteger(context, "z");

                                                ServerWorld world = source.getWorld();

                                                boolean found = PreviewManager.startPreview(world, player, targetX, targetY, targetZ);
                                                if (!found) return 0;

                                                Vec3d precise2 = new Vec3d(player.getX(), player.getY(), player.getZ());
                                                BlockPos goalPos2 = new BlockPos(targetX, targetY, targetZ);
                                                List<PathNode> previewNodes = PathfinderEngine.findNodePath(
                                                        world, player.getBlockPos(), precise2, goalPos2,
                                                        hasWaterBucketInHotbar(player));
                                                if (!previewNodes.isEmpty())
                                                    PathFollower.storePreview(previewNodes, goalPos2, false);

                                                source.sendFeedback(() -> Text.literal("Previewing path to (" + targetX + ", " + targetY + ", " + targetZ + "). Run §e/usepath§r to follow."), false);
                                                return 1;
                                            })))));

            // /preview all <x> <y> <z> — try walk then sprint, show best path
            dispatcher.register(CommandManager.literal("preview")
                    .then(CommandManager.literal("all")
                            .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> executePreviewAll(context)))))));

            // /usepath <x> <y> <z> — async path compute then follow
            // /usepath sprint <x> <y> <z> — force sprint mode
            dispatcher.register(CommandManager.literal("usepath")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> executeUsePath(context, false)))))
                    .then(CommandManager.literal("sprint")
                            .then(CommandManager.argument("x", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                            .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                                    .executes(context -> executeUsePath(context, true)))))));

            // /clearpreview — clear all overlays and stop path-following
            dispatcher.register(CommandManager.literal("clearpreview")
                    .executes(context -> {
                        TessellatorRenderer.setPath(java.util.Collections.emptyList());
                        TessellatorRenderer.clearBridgePlan();
                        TessellatorRenderer.clearBridgeBlockHighlight();
                        TessellatorRenderer.clearBridgeFaceHighlight();
                        TessellatorRenderer.clearStandingBlockHighlight();
                        TessellatorRenderer.clearPredictedBlocks();
                        TessellatorRenderer.clearNodeMarkers();
                        PreviewManager.stopPreview();
                        PathFollower.stopFollowing();
                        context.getSource().sendFeedback(() -> Text.literal("§aCleared active preview path."), false);
                        return 1;
                    }));

            // /testpath <x> <y> <z> — test pathfinding with multi-angle movement
            dispatcher.register(CommandManager.literal("testpath")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> executeTestPath(context, true))))));

            // /testpathsimple <x> <y> <z> — test with traditional 8-direction movement
            dispatcher.register(CommandManager.literal("testpathsimple")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> executeTestPath(context, false))))));

            // /walkpath <x> <y> <z> — compute and immediately follow a walk path
            dispatcher.register(CommandManager.literal("walkpath")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> executeWalkPath(context))))));

            // /sprintpath <x> <y> <z> — compute and immediately follow a sprint path
            dispatcher.register(CommandManager.literal("sprintpath")
                    .then(CommandManager.argument("x", IntegerArgumentType.integer())
                            .then(CommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(CommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(context -> executeSprintPath(context))))));

            // /stoppath — stop following the current path
            dispatcher.register(CommandManager.literal("stoppath")
                    .executes(context -> {
                        PathFollower.stopFollowing();
                        context.getSource().sendFeedback(() -> Text.literal("Stopped following path."), false);
                        return 1;
                    }));
        });
    }

    // -------------------------------------------------------------------------
    // /preview all
    // -------------------------------------------------------------------------

    private static int executePreviewAll(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity  player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal("Only players can execute this command.")); return 0; }

        int tx = IntegerArgumentType.getInteger(context, "x");
        int ty = IntegerArgumentType.getInteger(context, "y");
        int tz = IntegerArgumentType.getInteger(context, "z");

        ServerWorld world    = source.getWorld();
        BlockPos    startPos = player.getBlockPos();
        BlockPos    goalPos  = new BlockPos(tx, ty, tz);
        Vec3d       precise  = new Vec3d(player.getX(), player.getY(), player.getZ());
        boolean     hasWater = hasWaterBucketInHotbar(player);

        // Method 1: walk A*
        List<PathNode> walkPath = PathfinderEngine.findNodePath(world, startPos, precise, goalPos, hasWater);
        if (!walkPath.isEmpty()) {
            PathNode last = walkPath.get(walkPath.size() - 1);
            if (last.pos.getSquaredDistance(goalPos) <= 2.0) {
                TessellatorRenderer.setPath(walkPath);
                LineRenderer.setPath(world, walkPath);
                PathFollower.storePreview(walkPath, goalPos, false);
                int n = walkPath.size();
                source.sendFeedback(() -> Text.literal("§a[Walk]§r Path found: §e" + n
                        + " nodes§r. Run §e/usepath§r to follow."), false);
                return 1;
            }
        }

        // Method 2: sprint A*
        List<PathNode> sprintPath = PathfinderEngine.findSprintNodePath(world, startPos, precise, goalPos, hasWater);
        if (!sprintPath.isEmpty()) {
            PathNode last = sprintPath.get(sprintPath.size() - 1);
            if (last.pos.getSquaredDistance(goalPos) <= 2.0) {
                TessellatorRenderer.setPath(sprintPath);
                LineRenderer.setPath(world, sprintPath);
                PathFollower.storePreview(sprintPath, goalPos, true);
                int n = sprintPath.size();
                source.sendFeedback(() -> Text.literal("§b[Sprint]§r Path found: §e" + n
                        + " nodes§r. Run §e/usepath sprint§r to follow."), false);
                return 1;
            }
        }

        source.sendFeedback(() -> Text.literal("§c[Path]§r No walkable path found to ("
                + tx + ", " + ty + ", " + tz + ")."), false);
        return 0;
    }

    // -------------------------------------------------------------------------
    // /usepath — async path computation then follow (no server-tick freeze)
    // -------------------------------------------------------------------------

    private record PathResult(List<PathNode> path, boolean sprint) {}

    private static int executeUsePath(CommandContext<ServerCommandSource> context, boolean forceSprint) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity  player = source.getPlayer();
        if (player == null) { source.sendError(Text.literal("Only players can execute this command.")); return 0; }

        PathFollower.stopFollowing();

        int tx = IntegerArgumentType.getInteger(context, "x");
        int ty = IntegerArgumentType.getInteger(context, "y");
        int tz = IntegerArgumentType.getInteger(context, "z");

        ServerWorld world    = source.getWorld();
        BlockPos    goalPos  = new BlockPos(tx, ty, tz);
        Vec3d       precise  = new Vec3d(player.getX(), player.getY(), player.getZ());
        BlockPos    startPos = player.getBlockPos();
        boolean     hasWater = hasWaterBucketInHotbar(player);
        net.minecraft.server.MinecraftServer server = source.getServer();

        source.sendFeedback(() -> Text.literal(
                "§e[Path]§r Computing path to (" + tx + ", " + ty + ", " + tz + ")…"), false);

        CompletableFuture.supplyAsync(() -> {
            if (!forceSprint) {
                List<PathNode> walk = PathfinderEngine.findNodePath(
                        world, startPos, precise, goalPos, hasWater);
                if (!walk.isEmpty()
                        && walk.get(walk.size() - 1).pos.getSquaredDistance(goalPos) <= 2.0) {
                    return new PathResult(walk, false);
                }
            }
            List<PathNode> sprint = PathfinderEngine.findSprintNodePath(
                    world, startPos, precise, goalPos, hasWater);
            if (!sprint.isEmpty()
                    && sprint.get(sprint.size() - 1).pos.getSquaredDistance(goalPos) <= 2.0) {
                return new PathResult(sprint, true);
            }
            return null;
        }).thenAccept(result -> server.execute(() -> {
            if (result == null) {
                player.sendMessage(Text.literal(
                        "§c[Path]§r No path found to (" + tx + ", " + ty + ", " + tz + ")."), false);
                return;
            }
            TessellatorRenderer.setPath(result.path());
            PathFollower.setReplanGoal(goalPos, result.sprint());
            if (result.sprint()) {
                PathFollower.startFollowingSprint(result.path());
                player.sendMessage(Text.literal("§b[Sprint]§r Following " + result.path().size()
                        + " nodes to (" + tx + ", " + ty + ", " + tz + "). §e/stoppath§r to stop."), false);
            } else {
                PathFollower.startFollowing(result.path());
                player.sendMessage(Text.literal("§a[Walk]§r Following " + result.path().size()
                        + " nodes to (" + tx + ", " + ty + ", " + tz + "). §e/stoppath§r to stop."), false);
            }
        }));

        return 1;
    }

    // -------------------------------------------------------------------------
    // /testpath / /testpathsimple
    // -------------------------------------------------------------------------

    private static int executeTestPath(CommandContext<ServerCommandSource> context, boolean useMultiAngle) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) {
            source.sendError(Text.literal("Only players can execute this command."));
            return 0;
        }

        int targetX = IntegerArgumentType.getInteger(context, "x");
        int targetY = IntegerArgumentType.getInteger(context, "y");
        int targetZ = IntegerArgumentType.getInteger(context, "z");

        ServerWorld world = source.getWorld();
        BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
        boolean waterBucket = hasWaterBucketInHotbar(player);

        long startTime = System.currentTimeMillis();
        List<PathNode> path = PathfinderEngine.findNodePath(world, player.getBlockPos(), targetPos, useMultiAngle, waterBucket);
        long endTime = System.currentTimeMillis();

        TessellatorRenderer.setPath(path);

        String title = useMultiAngle ? "=== Pathfinding Test Results ===" : "=== Pathfinding Test Results (Simple) ===";
        source.sendFeedback(() -> Text.literal(title), false);
        int finalTargetX = targetX, finalTargetY = targetY, finalTargetZ = targetZ;
        source.sendFeedback(() -> Text.literal("Target: " + finalTargetX + ", " + finalTargetY + ", " + finalTargetZ), false);
        int pathSize = path.size();
        source.sendFeedback(() -> Text.literal("Path nodes: " + pathSize), false);
        long computeTime = endTime - startTime;
        source.sendFeedback(() -> Text.literal("Computation time: " + computeTime + "ms"), false);

        if (!path.isEmpty()) {
            PathNode lastNode = path.get(path.size() - 1);
            source.sendFeedback(() -> Text.literal("Path length: " + String.format("%.2f", lastNode.gCost) + " blocks"), false);
            source.sendFeedback(() -> Text.literal("Water bucket: " + (waterBucket ? "yes (WATER_DROP enabled)" : "no")), false);

            if (useMultiAngle) {
                int[] counts = countNodeTypes(path);
                source.sendFeedback(() -> Text.literal("Node types: Walk=" + counts[0] + ", Jump=" + counts[1]
                        + ", SprintJump=" + counts[2] + ", Drop=" + counts[3] + ", WaterDrop=" + counts[4]
                        + ", Bounce=" + counts[5] + ", Climb=" + counts[6] + ", Edge=" + counts[7]
                        + ", Interact=" + counts[8]), false);
            }
        } else {
            source.sendFeedback(() -> Text.literal("No path found!"), false);
        }

        return 1;
    }

    private static int[] countNodeTypes(List<PathNode> path) {
        int[] counts = new int[9];
        for (PathNode node : path) {
            switch (node.type) {
                case WALK        -> counts[0]++;
                case JUMP        -> counts[1]++;
                case SPRINT_JUMP -> counts[2]++;
                case DROP        -> counts[3]++;
                case WATER_DROP  -> counts[4]++;
                case BOUNCE      -> counts[5]++;
                case CLIMB       -> counts[6]++;
                case EDGE        -> counts[7]++;
                case INTERACT    -> counts[8]++;
            }
        }
        return counts;
    }

    // -------------------------------------------------------------------------
    // /sprintpath / /walkpath
    // -------------------------------------------------------------------------

    private static int executeSprintPath(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) {
            source.sendError(Text.literal("Only players can execute this command."));
            return 0;
        }

        int targetX = IntegerArgumentType.getInteger(context, "x");
        int targetY = IntegerArgumentType.getInteger(context, "y");
        int targetZ = IntegerArgumentType.getInteger(context, "z");

        ServerWorld world = source.getWorld();
        BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
        Vec3d playerPrecise = new Vec3d(player.getX(), player.getY(), player.getZ());

        List<PathNode> path = PathfinderEngine.findSprintNodePath(world, player.getBlockPos(),
                playerPrecise, targetPos, hasWaterBucketInHotbar(player));

        if (path.isEmpty()) {
            source.sendError(Text.literal("No path found to target!"));
            return 0;
        }

        TessellatorRenderer.setPath(path);
        PathFollower.setReplanGoal(targetPos, true);
        PathFollower.startFollowingSprint(path);

        source.sendFeedback(() -> Text.literal("Sprint-following path with " + path.size() + " nodes. Use /stoppath to stop."), false);
        return 1;
    }

    private static int executeWalkPath(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) {
            source.sendError(Text.literal("Only players can execute this command."));
            return 0;
        }

        int targetX = IntegerArgumentType.getInteger(context, "x");
        int targetY = IntegerArgumentType.getInteger(context, "y");
        int targetZ = IntegerArgumentType.getInteger(context, "z");

        ServerWorld world = source.getWorld();
        BlockPos targetPos = new BlockPos(targetX, targetY, targetZ);
        Vec3d playerPrecise = new Vec3d(player.getX(), player.getY(), player.getZ());

        List<PathNode> path = PathfinderEngine.findNodePath(world, player.getBlockPos(),
                playerPrecise, targetPos, hasWaterBucketInHotbar(player));

        if (path.isEmpty()) {
            source.sendError(Text.literal("No path found to target!"));
            return 0;
        }

        TessellatorRenderer.setPath(path);
        PathFollower.setReplanGoal(targetPos, false);
        PathFollower.startFollowing(path);

        source.sendFeedback(() -> Text.literal("Following path with " + path.size() + " nodes. Use /stoppath to stop."), false);
        return 1;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean hasWaterBucketInHotbar(ServerPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.WATER_BUCKET)) return true;
        }
        return false;
    }
}
