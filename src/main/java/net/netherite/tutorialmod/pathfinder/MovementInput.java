package net.netherite.tutorialmod.pathfinder;

/**
 * Represents a combination of player movement inputs for a single tick.
 * Used by the Kinematic A* pathfinder to generate neighbor states through
 * simulated controller inputs rather than grid-based movement.
 * 
 * Each input combination represents a specific key/mouse configuration
 * that will be fed into the physics simulator to generate trajectories.
 */
public class MovementInput {
    // ==================== Input State ====================
    
    /** Strafe input: -1 (left/A) to 1 (right/D), 0 = no strafe */
    public final double strafe;
    
    /** Forward input: -1 (back/S) to 1 (forward/W), 0 = no forward/back */
    public final double forward;
    
    /** Whether the jump key is pressed */
    public final boolean jumping;
    
    /** Whether the sprint key is held */
    public final boolean sprinting;
    
    /** Whether the sneak/crouch key is held */
    public final boolean sneaking;
    
    /** Whether to attack/break (left click) - for chain/candle interactions */
    public final boolean attacking;
    
    /** Whether to use item (right click) - for placing blocks */
    public final boolean usingItem;
    
    /** Target yaw for mid-air interpolation (used for neo jumps) */
    public final Float targetYaw;
    
    /** Maximum yaw change per tick when interpolating */
    public final double turnRate;

    // ==================== Constructors ====================

    public MovementInput(double strafe, double forward, boolean jumping, boolean sprinting, boolean sneaking) {
        this(strafe, forward, jumping, sprinting, sneaking, false, false, null, 0);
    }

    public MovementInput(double strafe, double forward, boolean jumping, boolean sprinting, boolean sneaking,
                         boolean attacking, boolean usingItem, Float targetYaw, double turnRate) {
        this.strafe = strafe;
        this.forward = forward;
        this.jumping = jumping;
        this.sprinting = sprinting;
        this.sneaking = sneaking;
        this.attacking = attacking;
        this.usingItem = usingItem;
        this.targetYaw = targetYaw;
        this.turnRate = turnRate;
    }

    // ==================== Basic Movement Inputs ====================

    /** No input (free fall / coasting in air) */
    public static MovementInput idle() {
        return new MovementInput(0, 0, false, false, false);
    }

    /** Forward only (walking) */
    public static MovementInput walkForward() {
        return new MovementInput(0, 1, false, false, false);
    }

    /** Backward only (walking back) */
    public static MovementInput walkBackward() {
        return new MovementInput(0, -1, false, false, false);
    }

    /** Strafe left only */
    public static MovementInput strafeLeft() {
        return new MovementInput(-1, 0, false, false, false);
    }

    /** Strafe right only */
    public static MovementInput strafeRight() {
        return new MovementInput(1, 0, false, false, false);
    }

    /** Forward + strafe left (diagonal movement) */
    public static MovementInput walkForwardLeft() {
        return new MovementInput(-1, 1, false, false, false);
    }

    /** Forward + strafe right (diagonal movement) */
    public static MovementInput walkForwardRight() {
        return new MovementInput(1, 1, false, false, false);
    }

    // ==================== Sprinting Inputs ====================

    /** Forward + sprint (sprinting without jump) */
    public static MovementInput sprintForward() {
        return new MovementInput(0, 1, false, true, false);
    }

    /** Backward + sprint */
    public static MovementInput sprintBackward() {
        return new MovementInput(0, -1, false, true, false);
    }

    /** Sprint + strafe left */
    public static MovementInput sprintStrafeLeft() {
        return new MovementInput(-1, 0, false, true, false);
    }

    /** Sprint + strafe right */
    public static MovementInput sprintStrafeRight() {
        return new MovementInput(1, 0, false, true, false);
    }

    /** Sprint + forward + strafe left */
    public static MovementInput sprintForwardLeft() {
        return new MovementInput(-1, 1, false, true, false);
    }

