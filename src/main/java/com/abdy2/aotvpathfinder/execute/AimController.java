package com.abdy2.aotvpathfinder.execute;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Turns the player toward a target the way a person would, and decides when the aim has settled.
 *
 * <p>Owns the small amount of state that answer needs — the target last aimed at and when the aim
 * became steady — which is why this is an object rather than more static helpers in
 * {@link Rotation}. Rotation is the angle arithmetic; this is the policy built on top of it.
 *
 * <p>Two things make the motion read as human rather than mechanical. Rotation steps are eased, so
 * a large turn starts fast and settles slowly instead of tracking at a constant rate. And a cast is
 * held back until the aim has been within tolerance for a moment, rather than firing the instant
 * the angle happens to line up on one tick.
 */
public final class AimController {

    private Vec3 lastAimTarget;
    private long aimStableSinceMs;
    /** Pitch walking settles at; kept as state so it can be retargeted without a jump. */
    private float walkPitchLock = 8.0F;

    /** Forgets the current aim so the next target starts its settle window fresh. */
    public void reset() {
        lastAimTarget = null;
        aimStableSinceMs = 0L;
        walkPitchLock = 8.0F;
    }

    private static final float WALK_YAW_STEP_DEG = 24.0F;
    private static final float WALK_PITCH_STEP_DEG = 1.4F;
    private static final float TELEPORT_YAW_STEP_DEG = 14.0F;
    private static final float TELEPORT_PITCH_STEP_DEG = 11.0F;


    public boolean aimAtAndReady(LocalPlayer player, Vec3 target, long now, boolean fastMode) {
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

        float yawError = Math.abs(Rotation.wrapDegrees(desiredYaw - player.getYRot()));
        float pitchError = Math.abs(desiredPitch - player.getXRot());
        float errorThreshold = fastMode ? 6.0F : 3.5F;
        if (yawError > errorThreshold || pitchError > errorThreshold) {
            aimStableSinceMs = now;
            return false;
        }

        long settleMs = fastMode ? 20L : 125L;
        return now - aimStableSinceMs >= settleMs;
    }


    public void lookAtTeleportHuman(LocalPlayer player, Vec3 target, boolean fastMode) {
        float desiredYaw = Rotation.desiredYaw(player, target);
        float desiredPitch = Rotation.desiredPitch(player, target);

        double targetDist = player.getEyePosition().distanceTo(target);
        float farScale = (float) Math.max(0.68, Math.min(1.0, 1.0 - ((targetDist - 8.0) / 34.0)));

        float yawMaxStep = (fastMode ? TELEPORT_YAW_STEP_DEG * 1.15F : TELEPORT_YAW_STEP_DEG) * farScale;
        float pitchMaxStep = (fastMode ? TELEPORT_PITCH_STEP_DEG * 1.15F : TELEPORT_PITCH_STEP_DEG) * farScale;

        float nextYaw = Rotation.approachAngleEased(player.getYRot(), desiredYaw, yawMaxStep, 0.8F);
        float nextPitch = Rotation.approachLinearEased(player.getXRot(), desiredPitch, pitchMaxStep, 0.6F);
        Rotation.applyRotation(player, nextYaw, nextPitch);
    }


    public void lookAtWalkHuman(LocalPlayer player, Vec3 target) {
        float desiredYaw = Rotation.desiredYaw(player, target);
        float yawDelta = Math.abs(Rotation.wrapDegrees(desiredYaw - player.getYRot()));
        float yawStep = yawDelta > 35.0F ? 42.0F : WALK_YAW_STEP_DEG;
        float nextYaw = Rotation.approachAngle(player.getYRot(), desiredYaw, yawStep);
        float nextPitch = Rotation.approachLinear(player.getXRot(), walkPitchLock, WALK_PITCH_STEP_DEG);
        Rotation.applyRotation(player, nextYaw, nextPitch);
    }
}
