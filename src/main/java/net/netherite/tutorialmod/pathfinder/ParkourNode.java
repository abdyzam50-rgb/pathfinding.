package net.netherite.tutorialmod.pathfinder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A node in the Kinematic A* pathfinding system.
 * Represents a precise physical state including position, velocity, orientation,
 * and movement state at a specific tick in the simulation.
 * 
 * Each node captures the complete kinematic state needed to continue
 * physics simulation from this exact point.
 */
public class ParkourNode {
    // ==================== Position State ====================
    
    /** Exact X position in the world (sub-block precision) */
    public double x;
    
    /** Exact Y position (feet level, sub-block precision) */
    public double y;
    
    /** Exact Z position in the world (sub-block precision) */
    public double z;

    // ==================== Velocity State ====================
    
    /** Horizontal velocity along X axis (blocks/tick) */
    public double vx;
    
    /** Vertical velocity (blocks/tick, positive = upward) */
    public double vy;
    
    /** Horizontal velocity along Z axis (blocks/tick) */
    public double vz;

    // ==================== Orientation State ====================
    
    /** Player's exact yaw orientation in degrees (0 = south, 90 = west, 180 = north, 270 = east) */
    public float yaw;
    
    /** Player's pitch in degrees (for completeness, though rarely used in parkour) */
    public float pitch;

    // ==================== Movement Flags ====================
    
    /** Whether the player is actively sprinting */
    public boolean isSprinting;
    
    /** Whether the player is sneaking/crouching */
    public boolean isSneaking;
    
    /** Whether the jump key is being held */
    public boolean isJumping;

    // ==================== Temporal State ====================
    
    /** Number of consecutive ticks spent in the air (0 = on ground) */
    public int ticksInAir;
    
    /** The tick number in the overall simulation (for debugging/ordering) */
    public int simulationTick;

    // ==================== Path Reconstruction ====================
    
    /** Parent node in the path tree */
    public ParkourNode parent;
    
    /** The key combination that generated this node from parent */
    public MovementInput inputUsed;

    // ==================== A* Cost Values ====================
    
    /** Actual cost from start to this node (g-cost) */
    public double gCost;
    
    /** Heuristic estimated cost from this node to goal (h-cost) */
    public double hCost;
    
    /** Total estimated cost (f-cost = g + h) */
    public double fCost;

    // ==================== Metadata ====================
    
    /** Whether this node represents a landing (just touched ground) */
    public boolean isLanding;
    
    /** Whether this node is at a keyframe (input change point) */
    public boolean isKeyframe;
    
    /** Penalty score for this node (higher = less desirable) */
    public double penalty;

    // ==================== Constructors ====================

    /**
     * Creates a new parkour node with the given physical state.
     */
    public ParkourNode(double x, double y, double z,
                       double vx, double vy, double vz,
                       float yaw, boolean isSprinting, boolean isSneaking,
                       int ticksInAir, ParkourNode parent, MovementInput inputUsed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.yaw = yaw;
        this.pitch = 0;
        this.isSprinting = isSprinting;
        this.isSneaking = isSneaking;
        this.isJumping = inputUsed != null && inputUsed.jumping;
        this.ticksInAir = ticksInAir;
        this.simulationTick = parent != null ? parent.simulationTick + 1 : 0;
        this.parent = parent;
        this.inputUsed = inputUsed;
        this.gCost = 0;
        this.hCost = 0;
        this.fCost = 0;
        this.isLanding = false;
        this.isKeyframe = false;
        this.penalty = 0;
    }

