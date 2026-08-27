package com.abdy2.aotvpathfinder.execute;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Angle maths for turning the player toward a target.
 *
 * <p>Pure functions over angles and positions, with no routing state of their own. They were mixed
 * in among the route-running code, which made both harder to follow than they needed to be: this is
 * geometry, and it is testable and reviewable on its own terms.
 *
 * <p>The eased variants exist because a rotation that steps by a fixed amount reads as mechanical.
 * Scaling the step with the distance still to turn, subject to a floor so it always converges,
 * looks closer to a person aiming.
 */
public final class Rotation {
    private Rotation() {}

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    public static float desiredYaw(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        return (float) (Math.atan2(delta.z, delta.x) * (180.0 / Math.PI)) - 90.0F;
    }

    public static float desiredPitch(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        double xz = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        return (float) (-(Math.atan2(delta.y, xz) * (180.0 / Math.PI)));
    }

    public static float approachAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        float step = Math.max(-maxStep, Math.min(maxStep, delta));
        return current + step;
    }

    public static float approachLinear(float current, float target, float maxStep) {
        float delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.copySign(maxStep, delta);
    }

    public static float approachAngleEased(float current, float target, float maxStep, float minStep) {
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

    public static float approachLinearEased(float current, float target, float maxStep, float minStep) {
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

    public static void applyRotation(LocalPlayer player, float yaw, float pitch) {
        player.setYBodyRot(yaw);
        player.setYHeadRot(yaw);
        player.setYRot(yaw);
        player.setXRot(pitch);
    }
}