    /** Sprint + forward + strafe right */
    public static MovementInput sprintForwardRight() {
        return new MovementInput(1, 1, false, true, false);
    }

    // ==================== Jumping Inputs ====================

    /** Forward + jump (normal jump, no sprint) */
    public static MovementInput normalJump() {
        return new MovementInput(0, 1, true, false, false);
    }

    /** Forward + jump + strafe left (curved jump) */
    public static MovementInput normalJumpLeft() {
        return new MovementInput(-1, 1, true, false, false);
    }

    /** Forward + jump + strafe right (curved jump) */
    public static MovementInput normalJumpRight() {
        return new MovementInput(1, 1, true, false, false);
    }

    /** Sprint + forward + jump (standard sprint jump - maximum distance) */
    public static MovementInput sprintJump() {
        return new MovementInput(0, 1, true, true, false);
    }

    /** Sprint + forward + strafe left + jump (left curve sprint jump) */
    public static MovementInput sprintJumpStrafeLeft() {
        return new MovementInput(-1, 1, true, true, false);
    }

    /** Sprint + forward + strafe right + jump (right curve sprint jump) */
    public static MovementInput sprintJumpStrafeRight() {
        return new MovementInput(1, 1, true, true, false);
    }

    /** Sprint + strafe left + jump (sharp left turn jump) */
    public static MovementInput sprintJumpLeft() {
        return new MovementInput(-1, 0, true, true, false);
    }

    /** Sprint + strafe right + jump (sharp right turn jump) */
    public static MovementInput sprintJumpRight() {
        return new MovementInput(1, 0, true, true, false);
    }

    /** Straight up jump (no forward momentum) */
    public static MovementInput jumpInPlace() {
        return new MovementInput(0, 0, true, false, false);
    }

    /** Sprint + jump in place */
    public static MovementInput sprintJumpInPlace() {
        return new MovementInput(0, 0, true, true, false);
    }

    // ==================== Sneaking Inputs ====================

    /** Sneaking forward (for precise edge movement) */
    public static MovementInput sneakForward() {
        return new MovementInput(0, 1, false, false, true);
    }

    /** Sneaking backward */
    public static MovementInput sneakBackward() {
        return new MovementInput(0, -1, false, false, true);
    }

    /** Sneaking strafe left */
    public static MovementInput sneakLeft() {
        return new MovementInput(-1, 0, false, false, true);
    }

    /** Sneaking strafe right */
    public static MovementInput sneakRight() {
        return new MovementInput(1, 0, false, false, true);
    }

    // ==================== Advanced Parkour Inputs ====================

    /**
     * Creates an input for a neo jump with mid-air yaw interpolation.
     * The pathfinder will gradually turn towards targetYaw during the trajectory.
     *
     * @param forward   Forward movement strength
     * @param strafe    Strafe movement strength
     * @param sprinting Whether to sprint
     * @param targetYaw Target yaw to interpolate towards
     * @param turnRate  Maximum degrees to turn per tick
     */
    public static MovementInput neoJump(double forward, double strafe, boolean sprinting,
                                         float targetYaw, double turnRate) {
        return new MovementInput(strafe, forward, true, sprinting, false,
                false, false, targetYaw, turnRate);
    }

    /**
     * Neo jump with sprint towards a target yaw.
     */
    public static MovementInput sprintNeoJump(float targetYaw, double turnRate) {
        return neoJump(1.0, 0.0, true, targetYaw, turnRate);
    }

    /**
     * Neo jump with sprint and strafe for curved trajectories.
     */
    public static MovementInput sprintNeoJumpStrafe(double strafe, float targetYaw, double turnRate) {
        return neoJump(1.0, strafe, true, targetYaw, turnRate);
    }

    /**
     * Creates an input for a delayed jump (head-hitter configuration).
     * The actual jump logic is handled by the simulation which checks ticksInAir.
     */
    public static MovementInput delayedJump(double strafe, double forward, boolean sprinting) {
        return new MovementInput(strafe, forward, false, sprinting, false);
    }