    /**
     * Full constructor with all fields.
     */
    public ParkourNode(double x, double y, double z,
                       double vx, double vy, double vz,
                       float yaw, float pitch,
                       boolean isSprinting, boolean isSneaking, boolean isJumping,
                       int ticksInAir, int simulationTick,
                       ParkourNode parent, MovementInput inputUsed) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.yaw = yaw;
        this.pitch = pitch;
        this.isSprinting = isSprinting;
        this.isSneaking = isSneaking;
        this.isJumping = isJumping;
        this.ticksInAir = ticksInAir;
        this.simulationTick = simulationTick;
        this.parent = parent;
        this.inputUsed = inputUsed;
        this.gCost = 0;
        this.hCost = 0;
        this.fCost = 0;
        this.isLanding = false;
        this.isKeyframe = false;
        this.penalty = 0;
    }

    /**
     * Creates a copy of this node.
     */
    public ParkourNode copy() {
        ParkourNode copy = new ParkourNode(
                x, y, z, vx, vy, vz,
                yaw, pitch,
                isSprinting, isSneaking, isJumping,
                ticksInAir, simulationTick,
                parent, inputUsed
        );
        copy.gCost = gCost;
        copy.hCost = hCost;
        copy.fCost = fCost;
        copy.isLanding = isLanding;
        copy.isKeyframe = isKeyframe;
        copy.penalty = penalty;
        return copy;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a new node representing the starting state.
     */
    public static ParkourNode createStart(double x, double y, double z, float yaw) {
        return new ParkourNode(x, y, z, 0, 0, 0, yaw, false, false, 0, null, null);
    }

    /**
     * Creates a new node representing the starting state with sprinting.
     */
    public static ParkourNode createStartSprinting(double x, double y, double z, float yaw) {
        return new ParkourNode(x, y, z, 0, 0, 0, yaw, true, false, 0, null, null);
    }

    /**
     * Creates a node from a Vec3d position.
     */
    public static ParkourNode fromVec(Vec3d pos, float yaw) {
        return createStart(pos.x, pos.y, pos.z, yaw);
    }

    // ==================== Position Helpers ====================

    /**
     * Gets the BlockPos of the block the player is currently in.
     */
    public BlockPos getBlockPos() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Gets the BlockPos of the block below the player (for ground checks).
     */
    public BlockPos getBlockPosBelow() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z));
    }

    /**
     * Gets the BlockPos of the block at head level.
     */
    public BlockPos getBlockPosAtHead() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y) + 1, (int) Math.floor(z));
    }

    /**
     * Gets the position as a Vec3d.
     */
    public Vec3d getPos() {
        return new Vec3d(x, y, z);
    }

    /**
     * Gets the velocity as a Vec3d.
     */
    public Vec3d getVelocity() {
        return new Vec3d(vx, vy, vz);
    }

    // ==================== Distance Calculations ====================

    /**
     * Calculates the horizontal distance to another node.
     */
    public double horizontalDistanceTo(ParkourNode other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculates the horizontal distance to a position.
     */
    public double horizontalDistanceTo(double targetX, double targetZ) {
        double dx = this.x - targetX;
        double dz = this.z - targetZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Calculates the 3D Euclidean distance to another node.
     */
    public double distanceTo(ParkourNode other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculates the 3D Euclidean distance to a position.
     */
    public double distanceTo(double targetX, double targetY, double targetZ) {
        double dx = this.x - targetX;
        double dy = this.y - targetY;
        double dz = this.z - targetZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ==================== State Queries ====================

    /**
     * Checks if this node represents a stable landing (on ground).
     */
    public boolean isStableLanding() {
        return ticksInAir == 0 || (vy <= 0 && isLanding);
    }

    /**
     * Returns true if the player is currently on the ground.
     */
    public boolean isOnGround() {
        return ticksInAir == 0;
    }

    /**
     * Returns true if the player is currently in the air.
     */
    public boolean isInAir() {
        return ticksInAir > 0;
    }

    /**
     * Returns true if the player is moving upward.
     */
    public boolean isMovingUp() {
        return vy > 0;
    }

    /**
     * Returns true if the player is falling.
     */
    public boolean isFalling() {
        return vy < 0;
    }

    /**
     * Gets the horizontal speed (magnitude of horizontal velocity).
     */
    public double getHorizontalSpeed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    /**
     * Gets the total speed (magnitude of 3D velocity).
     */
    public double getTotalSpeed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /**
     * Gets the yaw in radians.
     */
    public double getYawRadians() {
        return Math.toRadians(yaw);
    }

    /**
     * Gets the forward direction vector based on yaw.
     */
    public Vec3d getForwardDirection() {
        double rad = getYawRadians();
        return new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
    }

    /**
     * Gets the right direction vector based on yaw.
     */
    public Vec3d getRightDirection() {
        double rad = getYawRadians();
        return new Vec3d(Math.cos(rad), 0, Math.sin(rad));
    }

    // ==================== Cost Management ====================

    /**
     * Recalculates fCost from gCost and hCost.
     */
    public void recalculateFCost() {
        this.fCost = this.gCost + this.hCost + this.penalty;
    }

    /**
     * Adds a penalty to this node's cost.
     */
    public void addPenalty(double penalty) {
        this.penalty += penalty;
        recalculateFCost();
    }

    // ==================== Yaw Utilities ====================

    /**
     * Sets the yaw to face towards a target position.
     */
    public void faceTowards(double targetX, double targetZ) {
        double dx = targetX - this.x;
        double dz = targetZ - this.z;
        this.yaw = (float) Math.toDegrees(Math.atan2(-dx, -dz));
    }

    /**
     * Calculates the yaw difference to face a target.
     */
    public float getYawDifferenceTo(double targetX, double targetZ) {
        double dx = targetX - this.x;
        double dz = targetZ - this.z;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, -dz));
        float diff = targetYaw - this.yaw;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }

    /**
     * Interpolates yaw towards a target yaw by a maximum amount per tick.
     */
    public void interpolateYaw(float targetYaw, float maxChange) {
        float diff = targetYaw - this.yaw;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        if (Math.abs(diff) > maxChange) {
            this.yaw += Math.signum(diff) * maxChange;
        } else {
            this.yaw = targetYaw;
        }
    }

    // ==================== Validation ====================

    /**
     * Checks if this node's position is valid (not NaN or Infinity).
     */
    public boolean isValidPosition() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /**
     * Checks if this node's velocity is valid (not NaN or Infinity).
     */
    public boolean isValidVelocity() {
        return Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz);
    }

    /**
     * Checks if this node is fully valid.
     */
    public boolean isValid() {
        return isValidPosition() && isValidVelocity() && Double.isFinite(yaw);
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("ParkourNode[ pos=(%.3f,%.3f,%.3f) vel=(%.3f,%.3f,%.3f) yaw=%.1f sprint=%b sneak=%b air=%d g=%.2f h=%.2f ]",
                x, y, z, vx, vy, vz, yaw, isSprinting, isSneaking, ticksInAir, gCost, hCost);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParkourNode that = (ParkourNode) o;
        // Compare with tolerance for floating point
        final double EPSILON = 0.001;
        return Math.abs(this.x - that.x) < EPSILON &&
                Math.abs(this.y - that.y) < EPSILON &&
                Math.abs(this.z - that.z) < EPSILON &&
                Math.abs(this.vx - that.vx) < EPSILON &&
                Math.abs(this.vy - that.vy) < EPSILON &&
                Math.abs(this.vz - that.vz) < EPSILON &&
                Math.abs(this.yaw - that.yaw) < EPSILON &&
                this.ticksInAir == that.ticksInAir &&
                this.isSprinting == that.isSprinting;
    }

    @Override
    public int hashCode() {
        // Quantize to reduce hash collisions from floating point
        long bits = 17;
        bits = 31 * bits + (long) (x * 1000);
        bits = 31 * bits + (long) (y * 1000);
        bits = 31 * bits + (long) (z * 1000);
        bits = 31 * bits + (long) (vx * 10000);
        bits = 31 * bits + (long) (vy * 10000);
        bits = 31 * bits + (long) (vz * 10000);
        bits = 31 * bits + (int) yaw;
        bits = 31 * bits + ticksInAir;
        bits = 31 * bits + (isSprinting ? 1 : 0);
        return (int) (bits ^ (bits >>> 32));
    }
}