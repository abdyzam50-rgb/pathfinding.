package com.abdy2.aotvpathfinder;

import com.abdy2.aotvpathfinder.command.PathfinderCommands;

import com.abdy2.aotvpathfinder.execute.AimController;
import com.abdy2.aotvpathfinder.execute.CastController;
import com.abdy2.aotvpathfinder.execute.MovementController;
import com.abdy2.aotvpathfinder.execute.Rotation;

import com.abdy2.aotvpathfinder.render.PathRenderer;

import com.abdy2.aotvpathfinder.config.PathfinderSettings;
import com.abdy2.aotvpathfinder.diag.FaultLog;
import com.abdy2.aotvpathfinder.diag.ManaTracker;
import com.abdy2.aotvpathfinder.path.PathBuilder;

import com.abdy2.aotvpathfinder.ability.CastRules;
import com.abdy2.aotvpathfinder.path.HopType;
import com.abdy2.aotvpathfinder.path.PathHop;


import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

public class AotvPathfinderClient implements ClientModInitializer, PathRenderer.RouteView, PathfinderCommands.Actions {
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("aotvpathfinder", "general"));

    private final ManaTracker manaTracker = new ManaTracker();
    private final PathBuilder pathfinder = new PathBuilder();
    private FaultLog faultLog;
    private final PathRenderer renderer = new PathRenderer(this);
    private final AimController aim = new AimController();
    private final MovementController movement = new MovementController(aim);
    private PathfinderSettings settings;
    private final CastController cast = new CastController(() -> settings);

    private KeyMapping setTargetKey;
    private KeyMapping buildPathKey;
    private KeyMapping clearPathKey;
    private KeyMapping assistHopKey;
    private KeyMapping autoRunToggleKey;
    private KeyMapping liveAiToggleKey;

    private BlockPos goal;
    private volatile List<PathHop> activePath = new ArrayList<>();
    private volatile List<PathHop> livePreviewPath = new ArrayList<>();
    private volatile List<PathHop> livePlannedPath = new ArrayList<>();
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
    private long lastClickChatAtMs;

    private static final long PATCH_WINDOW_MS = 1000L;
    private final ArrayDeque<Long> patchAttemptTimes = new ArrayDeque<>();
    private int prebuiltFurthestStepIndex;
    private int liveFurthestStepIndex;

    /** How far vertically a walk node may sit and still be patch-reachable on foot. */
    private static final double WALK_PATCH_MAX_VERTICAL = 2.5;

    // --- failure detection / recovery ---
    /** No measurable progress toward the current node for this long counts as stuck. */
    private static final long STUCK_TIMEOUT_MS = 2500L;
    /** Minimum gap between rebuild attempts, so a hard failure cannot spin the pathfinder. */
    private static final long REBUILD_COOLDOWN_MS = 600L;
    /** Consecutive rebuilds with no progress in between before the run gives up. */
    private static final int MAX_CONSECUTIVE_REBUILDS = 4;
    /** Being this far from the current node means we were knocked off the route entirely. */
    private static final double OFF_ROUTE_DISTANCE = 7.0;
    /** Distance improvement that counts as real progress rather than jitter. */
    private static final double PROGRESS_EPSILON = 0.05;

    private long lastProgressAtMs;
    private double bestDistToNodeSq = Double.POSITIVE_INFINITY;
    private int trackedStepIndex = -1;
    private int rebuildAttempts;
    private long lastRebuildAtMs;


    @Override
    public void onInitializeClient() {
        setTargetKey = registerKey("set_target", GLFW.GLFW_KEY_J);
        buildPathKey = registerKey("build_path", GLFW.GLFW_KEY_K);
        clearPathKey = registerKey("clear_path", GLFW.GLFW_KEY_L);
        assistHopKey = registerKey("assist_next_hop", GLFW.GLFW_KEY_SEMICOLON);
        autoRunToggleKey = registerKey("toggle_auto", GLFW.GLFW_KEY_APOSTROPHE);
        liveAiToggleKey = registerKey("toggle_live_ai", GLFW.GLFW_KEY_O);

        settings = PathfinderSettings.load(Minecraft.getInstance().gameDirectory.toPath());
        faultLog = FaultLog.create(Minecraft.getInstance().gameDirectory.toPath());

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> manaTracker.acceptActionBar(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(renderer::renderPathEsp);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("aotvpathfinder", "status"),
            (gui, delta) -> renderer.renderHud(gui, delta));

        PathfinderCommands.register(this);
    }

    public int setGoal(CommandContext<?> context) {
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

    public int previewToGoal(CommandContext<?> context) {
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

    public int previewToCoords(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        int x = IntegerArgumentType.getInteger(context, "x");
        int y = IntegerArgumentType.getInteger(context, "y");
        int z = IntegerArgumentType.getInteger(context, "z");
        return buildPreviewPath(client, new BlockPos(x, y, z));
    }

    public int clearPreview(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }

        boolean keepGoal = true;
        resetRunState(client, !keepGoal);
        sendChat(client.player, "Preview cleared.");
        return 1;
    }

    /** Prints the most recent faults, newest last, plus where the full log lives. */
    public int showFaults(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || faultLog == null) {
            return 0;
        }
        List<String> lines = faultLog.recent(8);
        if (lines.isEmpty()) {
            sendChat(client.player, "No faults recorded this session.");
            return 1;
        }
        sendChat(client.player, "Last " + lines.size() + " of " + faultLog.faultCount() + " faults:");
        for (String line : lines) {
            client.player.sendSystemMessage(Component.literal(line));
        }
        sendChat(client.player, "Full log: " + faultLog.file());
        return 1;
    }

    public int clearFaults(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || faultLog == null) {
            return 0;
        }
        faultLog.clear();
        sendChat(client.player, "Fault log cleared.");
        return 1;
    }

    /** Full state wipe: route, indices, timers, held inputs and the goal itself. */
    public int clearAll(CommandContext<?> context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        resetRunState(client, true);
        sendChat(client.player, "Cleared route, goal, timers and all held inputs.");
        return 1;
    }

    private int buildPreviewPath(Minecraft client, BlockPos target) {
        LocalPlayer player = client.player;
        List<PathHop> path;
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
        movement.stopWalking(client);

        activePath = path;
        currentStepIndex = 0;
        prebuiltFurthestStepIndex = 0;
        livePreviewPath = new ArrayList<>();
        livePlannedPath = new ArrayList<>();

        int normal = 0;
        int shift = 0;
        int walk = 0;
        int manaCost = 0;

        for (PathHop hop : path) {
            manaCost += hop.manaCost();
            if (hop.type() == HopType.NORMAL) {
                normal++;
            } else if (hop.type() == HopType.SHIFT) {
                shift++;
            } else {
                walk++;
            }
        }

        sendChat(player, "Preview path: " + path.size() + " steps [normal=" + normal + ", shift=" + shift + ", walk=" + walk + "] mana=" + manaCost + ".");
        return 1;
    }

    public int setMovementMode(PathBuilder.MovementMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setMovementMode(mode);
        sendChat(client.player, "Path mode: " + mode.name().toLowerCase(Locale.ROOT));
        return 1;
    }

    public int setTeleportMode(PathBuilder.TeleportMode mode) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setTeleportMode(mode);
        sendChat(client.player, "Teleport mode: " + teleportModeLabel(mode));
        return 1;
    }

    public int setAirChain(boolean enabled) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0;
        }
        settings.setAirChainEnabled(enabled);
        sendChat(client.player, "Air-chain: " + (enabled ? "on" : "off"));
        return 1;
    }

    public int showSettings() {
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
            movement.stopWalking(client);
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
            boolean keepGoal = true;
            resetRunState(client, !keepGoal);
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
                movement.stopWalking(client);
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

        List<PathHop> path;
        try {
            path = pathfinder.findPath(player, player.blockPosition(), goal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        } catch (Exception e) {
            sendChat(player, "Pathfinder error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return;
        }
        if (path.isEmpty()) {
            sendChat(player, "No path found.");
            return;
        }

        activePath = path;
        currentStepIndex = 0;
        // Clear residue from any previous run, or the forward-patch scan starts part-way in.
        prebuiltFurthestStepIndex = 0;
        resetProgressTracking();
        resetLiveStabilizer();
        rebuildAttempts = 0;
        int manaCost = path.stream().mapToInt(PathHop::manaCost).sum();
        sendChat(player, "Path built: " + path.size() + " steps, est mana " + manaCost + ".");
    }

    private void assistNextStep(LocalPlayer player) {
        if (currentStepIndex >= activePath.size()) {
            sendChat(player, "No remaining steps.");
            return;
        }

        PathHop step = activePath.get(currentStepIndex);
        aim.lookAtTeleportHuman(player, cast.aimTargetForHop(player, step), false);
        player.setShiftKeyDown(step.requiresShift());
        sendChat(player, "Aimed at step " + (currentStepIndex + 1) + "/" + activePath.size() + " [" + step.type() + "]");
    }

    private void runPrebuiltRoute(Minecraft client) {
        LocalPlayer player = client.player;
        if (currentStepIndex >= activePath.size()) {
            movement.stopWalking(client);
            // Ran out of route without arriving: the plan was stale, so make a new one.
            if (goal != null && !cast.isAtGoal(player, goal)) {
                attemptRebuild(client, "route ended short of goal");
                return;
            }
            if (goal != null && cast.isAtGoal(player, goal)) {
                sendChat(player, "Arrived.");
                boolean keepGoal = true;
                resetRunState(client, !keepGoal);
            }
            return;
        }

        PathHop step = activePath.get(currentStepIndex);
        prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);

        long tickNow = System.currentTimeMillis();

        // Failsafe: knocked off the route entirely (fell, pushed, teleported by the server).
        //
        // Only meaningful while walking. Walk nodes sit about a block apart, so a large gap really
        // does mean we came off the path. A teleport node is a hop *target*: standing a full hop
        // away from it is the normal state before casting, and transmission reaches 12 blocks with
        // etherwarp reaching 54, so distance alone says nothing there. Applying this to every node
        // made each teleport node look off-route on arrival and rebuilt the same route on repeat.
        // Teleport nodes are covered by the range guard and the stuck timer instead.
        if (step.isWalk()
            && player.onGround()
            && player.position().distanceToSqr(Vec3.atBottomCenterOf(step.landing()))
                > OFF_ROUTE_DISTANCE * OFF_ROUTE_DISTANCE) {
            if (attemptRebuild(client, "off route")) {
                return;
            }
        }

        // Failsafe: no headway toward this node for a while.
        if (updateProgress(player, step, currentStepIndex, tickNow)) {
            if (attemptRebuild(client, "stuck")) {
                return;
            }
        }
        // Falling.
        //
        // A transmission chain is fired mid-air by definition, each hop cast before the last one
        // lands, and a chain descends while gravity pulls harder -- so the player passes below the
        // next node almost immediately. Treating that as a stale plan, which an earlier version of
        // this did, ends the chain on its first hop every time. Being below a node does not put it
        // out of reach either: transmission goes where it is aimed, upward included.
        //
        // Whether a hop can still be made is already decided further down by the range and cast
        // line checks, and a genuinely stuck run is caught by the stuck timer. Neither needs help
        // from a guess made here.
        if (!player.onGround() && player.getDeltaMovement().y < -0.08 && step.isWalk()) {
            // Walking mid-air achieves nothing; wait for ground.
            movement.stopWalking(client);
            return;
        }
        if (cast.isStepReached(player, step)) {
            currentStepIndex++;
            prebuiltFurthestStepIndex = Math.max(prebuiltFurthestStepIndex, currentStepIndex);
            return;
        }
        if (tryForwardPatchPrebuilt(player)) {
            return;
        }

        if (step.isWalk()) {
            if (movement.walkToStep(client, player, step)) {
                currentStepIndex++;
            }
            return;
        }

        movement.stopWalking(client);
        if (!cast.ensureAotvEquipped(client, player)) {
            return;
        }

        int mana = manaTracker.currentMana();
        if (mana >= 0 && mana < step.manaCost()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCastAtMs < cast.castCooldownMs(step) || client.gameMode == null) {
            return;
        }

        Vec3 stepTarget = cast.aimTargetForHop(player, step);

        // Out of reach for the ability that performs this hop. Aiming and clicking would never
        // land, so react now rather than waiting for the stuck timer to notice.
        if (!cast.withinHopRange(player, step)) {
            attemptRebuild(client, "node out of range");
            return;
        }

        if (!cast.hasCastLineFor(player, step, stepTarget)) {
            if (tryWalkAroundBlocked(player, now, false)) {
                return;
            }
            // The hop is genuinely blocked. Blindly retiring the node here used to leave the router
            // chasing a route that assumed a hop it never made; replan from where we actually are.
            if (attemptRebuild(client, "hop blocked")) {
                return;
            }
            currentStepIndex++;
            return;
        }
        if (!aim.aimAtAndReady(player, stepTarget, now, cast.useFastAirChainTiming(step))) {
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
            movement.stopWalking(client);
            livePlannedPath = new ArrayList<>();
            liveStepIndex = 0;
            liveGoal = null;
            liveAi = false;
            resetLiveStabilizer();
            sendChat(player, "Live AI stopped: set a goal with /setgoal x y z.");
            return;
        }
        if (player.blockPosition().closerThan(dynamicGoal, CastRules.GOAL_REACHED_RADIUS)) {
            movement.stopWalking(client);
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
            PathHop step = livePlannedPath.get(liveStepIndex);
            liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
            // Falling past the current node no longer advances the index here. This runs every
            // tick, so a descent retired one node per tick for its whole duration. Live AI
            // replans continuously and will pick a node suited to wherever we actually land.
            // Nothing else about the tick changes, so air-chain casting mid-fall still works.

            if (cast.isStepReached(player, step)) {
                liveStepIndex++;
                liveLastAdvanceAtMs = now;
                markStepAdvanced(now);
                liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
                if (liveStepIndex >= livePlannedPath.size()) {
                    movement.stopWalking(client);
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
            PathHop step = livePlannedPath.get(liveStepIndex);
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
            List<PathHop> path = pathfinder.findPath(player, player.blockPosition(), planGoal, manaTracker.currentMana(), settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
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
                movement.stopWalking(client);
                return;
            }
        }

        if (livePlannedPath.isEmpty() || liveStepIndex >= livePlannedPath.size()) {
            movement.stopWalking(client);
            return;
        }

        enforceLiveTargetLock(now);
        PathHop next = livePlannedPath.get(liveStepIndex);
        if (next.isWalk()) {
            if (movement.walkToStep(client, player, next)) {
                liveStepIndex++;
                liveLastAdvanceAtMs = now;
                markStepAdvanced(now);
                liveFurthestStepIndex = Math.max(liveFurthestStepIndex, liveStepIndex);
                if (liveStepIndex >= livePlannedPath.size()) {
                    movement.stopWalking(client);
                    liveAi = false;
                    resetLiveStabilizer();
                    sendChat(player, "Live AI completed path and turned off.");
                }
            }
            return;
        }

        movement.stopWalking(client);
        if (!cast.ensureAotvEquipped(client, player)) {
            return;
        }

        int mana = manaTracker.currentMana();
        if (mana >= 0 && mana < next.manaCost()) {
            return;
        }
        if (now - lastCastAtMs < cast.castCooldownMs(next) || client.gameMode == null) {
            return;
        }

        Vec3 nextTarget = cast.aimTargetForHop(player, next);
        if (!cast.hasCastLineFor(player, next, nextTarget)) {
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
        if (!aim.aimAtAndReady(player, nextTarget, now, cast.useFastAirChainTiming(next))) {
            return;
        }
        player.setShiftKeyDown(next.requiresShift());
        client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        lastCastAtMs = now;
        castDebugCount++;
        maybeSendClickDebug(player, "CLICK #" + castDebugCount + " [" + next.type().name().toLowerCase(Locale.ROOT) + "] live", now);
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
            PathHop alt = livePlannedPath.get(i);
            if (alt.type() != HopType.NORMAL && alt.type() != HopType.SHIFT) {
                continue;
            }
            Vec3 altTarget = cast.aimTargetForHop(player, alt);
            if (!cast.hasCastLineFor(player, alt, altTarget)) {
                continue;
            }
            float yaw = Rotation.desiredYaw(player, altTarget);
            float delta = Math.abs(Rotation.wrapDegrees(yaw - currentYaw));
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
        List<PathHop> path = isLive ? livePlannedPath : activePath;
        int idx = isLive ? liveStepIndex : currentStepIndex;
        if (path.isEmpty() || idx >= path.size()) return false;
        PathHop blocked = path.get(idx);
        if (blocked.isWalk()) return false;

        Vec3 target = cast.aimTargetForHop(player, blocked);
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

                List<PathHop> patched = new ArrayList<>(path);
                patched.add(idx, PathHop.of(lateral, HopType.WALK, 0));
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



    /**
     * Single authoritative teardown for a routing run.
     *
     * <p>Every field touched while routing is reset here. Partial resets scattered across the key
     * handler and command handlers were leaving residue — most importantly
     * {@code prebuiltFurthestStepIndex}/{@code liveFurthestStepIndex}, which gate the forward-patch
     * scan via {@code max(currentStepIndex + 1, furthest)}. A stale furthest index from a previous
     * run makes the next run begin its scan part-way down the path and skip nodes.
     *
     * @param clearGoal also forget the destination, not just the route to it
     */
    private void resetRunState(Minecraft client, boolean clearGoal) {
        autoRun = false;
        liveAi = false;

        activePath = new ArrayList<>();
        livePreviewPath = new ArrayList<>();
        livePlannedPath = new ArrayList<>();
        liveGoal = null;

        currentStepIndex = 0;
        liveStepIndex = 0;
        prebuiltFurthestStepIndex = 0;
        liveFurthestStepIndex = 0;

        lastCastAtMs = 0L;
        lastBlockedReplanAtMs = 0L;
        lastReplanAtMs = 0L;
        liveLastAdvanceAtMs = 0L;
        lastClickChatAtMs = 0L;
        castDebugCount = 0;

        aim.reset();

        resetLiveStabilizer();
        resetProgressTracking();
        rebuildAttempts = 0;
        lastRebuildAtMs = 0L;

        movement.releaseAllInputs(client);
        clearHighlights(client);

        if (clearGoal) {
            goal = null;
        }
    }


    private void resetProgressTracking() {
        lastProgressAtMs = 0L;
        bestDistToNodeSq = Double.POSITIVE_INFINITY;
        trackedStepIndex = -1;
    }

    /**
     * Tracks how close we have ever gotten to the node currently being routed to.
     *
     * @return true if we are making no headway and should be considered stuck
     */
    private boolean updateProgress(LocalPlayer player, PathHop step, int stepIndex, long now) {
        Vec3 target = Vec3.atBottomCenterOf(step.landing());
        double distSq = player.position().distanceToSqr(target);

        if (stepIndex != trackedStepIndex) {
            // New node: start a fresh progress window.
            trackedStepIndex = stepIndex;
            bestDistToNodeSq = distSq;
            lastProgressAtMs = now;
            return false;
        }

        if (lastProgressAtMs == 0L) {
            lastProgressAtMs = now;
        }
        if (distSq < bestDistToNodeSq - PROGRESS_EPSILON) {
            bestDistToNodeSq = distSq;
            lastProgressAtMs = now;
            // Genuine headway clears the failure budget.
            rebuildAttempts = 0;
            return false;
        }
        return now - lastProgressAtMs > STUCK_TIMEOUT_MS;
    }

    /**
     * Replans from wherever the player actually is now.
     *
     * <p>This is the reaction a player would have when a hop does not land or the way ahead closes
     * up: drop the stale route, look at the current situation, and work out a fresh one — rather
     * than blindly retiring the node and carrying on down a route that no longer applies.
     *
     * @return true if a fresh route was installed
     */
    /**
     * Captures a failed node as a crash-report style entry.
     *
     * <p>Called from the rebuild path only, so the log holds faults rather than a trace of ordinary
     * routing. Successes are the uninteresting case: what we need is what set the failing node
     * apart from its neighbours.
     */
    private void recordFault(LocalPlayer player, String reason) {
        if (faultLog == null) {
            return;
        }
        try {
            PathHop step = (currentStepIndex >= 0 && currentStepIndex < activePath.size())
                ? activePath.get(currentStepIndex)
                : null;

            Boolean inRange = null;
            Boolean castLine = null;
            Boolean reached = null;
            double range = 0.0;
            String ability = "n/a";
            if (step != null) {
                // Record the same verdicts the router acted on, so a report can be read without
                // having to re-derive why each guard fired.
                inRange = cast.withinHopRange(player, step);
                castLine = cast.hasCastLineFor(player, step, cast.aimTargetForHop(player, step));
                reached = cast.isStepReached(player, step);
                if (step.isWalk()) {
                    ability = "walk";
                } else {
                    range = cast.maxHopRange(step.type());
                    ability = step.type() == HopType.SHIFT ? "etherwarp" : "transmission";
                }
            }

            faultLog.record(new FaultLog.Fault(
                reason,
                rebuildAttempts + 1,
                MAX_CONSECUTIVE_REBUILDS,
                currentStepIndex,
                activePath.size(),
                step,
                goal,
                manaTracker.currentMana(),
                range,
                ability,
                inRange,
                castLine,
                reached,
                settings.movementMode().name().toLowerCase(Locale.ROOT),
                teleportModeLabel(settings.teleportMode())
            ), player);
        } catch (Exception ignored) {
            // Never let diagnostics break routing.
        }
    }

    private boolean attemptRebuild(Minecraft client, String reason) {
        LocalPlayer player = client.player;
        if (player == null || goal == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (now - lastRebuildAtMs < REBUILD_COOLDOWN_MS) {
            return false;
        }
        lastRebuildAtMs = now;

        recordFault(player, reason);

        if (rebuildAttempts >= MAX_CONSECUTIVE_REBUILDS) {
            sendChat(player, "Giving up after " + rebuildAttempts + " rebuilds (" + reason + "). Route stopped.");
            boolean keepGoal = true;
            resetRunState(client, !keepGoal);
            return false;
        }
        rebuildAttempts++;

        // Start from a clean slate so no held input or stale index leaks into the new route.
        movement.releaseAllInputs(client);

        List<PathHop> path;
        try {
            path = pathfinder.findPath(player, player.blockPosition(), goal, manaTracker.currentMana(),
                settings.movementMode(), settings.teleportMode(), settings.airChainEnabled());
        } catch (Exception e) {
            sendChat(player, "Rebuild failed (" + reason + "): " + e.getClass().getSimpleName());
            e.printStackTrace();
            return false;
        }

        if (path.isEmpty()) {
            sendChat(player, "Rebuild " + rebuildAttempts + "/" + MAX_CONSECUTIVE_REBUILDS
                + " found no path (" + reason + ").");
            return false;
        }

        activePath = path;
        currentStepIndex = 0;
        prebuiltFurthestStepIndex = 0;
        resetProgressTracking();
        resetLiveStabilizer();

        sendChat(player, "Rebuilt route (" + reason + "): " + path.size() + " steps.");
        return true;
    }


    // --- PathRenderer.RouteView ---------------------------------------------------------

    @Override
    public List<PathHop> renderRoute() {
        return liveAi ? livePlannedPath : activePath;
    }

    @Override
    public int renderStartIndex() {
        return liveAi ? liveStepIndex : currentStepIndex;
    }

    @Override
    public List<PathHop> previewRoute() {
        return livePreviewPath;
    }

    @Override
    public BlockPos goal() {
        return goal;
    }

    @Override
    public List<String> hudLines() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return List.of();
        }
        BlockPos preview = calculatePreviewLanding(player);
        String mode = liveAi ? "live-ai" : (autoRun ? "auto" : "manual");
        String pathMode = settings.movementMode().name().toLowerCase(Locale.ROOT);
        String manaText = manaTracker.hasAnyData()
            ? (manaTracker.currentMana() + "/" + manaTracker.maxMana())
            : "n/a";
        String previewText = preview == null
            ? "none"
            : (preview.getX() + " " + preview.getY() + " " + preview.getZ());
        String goalText = goal == null
            ? "none"
            : (goal.getX() + " " + goal.getY() + " " + goal.getZ());

        return List.of(
            "[AOTV] " + mode + " | " + pathMode + " | " + teleportModeLabel(settings.teleportMode()),
            "airchain: " + (settings.airChainEnabled() ? "on" : "off") + " | mana: " + manaText,
            "goal: " + goalText,
            "preview: " + previewText,
            routeCompositionText(),
            routePreviewText()
        );
    }

    private void updateRouteHighlights(Minecraft client) {
        // Visual path is drawn by renderPathEsp; no block-crack highlights needed.
    }

    


    

    

    

    

    

    private String teleportModeLabel(PathBuilder.TeleportMode mode) {
        if (mode == PathBuilder.TeleportMode.SHIFT_ONLY) {
            return "shift-only";
        }
        if (mode == PathBuilder.TeleportMode.JUST_TELEPORT) {
            return "just-teleport";
        }
        return "hybrid-teleport";
    }

    private void clearHighlights(Minecraft client) {
        // No-op: block-crack highlights removed; visual path handled by renderPathEsp.
    }

    /**
     * What the current route is made of.
     *
     * <p>Worth a line of its own: it answers whether the planner produced the kinds of step it was
     * meant to. A route with no jump nodes at all on ground that clearly needs them says the
     * planning went wrong, not the walking.
     */
    private String routeCompositionText() {
        List<PathHop> route = liveAi ? livePlannedPath : activePath;
        if (route.isEmpty()) {
            return "nodes: none";
        }
        int tp = 0;
        int warp = 0;
        int step = 0;
        int jump = 0;
        int sprint = 0;
        for (PathHop hop : route) {
            switch (hop.type()) {
                case NORMAL -> tp++;
                case SHIFT -> warp++;
                case WALK -> {
                    switch (hop.style()) {
                        case JUMP -> jump++;
                        case SPRINT_JUMP -> sprint++;
                        case STEP -> step++;
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("nodes: ");
        sb.append(route.size()).append(" =");
        if (tp > 0) sb.append(" ").append(tp).append("tp");
        if (warp > 0) sb.append(" ").append(warp).append("warp");
        if (step > 0) sb.append(" ").append(step).append("step");
        if (jump > 0) sb.append(" ").append(jump).append("jump");
        if (sprint > 0) sb.append(" ").append(sprint).append("sprint");
        return sb.toString();
    }

    private String routePreviewText() {
        List<PathHop> route = liveAi ? livePlannedPath : activePath;
        int start = liveAi ? liveStepIndex : currentStepIndex;
        if (route.isEmpty() || start >= route.size()) {
            return "route: none";
        }

        int count = Math.min(3, route.size() - start);
        StringBuilder sb = new StringBuilder("route: ");
        for (int i = 0; i < count; i++) {
            PathHop hop = route.get(start + i);
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
        if (!cast.isHoldingAotv(player.getMainHandItem())) {
            return null;
        }

        if (player.isShiftKeyDown()) {
            HitResult result = player.pick(CastRules.ETHERWARP_RANGE, 0.0F, false);
            if (result instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos().above();
                return movement.isSafeLanding(player, pos) ? pos : null;
            }
            return null;
        }

        Vec3 look = player.getViewVector(1.0F);
        Vec3 target = player.getEyePosition().add(look.scale(CastRules.TRANSMISSION_RANGE));
        BlockPos center = BlockPos.containing(target);

        for (int dy = 2; dy >= -3; dy--) {
            BlockPos candidate = center.offset(0, dy, 0);
            if (movement.isSafeLanding(player, candidate) && hasLineOfSight(player, candidate)) {
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
    }


    private void resetLiveStabilizer() {
        liveNodeLockUntilMs = 0L;
        liveLockedStepIndex = -1;
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
            PathHop candidate = activePath.get(i);
            if (!candidate.isWalk() && isStepPatchReachable(player, candidate)) {
                currentStepIndex = i;
                prebuiltFurthestStepIndex = i;
                return true;
            }
        }

        int walkWindow = Math.max(4, settings.walkPatchWindowBlocks());
        int walkMaxIndex = Math.min(activePath.size() - 1, fromIndex + walkWindow);
        for (int i = fromIndex; i <= walkMaxIndex; i++) {
            PathHop candidate = activePath.get(i);
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
            PathHop candidate = livePlannedPath.get(i);
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
            PathHop candidate = livePlannedPath.get(i);
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

    private boolean isStepPatchReachable(LocalPlayer player, PathHop hop) {
        if (hop.isWalk()) {
            // Horizontal reach only: a walk node several blocks above or below is not something we
            // can simply stroll to, and treating it as patchable makes the route leapfrog nodes.
            Vec3 feet = player.position();
            double dx = feet.x - (hop.landing().getX() + 0.5);
            double dz = feet.z - (hop.landing().getZ() + 0.5);
            double reach = Math.max(3.5, settings.walkPatchWindowBlocks());
            if (dx * dx + dz * dz > reach * reach) {
                return false;
            }
            return Math.abs(feet.y - hop.landing().getY()) <= WALK_PATCH_MAX_VERTICAL;
        }

        // A clear sightline alone is NOT enough to call a hop reachable. The forward patch scans up
        // to 60 nodes ahead and takes the first teleport node that passes this test, so without a
        // range check any distant node in open view wins: every walk node in between is discarded
        // and the router then parks on a node it can never cast to (transmission reaches 12 blocks,
        // etherwarp 61). Bound by the range of the ability that would actually perform the hop.
        if (!cast.withinHopRange(player, hop)) {
            return false;
        }
        return cast.hasCastLineFor(player, hop, cast.aimTargetForHop(player, hop));
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