    /**
     * Drop down (shift + space for scaffold drops) */
    public static MovementInput dropDown() {
        return new MovementInput(0, 0, true, false, true);
    }

    // ==================== Interaction Inputs ====================

    /**
     * Attack/break input (left click) - useful for breaking chains or candles
     * that might be in the way.
     */
    public static MovementInput attack(double strafe, double forward, boolean sprinting) {
        return new MovementInput(strafe, forward, false, sprinting, false,
                true, false, null, 0);
    }

    /**
     * Use item input (right click) - useful for placing blocks during parkour.
     */
    public static MovementInput useItem(double strafe, double forward, boolean sprinting) {
        return new MovementInput(strafe, forward, false, sprinting, false,
                false, true, null, 0);
    }

    // ==================== Utility Methods ====================

    /**
     * Returns true if any movement key is pressed.
     */
    public boolean hasMovement() {
        return strafe != 0 || forward != 0;
    }

    /**
     * Returns true if this input involves jumping.
     */
    public boolean isJumpInput() {
        return jumping;
    }

    /**
     * Returns true if this input involves sprinting.
     */
    public boolean isSprintInput() {
        return sprinting;
    }

    /**
     * Returns true if this input involves sneaking.
     */
    public boolean isSneakInput() {
        return sneaking;
    }

    /**
     * Returns true if this input requires mid-air yaw interpolation.
     */
    public boolean hasYawInterpolation() {
        return targetYaw != null;
    }

    /**
     * Calculates the movement magnitude (0-1 for normal, >1 if sprinting).
     */
    public double getMovementMagnitude() {
        return Math.sqrt(strafe * strafe + forward * forward);
    }

    /**
     * Creates a copy of this input with a different target yaw.
     */
    public MovementInput withTargetYaw(float newYaw) {
        return new MovementInput(strafe, forward, jumping, sprinting, sneaking,
                attacking, usingItem, newYaw, turnRate);
    }

    /**
     * Creates a copy of this input without jumping.
     */
    public MovementInput withoutJump() {
        return new MovementInput(strafe, forward, false, sprinting, sneaking,
                attacking, usingItem, targetYaw, turnRate);
    }

    /**
     * Creates a copy of this input with sprinting enabled.
     */
    public MovementInput withSprint() {
        return new MovementInput(strafe, forward, jumping, true, sneaking,
                attacking, usingItem, targetYaw, turnRate);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MovementInput[");
        sb.append(String.format("strafe=%.1f,forward=%.1f", strafe, forward));
        if (jumping) sb.append(",jump");
        if (sprinting) sb.append(",sprint");
        if (sneaking) sb.append(",sneak");
        if (targetYaw != null) sb.append(String.format(",targetYaw=%.1f", targetYaw));
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MovementInput that = (MovementInput) o;
        return Double.compare(that.strafe, strafe) == 0 &&
                Double.compare(that.forward, forward) == 0 &&
                jumping == that.jumping &&
                sprinting == that.sprinting &&
                sneaking == that.sneaking &&
                attacking == that.attacking &&
                usingItem == that.usingItem &&
                Double.compare(that.turnRate, turnRate) == 0 &&
                java.util.Objects.equals(targetYaw, that.targetYaw);
    }

    @Override
    public int hashCode() {
        int result;
        long temp;
        temp = Double.doubleToLongBits(strafe);
        result = (int) (temp ^ (temp >>> 32));
        temp = Double.doubleToLongBits(forward);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        result = 31 * result + (jumping ? 1 : 0);
        result = 31 * result + (sprinting ? 1 : 0);
        result = 31 * result + (sneaking ? 1 : 0);
        result = 31 * result + (attacking ? 1 : 0);
        result = 31 * result + (usingItem ? 1 : 0);
        result = 31 * result + (targetYaw != null ? targetYaw.hashCode() : 0);
        temp = Double.doubleToLongBits(turnRate);
        result = 31 * result + (int) (temp ^ (temp >>> 32));
        return result;
    }
}