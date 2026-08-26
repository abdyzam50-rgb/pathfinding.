package net.netherite.tutorialmod;

import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.TwistingVinesBlock;
import net.minecraft.block.WeepingVinesBlock;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.netherite.tutorialmod.client.TessellatorRenderer;
import net.netherite.tutorialmod.pathfinder.PathfinderEngine;
import net.netherite.tutorialmod.pathfinder.PathingEnvironment;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Client-side path follower. Each tick it:
 *  1. Rotates the player's yaw (and pitch for INTERACT) to face the next waypoint.
 *  2. Presses W (forward). Sprint / jump / climb / interact handled per node type.
 *  3. Auto-opens doors that are in the path direction.
 *  4. Detects stuck-in-place and force-advances the node index.
 */
public class PathFollower {

    private static List<PathNode> currentPath   = null;
    private static int  currentNodeIndex        = 0;
    private static boolean isFollowing          = false;

    // --- Async pathfinding: replans run on a background thread so the client never freezes ---
    private static final java.util.concurrent.ExecutorService PATHFIND_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pathfinder-async");
                t.setDaemon(true);
                return t;
            });
    private static volatile java.util.concurrent.Future<List<PathNode>> pendingReplanFuture  = null;
    private static volatile net.minecraft.util.math.BlockPos            pendingReplanGoalCap = null;
    private static volatile boolean                                      pendingReplanSprintCap = false;

    // --- Sprint mode ---
    private static boolean sprintModeForced     = false;

    // --- Water bucket clutch state ---
    private static boolean waterClutchPlaced    = false;
    // Ticks remaining to hold the right-click (useKey) for water place / pickup
    private static int  useKeyHoldTicks         = 0;

    // --- Humanization ---
    private static final Random RNG             = new Random();
    private static int  pauseTicksLeft          = 0;
    private static int  jumpCooldown            = 0;
    private static boolean sprintOn             = true;
    private static int  sprintRerollCD          = 0;
    private static double reachTolerance        = 0.40;

    // After right-clicking a door we wait this many ticks before advancing.
    private static int  interactWaitTicks       = 0;
    private static PathNode pendingInteractNext = null;

    // How many blocks ahead to scan for a cliff (void / large drop) when braking.
    private static final int CLIFF_SCAN_RANGE = 5;

    // --- Preview-path storage (for /usepath) ---
    // Holds the last path computed by any /preview or /find command so /usepath can
    // start following it without needing to re-run pathfinding.
    private static List<PathNode>                      previewPath   = null;
    private static net.minecraft.util.math.BlockPos   previewGoal   = null;
    private static boolean                             previewSprint = false;

    public static void storePreview(List<PathNode> path,
                                    net.minecraft.util.math.BlockPos goal, boolean sprint) {
        previewPath   = path;
        previewGoal   = goal;
        previewSprint = sprint;
    }
    public static boolean        hasPreview()        { return previewPath != null && !previewPath.isEmpty(); }
    public static List<PathNode> getPreviewPath()    { return previewPath; }
    public static net.minecraft.util.math.BlockPos getPreviewGoal() { return previewGoal; }
    public static boolean        isPreviewSprint()   { return previewSprint; }

    // --- Dynamic re-planning ---
    // When the player gets severely stuck, or drifts >1.5 blocks off the path, a fresh
    // A* search runs from the player's CURRENT position to the stored goal.
    private static net.minecraft.util.math.BlockPos replanGoal         = null;
    private static boolean                          replanSprint        = false;
    private static int                              replanCooldown      = 0;
    private static final int                        REPLAN_INTERVAL     = 100; // 5 s
    private static int                              consecutiveStuck    = 0;   // force-advances without progress

    /** Called from ModCommands before startFollowing to enable dynamic re-planning. */
    public static void setReplanGoal(net.minecraft.util.math.BlockPos goal, boolean sprint) {
        replanGoal   = goal;
        replanSprint = sprint;
        replanCooldown    = 0;
        consecutiveStuck  = 0;
    }

    // --- Sprint-jump yaw lock ---
    // Recorded the moment the jump key fires; held constant throughout the air phase
    // so air-acceleration stays straight and the arc matches the physics prediction.
    private static float sprintJumpLaunchYaw = 0f;

    // --- Corner / steering smoothing (prevents yaw flicker at tight turns) ---
    private static double lastSteerDx = 0;
    private static double lastSteerDz = 0;
    private static boolean hasSteerDir = false;

    // --- Pure pursuit arc-length parameterisation ---
    // cumulativeDist[i] = total horizontal arc-length from node 0 to node i.
    // Computed once when the path is set; used every tick to project the player
    // onto the path polyline and compute a look-ahead steering goal.
    private static double[] cumulativeDist = null;
    private static double   totalPathLen   = 0;

    // --- Stuck detection ---
    // Checked every STUCK_CHECK_INTERVAL ticks. If the player has moved less
    // than STUCK_THRESHOLD in that window, we count it as a stuck event.
    // After STUCK_MAX_EVENTS consecutive windows on the same node, force-advance.
    private static final int  STUCK_CHECK_INTERVAL = 25;
    private static final double STUCK_THRESHOLD    = 0.25;
    private static final int  STUCK_MAX_EVENTS     = 3;

    private static int   stuckCheckTimer           = 0;
    private static int   stuckEventCount           = 0;
    private static Vec3d lastStuckCheckPos         = null;
    // Maximum ticks to spend on a single node before forcing an advance
    private static final int NODE_TIMEOUT          = 120;
    private static int   nodeTicksSpent            = 0;

    /** Vanilla player jump clears at most ~1.25 blocks vertically. */
    private static final double MAX_JUMP_UP        = 1.25;

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public static boolean startFollowing(PlayerEntity player, World world, BlockPos targetPos) {
        if (player == null || world == null || targetPos == null) return false;
        currentPath = PathfinderEngine.findNodePath(world, player.getBlockPos(), targetPos);
        if (currentPath == null || currentPath.isEmpty()) return false;
        resetState();
        isFollowing = true;
        TessellatorRenderer.setPath(currentPath);
        return true;
    }

    public static boolean startFollowing(List<PathNode> path) {
        if (path == null || path.isEmpty()) return false;
        currentPath = path;
        resetState();
        buildArcLengths(path);
        isFollowing = true;
        TessellatorRenderer.setPath(path);
        return true;
    }

    public static boolean startFollowingSprint(List<PathNode> path) {
        if (path == null || path.isEmpty()) return false;
        currentPath = path;
        resetState();
        buildArcLengths(path);
        sprintModeForced = true;
        isFollowing = true;
        TessellatorRenderer.setPath(path);
        return true;
    }

    public static void stopFollowing() {
        if (pendingReplanFuture != null) { pendingReplanFuture.cancel(false); pendingReplanFuture = null; }
        isFollowing = false;
        currentPath = null;
        currentNodeIndex = 0;
        replanGoal = null;
        replanSprint = false;
        resetState();
        releaseAllKeys();
        TessellatorRenderer.clearPath();
    }

    public static boolean isFollowing()           { return isFollowing; }
    public static List<PathNode> getCurrentPath() { return currentPath; }
    public static int getCurrentNodeIndex()       { return currentNodeIndex; }
    public static int getTotalNodes()             { return currentPath != null ? currentPath.size() : 0; }

    // -----------------------------------------------------------------------
    // Tick — called every client tick from ClientTickHandler
    // -----------------------------------------------------------------------

    public static void tick(PlayerEntity player) {
        // Apply any async replan that finished since the last tick
        if (pendingReplanFuture != null && pendingReplanFuture.isDone()) {
            try {
                List<PathNode> newPath = pendingReplanFuture.get();
                pendingReplanFuture = null;
                if (newPath != null && !newPath.isEmpty()) {
                    resetState();
                    currentPath  = newPath;
                    buildArcLengths(newPath);
                    if (pendingReplanSprintCap) sprintModeForced = true;
                    replanGoal   = pendingReplanGoalCap;
                    replanSprint = pendingReplanSprintCap;
                    isFollowing  = true;
                    TessellatorRenderer.setPath(newPath);
                } else {
                    stopFollowing();
                    return;
                }
            } catch (Exception ignored) {
                pendingReplanFuture = null;
                stopFollowing();
                return;
            }
        }

        if (!isFollowing || currentPath == null || currentNodeIndex >= currentPath.size()) {
            stopFollowing();
            return;
        }
        if (player == null) { stopFollowing(); return; }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (replanCooldown > 0) replanCooldown--;

        // --- useKey hold timer: drives physical right-click for water placement / pickup ---
        if (useKeyHoldTicks > 0) {
            mc.options.useKey.setPressed(true);
            useKeyHoldTicks--;
        } else {
            mc.options.useKey.setPressed(false);
        }

        // --- Interact post-wait: door was clicked, now wait for it to open ---
        if (interactWaitTicks > 0) {
            interactWaitTicks--;
            // Keep walking gently toward the door so the player passes through naturally
            PathNode target = currentPath.get(currentNodeIndex);
            if (target.interactPos != null) {
                double dx = (target.interactPos.getX() + 0.5) - player.getX();
                double dz = (target.interactPos.getZ() + 0.5) - player.getZ();
                faceAndWalk(player, mc, dx, dz, false);
            }
            if (interactWaitTicks == 0 && pendingInteractNext != null) {
                currentNodeIndex++;
                reachTolerance = 0.35 + RNG.nextDouble() * 0.15;
                pendingInteractNext = null;
                resetNodeTimer();
                if (currentNodeIndex >= currentPath.size()) { stopFollowing(); return; }
            }
            return;
        }

        // --- Humanization micro-pauses ---
        if (pauseTicksLeft > 0) {
            pauseTicksLeft--;
            releaseMovementKeys();
            return;
        }

        // --- Jump cooldown ---
        if (jumpCooldown > 0) jumpCooldown--;

        // --- Sprint re-roll ---
        if (sprintRerollCD <= 0) {
            sprintOn = RNG.nextInt(10) < 8;
            sprintRerollCD = 40 + RNG.nextInt(80);
        }
        sprintRerollCD--;

        PathNode targetNode = currentPath.get(currentNodeIndex);
        Vec3d targetCenter  = targetNode.getCenterPos();

        double dx = targetCenter.x - player.getX();
        double dy = targetCenter.y - player.getY();
        double dz = targetCenter.z - player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);

        // Micro-pauses only on long straight WALK segments — they cause visible
        // stop-start jitter at corners and on narrow ledges.
        if (RNG.nextInt(200) == 0
                && targetNode.type == PathNode.Type.WALK
                && cornerSharpnessAhead(projectOnPath(player.getX(), player.getZ())) < 25.0) {
            pauseTicksLeft = 1;
            return;
        }

        // --- Stuck detection + dynamic re-planning ---
        nodeTicksSpent++;
        stuckCheckTimer++;
        if (stuckCheckTimer >= STUCK_CHECK_INTERVAL) {
            stuckCheckTimer = 0;
            Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
            if (lastStuckCheckPos != null
                    && pos.distanceTo(lastStuckCheckPos) < STUCK_THRESHOLD) {
                stuckEventCount++;
                if (stuckEventCount >= STUCK_MAX_EVENTS) {
                    consecutiveStuck++;
                    forceAdvanceNode();
                    if (!isFollowing) return;
                    // After two consecutive force-advances with no real progress, try to
                    // escape any hole the bot may have fallen into, then replan.
                    if (consecutiveStuck >= 2) {
                        if (tryEscapeHole(player, mc)) return;
                        if (replanGoal != null && replanCooldown == 0) {
                            triggerReplan(player, mc);
                            return;
                        }
                    }
                    targetNode = currentPath.get(currentNodeIndex);
                    targetCenter = targetNode.getCenterPos();
                    dx = targetCenter.x - player.getX();
                    dy = targetCenter.y - player.getY();
                    dz = targetCenter.z - player.getZ();
                    hDist = Math.sqrt(dx * dx + dz * dz);
                }
                // Also replan if the player has drifted far from the path polyline.
                if (cumulativeDist != null && replanGoal != null && replanCooldown == 0) {
                    double tP = projectOnPath(player.getX(), player.getZ());
                    Vec3d closest = goalPoint(tP);
                    if (closest != null) {
                        double lateralDist = Math.sqrt(
                            (player.getX()-closest.x)*(player.getX()-closest.x) +
                            (player.getZ()-closest.z)*(player.getZ()-closest.z));
                        if (lateralDist > 1.5) {
                            triggerReplan(player, mc);
                            return;
                        }
                    }
                }
            } else {
                stuckEventCount = 0;
                consecutiveStuck = 0;
            }
            lastStuckCheckPos = pos;
        }
        // Hard timeout: too long on one node, skip it
        if (nodeTicksSpent >= NODE_TIMEOUT) {
            forceAdvanceNode();
            if (!isFollowing) return;
            targetNode = currentPath.get(currentNodeIndex);
            targetCenter = targetNode.getCenterPos();
            dx = targetCenter.x - player.getX();
            dy = targetCenter.y - player.getY();
            dz = targetCenter.z - player.getZ();
            hDist = Math.sqrt(dx * dx + dz * dz);
        }

        // --- Pure pursuit: continuously advance the node index as the player travels
        //     along the path polyline, replacing the discrete reachTolerance check for
        //     WALK, EDGE, and SPRINT_JUMP nodes.  Special nodes keep their own reach logic.
        if (cumulativeDist != null
                && targetNode.type != PathNode.Type.CLIMB
                && targetNode.type != PathNode.Type.JUMP
                && targetNode.type != PathNode.Type.DROP
                && targetNode.type != PathNode.Type.WATER_DROP
                && targetNode.type != PathNode.Type.BOUNCE
                && targetNode.type != PathNode.Type.INTERACT
                && targetNode.type != PathNode.Type.SPRINT_JUMP
                && targetNode.type != PathNode.Type.BOOST_PLACE  // must land physically
                && targetNode.type != PathNode.Type.PILLAR        // vertical — zero horizontal arc contribution
                && targetNode.type != PathNode.Type.BRIDGE) {     // must confirm block placed
            double tPlayer = projectOnPath(player.getX(), player.getZ());
            while (currentNodeIndex + 1 < currentPath.size()
                    && tPlayer >= cumulativeDist[currentNodeIndex + 1]) {
                currentNodeIndex++;
                reachTolerance = 0.30 + RNG.nextDouble() * 0.20;
                resetNodeTimer();
                if (currentNodeIndex >= currentPath.size()) { stopFollowing(); return; }
                if (currentNodeIndex + 1 < currentPath.size()
                        && currentPath.get(currentNodeIndex + 1).type == PathNode.Type.SPRINT_JUMP) {
                    jumpCooldown = 0;
                }
            }
            targetNode   = currentPath.get(currentNodeIndex);
            targetCenter = targetNode.getCenterPos();
            dx    = targetCenter.x - player.getX();
            dy    = targetCenter.y - player.getY();
            dz    = targetCenter.z - player.getZ();
            hDist = Math.sqrt(dx * dx + dz * dz);
        }

        // --- INTERACT node: approach, look at door naturally, right-click, walk through ---
        if (targetNode.type == PathNode.Type.INTERACT && targetNode.interactPos != null) {
            Vec3d toBlock = Vec3d.ofCenter(targetNode.interactPos)
                    .subtract(player.getX(), player.getY(), player.getZ());
            double blockDist = toBlock.length();

            if (blockDist <= 3.0) {
                // Face the door in both yaw AND pitch (looks natural)
                faceBlock(player, targetNode.interactPos);
                setPitchToBlock(player, targetNode.interactPos);
                // Keep walking slowly toward the door (don't stop dead)
                setKey(mc.options.forwardKey, true);
                setKey(mc.options.backKey,    false);
                setKey(mc.options.leftKey,    false);
                setKey(mc.options.rightKey,   false);
                setKey(mc.options.sprintKey,  false);
                interactWithBlock(player, targetNode.interactPos);
                // Wait for door to open while still walking through
                interactWaitTicks = 4;
                pendingInteractNext = targetNode;
            } else {
                // Walk toward the interact position
                faceAndWalk(player, mc, dx, dz, false);
                autoInteractDoors(player, mc, dx / Math.max(hDist, 0.01), dz / Math.max(hDist, 0.01));
            }
            return;
        }

        // --- Check for doors in the movement direction ---
        if (hDist > 0.3) {
            autoInteractDoors(player, mc, dx / hDist, dz / hDist);
        }

        // --- Skip-ahead: if player has passed the current node, advance immediately ---
        // Detects overshoot by checking whether the vector from player→target is pointing
        // backward relative to the direction of travel (target→next).
        if (currentNodeIndex + 1 < currentPath.size()
                && (targetNode.type == PathNode.Type.WALK
                    || targetNode.type == PathNode.Type.EDGE
                    || targetNode.type == PathNode.Type.SPRINT_JUMP
                    || targetNode.type == PathNode.Type.DROP
                    || targetNode.type == PathNode.Type.WATER_DROP)
                && hDist < 3.0) {
            PathNode nextNode = currentPath.get(currentNodeIndex + 1);
            Vec3d nextCenter = nextNode.getCenterPos();
            double fwdX = nextCenter.x - targetCenter.x;
            double fwdZ = nextCenter.z - targetCenter.z;
            // dot(playerToTarget, targetToNext) < 0 → player is past the target node
            if (dx * fwdX + dz * fwdZ < 0) {
                currentNodeIndex++;
                reachTolerance = 0.30 + RNG.nextDouble() * 0.20;
                resetNodeTimer();
                if (currentNodeIndex >= currentPath.size()) { stopFollowing(); return; }
                targetNode  = currentPath.get(currentNodeIndex);
                targetCenter = targetNode.getCenterPos();
                dx    = targetCenter.x - player.getX();
                dy    = targetCenter.y - player.getY();
                dz    = targetCenter.z - player.getZ();
                hDist = Math.sqrt(dx * dx + dz * dz);
            }
        }

        // --- Reached node? ---
        boolean reached;
        if (targetNode.type == PathNode.Type.CLIMB) {
            reached = Math.abs(dy) < 0.5 && hDist < 0.6;
        } else if (targetNode.type == PathNode.Type.JUMP
                || targetNode.type == PathNode.Type.BOUNCE) {
            reached = hDist < 0.6 && Math.abs(dy) < 1.0;
        } else if (targetNode.type == PathNode.Type.DROP
                || targetNode.type == PathNode.Type.WATER_DROP) {
            // Wider tolerance: sprint-jump carry-over can land 1+ block past the DROP node.
            // Skip-ahead (dot-product check above) handles overshoot; this catches near-landings.
            reached = hDist < 1.2 && Math.abs(dy) < 1.0;
        } else if (targetNode.type == PathNode.Type.SPRINT_JUMP
                || targetNode.type == PathNode.Type.BOOST_PLACE) {
            reached = hDist < 0.8 && Math.abs(dy) < 1.2;
        } else if (targetNode.type == PathNode.Type.PILLAR) {
            // Block placed AND player has physically risen to target Y
            reached = targetNode.interactPos != null
                    && mc.world != null
                    && !mc.world.getBlockState(targetNode.interactPos).isAir()
                    && player.getBlockY() >= targetNode.pos.getY();
        } else if (targetNode.type == PathNode.Type.BRIDGE) {
            // Gap filled AND player has walked to the target XZ
            reached = targetNode.interactPos != null
                    && mc.world != null
                    && !mc.world.getBlockState(targetNode.interactPos).isAir()
                    && hDist < reachTolerance;
        } else {
            reached = hDist < reachTolerance && Math.abs(dy) < 1.2;
        }

        if (reached) {
            // When chaining sprint-jump nodes, reset cooldown so the next jump fires immediately
            if (currentNodeIndex + 1 < currentPath.size()
                    && currentPath.get(currentNodeIndex + 1).type == PathNode.Type.SPRINT_JUMP) {
                jumpCooldown = 0;
            }
            if (targetNode.type == PathNode.Type.WATER_DROP) {
                // Physically right-click to pick up the water we just landed in
                player.setPitch(80.0f); // look steeply down toward the water block
                useKeyHoldTicks = 3;   // hold right-click for 3 ticks so it registers
                waterClutchPlaced = false;
            }
            currentNodeIndex++;
            reachTolerance = 0.30 + RNG.nextDouble() * 0.20;
            resetNodeTimer();
            if (currentNodeIndex >= currentPath.size()) stopFollowing();
            return;
        }

        // --- DROP: in the air → hold heading, don't steer toward the drop node -----------
        // When the player overshoots the DROP node while airborne, dx/dz reverses direction
        // and faceAndWalk rotates the yaw 180° — producing a violent aim flick.  Skipping
        // yaw steering while in the air prevents this: gravity handles the fall and
        // skip-ahead / reach detection fires once the player lands.
        if (targetNode.type == PathNode.Type.DROP && !player.isOnGround()) {
            setKey(mc.options.forwardKey, true);
            setKey(mc.options.backKey,    false);
            setKey(mc.options.leftKey,    false);
            setKey(mc.options.rightKey,   false);
            setKey(mc.options.sprintKey,  true);
            mc.options.jumpKey.setPressed(false);
            return;
        }

        // --- BOUNCE: sprint toward the landing target; the slime block bounces the player automatically.
        //     Do NOT press jump — the bounce is driven by physics, not by the jump key.
        //     While in the air (on the bounce arc) keep sprinting toward the next node.
        if (targetNode.type == PathNode.Type.BOUNCE) {
            faceAndWalk(player, mc, dx, dz, true);
            mc.options.jumpKey.setPressed(false);
            return;
        }

        // --- SPRINT_JUMP: ground = converge + fire; air = hold launch yaw exactly -----------
        if (targetNode.type == PathNode.Type.SPRINT_JUMP) {
            if (player.isOnGround()) {
                // Sprint-jump cannot clear more than ~1.25 blocks up — walk and use a
                // normal jump, or replan with a walk path that includes JUMP/PILLAR nodes.
                if (dy > MAX_JUMP_UP) {
                    faceAndWalk(player, mc, dx, dz, false);
                    if (dy <= 1.35 && hDist < 1.6 && jumpCooldown <= 0) {
                        mc.options.jumpKey.setPressed(true);
                        jumpCooldown = 8;
                    } else {
                        mc.options.jumpKey.setPressed(false);
                        if (nodeTicksSpent >= 25 && replanGoal != null && replanCooldown == 0) {
                            replanSprint = false;
                            triggerReplan(player, mc);
                        }
                    }
                    return;
                }
                faceAndWalk(player, mc, dx, dz, true);
                // Aim at landing node surface (not velocity-based which looks up at launch)
                applyLandingPitch(player, hDist, dy);
                double estimatedLanding = estimateSprintJumpDistance(player, dx, dz, dy);
                // Generous lower bound: player may not be at full sprint speed at jump time.
                // Upper bound +2.0: stride-node overshoots handled by skip-ahead.
                boolean jumpWillLand = dy <= MAX_JUMP_UP
                        && estimatedLanding >= hDist - 0.5 && estimatedLanding <= hDist + 2.0;
                boolean forceJump    = nodeTicksSpent >= 12 && hDist > 0.8 && dy <= MAX_JUMP_UP;
                if (jumpCooldown <= 0 && hDist > 0.5 && (jumpWillLand || forceJump)) {
                    mc.options.jumpKey.setPressed(true);
                    jumpCooldown = 6;
                    sprintJumpLaunchYaw = player.getYaw(); // lock at exact moment of launch
                } else if (jumpCooldown <= 0) {
                    mc.options.jumpKey.setPressed(false);
                }
            } else {
                // Air: hold the launch yaw exactly — changing yaw mid-arc redirects the W-key
                // air-acceleration and curves the trajectory sideways, causing missed landings.
                player.setYaw(sprintJumpLaunchYaw);
                // Aim toward the landing surface throughout the arc (never looks up)
                applyLandingPitch(player, hDist, dy);
                setKey(mc.options.forwardKey, true);
                setKey(mc.options.backKey,    false);
                setKey(mc.options.leftKey,    false);
                setKey(mc.options.rightKey,   false);
                setKey(mc.options.sprintKey,  true);
                mc.options.jumpKey.setPressed(false);
            }
            return;
        }

        // --- BOOST_PLACE: sprint-jump + place landing block mid-arc -----------------
        if (targetNode.type == PathNode.Type.BOOST_PLACE) {
            if (player instanceof ClientPlayerEntity cpe) selectBlockSlot(cpe);
            if (player.isOnGround()) {
                faceAndWalk(player, mc, dx, dz, true);
                applyLandingPitch(player, hDist, dy);
                if (jumpCooldown <= 0 && hDist > 0.5) {
                    mc.options.jumpKey.setPressed(true);
                    jumpCooldown = 6;
                    sprintJumpLaunchYaw = player.getYaw();
                }
            } else {
                player.setYaw(sprintJumpLaunchYaw);
                setKey(mc.options.forwardKey, true);
                setKey(mc.options.sprintKey, true);
                mc.options.jumpKey.setPressed(false);
                // Place block as arc descends toward landing
                if (targetNode.interactPos != null && hDist < 3.5 && dy <= 0.8) {
                    faceBlock(player, targetNode.interactPos);
                    player.setPitch(72f);
                    boolean onFace = mc.crosshairTarget != null
                            && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK;
                    mc.options.useKey.setPressed(onFace);
                } else {
                    applyLandingPitch(player, hDist, dy);
                    mc.options.useKey.setPressed(false);
                }
            }
            return;
        }

        // --- PILLAR: jump and place block at feet level to rise 1 block ---------------
        // interactPos = player's current feet block (air); we click the floor below it.
        if (targetNode.type == PathNode.Type.PILLAR && targetNode.interactPos != null) {
            BlockPos placePos = targetNode.interactPos; // the air block at player's feet

            // Clear all lateral movement — pillar is purely vertical
            setKey(mc.options.forwardKey, false);
            setKey(mc.options.backKey,    false);
            setKey(mc.options.leftKey,    false);
            setKey(mc.options.rightKey,   false);
            setKey(mc.options.sneakKey,   false);
            setKey(mc.options.sprintKey,  false);

            if (mc.world != null && !mc.world.getBlockState(placePos).isAir()) {
                // Block has been placed — just wait to land on top of it
                mc.options.jumpKey.setPressed(false);
                return;
            }

            if (player.isOnGround()) {
                mc.options.jumpKey.setPressed(true);
                jumpCooldown = 4;
            } else {
                mc.options.jumpKey.setPressed(false);
                // Mid-air: right-click the top face of the floor block to place at placePos
                BlockPos floorBlock = placePos.down();
                if (mc.world != null && !mc.world.getBlockState(floorBlock).isAir()
                        && player instanceof ClientPlayerEntity cpe
                        && mc.interactionManager != null) {
                    selectBlockSlot(cpe);
                    player.setPitch(85f);
                    Vec3d hitVec = new Vec3d(floorBlock.getX() + 0.5, floorBlock.getY() + 1.0,
                                             floorBlock.getZ() + 0.5);
                    mc.interactionManager.interactBlock(cpe, Hand.MAIN_HAND,
                            new BlockHitResult(hitVec, Direction.UP, floorBlock, false));
                }
            }
            return;
        }

        // --- BRIDGE: place block at gap (interactPos) then walk forward ---------------
        // interactPos = the air gap block (target.down()); placed by clicking the side face
        // of the player's floor block that faces toward the gap.
        if (targetNode.type == PathNode.Type.BRIDGE && targetNode.interactPos != null) {
            BlockPos gapPos = targetNode.interactPos;

            if (mc.world != null && !mc.world.getBlockState(gapPos).isAir()) {
                // Block placed — stop sneaking and walk forward normally
                setKey(mc.options.sneakKey, false);
                faceAndWalk(player, mc, dx, dz, false);
                return;
            }

            // Determine which side face of the player's floor block faces the gap
            BlockPos playerFloor = player.getBlockPos().down();
            int gdx = gapPos.getX() - playerFloor.getX();
            int gdz = gapPos.getZ() - playerFloor.getZ();
            Direction placeDir = (Math.abs(gdx) >= Math.abs(gdz))
                    ? (gdx >= 0 ? Direction.EAST : Direction.WEST)
                    : (gdz >= 0 ? Direction.SOUTH : Direction.NORTH);

            if (player instanceof ClientPlayerEntity cpe
                    && mc.interactionManager != null
                    && mc.world != null
                    && !mc.world.getBlockState(playerFloor).isAir()) {
                selectBlockSlot(cpe);
                Vec3d hitVec = Vec3d.ofCenter(playerFloor).offset(placeDir, 0.5);
                mc.interactionManager.interactBlock(cpe, Hand.MAIN_HAND,
                        new BlockHitResult(hitVec, placeDir, playerFloor, false));
            }

            // Walk toward the gap while sneaking so player doesn't fall in
            setKey(mc.options.sneakKey, true);
            faceAndWalk(player, mc, dx, dz, false);
            return;
        }

        // --- CLIMB: ladders, scaffolding, and all vine variants ---
        // Root cause of spinning: faceAndWalk() uses atan2(dz, dx). When the target is
        // directly above (dx≈0, dz≈0), atan2 returns arbitrary values → camera spins.
        // Fix: never change yaw during climbing. Snap yaw ONCE for ladders; hold it stable
        // for everything else.
        if (targetNode.type == PathNode.Type.CLIMB) {
            boolean goingUp = targetCenter.y >= player.getY();

            ClimbType climbType = ClimbType.VINE_WALL; // fallback
            if (mc.world != null) {
                var b1 = mc.world.getBlockState(player.getBlockPos()).getBlock();
                var b2 = mc.world.getBlockState(targetNode.pos).getBlock();
                if (b1 instanceof ScaffoldingBlock || b2 instanceof ScaffoldingBlock) {
                    climbType = ClimbType.SCAFFOLDING;
                } else if (b1 instanceof LadderBlock || b2 instanceof LadderBlock) {
                    climbType = ClimbType.LADDER;
                } else if (b1 instanceof TwistingVinesBlock || b2 instanceof TwistingVinesBlock) {
                    // Twisting vines grow upward — pole-like, jump is sufficient
                    climbType = ClimbType.VINE_POLE;
                } else if (b1 instanceof WeepingVinesBlock || b2 instanceof WeepingVinesBlock) {
                    // Weeping vines hang downward — hold W to grip while going up
                    climbType = ClimbType.VINE_HANGING;
                } else if (b1 instanceof VineBlock || b2 instanceof VineBlock) {
                    // Regular wall vines — hold W to press into wall + jump
                    climbType = ClimbType.VINE_WALL;
                }
                // Cave vines, glow lichen, and any other CLIMBABLE tag block fall through
                // to the default VINE_WALL behaviour (hold W + jump)
            }

            setKey(mc.options.sprintKey, false);
            setKey(mc.options.leftKey,   false);
            setKey(mc.options.rightKey,  false);

            if (!goingUp) {
                // ── Descending ──────────────────────────────────────────────────────
                mc.options.jumpKey.setPressed(false);
                if (climbType == ClimbType.SCAFFOLDING) {
                    // Scaffolding only descends when sneaking
                    setKey(mc.options.sneakKey,   true);
                    setKey(mc.options.forwardKey, false);
                    setKey(mc.options.backKey,    false);
                } else {
                    // Ladders and all vines: release everything, gravity carries player down
                    setKey(mc.options.sneakKey,   false);
                    setKey(mc.options.forwardKey, false);
                    setKey(mc.options.backKey,    false);
                }
            } else {
                // ── Ascending ───────────────────────────────────────────────────────
                setKey(mc.options.sneakKey,  false);
                setKey(mc.options.backKey,   false);
                mc.options.jumpKey.setPressed(true); // jump is the universal "go up" key

                switch (climbType) {
                    case LADDER -> {
                        // Snap yaw toward the ladder face; W is NOT needed — jump alone climbs
                        setKey(mc.options.forwardKey, false);
                        snapToLadderFace(player, player.getBlockPos(), targetNode.pos, mc);
                    }
                    case SCAFFOLDING, VINE_POLE -> {
                        // No rotation or forward press needed — jump is sufficient
                        setKey(mc.options.forwardKey, false);
                    }
                    case VINE_WALL, VINE_HANGING -> {
                        // Must press W toward the vine surface to grip it; don't change yaw
                        setKey(mc.options.forwardKey, true);
                    }
                }
            }
            return;
        }

        // --- WATER_DROP: aim at landing block from tick 1; place bucket when in reach ---
        if (targetNode.type == PathNode.Type.WATER_DROP) {
            // Solid ground the player must land on (one below the air-landing pos)
            BlockPos groundBlock = new BlockPos(
                    targetNode.pos.getX(), targetNode.pos.getY() - 1, targetNode.pos.getZ());

            // Vector from player eye to the centre of the ground block's top face
            double tgx = groundBlock.getX() + 0.5 - player.getX();
            double tgy = (groundBlock.getY() + 1.0) - (player.getY() + 1.62);
            double tgz = groundBlock.getZ() + 0.5 - player.getZ();
            double tghd = Math.sqrt(tgx * tgx + tgz * tgz);

            // Aim at landing: track the block center at height, then snap straight down
            // within 4 blocks so the placement hitbox is as large as possible near impact.
            double distToGroundNow = player.getY() - (groundBlock.getY() + 1.0);
            float desiredPitch = (distToGroundNow <= 4.0)
                    ? 90.0f
                    : (float) -Math.toDegrees(Math.atan2(tgy, tghd));
            player.setPitch(player.getPitch() + (desiredPitch - player.getPitch()) * 0.5f);
            if (hDist > 0.2) {
                float desiredYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                player.setYaw(player.getYaw() + normalizeAngle(desiredYaw - player.getYaw()) * 0.4f);
            }

            // Walk off the edge while still on ground; stay still in air
            if (player.isOnGround() && hDist > 0.2) {
                setKey(mc.options.forwardKey, true);
                setKey(mc.options.backKey,    false);
                setKey(mc.options.leftKey,    false);
                setKey(mc.options.rightKey,   false);
                setKey(mc.options.sprintKey,  false);
            } else {
                releaseMovementKeys();
            }
            mc.options.jumpKey.setPressed(false);

            // Keep water bucket selected every tick and sync the change to the server.
            // Without the UpdateSelectedSlotC2SPacket the server uses the wrong item.
            if (player instanceof net.minecraft.client.network.ClientPlayerEntity cpe) {
                int bucketSlot = findWaterBucketSlot(cpe);
                if (bucketSlot >= 0 && cpe.getInventory().selectedSlot != bucketSlot) {
                    cpe.getInventory().selectedSlot = bucketSlot;
                    cpe.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(bucketSlot));
                }
            }

            // Single-tick lookahead clutch trigger.
            //
            // The 15-tick loop broke at high fall speeds: when vy approaches terminal
            // velocity (~3.9 blocks/tick), a single game tick moves the player >3.9 blocks.
            // The loop hit `distToGround <= 0` and broke without firing because the 1.5-block
            // window was narrower than one tick of fall — the player skipped past it entirely.
            //
            // Fix: predict only ONE tick ahead (player.getY() + vy).  If that lands ≤ 2.5 blocks
            // above ground, fire NOW.  The wider 2.5-block window gives the server 1-2 ticks of
            // registration time.  Emergency guard: if the player is ALREADY within 1.5 blocks
            // (high-speed overshoot — the prediction window was missed), fire immediately.
            double eyeDist       = Math.sqrt(tgx * tgx + tgy * tgy + tgz * tgz);
            double groundSurface = groundBlock.getY() + 1.0;
            if (!waterClutchPlaced && !player.isOnGround() && eyeDist <= 4.4) {
                double currAboveGround = player.getY() - groundSurface;
                double nextAboveGround = (player.getY() + player.getVelocity().y) - groundSurface;
                if (nextAboveGround <= 2.5 || currAboveGround <= 1.5) {
                    useKeyHoldTicks   = 3;
                    waterClutchPlaced = true;
                }
            }

            if (player.isOnGround() && Math.abs(dy) < 1.5) waterClutchPlaced = false;
            return;
        }

        // --- JUMP: trigger jump when close horizontally and need to go up (max 1 block) ---
        if (targetNode.type == PathNode.Type.JUMP
                && dy > 0.2 && dy <= MAX_JUMP_UP
                && player.isOnGround() && jumpCooldown <= 0) {
            mc.options.jumpKey.setPressed(true);
            jumpCooldown = 8;
        } else if (jumpCooldown <= 0) {
            mc.options.jumpKey.setPressed(false);
        }

        // --- Look-ahead cliff stopping (Tier 1 — proactive) ---
        // Scan forward along the player's VELOCITY direction (not just the target direction)
        // so that momentum carrying the player sideways toward a cliff is caught even when
        // the target waypoint is in a different direction.
        //
        // Only fire for WALK/EDGE nodes (the bot should NOT brake for SPRINT_JUMP,
        // DROP, or WATER_DROP, which intentionally approach or fall off edges).
        // Also skip when the NEXT node is SPRINT_JUMP — the gap ahead IS the gap the
        // bot needs to jump across; braking here kills the momentum needed to clear it.
        boolean nextIsSprint = currentNodeIndex + 1 < currentPath.size()
                && currentPath.get(currentNodeIndex + 1).type == PathNode.Type.SPRINT_JUMP;
        boolean nextIsDrop = nextNodeIsIntentionalGap();
        if (player.isOnGround() && mc.world != null
                && !nextIsSprint
                && !nextIsDrop
                && (targetNode.type == PathNode.Type.WALK
                 || targetNode.type == PathNode.Type.EDGE)) {
            double velX = player.getVelocity().x;
            double velZ = player.getVelocity().z;
            double hSpeed = Math.sqrt(velX * velX + velZ * velZ);
            if (hSpeed > 0.05) {
                // Prefer velocity direction over target direction when moving at speed —
                // the cliff we'll actually fall off is where our momentum is pointing.
                double scanDx = velX / hSpeed;
                double scanDz = velZ / hSpeed;
                double cliffDist = cliffDistanceAhead(mc.world, player, scanDx, scanDz);
                // Budget = future braking distance + THIS tick's un-stoppable movement.
                double stopBudget = calculateStoppingDistance(velX, velZ) + hSpeed;
                if (cliffDist <= stopBudget) {
                    float brakeYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                    player.setYaw(player.getYaw() +
                            normalizeAngle(brakeYaw - player.getYaw()) * 0.35f);
                    setKey(mc.options.forwardKey, false);
                    setKey(mc.options.backKey,    true);
                    setKey(mc.options.leftKey,    false);
                    setKey(mc.options.rightKey,   false);
                    setKey(mc.options.sprintKey,  false);
                    mc.options.jumpKey.setPressed(false);
                    return;
                }
            }
        }

        // --- Reactive braking (Tier 2 — fallback) ---
        // Counter-strafe when close to a WALK/EDGE target at high speed.
        // EDGE nodes get a wider window (2.0 blocks) because the player must stop
        // precisely at the ledge before stepping off; WALK nodes use the tighter 0.8.
        // Skip when the next node is SPRINT_JUMP — full momentum must be preserved.
        double reactBrakeWindow = (targetNode.type == PathNode.Type.EDGE) ? 1.2 : 0.8;
        if (player.isOnGround()
                && !nextIsSprint
                && !nextIsDrop
                && (targetNode.type == PathNode.Type.WALK || targetNode.type == PathNode.Type.EDGE)
                && hDist < reactBrakeWindow && hDist > reachTolerance) {
            double hSpeed = Math.sqrt(
                    player.getVelocity().x * player.getVelocity().x +
                    player.getVelocity().z * player.getVelocity().z);
            if (hSpeed > 0.15) {
                // Face the target but hold back — friction kills the excess speed in 1-2 ticks.
                float brakeYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                float brakeDiff = normalizeAngle(brakeYaw - player.getYaw());
                player.setYaw(player.getYaw() + brakeDiff * 0.35f);
                setKey(mc.options.forwardKey, false);
                setKey(mc.options.backKey,    true);
                setKey(mc.options.leftKey,    false);
                setKey(mc.options.rightKey,   false);
                setKey(mc.options.sprintKey,  false);
                mc.options.jumpKey.setPressed(false);
                return;
            }
        }

        // --- Standard horizontal movement ---
        // EDGE nodes precede DROP/BOUNCE — the player must stop precisely at the ledge,
        // so sprint is always disabled to keep approach speed low and controllable.
        boolean sprint = sprintModeForced
                ? (targetNode.type != PathNode.Type.CLIMB && targetNode.type != PathNode.Type.EDGE)
                : sprintOn && targetNode.type != PathNode.Type.JUMP
                           && targetNode.type != PathNode.Type.CLIMB
                           && targetNode.type != PathNode.Type.EDGE;
        // Pre-sprint runway: force sprint if any of the next 3 nodes is a SPRINT_JUMP.
        // Looking only 1 node ahead was insufficient — WALK nodes 2-3 nodes before the
        // jump were traversed without sprint, so the player arrived at the launch position
        // with 20 % less speed than needed, consistently undershooting the gap.
        // 3 nodes covers ~3-6 blocks of approach, enough to build full sprint velocity.
        if (!sprint) {
            for (int k = 1; k <= 3 && currentNodeIndex + k < currentPath.size(); k++) {
                if (currentPath.get(currentNodeIndex + k).type == PathNode.Type.SPRINT_JUMP) {
                    sprint = true;
                    break;
                }
            }
        }
        // --- Pure pursuit steering: funnel goal on the path centre line ----------
        // computeFunnelGoal blends velocity direction with the corridor so corners
        // don't snap the aim point and cause yaw flicker.
        if (cumulativeDist != null
                && (targetNode.type == PathNode.Type.WALK
                 || targetNode.type == PathNode.Type.EDGE
                 || targetNode.type == PathNode.Type.SPRINT_JUMP)) {
            double hSpeed = Math.sqrt(player.getVelocity().x * player.getVelocity().x
                                    + player.getVelocity().z * player.getVelocity().z);
            double tPlayer = projectOnPath(player.getX(), player.getZ());
            double sharpness = cornerSharpnessAhead(tPlayer);
            double L = sprintModeForced
                    ? Math.max(2.0, Math.min(4.0, hSpeed * 15))
                    : Math.max(0.6, Math.min(2.5, hSpeed * 12));
            // Tight corners: shorten look-ahead so the steering target doesn't leap
            // across the turn and flip the camera 90° in one tick.
            if (sharpness > 35.0) {
                L = Math.min(L, 0.8);
            } else if (sharpness > 20.0) {
                L = Math.min(L, 1.4);
            }
            Vec3d goal = computeFunnelGoal(player, tPlayer, L);
            if (goal == null) goal = goalPoint(tPlayer + L);
            if (goal != null) {
                double sdx = goal.x - player.getX();
                double sdz = goal.z - player.getZ();
                double steerDx = sdx, steerDz = sdz;
                if (hasSteerDir) {
                    // Exponential smoothing on steering vector — damps oscillation when
                    // projectOnPath hops between segments at a corner.
                    double alpha = sharpness > 35.0 ? 0.25 : (sharpness > 20.0 ? 0.40 : 0.65);
                    steerDx = lastSteerDx + (sdx - lastSteerDx) * alpha;
                    steerDz = lastSteerDz + (sdz - lastSteerDz) * alpha;
                }
                lastSteerDx = steerDx;
                lastSteerDz = steerDz;
                hasSteerDir = true;
                faceAndWalk(player, mc, steerDx, steerDz, sprint);
                return;
            }
        }
        faceAndWalk(player, mc, dx, dz, sprint);
    }

    /**
     * Returns the maximum turn angle (degrees) between the current path segment and
     * any segment within the next 2.5 blocks of arc-length ahead of {@code tPlayer}.
     * 0 = straight, 90 = right-angle corner.
     */
    private static double cornerSharpnessAhead(double tPlayer) {
        if (currentPath == null || cumulativeDist == null || currentPath.size() < 3) return 0;
        double maxAngle = 0;
        for (int i = 0; i < currentPath.size() - 2; i++) {
            if (cumulativeDist[i + 1] < tPlayer - 0.5) continue;
            if (cumulativeDist[i] > tPlayer + 2.5) break;

            Vec3d a = currentPath.get(i).getFeetPos();
            Vec3d b = currentPath.get(i + 1).getFeetPos();
            Vec3d c = currentPath.get(i + 2).getFeetPos();
            double inDx = b.x - a.x, inDz = b.z - a.z;
            double outDx = c.x - b.x, outDz = c.z - b.z;
            double inLen = Math.sqrt(inDx * inDx + inDz * inDz);
            double outLen = Math.sqrt(outDx * outDx + outDz * outDz);
            if (inLen < 0.05 || outLen < 0.05) continue;

            double dot = (inDx * outDx + inDz * outDz) / (inLen * outLen);
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            if (angle > maxAngle) maxAngle = angle;
        }
        return maxAngle;
    }

    /** True when the next node intentionally walks or falls off a ledge. */
    private static boolean nextNodeIsIntentionalGap() {
        if (currentPath == null || currentNodeIndex + 1 >= currentPath.size()) return false;
        PathNode.Type next = currentPath.get(currentNodeIndex + 1).type;
        return next == PathNode.Type.DROP
                || next == PathNode.Type.WATER_DROP
                || next == PathNode.Type.BOUNCE;
    }

    // -----------------------------------------------------------------------
    // Funnel-based steering goal
    // -----------------------------------------------------------------------

    /**
     * Computes a steering goal that follows the player's current velocity direction
     * unless that direction would violate an upcoming corridor portal, in which case
     * the goal is clamped to the nearest valid portal edge.
     *
     * <p>This eliminates the "center-lock" of pure pursuit: the player travels naturally
     * in their own direction through open space and is only redirected when needed.
     *
     * @param tPlayer arc-length parameter of the player's projected position on the path
     * @param L       look-ahead distance in blocks
     */
    private static Vec3d computeFunnelGoal(PlayerEntity player, double tPlayer, double L) {
        if (currentPath == null || cumulativeDist == null) return null;

        Vec3d pos = new Vec3d(player.getX(), player.getY(), player.getZ());
        double vx = player.getVelocity().x, vz = player.getVelocity().z;
        double hSpeed = Math.sqrt(vx * vx + vz * vz);

        // Compute the "natural" goal from current velocity direction.
        // Blend toward the centerline when nearly stopped to avoid a jittery goal.
        Vec3d naturalGoal;
        if (hSpeed > 0.15) {
            // Moving confidently: project straight ahead in velocity direction
            naturalGoal = pos.add(vx / hSpeed * L, 0, vz / hSpeed * L);
        } else if (hSpeed > 0.05) {
            // Slow: blend velocity goal with centerline goal
            double w = (hSpeed - 0.05) / 0.10; // 0→1 as speed rises from 0.05 to 0.15
            Vec3d center = goalPoint(tPlayer + L);
            Vec3d velGoal = pos.add(vx / hSpeed * L, 0, vz / hSpeed * L);
            naturalGoal = center != null
                ? new Vec3d(center.x + (velGoal.x - center.x) * w,
                            center.y,
                            center.z + (velGoal.z - center.z) * w)
                : velGoal;
        } else {
            // Stopped: fall back to centerline to prevent drift
            return goalPoint(tPlayer + L);
        }

        if (naturalGoal == null) return null;

        // Test each upcoming portal for constraint violations.
        // A portal is the perpendicular cross-section of the corridor at each node boundary.
        for (int i = currentNodeIndex; i < currentPath.size() - 1; i++) {
            if (cumulativeDist[i + 1] <= tPlayer) continue;          // portal is behind
            if (cumulativeDist[i + 1] > tPlayer + L + 1.0) break;   // portal is too far

            Vec3d A = currentPath.get(i).getFeetPos();
            Vec3d B = currentPath.get(i + 1).getFeetPos();
            double segDx = B.x - A.x, segDz = B.z - A.z;
            double segLen = Math.sqrt(segDx * segDx + segDz * segDz);
            if (segLen < 0.01) continue;

            // Portal perpendicular unit vector at B
            double px = -segDz / segLen, pz = segDx / segLen;

            // Lateral offset of naturalGoal from the portal centre
            double lateralOffset = (naturalGoal.x - B.x) * px + (naturalGoal.z - B.z) * pz;

            if (Math.abs(lateralOffset) > 0.3) {
                // Natural trajectory misses this portal — clamp to nearest valid edge
                double clamped = Math.max(-0.3, Math.min(0.3, lateralOffset));
                return new Vec3d(B.x + px * clamped, B.y, B.z + pz * clamped);
            }
        }

        // All portals clear: player can go straight — no center correction needed
        return naturalGoal;
    }

    // -----------------------------------------------------------------------
    // Hole-escape heuristic
    // -----------------------------------------------------------------------

    /**
     * Detects when the bot has fallen into a narrow hole (≥3 of 4 horizontal sides
     * blocked at both foot and head level) and jumps out if above is open.
     *
     * @return {@code true} if an escape action was taken this tick.
     */
    private static boolean tryEscapeHole(PlayerEntity player, MinecraftClient mc) {
        if (mc.world == null || !player.isOnGround()) return false;

        BlockPos feet = player.getBlockPos();

        int solidFoot = 0, solidHead = 0;
        Direction openDir = null;

        for (Direction d : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST }) {
            var sfState = mc.world.getBlockState(feet.offset(d));
            var shState = mc.world.getBlockState(feet.up().offset(d));
            if (!sfState.isAir()) solidFoot++;
            if (!shState.isAir()) solidHead++;
            if (sfState.isAir() && shState.isAir() && openDir == null) openDir = d;
        }

        if (solidFoot < 3 && solidHead < 3) return false;

        boolean aboveOpen = mc.world.getBlockState(feet.up(2)).isAir();
        if (aboveOpen) {
            mc.options.jumpKey.setPressed(true);
            if (openDir != null) {
                faceAndWalk(player, mc, openDir.getOffsetX(), openDir.getOffsetZ(), false);
            } else {
                setKey(mc.options.forwardKey, true);
                setKey(mc.options.backKey,    false);
                setKey(mc.options.leftKey,    false);
                setKey(mc.options.rightKey,   false);
                setKey(mc.options.sprintKey,  false);
            }
            return true;
        }

        return false;
    }

    // -----------------------------------------------------------------------
    // Dynamic re-planning
    // -----------------------------------------------------------------------

    /**
     * Submits a fresh A* from the player's current position to the goal on a background
     * thread so the client tick never stalls.  The result is applied at the top of the
     * next {@link #tick} call via the {@code pendingReplanFuture} check.
     */
    private static void triggerReplan(PlayerEntity player, MinecraftClient mc) {
        if (replanGoal == null || mc.world == null) { stopFollowing(); return; }
        if (pendingReplanFuture != null && !pendingReplanFuture.isDone()) return; // already computing

        net.minecraft.util.math.BlockPos goal = replanGoal;
        boolean sprint  = replanSprint;
        Vec3d   precise = new Vec3d(player.getX(), player.getY(), player.getZ());
        net.minecraft.util.math.BlockPos startPos = player.getBlockPos();
        net.minecraft.world.World world = mc.world; // read-only use on background thread

        pendingReplanGoalCap   = goal;
        pendingReplanSprintCap = sprint;
        replanCooldown         = REPLAN_INTERVAL;
        consecutiveStuck       = 0;

        pendingReplanFuture = PATHFIND_EXECUTOR.submit(() -> sprint
            ? net.netherite.tutorialmod.pathfinder.PathfinderEngine.findSprintNodePath(
                    world, startPos, precise, goal, false)
            : net.netherite.tutorialmod.pathfinder.PathfinderEngine.findNodePath(
                    world, startPos, precise, goal, false));
    }

    // -----------------------------------------------------------------------
    // Pure pursuit helpers
    // -----------------------------------------------------------------------

    /** Computes cumulative horizontal arc-lengths for each node in the path. */
    private static void buildArcLengths(List<PathNode> path) {
        cumulativeDist = new double[path.size()];
        cumulativeDist[0] = 0;
        for (int i = 1; i < path.size(); i++) {
            Vec3d a = path.get(i - 1).getFeetPos();
            Vec3d b = path.get(i).getFeetPos();
            double dx = b.x - a.x, dz = b.z - a.z;
            cumulativeDist[i] = cumulativeDist[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }
        totalPathLen = cumulativeDist[path.size() - 1];
    }

    /**
     * Projects (px, pz) onto the path polyline and returns the arc-length parameter t
     * of the closest point.  Only XZ is used — Y is irrelevant for horizontal steering.
     */
    private static double projectOnPath(double px, double pz) {
        if (currentPath == null || cumulativeDist == null) return 0;
        double bestT = 0, bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < currentPath.size() - 1; i++) {
            Vec3d a = currentPath.get(i).getFeetPos();
            Vec3d b = currentPath.get(i + 1).getFeetPos();
            double segDx = b.x - a.x, segDz = b.z - a.z;
            double segLen2 = segDx * segDx + segDz * segDz;
            if (segLen2 < 1e-6) continue;
            double t = ((px - a.x) * segDx + (pz - a.z) * segDz) / segLen2;
            t = Math.max(0, Math.min(1, t));
            double cx = a.x + t * segDx, cz = a.z + t * segDz;
            double ddx = px - cx, ddz = pz - cz;
            double distSq = ddx * ddx + ddz * ddz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestT = cumulativeDist[i] + t * Math.sqrt(segLen2);
            }
        }
        return bestT;
    }

    /**
     * Returns the 3D point at arc-length t along the path polyline.
     * Clamps to the end of the path if t exceeds total length.
     */
    private static Vec3d goalPoint(double t) {
        if (currentPath == null || cumulativeDist == null) return null;
        t = Math.min(t, totalPathLen);
        for (int i = 0; i < currentPath.size() - 1; i++) {
            if (t <= cumulativeDist[i + 1]) {
                Vec3d a = currentPath.get(i).getFeetPos();
                Vec3d b = currentPath.get(i + 1).getFeetPos();
                double segLen = cumulativeDist[i + 1] - cumulativeDist[i];
                double frac = (segLen > 1e-6) ? (t - cumulativeDist[i]) / segLen : 0;
                return new Vec3d(a.x + (b.x - a.x) * frac,
                                 a.y + (b.y - a.y) * frac,
                                 a.z + (b.z - a.z) * frac);
            }
        }
        return currentPath.get(currentPath.size() - 1).getFeetPos();
    }

    // -----------------------------------------------------------------------
    // Climb helpers
    // -----------------------------------------------------------------------

    private enum ClimbType { LADDER, SCAFFOLDING, VINE_WALL, VINE_HANGING, VINE_POLE }

    /**
     * Smoothly snaps the player's yaw toward the direction the ladder faces so the
     * player is pressing into it. LadderBlock.FACING = the open-air side of the ladder
     * (the side you stand on to climb). Player must face that same direction.
     */
    private static void snapToLadderFace(PlayerEntity player, BlockPos playerBlock,
                                         BlockPos targetBlock, MinecraftClient mc) {
        if (mc.world == null) return;
        var state = mc.world.getBlockState(playerBlock);
        if (!(state.getBlock() instanceof LadderBlock)) {
            state = mc.world.getBlockState(targetBlock);
        }
        if (!(state.getBlock() instanceof LadderBlock)) return;

        Direction facing = state.get(LadderBlock.FACING);
        float targetYaw = switch (facing) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST  -> -90f;
            case WEST  -> 90f;
            default    -> player.getYaw();
        };
        float diff = normalizeAngle(targetYaw - player.getYaw());
        player.setYaw(player.getYaw() + diff * 0.4f);
    }

    // -----------------------------------------------------------------------
    // Core movement: face target direction and press W
    // -----------------------------------------------------------------------

    /**
     * Sets the player's yaw to face (dx, dz) with smooth interpolation and
     * a small humanization wobble, then presses the forward key.
     *
     * MC yaw: 0=south(+Z), 90=west(-X), -90=east(+X), 180=north(-Z)
     * Formula: desiredYaw = atan2(dz, dx) * 180/π - 90
     */
    private static void faceAndWalk(PlayerEntity player, MinecraftClient mc,
                                    double dx, double dz, boolean sprint) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.05) {
            releaseMovementKeys();
            return;
        }

        float desiredYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float diff = normalizeAngle(desiredYaw - player.getYaw());
        // Cap per-tick yaw rotation at corners to stop camera flicker when the
        // steering target hops between path segments.
        float maxTurn = 18f;
        if (Math.abs(diff) > maxTurn) {
            diff = Math.copySign(maxTurn, diff);
        }
        float lerpRate = (Math.abs(diff) > 5f) ? 0.55f : 0.85f;
        player.setYaw(player.getYaw() + diff * lerpRate);

        applyVelocityPitch(player);

        // Yaw-gate: don't move until roughly aligned.  On ledges use a tight threshold;
        // at corners allow more slack so the bot doesn't stop-start while turning.
        float threshold = hasDrop(player, mc, dx, dz) ? 25f : 45f;
        if (Math.abs(diff) > threshold) {
            setKey(mc.options.forwardKey, false);
            setKey(mc.options.backKey,    false);
            setKey(mc.options.sprintKey,  false);
            return;
        }

        setKey(mc.options.forwardKey, true);
        setKey(mc.options.backKey,    false);
        setKey(mc.options.leftKey,    false);
        setKey(mc.options.rightKey,   false);
        setKey(mc.options.sprintKey,  sprint);
    }

    /**
     * Returns {@code true} when either side of the player (perpendicular to the
     * travel direction (dx, dz)) has a drop-off: the adjacent block at player level
     * AND the block one below it are both air.  Used to tighten the yaw alignment
     * threshold on bridges/ledges where moving before aligned would cause a fall.
     */
    private static boolean hasDrop(PlayerEntity player, MinecraftClient mc,
                                   double dx, double dz) {
        if (mc.world == null) return false;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return false;
        double perpX = -dz / len;
        double perpZ =  dx / len;
        int fy = (int) Math.floor(player.getY());

        for (int side = -1; side <= 1; side += 2) {
            int bx = (int) Math.floor(player.getX() + perpX * side);
            int bz = (int) Math.floor(player.getZ() + perpZ * side);
            boolean airBeside = mc.world.getBlockState(new BlockPos(bx, fy,     bz)).isAir();
            boolean airBelow  = mc.world.getBlockState(new BlockPos(bx, fy - 1, bz)).isAir();
            if (airBeside && airBelow) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Auto door opening
    // -----------------------------------------------------------------------

    private static void autoInteractDoors(PlayerEntity player, MinecraftClient mc,
                                          double dirX, double dirZ) {
        if (mc.world == null) return;
        BlockPos origin = player.getBlockPos();

        for (int i = 1; i <= 2; i++) {
            BlockPos ahead = origin.add(
                    (int) Math.round(dirX * i), 0, (int) Math.round(dirZ * i));
            checkAndOpenDoor(player, mc, ahead);
            checkAndOpenDoor(player, mc, ahead.up());
        }
    }

    private static void checkAndOpenDoor(PlayerEntity player, MinecraftClient mc, BlockPos pos) {
        if (mc.world == null) return;
        var state = mc.world.getBlockState(pos);
        boolean isClosedDoor =
                (state.getBlock() instanceof DoorBlock && !state.get(DoorBlock.OPEN))
             || (state.getBlock() instanceof FenceGateBlock && !state.get(FenceGateBlock.OPEN))
             || (state.getBlock() instanceof TrapdoorBlock && !state.get(TrapdoorBlock.OPEN));
        if (isClosedDoor) {
            faceBlock(player, pos);
            setPitchToBlock(player, pos);
            interactWithBlock(player, pos);
        }
    }

    // -----------------------------------------------------------------------
    // Block interaction (right-click)
    // -----------------------------------------------------------------------

    private static void interactWithBlock(PlayerEntity player, BlockPos blockPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(player instanceof ClientPlayerEntity cpe) || mc.interactionManager == null) return;

        Vec3d hitVec = Vec3d.ofCenter(blockPos);
        Direction face = getApproachFace(player.getX(), player.getZ(), blockPos);

        mc.interactionManager.interactBlock(cpe, Hand.MAIN_HAND,
                new BlockHitResult(hitVec, face, blockPos, false));
    }

    private static void selectBlockSlot(ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            var st = bi.getBlock().getDefaultState();
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (bi.getBlock() instanceof FallingBlock) continue;
            if (inv.selectedSlot != i) {
                inv.selectedSlot = i;
                player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(i));
            }
            return;
        }
    }

    /** Rotate player yaw to look at a block position. */
    private static void faceBlock(PlayerEntity player, BlockPos target) {
        double dx = (target.getX() + 0.5) - player.getX();
        double dz = (target.getZ() + 0.5) - player.getZ();
        player.setYaw(normalizeAngle((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f));
    }

    /**
     * Sets the player's pitch to aim at the landing node's surface.
     * {@code hDist} is the horizontal distance to the landing node;
     * {@code dy} is the feet-to-feet height difference (positive = uphill).
     * For a same-height 4-block jump this gives ≈ 22° downward — natural parkour aim.
     */
    private static void applyLandingPitch(PlayerEntity player, double hDist, double dy) {
        if (hDist < 0.01) return;
        // Player eye is 1.62 above feet; target is at feet level (dy from player feet)
        double eyeToTargetY = dy - 1.62; // positive = target feet above eye (uphill)
        float target = (float) Math.toDegrees(Math.atan2(-eyeToTargetY, hDist));
        target = Math.max(-90f, Math.min(90f, target));
        player.setPitch(player.getPitch() + (target - player.getPitch()) * 0.5f);
    }

    /**
     * Sets the player's pitch to match the direction of their current velocity vector.
     * This makes the view align with the trajectory the magenta eye-height line depicts:
     * looking slightly upward at jump launch, level at the arc peak, downward while falling.
     * A 0.4 lerp keeps transitions smooth without snapping.
     */
    private static void applyVelocityPitch(PlayerEntity player) {
        double vx = player.getVelocity().x;
        double vy = player.getVelocity().y;
        double vz = player.getVelocity().z;
        double hSpeed = Math.sqrt(vx*vx + vz*vz);
        if (hSpeed > 0.05) {
            float targetPitch = (float) Math.toDegrees(Math.atan2(-vy, hSpeed));
            player.setPitch(player.getPitch() + (targetPitch - player.getPitch()) * 0.4f);
        }
    }

    /**
     * Rotate player pitch toward a block center — makes door interaction look
     * natural instead of staring straight ahead while clicking.
     */
    private static void setPitchToBlock(PlayerEntity player, BlockPos target) {
        double dx   = (target.getX() + 0.5) - player.getX();
        double dy   = (target.getY() + 1.0) - (player.getY() + 1.62); // block center vs eye
        double dz   = (target.getZ() + 0.5) - player.getZ();
        double hd   = Math.sqrt(dx * dx + dz * dz);
        float desired = (float) -Math.toDegrees(Math.atan2(dy, hd));
        float newPitch = player.getPitch() + (desired - player.getPitch()) * 0.4f;
        player.setPitch(newPitch);
    }

    /** Returns the block face closest to the approaching player. */
    private static Direction getApproachFace(double px, double pz, BlockPos block) {
        double dx = px - (block.getX() + 0.5);
        double dz = pz - (block.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // -----------------------------------------------------------------------
    // Key helpers
    // -----------------------------------------------------------------------

    private static void releaseMovementKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        setKey(mc.options.forwardKey, false);
        setKey(mc.options.backKey,    false);
        setKey(mc.options.leftKey,    false);
        setKey(mc.options.rightKey,   false);
        setKey(mc.options.sprintKey,  false);
    }

    private static void releaseAllKeys() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        releaseMovementKeys();
        setKey(mc.options.jumpKey, false);
    }

    private static void setKey(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
    }

    private static float normalizeAngle(float a) {
        while (a >  180f) a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    /**
     * Simulates Minecraft's ground-friction braking formula
     *   V_next = (V_current − 0.098) × 0.546
     * tick-by-tick to compute the total horizontal distance the player will
     * slide after the S-key is pressed, starting from speed (vx, vz).
     * A 0.15-block safety buffer is appended to account for block-grid
     * discretization and server-side registration lag.
     */
    private static double calculateStoppingDistance(double vx, double vz) {
        double speed = Math.sqrt(vx * vx + vz * vz);
        double dist  = 0.0;
        while (speed > 0.01) {
            speed = (speed - 0.098) * 0.546;
            if (speed < 0.0) speed = 0.0;
            dist += speed;
        }
        return dist + 0.15;
    }

    /**
     * Scans forward along the given pre-normalized (nx, nz) direction from the
     * player's actual floating-point position, stepping every 0.5 blocks so that
     * sub-block cliff positions are detected accurately.
     *
     * Returns the distance to the first block column with no solid ground within
     * a ±2-block vertical window, or CLIFF_SCAN_RANGE if none is found.
     * The wider vertical window prevents the scan from treating large drops as
     * ordinary step-downs.
     *
     * @param nx pre-normalized X component of the scan direction
     * @param nz pre-normalized Z component of the scan direction
     */
    private static double cliffDistanceAhead(World world, PlayerEntity player,
                                             double nx, double nz) {
        if (Math.abs(nx) < 0.001 && Math.abs(nz) < 0.001) return CLIFF_SCAN_RANGE;
        int playerY = player.getBlockPos().getY();

        double prevD = 0.0;
        for (double d = 0.5; d <= CLIFF_SCAN_RANGE; d += 0.5) {
            int bx = (int) Math.floor(player.getX() + nx * d);
            int bz = (int) Math.floor(player.getZ() + nz * d);
            // ±2-block vertical window: +1 for step-ups, -2 for drops beyond slab level.
            boolean hasGround = false;
            for (int dy = 1; dy >= -2; dy--) {
                if (PathingEnvironment.hasSolidGround(world, new BlockPos(bx, playerY + dy, bz))) {
                    hasGround = true;
                    break;
                }
            }
            if (!hasGround) {
                // Return the distance to the last safe sample, not the failing one.
                return Math.max(0.0, prevD);
            }
            prevD = d;
        }
        return CLIFF_SCAN_RANGE;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private static void forceAdvanceNode() {
        currentNodeIndex++;
        reachTolerance = 0.35 + RNG.nextDouble() * 0.15;
        resetNodeTimer();
        if (currentNodeIndex >= currentPath.size()) { stopFollowing(); }
    }

    private static void resetNodeTimer() {
        nodeTicksSpent  = 0;
        stuckCheckTimer = 0;
        stuckEventCount = 0;
        lastStuckCheckPos = null;
    }

    /**
     * Returns the hotbar slot index (0–8) holding a water bucket, or -1 if not found.
     * Only the hotbar is checked because the player must have the bucket accessible
     * without opening inventory.
     */
    private static int findWaterBucketSlot(net.minecraft.client.network.ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.WATER_BUCKET)) return i;
        }
        return -1;
    }

    /**
     * Estimates how far a sprint jump will travel horizontally from the player's
     * current position, given their current velocity and the direction to the target.
     *
     * Simulates Minecraft physics tick-by-tick:
     *   - Adds the sprint-jump horizontal boost (0.2 blocks/tick) in the target direction
     *   - Applies the jump vertical impulse (0.42 blocks/tick)
     *   - Each tick: vy = (vy - 0.08) * 0.98, vx/vz *= 0.91 (air drag)
     *   - Stops when the player returns to launch height (flat ground assumption)
     *
     * The result lets the jump gate decide whether the current velocity is enough
     * to reach (or not overshoot) the target — solving the "first jump from rest is
     * too short" problem without needing world access.
     */
    /**
     * @param dy vertical height difference to target (positive = uphill, negative = downhill).
     *           Pass 0 for same-level jumps.
     */
    private static double estimateSprintJumpDistance(PlayerEntity player, double dx, double dz,
                                                      double dy) {
        double hdist = Math.sqrt(dx * dx + dz * dz);
        if (hdist < 0.001) return 0;

        double vx = player.getVelocity().x;
        double vz = player.getVelocity().z;
        float currentYaw = player.getYaw();

        double sinYaw = Math.sin(currentYaw * Math.PI / 180.0);
        double cosYaw = Math.cos(currentYaw * Math.PI / 180.0);
        vx += -sinYaw * 0.2;
        vz +=  cosYaw * 0.2;
        double vy = 0.42;
        double py = 0; // vertical position — must reach dy to detect landing

        float targetYaw = (float) Math.toDegrees(Math.atan2(-(dx / hdist), dz / hdist));

        double totalX = 0, totalZ = 0;
        for (int t = 0; t < 40; t++) {
            float yawDiff = normalizeAngle(targetYaw - currentYaw);
            currentYaw += yawDiff * 0.35f;

            double sin = Math.sin(currentYaw * Math.PI / 180.0);
            double cos = Math.cos(currentYaw * Math.PI / 180.0);
            vx += -sin * 0.026;
            vz +=  cos * 0.026;

            totalX += vx;
            totalZ += vz;

            py += vy;              // height before gravity so arc peak is correctly tracked
            vy = (vy - 0.08) * 0.98;

            vx *= 0.91;
            vz *= 0.91;

            // Land when the player has descended to (or past) the target Y level
            if (py <= dy && t > 3) break;
        }

        return Math.sqrt(totalX * totalX + totalZ * totalZ);
    }

    private static void resetState() {
        currentNodeIndex    = 0;
        pauseTicksLeft      = 0;
        jumpCooldown        = 0;
        sprintModeForced    = false;
        waterClutchPlaced   = false;
        useKeyHoldTicks     = 0;
        sprintOn            = true;
        sprintRerollCD      = 0;
        reachTolerance      = 0.40;
        interactWaitTicks   = 0;
        pendingInteractNext = null;
        nodeTicksSpent      = 0;
        stuckCheckTimer     = 0;
        stuckEventCount     = 0;
        lastStuckCheckPos   = null;
        cumulativeDist      = null;
        totalPathLen        = 0;
        sprintJumpLaunchYaw = 0f;
        lastSteerDx         = 0;
        lastSteerDz         = 0;
        hasSteerDir         = false;
    }
}
