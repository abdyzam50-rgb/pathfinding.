package com.abdy2.aotvpathfinder;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

public class AotvPathfinderClient implements ClientModInitializer {
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("aotvpathfinder", "general"));

    private final HypixelManaTracker manaTracker = new HypixelManaTracker();
    private final TeleportPathfinder pathfinder = new TeleportPathfinder();
    private AotvClientSettings settings;

    private KeyMapping setTargetKey;
    private KeyMapping buildPathKey;
    private KeyMapping clearPathKey;
    private KeyMapping assistHopKey;
    private KeyMapping autoRunToggleKey;
    private KeyMapping liveAiToggleKey;

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
    private Vec3 lastAimTarget;
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

    private static final VoxelShape NORMAL_NODE_SHAPE = Shapes.box(0.12, 0.0, 0.12, 0.88, 0.95, 0.88);
    private static final VoxelShape SHIFT_NODE_SHAPE  = Shapes.box(0.08, 0.0, 0.08, 0.92, 0.72, 0.92);
    private static final VoxelShape WALK_NODE_SHAPE   = Shapes.box(0.28, 0.0, 0.28, 0.72, 0.28, 0.72);
    private static final VoxelShape NORMAL_GLOW_SHAPE = Shapes.box(0.04, -0.08, 0.04, 0.96, 1.03, 0.96);
    private static final VoxelShape SHIFT_GLOW_SHAPE  = Shapes.box(0.0,  -0.08, 0.0,  1.0,  0.80, 1.0);
    private static final VoxelShape WALK_GLOW_SHAPE   = Shapes.box(0.20, -0.05, 0.20, 0.80, 0.35, 0.80);
    private static final VoxelShape CURRENT_BEACON    = Shapes.box(0.05, 0.0, 0.05, 0.95, 1.4, 0.95);
    private static final VoxelShape CURRENT_CORE      = Shapes.box(0.20, 0.08, 0.20, 0.80, 1.20, 0.80);
    private static final VoxelShape GOAL_SHAPE        = Shapes.box(0.0, 0.0, 0.0, 1.0, 1.5, 1.0);

    @Override
    public void onInitializeClient() {
        setTargetKey = registerKey("set_target", GLFW.GLFW_KEY_J);
        buildPathKey = registerKey("build_path", GLFW.GLFW_KEY_K);
        clearPathKey = registerKey("clear_path", GLFW.GLFW_KEY_L);
        assistHopKey = registerKey("assist_next_hop", GLFW.GLFW_KEY_SEMICOLON);
        autoRunToggleKey = registerKey("toggle_auto", GLFW.GLFW_KEY_APOSTROPHE);
        liveAiToggleKey = registerKey("toggle_live_ai", GLFW.GLFW_KEY_O);

        settings = AotvClientSettings.load(Minecraft.getInstance().gameDirectory.toPath());

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> manaTracker.acceptActionBar(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(this::renderPathEsp);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aotvpathfinder", "status"),
            (gui, delta) -> renderTopRightStatus(gui, delta));

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
                    .then(literal("show").executes(ctx -> showSettingsCommand()))
            );
        });
    }

    private int executeSetGoal(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        BlockPos target = goal;
        if (target == null && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            target = hit.getBlockPos().above();
        }

        if (target == null) {
            sendChat(client.player, "No preview target. Use /setgoal x y z, /preview x y z, or look at a block.");
            return 0;
        }

        return buildPreviewPath(client, target);
    }

    private int executePreviewCoords(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        return buildPreviewPath(client, new BlockPos(x, y, z));
    }

    private int executePreviewClear(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
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
        sendChat(client.player, "Preview cleared.");
        return 1;
    }

    private int buildPreviewPath(Minecraft client, BlockPos target) {
        LocalPlayer player = client.player;
        List<TeleportHop> path;
        try {
            path = pathfinder.findPath(player, player.blockPosition(), target, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        } catch (Exception e) {
            sendChat(player, "Pathfinder error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
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
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setMovementMode(mode);
        sendChat(client.player, "Path mode: " + mode.name().toLowerCase(Locale.ROOT));
        return 1;
    }

    private int setTeleportModeCommand(TeleportPathfinder.TeleportMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setTeleportMode(mode);
        sendChat(client.player, "Teleport mode: " + teleportModeLabel(mode));
        return 1;
    }

    private int setAirChainCommand(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setAirChainEnabled(enabled);
        sendChat(client.player, "Air-chain: " + (enabled ? "on" : "off"));
        return 1;
    }

    private int showSettingsCommand() {
        Minecraft client = Minecraft.getInstance();
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

    private void onClientTick(Minecraft client) {
        if (client.player == null || client.level == null) {
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

    private void handleKeys(Minecraft client) {
        while (setTargetKey.consumeClick()) {
            setGoalFromCrosshair(client);
        }

        while (buildPathKey.consumeClick()) {
            buildPath(client);
        }

        while (clearPathKey.consumeClick()) {
            activePath = new ArrayList<>();
            livePreviewPath = new ArrayList<>();
            livePlannedPath = new ArrayList<>();
            currentStepIndex = 0;
            liveStepIndex = 0;
            liveGoal = null;
            resetLiveStabilizer();
            stopWalking(client);
            clearHighlights(client);
            sendChat(client.player, "Path cleared.");
        }

        while (assistHopKey.consumeClick()) {
            assistNextStep(client.player);
        }

        while (autoRunToggleKey.consumeClick()) {
            autoRun = !autoRun;
            if (autoRun) {
                liveAi = false;
            }
            sendChat(client.player, "Auto route " + (autoRun ? "enabled" : "disabled") + ".");
        }

        while (liveAiToggleKey.consumeClick()) {
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

    private void setGoalFromCrosshair(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            sendChat(client.player, "No block targeted.");
            return;
        }

        goal = hit.getBlockPos().above();
        sendChat(client.player, String.format("Goal set: %d %d %d", goal.getX(), goal.getY(), goal.getZ()));
    }

    private void buildPath(Minecraft client) {
        LocalPlayer player = client.player;
        if (goal == null) {
            sendChat(player, "Set a goal first (J or /setgoal x y z).");
            return;
        }

        List<TeleportHop> path = pathfinder.findPath(player, player.blockPosition(), goal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        if (path.isEmpty()) {
            sendChat(player, "No path found.");
            return;
        }

        activePath = path;
        currentStepIndex = 0;
        int manaCost = path.stream().mapToInt(TeleportHop::manaCost).sum();
        sendChat(player, "Path built: " + path.size() + " steps, est mana " + manaCost + ".");
    }

    private void assistNextStep(LocalPlayer player) {
        if (currentStepIndex >= activePath.size()) {
            sendChat(player, "No remaining steps.");
            return;
        }

        TeleportHop step = activePath.get(currentStepIndex);
        lookAtTeleportHuman(player, aimTargetForHop(player, step), false);
        player.setShiftKeyDown(step.requiresShift());
        sendChat(player, "Aimed at step " + (currentStepIndex + 1) + "/" + activePath.size() + " [" + step.type() + "]");
    }

    private boolean isStepReached(LocalPlayer player, TeleportHop step) {
        if (step.isWalk()) {
            return player.blockPosition().closerThan(step.landing(), 1.2);
        }
        return player.blockPosition().closerThan(step.landing(), 2.25);
    }

    private void runPrebuiltRoute(Minecraft client) {
        LocalPlayer player = client.player;
        if (currentStepIndex >= activePath.size()) {
            stopWalking(client);
            return;
        }

        TeleportHop step = activePath.get(currentStepIndex);
        prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);
        boolean fallingPastStep = !player.onGround() && player.getDeltaMovement().y < -0.08;
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
        if (now - lastCastAtMs < castCooldownMs(step) || client.gameMode == null) {
            return;
        }

        Vec3 stepTarget = aimTargetForHop(player, step);
        if (!hasServerStyleCastClear(player, stepTarget)) {
            if (tryWalkAroundBlocked(player, now, false)) {
                return;
            }
            currentStepIndex++;
            return;
        }
        if (!aimAtAndReady(player, stepTarget, now, useFastAirChainTiming(step))) {
            return;
        }
        player.setShiftKeyDown(step.requiresShift());
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        lastCastAtMs = now;
        castDebugCount++;
        maybeSendClickDebug(player, "CLICK #" + castDebugCount + " [" + step.type().name().toLowerCase(Locale.ROOT) + "] prebuilt", now);
    }

    private void runLiveAi(Minecraft client) {
        LocalPlayer player = client.player;

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
        if (player.blockPosition().closerThan(dynamicGoal, AotvConfig.GOAL_REACHED_RADIUS)) {
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
            boolean fallingPastStep = !player.onGround() && player.getDeltaMovement().y < -0.08;
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
            boolean walkHandoffFalling = step.isWalk() && !player.onGround();
            if (!walkHandoffFalling) {
                if (step.isWalk()) {
                    followFailed = !player.blockPosition().closerThan(step.landing(), 18.0)
                        || now - liveLastAdvanceAtMs > 5000L;
                    if (followFailed) {
                        replanReason = "stuck_walk";
                    }
                } else {
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
            List<TeleportHop> path = pathfinder.findPath(player, player.blockPosition(), planGoal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
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
        if (now - lastCastAtMs < castCooldownMs(next) || client.gameMode == null) {
            return;
        }

        Vec3 nextTarget = aimTargetForHop(player, next);
        updateSpinDetector(player, nextTarget, now);
        if (!hasServerStyleCastClear(player, nextTarget)) {
            boolean switched = tryLocalBlockedRayFallback(player, now);
            if (switched) {
                return;
            }
            boolean walked = tryWalkAroundBlocked(player, now, true);
            if (walked) {
                sendChat(player, "patch: walk_around");
                return;
            }
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
        player.setShiftKeyDown(next.requiresShift());
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        lastCastAtMs = now;
        castDebugCount++;
        maybeSendClickDebug(player, "CLICK #" + castDebugCount + " [" + next.type().name().toLowerCase(Locale.ROOT) + "] live", now);
    }

    private Vec3 aimTargetForHop(LocalPlayer player, TeleportHop hop) {
        if (hop.type() == TeleportHop.HopType.SHIFT) {
            return Vec3.atCenterOf(hop.landing().below());
        }

        if (settings.teleportMode() == TeleportPathfinder.TeleportMode.JUST_TELEPORT && hop.type() == TeleportHop.HopType.NORMAL) {
            BlockPos above = hop.landing().above();
            if (player.level().getBlockState(above).isAir()) {
                return Vec3.atCenterOf(above).add(0.0, 0.62, 0.0);
            }
        }

        return saferNormalAimTarget(player, hop.landing());
    }

    private long castCooldownMs(TeleportHop hop) {
        return useFastAirChainTiming(hop) ? 35L : 280L;
    }

    private boolean useFastAirChainTiming(TeleportHop hop) {
        return settings.airChainEnabled() && hop.type() == TeleportHop.HopType.NORMAL;
    }

    private boolean aimAtAndReady(LocalPlayer player, Vec3 target, long now, boolean fastMode) {
        lookAtTeleportHuman(player, target, fastMode);

        if (lastAimTarget == null || lastAimTarget.distanceToSqr(target) > 0.04) {
            lastAimTarget = target;
            aimStableSinceMs = now;
            if (!fastMode) {
                return false;
            }
        }

        Vec3 delta = target.subtract(player.getEyePosition());
        double xz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float desiredYaw = (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
        float desiredPitch = (float) (-(Math.atan2(delta.y, xz) * (180.0 / Math.PI)));

        float yawError = Math.abs(wrapDegrees(desiredYaw - player.getYRot()));
        float pitchError = Math.abs(desiredPitch - player.getXRot());
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

    private boolean walkToStep(Minecraft client, LocalPlayer player, TeleportHop step) {
        Vec3 target = Vec3.atCenterOf(step.landing()).add(0.0, 0.62, 0.0);
        lookAtWalkHuman(player, target);

        Vec3 here = new Vec3(player.getX(), player.getY(), player.getZ());
        double dist = here.distanceTo(target);
        if (dist < 1.15) {
            stopWalking(client);
            return true;
        }

        if (client.options != null) {
            client.options.keyUp.setDown(true);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keyJump.setDown(false);

            boolean inWater = player.level().getBlockState(player.blockPosition()).getFluidState().is(FluidTags.WATER)
                || player.level().getBlockState(player.blockPosition().above()).getFluidState().is(FluidTags.WATER);
            if (inWater) {
                boolean targetHigher = step.landing().getY() >= player.getY() - 0.05;
                client.options.keyJump.setDown(targetHigher);
                return false;
            }

            double hereFloor = floorTopY(player, player.blockPosition());
            double nextFloor = floorTopY(player, step.landing().below());
            double floorDelta = nextFloor - hereFloor;
            boolean uphillStep = floorDelta > 0.78;
            var aheadDir = player.getDirection();
            BlockPos ahead = player.blockPosition().relative(aheadDir);
            BlockState aheadState = player.level().getBlockState(ahead);
            boolean stepLikeAhead = aheadState.getBlock() instanceof SlabBlock || aheadState.getBlock() instanceof StairBlock;
            boolean oneBlockObstacleAhead = !stepLikeAhead
                && aheadState.isSolid()
                && player.level().getBlockState(ahead.above()).isAir();

            int cliffDropAhead = dropDistanceToFloor(player, ahead, 24);
            boolean cliffAhead = cliffDropAhead > 3;
            if (cliffAhead) {
                client.options.keyUp.setDown(false);
                client.options.keyShift.setDown(true);
                client.options.keyJump.setDown(false);
                return false;
            }

            boolean shouldJump = (uphillStep || oneBlockObstacleAhead) && dist < 2.35 && floorDelta >= 0.78;
            client.options.keyJump.setDown(shouldJump);
            if (shouldJump && player.onGround()) {
                player.jumpFromGround();
            }
        }

        return false;
    }

    private int dropDistanceToFloor(LocalPlayer player, BlockPos pos, int maxDrop) {
        BlockPos cursor = pos;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (player.level().getBlockState(cursor.below()).isSolid()) {
                return drop;
            }
            cursor = cursor.below();
        }
        return maxDrop + 1;
    }

    private boolean tryLocalBlockedRayFallback(LocalPlayer player, long now) {
        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size()) {
            return false;
        }
        int maxCheck = Math.min(livePlannedPath.size() - 1, liveStepIndex + Math.max(1, settings.teleportPatchLookaheadNodes()));
        float currentYaw = player.getYRot();
        int bestIndex = -1;
        float bestYawDelta = Float.MAX_VALUE;
        for (int i = liveStepIndex + 1; i <= maxCheck; i++) {
            TeleportHop alt = livePlannedPath.get(i);
            if (alt.type() != TeleportHop.HopType.NORMAL && alt.type() != TeleportHop.HopType.SHIFT) {
                continue;
            }
            Vec3 altTarget = aimTargetForHop(player, alt);
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

    private boolean tryWalkAroundBlocked(LocalPlayer player, long now, boolean isLive) {
        List<TeleportHop> path = isLive ? livePlannedPath : activePath;
        int idx = isLive ? liveStepIndex : currentStepIndex;
        if (path.isEmpty() || idx >= path.size()) return false;
        TeleportHop blocked = path.get(idx);
        if (blocked.isWalk()) return false;

        Vec3 target = aimTargetForHop(player, blocked);
        BlockPos playerPos = player.blockPosition();

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int dist = 1; dist <= 2; dist++) {
            for (int[] d : dirs) {
                BlockPos lateral = playerPos.offset(d[0] * dist, 0, d[1] * dist);
                if (lateral.equals(playerPos)) continue;
                BlockState feet = player.level().getBlockState(lateral);
                BlockState head = player.level().getBlockState(lateral.above());
                BlockState below = player.level().getBlockState(lateral.below());
                if (!feet.getCollisionShape(player.level(), lateral).isEmpty()) continue;
                if (!head.getCollisionShape(player.level(), lateral.above()).isEmpty()) continue;
                if (!below.isSolid()) continue;

                Vec3 lateralEye = new Vec3(lateral.getX() + 0.5, lateral.getY() + 1.62, lateral.getZ() + 0.5);
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

    private boolean isRayClearFromPosition(LocalPlayer player, Vec3 from, Vec3 to) {
        HitResult colliderHit = player.level().clip(new ClipContext(
            from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) return false;

        HitResult outlineHit = player.level().clip(new ClipContext(
            from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
        ));
        if (outlineHit.getType() != HitResult.Type.MISS) {
            double hitDistSq = outlineHit.getLocation().distanceToSqr(from);
            if (hitDistSq > 2.25) return false;
        }
        return true;
    }

    private double floorTopY(LocalPlayer player, BlockPos floorPos) {
        BlockState below = player.level().getBlockState(floorPos);
        var shape = below.getCollisionShape(player.level(), floorPos);
        if (shape.isEmpty()) {
            return floorPos.getY();
        }
        return floorPos.getY() + shape.max(Direction.Axis.Y);
    }

    private void stopWalking(Minecraft client) {
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private BlockPos resolveDynamicGoal(Minecraft client) {
        if (goal != null) {
            return goal;
        }
        if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos().above();
        }
        return null;
    }

    private void renderTopRightStatus(GuiGraphicsExtractor gui, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        Font tr = client.font;
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
        int xRight = gui.guiWidth() - 8;
        int y = 8;
        for (String line : lines) {
            int x = xRight - tr.width(line);
            gui.text(tr, line, x, y, 0xEDEDED);
            y += 10;
        }
    }

    private void updateRouteHighlights(Minecraft client) {
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
            BlockPos base = landing.below();
            espBlocks.add(base);
            espBlocks.add(base.above());
            espBlocks.add(base.north());
            espBlocks.add(base.south());
            espBlocks.add(base.east());
            espBlocks.add(base.west());
        }

        int idBase = 910000;
        int idx = 0;
        for (BlockPos pos : espBlocks) {
            int crackStage = idx < 14 ? 9 : 7;
            client.levelRenderer.destroyBlockProgress(idBase + idx, pos, crackStage);
            idx++;
            if (idx >= 350) {
                break;
            }
        }

        highlightedBlocks = new ArrayList<>(espBlocks);
    }

    private void renderPathEsp(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

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

        Vec3 cam = context.gameRenderer().getMainCamera().position();
        if (cam == null || context.poseStack() == null || context.bufferSource() == null) return;

        VertexConsumer lines = context.bufferSource().getBuffer(RenderTypes.lines());
        context.poseStack().pushPose();
        context.poseStack().translate(-cam.x, -cam.y, -cam.z);

        if (start >= route.size()) start = 0;
        double pulse = (Math.sin(System.currentTimeMillis() * 0.004) + 1.0) * 0.5;
        int end = Math.min(route.size(), start + 70);
        Vec3 previousCenter = null;

        for (int i = start; i < end; i++) {
            TeleportHop hop = route.get(i);
            BlockPos p = hop.landing();
            int color = colorForHop(hop.type());
            boolean isCurrent = (i == start);
            double renderY = hop.isWalk() ? 0.0 : 1.0;

            if (isCurrent) {
                double pad = 0.08 + pulse * 0.05;
                VoxelShape halo = Shapes.box(
                    -pad, -0.06, -pad,
                    1.0 + pad, 1.5 + pulse * 0.25, 1.0 + pad);
                ShapeRenderer.renderShape(context.poseStack(), lines, halo,
                    p.getX(), p.getY() + renderY, p.getZ(), dimColor(0xFFFFFF, 0.55), 1.4F);
                ShapeRenderer.renderShape(context.poseStack(), lines, CURRENT_BEACON,
                    p.getX(), p.getY() + renderY, p.getZ(), color, 4.2F);
                ShapeRenderer.renderShape(context.poseStack(), lines, CURRENT_CORE,
                    p.getX(), p.getY() + renderY, p.getZ(), 0xFFFFFF, 2.6F);
            } else {
                ShapeRenderer.renderShape(context.poseStack(), lines, glowShapeForHop(hop.type()),
                    p.getX(), p.getY() + renderY, p.getZ(), dimColor(color, 0.45), 1.3F);
                ShapeRenderer.renderShape(context.poseStack(), lines, shapeForHop(hop.type()),
                    p.getX(), p.getY() + renderY, p.getZ(), color, 2.8F);
            }

            Vec3 center = Vec3.atCenterOf(p).add(0.0, renderY + 0.45, 0.0);
            if (previousCenter != null) {
                float w = isCurrent ? 3.2F : 2.0F;
                drawLine(context, lines, previousCenter, center, color, 230, w);
                drawLine(context, lines,
                    previousCenter.add(0.0, 0.025, 0.0),
                    center.add(0.0, 0.025, 0.0),
                    color, 180, Math.max(1.0F, w - 0.8F));
                drawLine(context, lines,
                    previousCenter.add(0.0, -0.025, 0.0),
                    center.add(0.0, -0.025, 0.0),
                    dimColor(color, 0.55), 140, Math.max(1.0F, w - 1.0F));

                Vec3 seg = center.subtract(previousCenter);
                double segLen = Math.sqrt(seg.x * seg.x + seg.y * seg.y + seg.z * seg.z);
                if (segLen > 0.5) {
                    Vec3 d = seg.scale(1.0 / segLen);
                    Vec3 mid = previousCenter.add(seg.scale(0.55));
                    Vec3 perp = new Vec3(-d.z, 0.0, d.x);
                    double a = 0.32;
                    drawLine(context, lines, mid, mid.add(perp.scale(a)).subtract(d.scale(a)), 0xFFFFFF, 200, 1.6F);
                    drawLine(context, lines, mid, mid.subtract(perp.scale(a)).subtract(d.scale(a)), 0xFFFFFF, 200, 1.6F);
                }
            }
            previousCenter = center;
        }

        if (start < route.size()) {
            Vec3 playerPos = new Vec3(client.player.getX(), client.player.getY() + 0.5, client.player.getZ());
            TeleportHop firstHop = route.get(start);
            double firstY = firstHop.isWalk() ? 0.0 : 1.0;
            Vec3 firstCenter = Vec3.atCenterOf(firstHop.landing()).add(0.0, firstY + 0.45, 0.0);
            drawLine(context, lines, playerPos, firstCenter, 0xFF4444, 255, 3.8F);
            drawLine(context, lines,
                playerPos.add(0.0, 0.025, 0.0),
                firstCenter.add(0.0, 0.025, 0.0),
                0xFF8888, 180, 2.0F);
        }

        if (goal != null) {
            double gp = (Math.sin(System.currentTimeMillis() * 0.003 + 1.0) + 1.0) * 0.5;
            double gPad = 0.1 + gp * 0.08;
            VoxelShape goalHalo = Shapes.box(
                -gPad, -0.1, -gPad, 1.0 + gPad, 1.6 + gp * 0.3, 1.0 + gPad);
            ShapeRenderer.renderShape(context.poseStack(), lines, goalHalo,
                goal.getX(), goal.getY(), goal.getZ(), dimColor(0x00FF44, 0.5), 1.5F);
            ShapeRenderer.renderShape(context.poseStack(), lines, GOAL_SHAPE,
                goal.getX(), goal.getY(), goal.getZ(), 0x00FF44, 4.5F);
            ShapeRenderer.renderShape(context.poseStack(), lines, GOAL_SHAPE,
                goal.getX(), goal.getY() + 1, goal.getZ(), 0x00FF44, 2.5F);
        }

        context.poseStack().popPose();
    }

    private void drawLine(LevelRenderContext context, VertexConsumer lines,
                          Vec3 from, Vec3 to, int color, int alpha, float width) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        var entry = context.poseStack().last();
        lines.addVertex(entry, (float) from.x, (float) from.y, (float) from.z)
             .setColor(r, g, b, alpha).setNormal(entry, 0.0F, 1.0F, 0.0F);
        lines.addVertex(entry, (float) to.x, (float) to.y, (float) to.z)
             .setColor(r, g, b, alpha).setNormal(entry, 0.0F, 1.0F, 0.0F);
    }

    private static int dimColor(int color, double factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private VoxelShape glowShapeForHop(TeleportHop.HopType type) {
        if (type == TeleportHop.HopType.SHIFT) return SHIFT_GLOW_SHAPE;
        if (type == TeleportHop.HopType.WALK) return WALK_GLOW_SHAPE;
        return NORMAL_GLOW_SHAPE;
    }

    private int colorForHop(TeleportHop.HopType type) {
        if (type == TeleportHop.HopType.SHIFT) {
            return 0xFF55FF;
        }
        if (type == TeleportHop.HopType.WALK) {
            return 0xFFFFFF;
        }
        return 0xFFD700;
    }

    private VoxelShape shapeForHop(TeleportHop.HopType type) {
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

    private void clearHighlights(Minecraft client) {
        int idBase = 910000;
        for (int i = 0; i < highlightedBlocks.size(); i++) {
            client.levelRenderer.destroyBlockProgress(idBase + i, highlightedBlocks.get(i), -1);
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

    private BlockPos calculatePreviewLanding(LocalPlayer player) {
        if (!isHoldingAotv(player.getMainHandItem())) {
            return null;
        }

        if (player.isShiftKeyDown()) {
            HitResult result = player.pick(AotvConfig.ETHERWARP_RANGE, 0.0F, false);
            if (result instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos().above();
                return isSafeLanding(player, pos) ? pos : null;
            }
            return null;
        }

        Vec3 look = player.getViewVector(1.0F);
        Vec3 target = player.getEyePosition().add(look.scale(AotvConfig.TRANSMISSION_RANGE));
        BlockPos center = BlockPos.containing(target);

        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = center.offset(0, dy, 0);
            if (isSafeLanding(player, candidate) && hasLineOfSight(player, candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private boolean hasLineOfSight(LocalPlayer player, BlockPos to) {
        HitResult hit = player.level().clip(new ClipContext(
            player.getEyePosition(),
            Vec3.atCenterOf(to).add(0.0, 0.62, 0.0),
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            player
        ));

        return hit.getType() == HitResult.Type.MISS;
    }

    private boolean isSafeLanding(LocalPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos).isAir()
            && player.level().getBlockState(pos.above()).isAir()
            && player.level().getBlockState(pos.below()).isSolid();
    }

    private static boolean isHoldingAotv(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("aspect of the void") || name.contains("aspect of the end");
    }

    private boolean ensureAotvEquipped(Minecraft client, LocalPlayer player) {
        if (isHoldingAotv(player.getMainHandItem())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isHoldingAotv(player.getInventory().getItem(i))) {
                player.getInventory().setSelectedSlot(i);
                return false;
            }
        }
        return false;
    }

    private void lookAtWalkHuman(LocalPlayer player, Vec3 target) {
        float desiredYaw = desiredYaw(player, target);
        float yawDelta = Math.abs(wrapDegrees(desiredYaw - player.getYRot()));
        float yawStep = yawDelta > 35.0F ? 42.0F : WALK_YAW_STEP_DEG;
        float nextYaw = approachAngle(player.getYRot(), desiredYaw, yawStep);
        float nextPitch = approachLinear(player.getXRot(), walkPitchLock, WALK_PITCH_STEP_DEG);
        applyRotation(player, nextYaw, nextPitch);
    }

    private void lookAtTeleportHuman(LocalPlayer player, Vec3 target, boolean fastMode) {
        float desiredYaw = desiredYaw(player, target);
        float desiredPitch = desiredPitch(player, target);

        double targetDist = player.getEyePosition().distanceTo(target);
        float farScale = (float) Math.max(0.68, Math.min(1.0, 1.0 - ((targetDist - 8.0) / 34.0)));

        float yawMaxStep = (fastMode ? TELEPORT_YAW_STEP_DEG * 1.15F : TELEPORT_YAW_STEP_DEG) * farScale;
        float pitchMaxStep = (fastMode ? TELEPORT_PITCH_STEP_DEG * 1.15F : TELEPORT_PITCH_STEP_DEG) * farScale;

        float nextYaw = approachAngleEased(player.getYRot(), desiredYaw, yawMaxStep, 0.8F);
        float nextPitch = approachLinearEased(player.getXRot(), desiredPitch, pitchMaxStep, 0.6F);
        applyRotation(player, nextYaw, nextPitch);
    }

    private float desiredYaw(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        return (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
    }

    private float desiredPitch(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
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

    private void applyRotation(LocalPlayer player, float yaw, float pitch) {
        player.setYBodyRot(yaw);
        player.setYHeadRot(yaw);
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    private Vec3 saferNormalAimTarget(LocalPlayer player, BlockPos landingAirBlock) {
        BlockPos floor = landingAirBlock.below();
        Vec3 center = Vec3.atCenterOf(floor);
        Vec3 from = player.getEyePosition();
        Vec3 horizontal = new Vec3(center.x - from.x, 0.0, center.z - from.z);
        double len = Math.sqrt(horizontal.x * horizontal.x + horizontal.z * horizontal.z);
        if (len > 0.0001) {
            double nudge = 0.22;
            center = center.subtract((horizontal.x / len) * nudge, 0.0, (horizontal.z / len) * nudge);
        }
        return new Vec3(center.x, floor.getY() + 0.92, center.z);
    }

    private boolean hasServerStyleCastClear(LocalPlayer player, Vec3 target) {
        Vec3 start = player.getEyePosition();
        HitResult colliderHit = player.level().clip(new ClipContext(
            start, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player
        ));
        if (colliderHit.getType() != HitResult.Type.MISS) {
            return false;
        }

        HitResult outlineHit = player.level().clip(new ClipContext(
            start, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
        ));
        return outlineHit.getType() == HitResult.Type.MISS;
    }

    private void maybeSendClickDebug(LocalPlayer player, String msg, long now) {
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

    private void updateSpinDetector(LocalPlayer player, Vec3 target, long now) {
        float yawDelta = wrapDegrees(desiredYaw(player, target) - player.getYRot());
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

        double distSq = player.getEyePosition().distanceToSqr(target);
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

    private boolean tryForwardPatchPrebuilt(LocalPlayer player) {
        if (activePath.isEmpty() || currentStepIndex >= activePath.size()) {
            return false;
        }
        int fromIndex = Math.max(currentStepIndex + 1, prebuiltFurthestStepIndex);

        int teleportScanMax = Math.min(activePath.size() - 1, fromIndex + 60);
        for (int i = fromIndex; i <= teleportScanMax; i++) {
            TeleportHop candidate = activePath.get(i);
            if (!candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                currentStepIndex = i;
                prebuiltFurthestStepIndex = i;
                return true;
            }
        }

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

    private boolean tryForwardPatchLive(LocalPlayer player, long now) {
        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size() || patchRateExceeded(now)) {
            return false;
        }
        int fromIndex = Math.max(liveStepIndex + 1, liveFurthestStepIndex);

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

    private boolean isStepPatchReachable(LocalPlayer player, TeleportHop hop) {
        if (hop.isWalk()) {
            return player.blockPosition().closerThan(hop.landing(), Math.max(3.5, settings.walkPatchWindowBlocks()));
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

    private KeyMapping registerKey(String idSuffix, int defaultKey) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.aotvpathfinder." + idSuffix,
            defaultKey,
            KEY_CATEGORY
        ));
    }

    private void sendChat(LocalPlayer player, String msg) {
        player.sendSystemMessage(Component.literal("[AOTV] " + msg));
    }
}
