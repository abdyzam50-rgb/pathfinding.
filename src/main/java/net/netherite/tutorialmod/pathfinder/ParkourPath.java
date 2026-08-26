package net.netherite.tutorialmod.pathfinder;

import net.minecraft.util.math.Vec3d;
import java.util.List;

/**
 * Represents a completed parkour path with methods for following it.
 * Contains the sequence of keyframe nodes and provides utilities for
 * path following, progress tracking, and input generation.
 */
public class ParkourPath {
    private final List<ParkourNode> keyframes;
    private final List<ParkourNode> fullTrajectory;
    private int currentKeyframeIndex;
    private int currentTickIndex;

    /**
     * Creates a parkour path from keyframe nodes.
     * The full trajectory is reconstructed by replaying the inputs.
     */
    public ParkourPath(List<ParkourNode> keyframes) {
        this.keyframes = keyframes;
        this.fullTrajectory = reconstructFullTrajectory(keyframes);
        this.currentKeyframeIndex = 0;
        this.currentTickIndex = 0;
    }

    /**
     * Reconstructs the full tick-by-tick trajectory from keyframes.
     */
    private List<ParkourNode> reconstructFullTrajectory(List<ParkourNode> keyframes) {
        // The keyframes already contain the trajectory points from the simulation
        // Just flatten them into a single list
        return keyframes;
    }

    /**
     * Gets the current target position for the follower.
     */
    public Vec3d getCurrentTarget() {
        if (keyframes.isEmpty()) return Vec3d.ZERO;
        if (currentKeyframeIndex >= keyframes.size()) {
            ParkourNode last = keyframes.get(keyframes.size() - 1);
            return new Vec3d(last.x, last.y, last.z);
        }
        ParkourNode current = keyframes.get(currentKeyframeIndex);
        return new Vec3d(current.x, current.y, current.z);
    }

    /**
     * Gets the input that should be applied at the current moment.
     */
    public MovementInput getCurrentInput() {
        if (keyframes.isEmpty()) return MovementInput.idle();
        if (currentKeyframeIndex >= keyframes.size()) {
            return MovementInput.idle();
        }
        ParkourNode current = keyframes.get(currentKeyframeIndex);
        return current.inputUsed != null ? current.inputUsed : MovementInput.idle();
    }

    /**
     * Gets the predicted state at the current tick.
     */
    public ParkourNode getPredictedState() {
        if (fullTrajectory.isEmpty()) return null;
        if (currentTickIndex >= fullTrajectory.size()) {
            return fullTrajectory.get(fullTrajectory.size() - 1);
        }
        return fullTrajectory.get(currentTickIndex);
    }

    /**
     * Advances the path follower to the next keyframe.
     * Should be called when the current keyframe is reached.
     */
    public void advanceKeyframe() {
        if (currentKeyframeIndex < keyframes.size() - 1) {
            currentKeyframeIndex++;
        }
    }

    /**
     * Advances the tick counter.
     * Should be called each game tick.
     */
    public void advanceTick() {
        if (currentTickIndex < fullTrajectory.size() - 1) {
            currentTickIndex++;
        }
    }

    /**
     * Checks if the path has been fully traversed.
     */
    public boolean isComplete() {
        return keyframes.isEmpty() || currentKeyframeIndex >= keyframes.size() - 1;
    }

    /**
     * Gets the total number of keyframes in the path.
     */
    public int getKeyframeCount() {
        return keyframes.size();
    }

    /**
     * Gets the total number of ticks in the full trajectory.
     */
    public int getTickCount() {
        return fullTrajectory.size();
    }

    /**
     * Gets the current keyframe index.
     */
    public int getCurrentKeyframeIndex() {
        return currentKeyframeIndex;
    }

    /**
     * Gets the current tick index.
     */
    public int getCurrentTickIndex() {
        return currentTickIndex;
    }

    /**
     * Gets all keyframes in the path.
     */
    public List<ParkourNode> getKeyframes() {
        return keyframes;
    }

    /**
     * Gets the full tick-by-tick trajectory.
     */
    public List<ParkourNode> getFullTrajectory() {
        return fullTrajectory;
    }

    /**
     * Checks if the player is close enough to the current target.
     */
    public boolean isNearCurrentTarget(double playerX, double playerY, double playerZ, double threshold) {
        Vec3d target = getCurrentTarget();
        double dx = playerX - target.x;
        double dy = playerY - target.y;
        double dz = playerZ - target.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < threshold;
    }

    /**
     * Gets the remaining distance to the goal.
     */
    public double getRemainingDistance(double playerX, double playerY, double playerZ) {
        if (keyframes.isEmpty()) return 0;
        ParkourNode goal = keyframes.get(keyframes.size() - 1);
        double dx = playerX - goal.x;
        double dy = playerY - goal.y;
        double dz = playerZ - goal.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Creates a path from a list of nodes (for compatibility with the pathfinder).
     */
    public static ParkourPath fromNodes(List<ParkourNode> nodes) {
        return new ParkourPath(nodes);
    }

    @Override
    public String toString() {
        return String.format("ParkourPath[keyframes=%d, ticks=%d, current=%d/%d]",
                keyframes.size(), fullTrajectory.size(),
                currentKeyframeIndex, keyframes.isEmpty() ? 0 : keyframes.size() - 1);
    }
}