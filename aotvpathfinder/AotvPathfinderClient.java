package com.abdy2.aotvpathfinder;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.registry.Registries;

public class AotvPathfinderClient implements ClientModInitializer {
    private static final KeyBinding.Category KEY_CATEGORY = KeyBinding.Category.create(Identifier.of("aotvpathfinder", "general"));

    private final HypixelManaTracker manaTracker = new HypixelManaTracker();
    private final TeleportPathfinder pathfinder = new TeleportPathfinder();
    private AotvClientSettings settings;

    private KeyBinding setTargetKey;
    private KeyBinding buildPathKey;
    private KeyBinding clearPathKey;
    private KeyBinding assistHopKey;
    private KeyBinding autoRunToggleKey;
    private KeyBinding liveAiToggleKey;

    private BlockPos goal;
    private volatile List<TeleportHop> activePath = new ArrayList<>();
    private volatile List<TeleportHop> livePreviewPath = new ArrayList<>();
    private volatile List<TeleportHop> livePlannedPath = new ArrayList<>();
    private BlockPos liveGoal;
    private int currentStepIndex;
    private int liveStepIndex;
    private boolean autoRun;
    private boolean liveAi;
    private long lastCastAtMs;
    private int castDebugCount;
    private long lastBlockedReplanAtMs;
    private long lastReplanAtMs;
    private long liveLastAdvanceAtMs;
    private long liveNodeLockUntilMs;
    private int liveLockedStepIndex = -1;
    private long spinStartedAtMs;
    private long lastYawSignFlipAtMs;
    private int yawSignFlipCount;
    private float lastYawDelta;
    private double lastTargetDistSq = Double.POSITIVE_INFINITY;
    private long lastClickChatAtMs;
    private Vec3d lastAimTarget;
    private long aimStableSinceMs;
    private float walkPitchLock = 8.0F;

    private static final float WALK_YAW_STEP_DEG = 24.0F;
    private static final float WALK_PITCH_STEP_DEG = 1.4F;
    private static final float TELEPORT_YAW_STEP_DEG = 14.0F;
    private static final float TELEPORT_PITCH_STEP_DEG = 11.0F;
    private static final long SPIN_WINDOW_MS = 650L;
    private static final long SPIN_TRIGGER_MS = 600L;
    private static final long PATCH_WINDOW_MS = 1000L;
    private final ArrayDeque<Long> patchAttemptTimes = new ArrayDeque<>();
    private int prebuiltFurthestStepIndex;
    private int liveFurthestStepIndex;
    private List<BlockPos> highlightedBlocks = new ArrayList<>();
    // ── Node shapes: main outline ──
    private static final net.minecraft.util.shape.VoxelShape NORMAL_NODE_SHAPE = VoxelShapes.cuboid(0.12, 0.0, 0.12, 0.88, 0.95, 0.88);
    private static final net.minecraft.util.shape.VoxelShape SHIFT_NODE_SHAPE  = VoxelShapes.cuboid(0.08, 0.0, 0.08, 0.92, 0.72, 0.92);
    private static final net.minecraft.util.shape.VoxelShape WALK_NODE_SHAPE   = VoxelShapes.cuboid(0.28, 0.0, 0.28, 0.72, 0.28, 0.72);
    // ── Node shapes: outer glow halo (slightly larger than main) ──
    private static final net.minecraft.util.shape.VoxelShape NORMAL_GLOW_SHAPE = VoxelShapes.cuboid(0.04, -0.08, 0.04, 0.96, 1.03, 0.96);
    private static final net.minecraft.util.shape.VoxelShape SHIFT_GLOW_SHAPE  = VoxelShapes.cuboid(0.0,  -0.08, 0.0,  1.0,  0.80, 1.0);
    private static final net.minecraft.util.shape.VoxelShape WALK_GLOW_SHAPE   = VoxelShapes.cuboid(0.20, -0.05, 0.20, 0.80, 0.35, 0.80);
    // ── Current-step beacon + goal ──
    private static final net.minecraft.util.shape.VoxelShape CURRENT_BEACON    = VoxelShapes.cuboid(0.05, 0.0, 0.05, 0.95, 1.4, 0.95);
    private static final net.minecraft.util.shape.VoxelShape CURRENT_CORE      = VoxelShapes.cuboid(0.20, 0.08, 0.20, 0.80, 1.20, 0.80);
    private static final net.minecraft.util.shape.VoxelShape GOAL_SHAPE        = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 1.5, 1.0);

    @Override
    public void onInitializeClient() {
        setTargetKey = registerKey("set_target", GLFW.GLFW_KEY_J);
        buildPathKey = registerKey("build_path", GLFW.GLFW_KEY_K);
        clearPathKey = registerKey("clear_path", GLFW.GLFW_KEY_L);
        assistHopKey = registerKey("assist_next_hop", GLFW.GLFW_KEY_SEMICOLON);
        autoRunToggleKey = registerKey("toggle_auto", GLFW.GLFW_KEY_APOSTROPHE);
        liveAiToggleKey = registerKey("toggle_live_ai", GLFW.GLFW_KEY_O);

        settings = AotvClientSettings.load(MinecraftClient.getInstance().runDirectory.toPath());

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> manaTracker.acceptActionBar(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::renderPathEsp);
        HudRenderCallback.EVENT.register(this::renderTopRightStatus);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                literal("setgoal")
                    .then(argument("x", IntegerArgumentType.integer())
                        .then(argument("y", IntegerArgumentType.integer())
                            .then(argument("z", IntegerArgumentType.integer())
                                .executes(this::executeSetGoal)
                            )
                        )
                    )
            );

            dispatcher.register(
                literal("preview")
                    .executes(this::executePreview)
                    .then(literal("clear").executes(this::executePreviewClear))
                    .then(argument("x", IntegerArgumentType.integer())
                        .then(argument("y", IntegerArgumentType.integer())
                            .then(argument("z", IntegerArgumentType.integer())
                                .executes(this::executePreviewCoords)
                            )
                        )
                    )
            );

            dispatcher.register(
                literal("aotv")
                    .then(literal("mode")
                        .then(literal("hybrid").executes(ctx -> setModeCommand(TeleportPathfinder.MovementMode.HYBRID)))
                        .then(literal("walk").executes(ctx -> setModeCommand(TeleportPathfinder.MovementMode.WALK_ONLY)))
                        .then(literal("teleport").executes(ctx -> setModeCommand(TeleportPathfinder.MovementMode.TELEPORT_ONLY)))
                    )
                    .then(literal("tpmode")
                        .then(literal("shift").executes(ctx -> setTeleportModeCommand(TeleportPathfinder.TeleportMode.SHIFT_ONLY)))
                        .then(literal("hybrid").executes(ctx -> setTeleportModeCommand(TeleportPathfinder.TeleportMode.HYBRID_TELEPORT)))
                        .then(literal("just").executes(ctx -> setTeleportModeCommand(TeleportPathfinder.TeleportMode.JUST_TELEPORT)))
                    )
                    .then(literal("airchain")
                        .then(literal("on").executes(ctx -> setAirChainCommand(true)))
                        .then(literal("off").executes(ctx -> setAirChainCommand(false)))
                    )
                    .then(literal("exportpaths")
                        .executes(ctx -> exportCandidatePathsCommand(30))
                        .then(argument("count", IntegerArgumentType.integer(10, 300))
                            .executes(ctx -> exportCandidatePathsCommand(IntegerArgumentType.getInteger(ctx, "count")))
                        )
                    )
                    .then(literal("show").executes(ctx -> showSettingsCommand()))
            );
        });
    }

    private int executeSetGoal(CommandContext<?> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        goal = new BlockPos(x, y, z);

        sendChat(client.player, "Goal set: " + x + " " + y + " " + z);
        return 1;
    }

    private int executePreview(CommandContext<?> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }

        BlockPos target = goal;
        if (target == null && client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            target = hit.getBlockPos().up();
        }

        if (target == null) {
            sendChat(client.player, "No preview target. Use /setgoal x y z, /preview x y z, or look at a block.");
            return 0;
        }

        return buildPreviewPath(client, target);
    }

    private int executePreviewCoords(CommandContext<?> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        return buildPreviewPath(client, new BlockPos(x, y, z));
    }

    private int executePreviewClear(CommandContext<?> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }

        activePath = new ArrayList<>();
        livePreviewPath = new ArrayList<>();
        livePlannedPath = new ArrayList<>();
        currentStepIndex = 0;
        liveStepIndex = 0;
        prebuiltFurthestStepIndex = 0;
        liveFurthestStepIndex = 0;
        clearHighlights(client);
        ExperimentalPathInsights.clear();
        sendChat(client.player, "Preview cleared.");
        return 1;
    }

    private int buildPreviewPath(MinecraftClient client, BlockPos target) {
        ClientPlayerEntity player = client.player;
        List<TeleportHop> path = pathfinder.findPath(player, player.getBlockPos(), target, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        if (path.isEmpty()) {
            sendChat(player, "Preview: no path found to " + target.getX() + " " + target.getY() + " " + target.getZ() + ".");
            return 0;
        }

        goal = target;
        autoRun = false;
        liveAi = false;
        stopWalking(client);

        activePath = path;
        currentStepIndex = 0;
        prebuiltFurthestStepIndex = 0;
        livePreviewPath = new ArrayList<>();
        livePlannedPath = new ArrayList<>();

        int normal = 0;
        int shift = 0;
        int walk = 0;
        int manaCost = 0;

        for (TeleportHop hop : path) {
            manaCost += hop.manaCost();
            if (hop.type() == TeleportHop.HopType.NORMAL) {
                normal++;
            } else if (hop.type() == TeleportHop.HopType.SHIFT) {
                shift++;
            } else {
                walk++;
            }
        }

        sendChat(player, "Preview path: " + path.size() + " steps [normal=" + normal + ", shift=" + shift + ", walk=" + walk + "] mana=" + manaCost + ".");
        return 1;
    }
    private int setModeCommand(TeleportPathfinder.MovementMode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setMovementMode(mode);
        sendChat(client.player, "Path mode: " + mode.name().toLowerCase(Locale.ROOT));
        return 1;
    }

    private int setTeleportModeCommand(TeleportPathfinder.TeleportMode mode) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setTeleportMode(mode);
        sendChat(client.player, "Teleport mode: " + teleportModeLabel(mode));
        return 1;
    }

    private int setAirChainCommand(boolean enabled) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setAirChainEnabled(enabled);
        sendChat(client.player, "Air-chain: " + (enabled ? "on" : "off"));
        return 1;
    }

    private int showSettingsCommand() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return 0;
        }
        sendChat(client.player, "Mode=" + settings.movementMode().name().toLowerCase(Locale.ROOT)
            + ", tp-mode=" + teleportModeLabel(settings.teleportMode())
            + ", air-chain=" + (settings.airChainEnabled() ? "on" : "off")
            + ", patch(w/t)=" + settings.walkPatchWindowBlocks() + "/" + settings.teleportPatchLookaheadNodes()
            + ", lockMs=" + settings.commitLockMs());
        return 1;
    }
    private int exportCandidatePathsCommand(int maxCandidates) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return 0;
        }

        BlockPos target = resolveDynamicGoal(client);
        if (target == null) {
            sendChat(player, "No goal to export. Use /setgoal x y z or look at a block.");
            return 0;
        }

        BlockPos start = player.getBlockPos();
        maxCandidates = Math.max(10, Math.min(maxCandidates, 120));
        List<TeleportPathfinder.CandidatePath> candidates = pathfinder.enumerateCandidatePaths(
            player,
            start,
            target,
            manaTracker.currentMana(),
            maxCandidates,
            22000
        );

        if (candidates.isEmpty()) {
            sendChat(player, "No candidate paths found for export.");
            return 0;
        }

        List<Map<String, Object>> candidateJson = new ArrayList<>();
        for (TeleportPathfinder.CandidatePath candidate : candidates) {
            Map<String, Object> candidateMap = new HashMap<>();
            candidateMap.put("movement_mode", candidate.movementMode().name());
            candidateMap.put("teleport_mode", candidate.teleportMode().name());
            candidateMap.put("airchain", candidate.airChainEnabled());
            candidateMap.put("reached_goal", candidate.reachedGoal());
            candidateMap.put("best_distance_sq", candidate.bestDistanceSq());

            int mana = 0;
            int walk = 0;
            int normal = 0;
            int shift = 0;
            List<Map<String, Object>> steps = new ArrayList<>();
            for (TeleportHop hop : candidate.hops()) {
                Map<String, Object> step = new HashMap<>();
                step.put("x", hop.landing().getX());
                step.put("y", hop.landing().getY());
                step.put("z", hop.landing().getZ());
                step.put("type", hop.type().name());
                step.put("mana", hop.manaCost());
                steps.add(step);

                mana += hop.manaCost();
                if (hop.type() == TeleportHop.HopType.WALK) {
                    walk++;
                } else if (hop.type() == TeleportHop.HopType.SHIFT) {
                    shift++;
                } else {
                    normal++;
                }
            }
            candidateMap.put("mana_cost", mana);
            candidateMap.put("steps_total", candidate.hops().size());
            candidateMap.put("steps_walk", walk);
            candidateMap.put("steps_normal", normal);
            candidateMap.put("steps_shift", shift);
            candidateMap.put("steps", steps);
            candidateJson.add(candidateMap);
        }

        Map<String, Object> root = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("generated_at_ms", System.currentTimeMillis());
        meta.put("world", client.world.getRegistryKey().getValue().toString());
        meta.put("start", Map.of("x", start.getX(), "y", start.getY(), "z", start.getZ()));
        meta.put("goal", Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()));
        meta.put("candidate_count", candidateJson.size());
        root.put("meta", meta);
        root.put("candidates", candidateJson);
        root.put("obstacles", captureObstacleData(client, start, target, candidates));

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path out = client.runDirectory.toPath().resolve("config").resolve("aotv-path-candidates.json");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, gson.toJson(root));
        } catch (IOException e) {
            sendChat(player, "Failed writing JSON: " + e.getMessage());
            return 0;
        }

        ExperimentalPathInsights.buildFromCandidates(start, target, candidates);
        sendChat(player, "Exported " + candidateJson.size() + " candidate paths to " + out.toAbsolutePath());
        return 1;
    }

    private Map<String, Object> captureObstacleData(
        MinecraftClient client,
        BlockPos start,
        BlockPos goal,
        List<TeleportPathfinder.CandidatePath> candidates
    ) {
        int minX = Math.min(start.getX(), goal.getX());
        int minY = Math.min(start.getY(), goal.getY());
        int minZ = Math.min(start.getZ(), goal.getZ());
        int maxX = Math.max(start.getX(), goal.getX());
        int maxY = Math.max(start.getY(), goal.getY());
        int maxZ = Math.max(start.getZ(), goal.getZ());

        for (TeleportPathfinder.CandidatePath candidate : candidates) {
            for (TeleportHop hop : candidate.hops()) {
                BlockPos p = hop.landing();
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
        }

        int margin = 6;
        minX -= margin;
        minY -= margin;
        minZ -= margin;
        maxX += margin;
        maxY += margin;
        maxZ += margin;

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 90000L) {
            int midY = (minY + maxY) / 2;
            minY = midY - 32;
            maxY = midY + 32;
        }

        int sampleStride = volume > 180000L ? 3 : (volume > 70000L ? 2 : 1);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (int x = minX; x <= maxX; x += sampleStride) {
            for (int y = minY; y <= maxY; y += sampleStride) {
                for (int z = minZ; z <= maxZ; z += sampleStride) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    boolean collisionEmpty = state.getCollisionShape(client.world, pos).isEmpty();
                    boolean avoidWalk = !collisionEmpty;
                    boolean avoidTeleport = !state.isAir();
                    if (!avoidWalk && !avoidTeleport) {
                        continue;
                    }

                    Map<String, Object> b = new HashMap<>();
                    b.put("x", x);
                    b.put("y", y);
                    b.put("z", z);
                    b.put("id", Registries.BLOCK.getId(state.getBlock()).toString());
                    b.put("solid", state.isSolidBlock(client.world, pos));
                    b.put("collision_empty", collisionEmpty);
                    b.put("avoid_walk", avoidWalk);
                    b.put("avoid_teleport", avoidTeleport);
                    blocks.add(b);
                }
            }
        }

        Map<String, Object> bounds = new HashMap<>();
        bounds.put("min_x", minX);
        bounds.put("min_y", minY);
        bounds.put("min_z", minZ);
        bounds.put("max_x", maxX);
        bounds.put("max_y", maxY);
        bounds.put("max_z", maxZ);

        Map<String, Object> out = new HashMap<>();
        out.put("bounds", bounds);
        out.put("block_count", blocks.size());
        out.put("sample_stride", sampleStride);
        out.put("blocks", blocks);
        return out;
    }
    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }

        handleKeys(client);

        if (autoRun) {
            runPrebuiltRoute(client);
        }
        if (liveAi) {
            runLiveAi(client);
        }

        if (!autoRun && !liveAi) {
            stopWalking(client);
        }

        updateRouteHighlights(client);
    }

    private void handleKeys(MinecraftClient client) {
        while (setTargetKey.wasPressed()) {
            setGoalFromCrosshair(client);
        }

        while (buildPathKey.wasPressed()) {
            buildPath(client);
        }

        while (clearPathKey.wasPressed()) {
            activePath = new ArrayList<>();
            livePreviewPath = new ArrayList<>();
            livePlannedPath = new ArrayList<>();
            currentStepIndex = 0;
            liveStepIndex = 0;
            liveGoal = null;
            resetLiveStabilizer();
            stopWalking(client);
            clearHighlights(client);
            ExperimentalPathInsights.clear();
            sendChat(client.player, "Path cleared.");
        }

        while (assistHopKey.wasPressed()) {
            assistNextStep(client.player);
        }

        while (autoRunToggleKey.wasPressed()) {
            autoRun = !autoRun;
            if (autoRun) {
                liveAi = false;
            }
            sendChat(client.player, "Auto route " + (autoRun ? "enabled" : "disabled") + ".");
        }

        while (liveAiToggleKey.wasPressed()) {
            liveAi = !liveAi;
            if (liveAi) {
                autoRun = false;
                activePath = new ArrayList<>();
                livePlannedPath = new ArrayList<>();
                livePreviewPath = new ArrayList<>();
                currentStepIndex = 0;
                liveStepIndex = 0;
                liveGoal = null;
                resetLiveStabilizer();
                sendChat(client.player, "Live AI enabled.");
            } else {
                stopWalking(client);
                livePlannedPath = new ArrayList<>();
                liveGoal = null;
                resetLiveStabilizer();
                sendChat(client.player, "Live AI disabled.");
            }
        }
    }

    private void setGoalFromCrosshair(MinecraftClient client) {
        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            sendChat(client.player, "No block targeted.");
            return;
        }

        goal = hit.getBlockPos().up();
        sendChat(client.player, String.format("Goal set: %d %d %d", goal.getX(), goal.getY(), goal.getZ()));
    }

    private void buildPath(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (goal == null) {
            sendChat(player, "Set a goal first (J or /setgoal x y z).");
            return;
        }

        List<TeleportHop> path = pathfinder.findPath(player, player.getBlockPos(), goal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        if (path.isEmpty()) {
            sendChat(player, "No path found.");
            return;
        }

        activePath = path;
        currentStepIndex = 0;
        int manaCost = path.stream().mapToInt(TeleportHop::manaCost).sum();
        sendChat(player, "Path built: " + path.size() + " steps, est mana " + manaCost + ".");
    }

    private void assistNextStep(ClientPlayerEntity player) {
        if (currentStepIndex >= activePath.size()) {
            sendChat(player, "No remaining steps.");
            return;
        }

        TeleportHop step = activePath.get(currentStepIndex);
        lookAtTeleportHuman(player, aimTargetForHop(player, step), false);
        player.setSneaking(step.requiresShift());
        sendChat(player, "Aimed at step " + (currentStepIndex + 1) + "/" + activePath.size() + " [" + step.type() + "]");
    }

    private boolean isStepReached(ClientPlayerEntity player, TeleportHop step) {
        if (step.isWalk()) {
            return player.getBlockPos().isWithinDistance(step.landing(), 1.2);
        }
        return player.getBlockPos().isWithinDistance(step.landing(), 2.25);
    }

    private void runPrebuiltRoute(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (currentStepIndex >= activePath.size()) {
            stopWalking(client);
            return;
        }

        TeleportHop step = activePath.get(currentStepIndex);
        prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);
        boolean fallingPastStep = !player.isOnGround() && player.getVelocity().y < -0.08;
        if (fallingPastStep && (step.type() == TeleportHop.HopType.NORMAL || step.type() == TeleportHop.HopType.SHIFT) && player.getY() < step.landing().getY() - 1.1) {
            currentStepIndex++;
            prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);
            return;
        }
        if (isStepReached(player, step)) {
            currentStepIndex++;
            prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);
            return;
        }
        if (tryForwardPatchPrebuilt(player)) {
            return;
        }

        if (step.isWalk()) {
            if (walkToStep(client, player, step)) {
                currentStepIndex++;
            }
            return;
        }

        stopWalking(client);
        if (!ensureAotvEquipped(client, player)) {
            return;
        }

        int mana = manaTracker.currentMana();
        if (mana >= 0 && mana < step.manaCost()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCastAtMs < castCooldownMs(step) || client.interactionManager == null) {
            return;
        }

        Vec3d stepTarget = aimTargetForHop(player, step);
        if (!hasServerStyleCastClear(player, stepTarget)) {
            // Try walking sideways to get a clear cast before skipping.
            if (tryWalkAroundBlocked(player, now, false)) {
                return;
            }
            // Prebuilt route: skip blocked hop instead of wasting casts.
            currentStepIndex++;
            return;
        }
        if (!aimAtAndReady(player, stepTarget, now, useFastAirChainTiming(step))) {
            return;
        }
        player.setSneaking(step.requiresShift());
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        lastCastAtMs = now;
        castDebugCount++;
        maybeSendClickDebug(player, "CLICK #" + castDebugCount + " [" + step.type().name().toLowerCase(Locale.ROOT) + "] prebuilt", now);
    }
    private void runLiveAi(MinecraftClient client) {
        ClientPlayerEntity player = client.player;

        BlockPos dynamicGoal = goal;
        if (dynamicGoal == null) {
            stopWalking(client);
            livePlannedPath = new ArrayList<>();
            liveStepIndex = 0;
            liveGoal = null;
            liveAi = false;
            resetLiveStabilizer();
            sendChat(player, "Live AI stopped: set a goal with /setgoal x y z.");
            return;
        }
        if (player.getBlockPos().isWithinDistance(dynamicGoal, AotvConfig.GOAL_REACHED_RADIUS)) {
            stopWalking(client);
            livePlannedPath = new ArrayList<>();
            liveStepIndex = 0;
            liveGoal = dynamicGoal;
            liveAi = false;
            resetLiveStabilizer();
            sendChat(player, "Live AI reached goal and turned off.");
            return;
        }

        long now = System.currentTimeMillis();
        boolean goalChanged = liveGoal == null || !liveGoal.equals(dynamicGoal);
        boolean noPath = livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size();

        if (!noPath) {
            TeleportHop step = livePlannedPath.get(liveStepIndex);
            liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
            boolean fallingPastStep = !player.isOnGround() && player.getVelocity().y < -0.08;
            if (fallingPastStep && (step.type() == TeleportHop.HopType.NORMAL || step.type() == TeleportHop.HopType.SHIFT) && player.getY() < step.landing().getY() - 1.1) {
                liveStepIndex++;
                liveLastAdvanceAtMs = now;
                markStepAdvanced(now);
                liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
                if (liveStepIndex >= livePlannedPath.size()) {
                    stopWalking(client);
                    liveAi = false;
                    resetLiveStabilizer();
                    sendChat(player, "Live AI completed path and turned off.");
                    return;
                }
                step = livePlannedPath.get(liveStepIndex);
            }

            if (isStepReached(player, step)) {
                liveStepIndex++;
                liveLastAdvanceAtMs = now;
                markStepAdvanced(now);
                liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
                if (liveStepIndex >= livePlannedPath.size()) {
                    stopWalking(client);
                    liveAi = false;
                    resetLiveStabilizer();
                    sendChat(player, "Live AI completed path and turned off.");
                    return;
                }
            }
        }

        noPath = livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size();
        boolean followFailed = false;
        String replanReason = null;
        if (!noPath && tryForwardPatchLive(player, now)) {
            noPath = livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size();
        }
        if (!noPath) {
            TeleportHop step = livePlannedPath.get(liveStepIndex);
            boolean walkHandoffFalling = step.isWalk() && !player.isOnGround();
            if (!walkHandoffFalling) {
                if (step.isWalk()) {
                    // Walk: bail if player has drifted too far from the target or taken too long.
                    followFailed = !player.getBlockPos().isWithinDistance(step.landing(), 18.0)
                        || now - liveLastAdvanceAtMs > 5000L;
                    if (followFailed) {
                        replanReason = "stuck_walk";
                    }
                } else {
                    // Teleport: the player is at the *launch* position, nowhere near the landing yet.
                    // Distance-to-landing is always large before the hop fires, so only use timeout.
                    followFailed = now - liveLastAdvanceAtMs > 6500L;
                    if (followFailed) {
                        replanReason = "timeout";
                    }
                }
            }
        }

        if (goalChanged) {
            replanReason = "goal_changed";
        } else if (noPath && replanReason == null) {
            replanReason = "no_path";
        } else if (followFailed) {
            replanReason = "patch_failed_full_replan";
        }

        if (goalChanged || noPath || followFailed) {
            if (now - lastReplanAtMs < 900L) {
                return;
            }

            lastReplanAtMs = now;
            BlockPos planGoal = dynamicGoal;
            List<TeleportHop> path = pathfinder.findPath(player, player.getBlockPos(), planGoal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
            livePreviewPath = path;
            livePlannedPath = path;
            liveStepIndex = 0;
            liveFurthestStepIndex = 0;
            liveLockedStepIndex = 0;
            liveNodeLockUntilMs = now + lockWindowMs();
            liveGoal = dynamicGoal;
            liveLastAdvanceAtMs = now;
            if (replanReason != null) {
                sendChat(player, "rebuild: " + replanReason);
            }
            if (path.isEmpty()) {
                stopWalking(client);
                return;
            }
        }

        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size()) {
            stopWalking(client);
            return;
        }

        enforceLiveTargetLock(now);
        TeleportHop next = livePlannedPath.get(liveStepIndex);
        if (next.isWalk()) {
            if (walkToStep(client, player, next)) {
                liveStepIndex++;
                liveLastAdvanceAtMs = now;
                markStepAdvanced(now);
                liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
                if (liveStepIndex >= livePlannedPath.size()) {
                    stopWalking(client);
                    liveAi = false;
                    resetLiveStabilizer();
                    sendChat(player, "Live AI completed path and turned off.");
                }
            }
            return;
        }

        stopWalking(client);
        if (!ensureAotvEquipped(client, player)) {
            return;
        }

        int mana = manaTracker.currentMana();
        if (mana >= 0 && mana < next.manaCost()) {
            return;
        }
        if (now - lastCastAtMs < castCooldownMs(next) || client.interactionManager == null) {
            return;
        }

        Vec3d nextTarget = aimTargetForHop(player, next);
        updateSpinDetector(player, nextTarget, now);
        if (!hasServerStyleCastClear(player, nextTarget)) {
            // 1. Try skipping ahead to a later hop that IS clear.
            boolean switched = tryLocalBlockedRayFallback(player, now);
            if (switched) {
                return;
            }
            // 2. Try walking 1-2 blocks sideways to find a clear cast position.
            boolean walked = tryWalkAroundBlocked(player, now, true);
            if (walked) {
                sendChat(player, "patch: walk_around");
                return;
            }
            // 3. Full rebuild as last resort.
            if (now - lastBlockedReplanAtMs >= 120L) {
                lastBlockedReplanAtMs = now;
                lastReplanAtMs = 0L;
                livePlannedPath = new ArrayList<>();
                sendChat(player, "rebuild: blocked_ray");
            }
            return;
        }
        if (!aimAtAndReady(player, nextTarget, now, useFastAirChainTiming(next))) {
            return;
        }
        player.setSneaking(next.requiresShift());
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
        lastCastAtMs = now;
        castDebugCount++;
        maybeSendClickDebug(player, "CLICK #" + castDebugCount + " [" + next.type().name().toLowerCase(Locale.ROOT) + "] live", now);
    }
    private Vec3d aimTargetForHop(ClientPlayerEntity player, TeleportHop hop) {
        if (hop.type() == TeleportHop.HopType.SHIFT) {
            return Vec3d.ofCenter(hop.landing().down());
        }

        if (settings.teleportMode() == TeleportPathfinder.TeleportMode.JUST_TELEPORT && hop.type() == TeleportHop.HopType.NORMAL) {
            BlockPos above = hop.landing().up();
            if (player.getEntityWorld().getBlockState(above).isAir()) {
                return Vec3d.ofCenter(above).add(0.0, 0.62, 0.0);
            }
        }

        // Normal teleport: target a safer point on the top face of the support block,
        // nudged toward the near edge from player perspective to avoid edge/back-face misses.
        return saferNormalAimTarget(player, hop.landing());
    }
    private long castCooldownMs(TeleportHop hop) {
        return useFastAirChainTiming(hop) ? 35L : 280L;
    }

    private boolean useFastAirChainTiming(TeleportHop hop) {
        return settings.airChainEnabled() && hop.type() == TeleportHop.HopType.NORMAL;
    }

    private boolean aimAtAndReady(ClientPlayerEntity player, Vec3d target, long now, boolean fastMode) {
        lookAtTeleportHuman(player, target, fastMode);

        if (lastAimTarget == null || lastAimTarget.squaredDistanceTo(target) > 0.04) {
            lastAimTarget = target;
            aimStableSinceMs = now;
            if (!fastMode) {
                return false;
            }
        }

        Vec3d delta = target.subtract(player.getEyePos());
        double xz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float desiredYaw = (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
        float desiredPitch = (float) (-(Math.atan2(delta.y, xz) * (180.0 / Math.PI)));

        float yawError = Math.abs(wrapDegrees(desiredYaw - player.getYaw()));
        float pitchError = Math.abs(desiredPitch - player.getPitch());
        float errorThreshold = fastMode ? 6.0F : 3.5F;
        if (yawError > errorThreshold || pitchError > errorThreshold) {
            aimStableSinceMs = now;
            return false;
        }

        long settleMs = fastMode ? 20L : 125L;
        return now - aimStableSinceMs >= settleMs;
    }

    private float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private boolean walkToStep(MinecraftClient client, ClientPlayerEntity player, TeleportHop step) {
        Vec3d target = Vec3d.ofCenter(step.landing()).add(0.0, 0.62, 0.0);
        lookAtWalkHuman(player, target);

        Vec3d here = new Vec3d(player.getX(), player.getY(), player.getZ());
        double dist = here.distanceTo(target);
        if (dist < 1.15) {
            stopWalking(client);
            return true;
        }

        if (client.options != null) {
            client.options.forwardKey.setPressed(true);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.jumpKey.setPressed(false);

            boolean inWater = player.getEntityWorld().getBlockState(player.getBlockPos()).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)
                || player.getEntityWorld().getBlockState(player.getBlockPos().up()).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER);
            if (inWater) {
                boolean targetHigher = step.landing().getY() >= player.getY() - 0.05;
                client.options.jumpKey.setPressed(targetHigher);
                return false;
            }

            double hereFloor = floorTopY(player, player.getBlockPos());
            double nextFloor = floorTopY(player, step.landing().down());
            double floorDelta = nextFloor - hereFloor;
            boolean uphillStep = floorDelta > 0.78;
            var aheadDir = player.getHorizontalFacing();
            BlockPos ahead = player.getBlockPos().offset(aheadDir);
            BlockState aheadState = player.getEntityWorld().getBlockState(ahead);
            boolean stepLikeAhead = aheadState.getBlock() instanceof SlabBlock || aheadState.getBlock() instanceof StairsBlock;
            boolean oneBlockObstacleAhead = !stepLikeAhead
                && aheadState.isSolidBlock(player.getEntityWorld(), ahead)
                && player.getEntityWorld().getBlockState(ahead.up()).isAir();

            int cliffDropAhead = dropDistanceToFloor(player, ahead, 24);
            boolean cliffAhead = cliffDropAhead > 3;
            if (cliffAhead) {
                client.options.forwardKey.setPressed(false);
                client.options.sneakKey.setPressed(true);
                client.options.jumpKey.setPressed(false);
                return false;
            }

            boolean shouldJump = (uphillStep || oneBlockObstacleAhead) && dist < 2.35 && floorDelta >= 0.78;
            client.options.jumpKey.setPressed(shouldJump);
            if (shouldJump && player.isOnGround()) {
                player.jump();
            }
        }

        return false;
    }

    private int dropDistanceToFloor(ClientPlayerEntity player, BlockPos pos, int maxDrop) {
        BlockPos cursor = pos;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (player.getEntityWorld().getBlockState(cursor.down()).isSolidBlock(player.getEntityWorld(), cursor.down())) {
                return drop;
            }
            cursor = cursor.down();
        }
        return maxDrop + 1;
    }

    private boolean tryLocalBlockedRayFallback(ClientPlayerEntity player, long now) {
        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size()) {
            return false;
        }
        int maxCheck = Math.min(livePlannedPath.size() - 1, liveStepIndex + Math.max(1, settings.teleportPatchLookaheadNodes()));
        float currentYaw = player.getYaw();
        int bestIndex = -1;
        float bestYawDelta = Float.MAX_VALUE;
        for (int i = liveStepIndex + 1; i <= maxCheck; i++) {
            TeleportHop alt = livePlannedPath.get(i);
            if (alt.type() != TeleportHop.HopType.NORMAL && alt.type() != TeleportHop.HopType.SHIFT) {
                continue;
            }
            Vec3d altTarget = aimTargetForHop(player, alt);
            if (!hasServerStyleCastClear(player, altTarget)) {
                continue;
            }
            float yaw = desiredYaw(player, altTarget);
            float delta = Math.abs(wrapDegrees(yaw - currentYaw));
            if (delta < bestYawDelta) {
                bestYawDelta = delta;
                bestIndex = i;
            }
        }
        if (bestIndex >= 0 && bestYawDelta <= 70.0F) {
            liveStepIndex = bestIndex;
            liveLockedStepIndex = bestIndex;
            liveNodeLockUntilMs = now + lockWindowMs();
            sendChat(player, "patch: blocked_skip");
            return true;
        }
        return false;
    }

    /**
     * When the current teleport hop is blocked from the player's position, try walking
     * 1-2 blocks sideways to find a position with a clear cast.  Inserts a WALK hop at
     * the current index so the player walks there first, then retries the teleport.
     */
    private boolean tryWalkAroundBlocked(ClientPlayerEntity player, long now, boolean isLive) {
        List<TeleportHop> path = isLive ? livePlannedPath : activePath;
        int idx = isLive ? liveStepIndex : currentStepIndex;
        if (path.isEmpty() || idx >= path.size()) return false;
        TeleportHop blocked = path.get(idx);
        if (blocked.isWalk()) return false;

        Vec3d target = aimTargetForHop(player, blocked);
        BlockPos playerPos = player.getBlockPos();

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int dist = 1; dist <= 2; dist++) {
            for (int[] d : dirs) {
                BlockPos lateral = playerPos.add(d[0] * dist, 0, d[1] * dist);
                if (lateral.equals(playerPos)) continue;
                // Walkable check: feet + head passable, solid floor below
                BlockState feet = player.getEntityWorld().getBlockState(lateral);
                BlockState head = player.getEntityWorld().getBlockState(lateral.up());
                BlockState below = player.getEntityWorld().getBlockState(lateral.down());
                if (!feet.getCollisionShape(player.getEntityWorld(), lateral).isEmpty()) continue;
                if (!head.getCollisionShape(player.getEntityWorld(), lateral.up()).isEmpty()) continue;
                if (!below.isSolidBlock(player.getEntityWorld(), lateral.down())) continue;

                // Check cast from lateral position (eye height 1.62)
                Vec3d lateralEye = new Vec3d(lateral.getX() + 0.5, lateral.getY() + 1.62, lateral.getZ() + 0.5);
                if (!isRayClearFromPosition(player, lateralEye, target)) continue;

                List<TeleportHop> patched = new ArrayList<>(path);
                patched.add(idx, new TeleportHop(lateral, TeleportHop.HopType.WALK, 0));
                if (isLive) {
                    livePlannedPath = patched;
                    liveLastAdvanceAtMs = now;
                } else {
                    activePath = patched;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Server-style raycast from an arbitrary eye position (not the player's current position).
     * Used by walk-around to verify a cast would work from a lateral position.
     */
    private boolean isRayClearFromPosition(ClientPlayerEntity player, Vec3d from, Vec3d to) {
        HitResult colliderHit = player.getEntityWorld().raycast(new RaycastContext(
            from, to,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) return false;

        HitResult outlineHit = player.getEntityWorld().raycast(new RaycastContext(
            from, to,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (outlineHit.getType() != HitResult.Type.MISS) {
            // Ignore OUTLINE hits very close to the source (adjacent blocks)
            double hitDistSq = outlineHit.getPos().squaredDistanceTo(from);
            if (hitDistSq > 2.25) return false;
        }
        return true;
    }

    private double floorTopY(ClientPlayerEntity player, BlockPos floorPos) {
        BlockState below = player.getEntityWorld().getBlockState(floorPos);
        var shape = below.getCollisionShape(player.getEntityWorld(), floorPos);
        if (shape.isEmpty()) {
            return floorPos.getY();
        }
        return floorPos.getY() + shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
    }
    private void stopWalking(MinecraftClient client) {
        if (client.options == null) {
            return;
        }
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }
    private BlockPos resolveDynamicGoal(MinecraftClient client) {
        if (goal != null) {
            return goal;
        }
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().up();
        }
        return null;
    }

    private void renderTopRightStatus(DrawContext drawContext, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        TextRenderer tr = client.textRenderer;
        BlockPos preview = calculatePreviewLanding(player);
        String mode = liveAi ? "live-ai" : (autoRun ? "auto" : "manual");
        String pathMode = settings.movementMode().name().toLowerCase(Locale.ROOT);
        String manaText = manaTracker.hasAnyData() ? (manaTracker.currentMana() + "/" + manaTracker.maxMana()) : "n/a";
        String previewText = preview == null ? "none" : (preview.getX() + " " + preview.getY() + " " + preview.getZ());
        String goalText = goal == null ? "none" : (goal.getX() + " " + goal.getY() + " " + goal.getZ());

        List<String> lines = new ArrayList<>();
        lines.add("[AOTV] " + mode + " | " + pathMode + " | " + teleportModeLabel(settings.teleportMode()));
        lines.add("airchain: " + (settings.airChainEnabled() ? "on" : "off") + " | mana: " + manaText);
        lines.add("goal: " + goalText);
        lines.add("preview: " + previewText);
        lines.add(routePreviewText());
        int xRight = client.getWindow().getScaledWidth() - 8;
        int y = 8;
        for (String line : lines) {
            int x = xRight - tr.getWidth(line);
            drawContext.drawTextWithShadow(tr, Text.literal(line), x, y, 0xEDEDED);
            y += 10;
        }
    }
    private void updateRouteHighlights(MinecraftClient client) {
        List<BlockPos> routeBlocks = new ArrayList<>();
        List<TeleportHop> route = liveAi ? livePlannedPath : activePath;

        int start = liveAi ? Math.min(liveStepIndex, route.size()) : Math.min(currentStepIndex, route.size());
        int end = Math.min(route.size(), start + 24);
        for (int i = start; i < end; i++) {
            routeBlocks.add(route.get(i).landing());
        }

        clearHighlights(client);

        Set<BlockPos> espBlocks = new LinkedHashSet<>();
        for (BlockPos landing : routeBlocks) {
            BlockPos base = landing.down();
            espBlocks.add(base);
            espBlocks.add(base.up());
            espBlocks.add(base.north());
            espBlocks.add(base.south());
            espBlocks.add(base.east());
            espBlocks.add(base.west());
        }

        int idBase = 910000;
        int idx = 0;
        for (BlockPos pos : espBlocks) {
            int crackStage = idx < 14 ? 9 : 7;
            client.worldRenderer.setBlockBreakingInfo(idBase + idx, pos, crackStage);
            idx++;
            if (idx >= 350) {
                break;
            }
        }

        highlightedBlocks = new ArrayList<>(espBlocks);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  World overlay rendering — "pro" path visualisation
    //
    //  1.21.11 no longer exposes RenderSystem.setShader / enableBlend / disableDepthTest.
    //  Everything goes through RenderLayers + VertexConsumer + VertexRendering.
    //
    //  Techniques used to maximise visual quality within this constraint:
    //    • Multi-layer glow: outer halo → main outline → inner core (3 draw passes per node)
    //    • Simulated thick lines: triple-draw at slight Y-offsets because GPU lineWidth is clamped
    //    • Pulsing animation: sinusoidal cycle on current-step and goal beacons
    //    • Direction chevrons: V-arrows at 55 % along each connecting segment
    //    • Per-type colours, shapes, and glow radii for instant identification
    // ═══════════════════════════════════════════════════════════════════════════
    private void renderPathEsp(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        List<TeleportHop> route = List.copyOf(liveAi ? livePlannedPath : activePath);
        int start = liveAi ? liveStepIndex : currentStepIndex;

        if (route.isEmpty()) {
            List<TeleportHop> preview = livePreviewPath;
            if (!preview.isEmpty()) {
                route = List.copyOf(preview);
                start = 0;
            } else {
                return;
            }
        }

        Vec3d cam = context.worldState().cameraRenderState.pos;
        if (cam == null || context.matrices() == null || context.consumers() == null) return;

        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.linesTranslucent());
        context.matrices().push();
        context.matrices().translate(-cam.x, -cam.y, -cam.z);

        if (start >= route.size()) start = 0;
        double pulse = (Math.sin(System.currentTimeMillis() * 0.004) + 1.0) * 0.5;
        int end = Math.min(route.size(), start + 70);
        Vec3d previousCenter = null;

        for (int i = start; i < end; i++) {
            TeleportHop hop = route.get(i);
            BlockPos p = hop.landing();
            int color = colorForHop(hop.type());
            boolean isCurrent = (i == start);
            double renderY = hop.isWalk() ? 0.0 : 1.0;

            // ── NODE GLOW ──────────────────────────────────────────────
            if (isCurrent) {
                // Layer 1 — pulsing outer halo (white, large, thin)
                double pad = 0.08 + pulse * 0.05;
                net.minecraft.util.shape.VoxelShape halo = VoxelShapes.cuboid(
                    -pad, -0.06, -pad,
                    1.0 + pad, 1.5 + pulse * 0.25, 1.0 + pad);
                VertexRendering.drawOutline(context.matrices(), lines, halo,
                    p.getX(), p.getY() + renderY, p.getZ(), dimColor(0xFFFFFF, 0.55), 1.4F);
                // Layer 2 — main beacon (type colour, thick)
                VertexRendering.drawOutline(context.matrices(), lines, CURRENT_BEACON,
                    p.getX(), p.getY() + renderY, p.getZ(), color, 4.2F);
                // Layer 3 — bright inner core (white, medium)
                VertexRendering.drawOutline(context.matrices(), lines, CURRENT_CORE,
                    p.getX(), p.getY() + renderY, p.getZ(), 0xFFFFFF, 2.6F);
            } else {
                // Layer 1 — outer glow (dimmed colour)
                VertexRendering.drawOutline(context.matrices(), lines, glowShapeForHop(hop.type()),
                    p.getX(), p.getY() + renderY, p.getZ(), dimColor(color, 0.45), 1.3F);
                // Layer 2 — main outline (full colour)
                VertexRendering.drawOutline(context.matrices(), lines, shapeForHop(hop.type()),
                    p.getX(), p.getY() + renderY, p.getZ(), color, 2.8F);
            }

            // ── CONNECTING LINES (triple-draw for thickness) ──────────
            Vec3d center = Vec3d.ofCenter(p).add(0.0, renderY + 0.45, 0.0);
            if (previousCenter != null) {
                float w = isCurrent ? 3.2F : 2.0F;
                // Core line
                drawLine(context, lines, previousCenter, center, color, 230, w);
                // Upper edge (slightly brighter)
                drawLine(context, lines,
                    previousCenter.add(0.0, 0.025, 0.0),
                    center.add(0.0, 0.025, 0.0),
                    color, 180, Math.max(1.0F, w - 0.8F));
                // Lower edge (dimmer glow)
                drawLine(context, lines,
                    previousCenter.add(0.0, -0.025, 0.0),
                    center.add(0.0, -0.025, 0.0),
                    dimColor(color, 0.55), 140, Math.max(1.0F, w - 1.0F));

                // Direction chevron at 55 % along the segment
                Vec3d seg = center.subtract(previousCenter);
                double segLen = Math.sqrt(seg.x * seg.x + seg.y * seg.y + seg.z * seg.z);
                if (segLen > 0.5) {
                    Vec3d d = seg.multiply(1.0 / segLen);
                    Vec3d mid = previousCenter.add(seg.multiply(0.55));
                    Vec3d perp = new Vec3d(-d.z, 0.0, d.x);
                    double a = 0.32;
                    drawLine(context, lines, mid, mid.add(perp.multiply(a)).subtract(d.multiply(a)), 0xFFFFFF, 200, 1.6F);
                    drawLine(context, lines, mid, mid.subtract(perp.multiply(a)).subtract(d.multiply(a)), 0xFFFFFF, 200, 1.6F);
                }
            }
            previousCenter = center;
        }

        // ── PLAYER → FIRST STEP (leader line) ────────────────────────
        if (start < route.size()) {
            Vec3d playerPos = new Vec3d(client.player.getX(), client.player.getY() + 0.5, client.player.getZ());
            TeleportHop firstHop = route.get(start);
            double firstY = firstHop.isWalk() ? 0.0 : 1.0;
            Vec3d firstCenter = Vec3d.ofCenter(firstHop.landing()).add(0.0, firstY + 0.45, 0.0);
            drawLine(context, lines, playerPos, firstCenter, 0xFF4444, 255, 3.8F);
            drawLine(context, lines,
                playerPos.add(0.0, 0.025, 0.0),
                firstCenter.add(0.0, 0.025, 0.0),
                0xFF8888, 180, 2.0F);
        }

        // ── GOAL BEACON (pulsing) ────────────────────────────────────
        if (goal != null) {
            double gp = (Math.sin(System.currentTimeMillis() * 0.003 + 1.0) + 1.0) * 0.5;
            // Pulsing outer halo
            double gPad = 0.1 + gp * 0.08;
            net.minecraft.util.shape.VoxelShape goalHalo = VoxelShapes.cuboid(
                -gPad, -0.1, -gPad, 1.0 + gPad, 1.6 + gp * 0.3, 1.0 + gPad);
            VertexRendering.drawOutline(context.matrices(), lines, goalHalo,
                goal.getX(), goal.getY(), goal.getZ(), dimColor(0x00FF44, 0.5), 1.5F);
            // Ground-level main beacon
            VertexRendering.drawOutline(context.matrices(), lines, GOAL_SHAPE,
                goal.getX(), goal.getY(), goal.getZ(), 0x00FF44, 4.5F);
            // Upper ring
            VertexRendering.drawOutline(context.matrices(), lines, GOAL_SHAPE,
                goal.getX(), goal.getY() + 1, goal.getZ(), 0x00FF44, 2.5F);
        }

        context.matrices().pop();
        ExperimentalPathInsights.renderWorld(context, client);
    }

    private void drawLine(WorldRenderContext context, VertexConsumer lines,
                          Vec3d from, Vec3d to, int color, int alpha, float width) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        var entry = context.matrices().peek();
        lines.vertex(entry, (float) from.x, (float) from.y, (float) from.z)
             .color(r, g, b, alpha).normal(entry, 0.0F, 1.0F, 0.0F).lineWidth(width);
        lines.vertex(entry, (float) to.x, (float) to.y, (float) to.z)
             .color(r, g, b, alpha).normal(entry, 0.0F, 1.0F, 0.0F).lineWidth(width);
    }

    private static int dimColor(int color, double factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private net.minecraft.util.shape.VoxelShape glowShapeForHop(TeleportHop.HopType type) {
        if (type == TeleportHop.HopType.SHIFT) return SHIFT_GLOW_SHAPE;
        if (type == TeleportHop.HopType.WALK) return WALK_GLOW_SHAPE;
        return NORMAL_GLOW_SHAPE;
    }
    private int colorForHop(TeleportHop.HopType type) {
        if (type == TeleportHop.HopType.SHIFT) {
            return 0xFF55FF;   // bright magenta — stands out against sky and terrain
        }
        if (type == TeleportHop.HopType.WALK) {
            return 0xFFFFFF;   // white — clear and neutral
        }
        return 0xFFD700;       // gold — high contrast on any background
    }

    private net.minecraft.util.shape.VoxelShape shapeForHop(TeleportHop.HopType type) {
        if (type == TeleportHop.HopType.SHIFT) {
            return SHIFT_NODE_SHAPE;
        }
        if (type == TeleportHop.HopType.WALK) {
            return WALK_NODE_SHAPE;
        }
        return NORMAL_NODE_SHAPE;
    }
    private String teleportModeLabel(TeleportPathfinder.TeleportMode mode) {
        if (mode == TeleportPathfinder.TeleportMode.SHIFT_ONLY) {
            return "shift-only";
        }
        if (mode == TeleportPathfinder.TeleportMode.JUST_TELEPORT) {
            return "just-teleport";
        }
        return "hybrid-teleport";
    }

    private void clearHighlights(MinecraftClient client) {
        int idBase = 910000;
        for (int i = 0; i < highlightedBlocks.size(); i++) {
            client.worldRenderer.setBlockBreakingInfo(idBase + i, highlightedBlocks.get(i), -1);
        }
        highlightedBlocks = new ArrayList<>();
    }

    private String routePreviewText() {
        List<TeleportHop> route = liveAi ? livePlannedPath : activePath;
        int start = liveAi ? liveStepIndex : currentStepIndex;
        if (route.isEmpty() || start >= route.size()) {
            return "route: none";
        }

        int count = Math.min(3, route.size() - start);
        StringBuilder sb = new StringBuilder("route: ");
        for (int i = 0; i < count; i++) {
            TeleportHop hop = route.get(start + i);
            sb.append(hop.type().name())
                .append("@")
                .append(hop.landing().getX()).append(",")
                .append(hop.landing().getY()).append(",")
                .append(hop.landing().getZ());
            if (i + 1 < count) {
                sb.append(" -> ");
            }
        }
        if (route.size() - start > count) {
            sb.append(" ...");
        }
        return sb.toString();
    }

    private BlockPos calculatePreviewLanding(ClientPlayerEntity player) {
        if (!isHoldingAotv(player.getMainHandStack())) {
            return null;
        }

        if (player.isSneaking()) {
            HitResult result = player.raycast(AotvConfig.ETHERWARP_RANGE, 0.0F, false);
            if (result instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos().up();
                return isSafeLanding(player, pos) ? pos : null;
            }
            return null;
        }

        Vec3d look = player.getRotationVec(1.0F);
        Vec3d target = player.getEyePos().add(look.multiply(AotvConfig.TRANSMISSION_RANGE));
        BlockPos center = BlockPos.ofFloored(target);

        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = center.add(0, dy, 0);
            if (isSafeLanding(player, candidate) && hasLineOfSight(player, candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean hasLineOfSight(ClientPlayerEntity player, BlockPos to) {
        HitResult hit = player.getEntityWorld().raycast(new RaycastContext(
            player.getEyePos(),
            Vec3d.ofCenter(to).add(0.0, 0.62, 0.0),
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));

        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isSafeLanding(ClientPlayerEntity player, BlockPos pos) {
        return player.getEntityWorld().getBlockState(pos).isAir()
            && player.getEntityWorld().getBlockState(pos.up()).isAir()
            && player.getEntityWorld().getBlockState(pos.down()).isSolidBlock(player.getEntityWorld(), pos.down());
    }

    private static boolean isHoldingAotv(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return name.contains("aspect of the void") || name.contains("aspect of the end");
    }

    /**
     * Ensures the AOTV is in the player's main hand, switching hotbar slots if needed.
     * Returns true if AOTV is now in hand and ready to use.
     * Returns false if a slot switch was just issued (wait one tick) or AOTV is not in hotbar.
     */
    private boolean ensureAotvEquipped(MinecraftClient client, ClientPlayerEntity player) {
        if (isHoldingAotv(player.getMainHandStack())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isHoldingAotv(player.getInventory().getStack(i))) {
                player.getInventory().selectedSlot = i;
                return false;  // just switched — wait one tick for the change to apply
            }
        }
        return false;  // AOTV not found in hotbar
    }

    private void lookAtWalkHuman(ClientPlayerEntity player, Vec3d target) {
        float desiredYaw = desiredYaw(player, target);
        float yawDelta = Math.abs(wrapDegrees(desiredYaw - player.getYaw()));
        float yawStep = yawDelta > 35.0F ? 42.0F : WALK_YAW_STEP_DEG;
        float nextYaw = approachAngle(player.getYaw(), desiredYaw, yawStep);
        float nextPitch = approachLinear(player.getPitch(), walkPitchLock, WALK_PITCH_STEP_DEG);
        applyRotation(player, nextYaw, nextPitch);
    }

    private void lookAtTeleportHuman(ClientPlayerEntity player, Vec3d target, boolean fastMode) {
        float desiredYaw = desiredYaw(player, target);
        float desiredPitch = desiredPitch(player, target);

        double targetDist = player.getEyePos().distanceTo(target);
        float farScale = (float) Math.max(0.68, Math.min(1.0, 1.0 - ((targetDist - 8.0) / 34.0)));

        float yawMaxStep = (fastMode ? TELEPORT_YAW_STEP_DEG * 1.15F : TELEPORT_YAW_STEP_DEG) * farScale;
        float pitchMaxStep = (fastMode ? TELEPORT_PITCH_STEP_DEG * 1.15F : TELEPORT_PITCH_STEP_DEG) * farScale;

        float nextYaw = approachAngleEased(player.getYaw(), desiredYaw, yawMaxStep, 0.8F);
        float nextPitch = approachLinearEased(player.getPitch(), desiredPitch, pitchMaxStep, 0.6F);
        applyRotation(player, nextYaw, nextPitch);
    }

    private float desiredYaw(ClientPlayerEntity player, Vec3d target) {
        Vec3d delta = target.subtract(player.getEyePos());
        return (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
    }

    private float desiredPitch(ClientPlayerEntity player, Vec3d target) {
        Vec3d delta = target.subtract(player.getEyePos());
        double xz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        return (float) (-(Math.atan2(delta.y, xz) * (180.0 / Math.PI)));
    }

    private float approachAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        float step = Math.max(-maxStep, Math.min(maxStep, delta));
        return current + step;
    }

    private float approachLinear(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.copySign(maxStep, delta);
    }

    private float approachAngleEased(float current, float target, float maxStep, float minStep) {
        float delta = wrapDegrees(target - current);
        float magnitude = Math.abs(delta);
        if (magnitude < 0.01F) {
            return target;
        }

        // Eased turn with long-turn dampening to prevent hard flicks.
        float longTurnScale = (float) Math.max(0.52, Math.min(1.0, 1.0 - (magnitude / 220.0)));
        float dynamicMax = Math.max(minStep, maxStep * longTurnScale);
        float eased = (float) (Math.sqrt(magnitude) * 1.35F);
        float step = Math.max(minStep, Math.min(dynamicMax, eased));
        if (magnitude <= step) {
            return target;
        }
        return current + Math.copySign(step, delta);
    }

    private float approachLinearEased(float current, float target, float maxStep, float minStep) {
        float delta = target - current;
        float magnitude = Math.abs(delta);
        if (magnitude < 0.01F) {
            return target;
        }

        float eased = (float) (Math.sqrt(magnitude) * 1.4F);
        float step = Math.max(minStep, Math.min(maxStep, eased));
        if (magnitude <= step) {
            return target;
        }
        return current + Math.copySign(step, delta);
    }

    private void applyRotation(ClientPlayerEntity player, float yaw, float pitch) {
        player.setBodyYaw(yaw);
        player.setHeadYaw(yaw);
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private Vec3d saferNormalAimTarget(ClientPlayerEntity player, BlockPos landingAirBlock) {
        BlockPos floor = landingAirBlock.down();
        Vec3d center = Vec3d.ofCenter(floor);
        Vec3d from = player.getEyePos();
        Vec3d horizontal = new Vec3d(center.x - from.x, 0.0, center.z - from.z);
        double len = Math.sqrt(horizontal.x * horizontal.x + horizontal.z * horizontal.z);
        if (len > 0.0001) {
            // Nudge toward near side of the block top to reduce "blocks in the way" edge hits.
            double nudge = 0.22;
            center = center.subtract((horizontal.x / len) * nudge, 0.0, (horizontal.z / len) * nudge);
        }
        return new Vec3d(center.x, floor.getY() + 0.92, center.z);
    }

    private boolean hasServerStyleCastClear(ClientPlayerEntity player, Vec3d target) {
        Vec3d start = player.getEyePos();
        HitResult colliderHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        // OUTLINE catches grass/partial blockers that often fail AOTV server-side.
        HitResult outlineHit = player.getEntityWorld().raycast(new RaycastContext(
            start,
            target,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            player
        ));
        return outlineHit.getType() == HitResult.Type.MISS;
    }

    private void maybeSendClickDebug(ClientPlayerEntity player, String msg, long now) {
        if (now - lastClickChatAtMs < 250L) {
            return;
        }
        lastClickChatAtMs = now;
        sendChat(player, msg);
    }

    private void enforceLiveTargetLock(long now) {
        if (livePlannedPath.isEmpty()) {
            liveLockedStepIndex = -1;
            liveNodeLockUntilMs = 0L;
            return;
        }

        int maxIndex = livePlannedPath.size() - 1;
        if (liveStepIndex < 0 || liveStepIndex > maxIndex) {
            liveStepIndex = Math.max(0, Math.min(liveStepIndex, maxIndex));
        }
        if (liveLockedStepIndex > maxIndex) {
            liveLockedStepIndex = maxIndex;
        }

        if (liveLockedStepIndex >= 0 && now < liveNodeLockUntilMs && liveStepIndex != liveLockedStepIndex) {
            liveStepIndex = liveLockedStepIndex;
            return;
        }
        liveLockedStepIndex = liveStepIndex;
        liveNodeLockUntilMs = now + lockWindowMs();
    }

    private void markStepAdvanced(long now) {
        liveLockedStepIndex = liveStepIndex;
        liveNodeLockUntilMs = now + lockWindowMs();
        lastTargetDistSq = Double.POSITIVE_INFINITY;
        spinStartedAtMs = 0L;
        yawSignFlipCount = 0;
        lastYawDelta = 0.0F;
    }

    private void updateSpinDetector(ClientPlayerEntity player, Vec3d target, long now) {
        float yawDelta = wrapDegrees(desiredYaw(player, target) - player.getYaw());
        int currentSign = yawDelta > 0.2F ? 1 : (yawDelta < -0.2F ? -1 : 0);
        int previousSign = lastYawDelta > 0.2F ? 1 : (lastYawDelta < -0.2F ? -1 : 0);
        if (currentSign != 0 && previousSign != 0 && currentSign != previousSign) {
            if (now - lastYawSignFlipAtMs <= SPIN_WINDOW_MS) {
                yawSignFlipCount++;
            } else {
                yawSignFlipCount = 1;
            }
            lastYawSignFlipAtMs = now;
        }
        lastYawDelta = yawDelta;

        double distSq = player.getEyePos().squaredDistanceTo(target);
        boolean makingProgress = distSq < lastTargetDistSq - 0.08;
        if (makingProgress) {
            spinStartedAtMs = 0L;
            yawSignFlipCount = 0;
        } else if (Math.abs(yawDelta) > 14.0F && yawSignFlipCount >= 3) {
            if (spinStartedAtMs == 0L) {
                spinStartedAtMs = now;
            }
        } else {
            spinStartedAtMs = 0L;
        }
        lastTargetDistSq = distSq;
    }

    private void resetLiveStabilizer() {
        liveNodeLockUntilMs = 0L;
        liveLockedStepIndex = -1;
        spinStartedAtMs = 0L;
        lastYawSignFlipAtMs = 0L;
        yawSignFlipCount = 0;
        lastYawDelta = 0.0F;
        lastTargetDistSq = Double.POSITIVE_INFINITY;
        patchAttemptTimes.clear();
    }

    private long lockWindowMs() {
        return Math.max(120L, settings != null ? settings.commitLockMs() : 300L);
    }

    private boolean tryForwardPatchPrebuilt(ClientPlayerEntity player) {
        if (activePath.isEmpty() || currentStepIndex >= activePath.size()) {
            return false;
        }
        int fromIndex = Math.max(currentStepIndex + 1, prebuiltFurthestStepIndex);

        // Priority 1: jump to any directly cast-clear teleport hop, skipping walk segments.
        int teleportScanMax = Math.min(activePath.size() - 1, fromIndex + 60);
        for (int i = fromIndex; i <= teleportScanMax; i++) {
            TeleportHop candidate = activePath.get(i);
            if (!candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                currentStepIndex = i;
                prebuiltFurthestStepIndex = i;
                return true;
            }
        }

        // Priority 2: proximity-based skip for walk steps only.
        int walkWindow = Math.max(4, settings.walkPatchWindowBlocks());
        int walkMaxIndex = Math.min(activePath.size() - 1, fromIndex + walkWindow);
        for (int i = fromIndex; i <= walkMaxIndex; i++) {
            TeleportHop candidate = activePath.get(i);
            if (candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                currentStepIndex = i;
                prebuiltFurthestStepIndex = i;
                return true;
            }
        }

        return false;
    }

    private boolean tryForwardPatchLive(ClientPlayerEntity player, long now) {
        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size() || patchRateExceeded(now)) {
            return false;
        }
        int fromIndex = Math.max(liveStepIndex + 1, liveFurthestStepIndex);

        // Priority 1: scan generously ahead for any teleport hop that is directly cast-clear
        // from the current position. Walk segments in between are skipped entirely when
        // the player can fire an AOTV cast right now.
        int teleportScanMax = Math.min(livePlannedPath.size() - 1, fromIndex + 60);
        for (int i = fromIndex; i <= teleportScanMax; i++) {
            TeleportHop candidate = livePlannedPath.get(i);
            if (!candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                liveStepIndex = i;
                liveFurthestStepIndex = i;
                liveLockedStepIndex = i;
                liveNodeLockUntilMs = now + lockWindowMs();
                registerPatchAttempt(now);
                maybeSendClickDebug(player, "patch: forward", now);
                return true;
            }
        }

        // Priority 2: proximity-based skip for walk steps only.
        int walkWindow = Math.max(4, settings.walkPatchWindowBlocks());
        int walkMaxIndex = Math.min(livePlannedPath.size() - 1, fromIndex + walkWindow);
        for (int i = fromIndex; i <= walkMaxIndex; i++) {
            TeleportHop candidate = livePlannedPath.get(i);
            if (candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                liveStepIndex = i;
                liveFurthestStepIndex = i;
                liveLockedStepIndex = i;
                liveNodeLockUntilMs = now + lockWindowMs();
                registerPatchAttempt(now);
                return true;
            }
        }
        return false;
    }

    private boolean isStepPatchReachable(ClientPlayerEntity player, TeleportHop hop) {
        if (hop.isWalk()) {
            return player.getBlockPos().isWithinDistance(hop.landing(), Math.max(3.5, settings.walkPatchWindowBlocks()));
        }
        return hasServerStyleCastClear(player, aimTargetForHop(player, hop));
    }

    private void registerPatchAttempt(long now) {
        patchAttemptTimes.addLast(now);
        while (!patchAttemptTimes.isEmpty() && now - patchAttemptTimes.peekFirst() > PATCH_WINDOW_MS) {
            patchAttemptTimes.removeFirst();
        }
    }

    private boolean patchRateExceeded(long now) {
        while (!patchAttemptTimes.isEmpty() && now - patchAttemptTimes.peekFirst() > PATCH_WINDOW_MS) {
            patchAttemptTimes.removeFirst();
        }
        return patchAttemptTimes.size() >= Math.max(1, settings.maxPatchAttemptsPerSecond());
    }

    private KeyBinding registerKey(String idSuffix, int defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.aotvpathfinder." + idSuffix,
            InputUtil.Type.KEYSYM,
            defaultKey,
            KEY_CATEGORY
        ));
    }

    private void sendChat(ClientPlayerEntity player, String msg) {
        player.sendMessage(Text.literal("[AOTV] " + msg), false);
    }
}



















































