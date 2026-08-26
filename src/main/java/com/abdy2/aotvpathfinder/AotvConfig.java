package com.abdy2.aotvpathfinder;

public final class AotvConfig {
    private AotvConfig() {}

    // Maxed AOTV assumptions for simplicity.
    public static final int TRANSMISSION_RANGE = 12;

    /**
     * Etherwarp's true maximum, in blocks, measured from the eye to the targeted block.
     *
     * <p>This is an exact cutoff and it applies to real distance, not block-grid distance. Standing
     * on the far edge of your own block and aiming at a block nominally 56 away is really ~56.5,
     * and the cast simply fails. Anything planning or validating an etherwarp must leave room for
     * where the player sits inside their own block rather than treating a 56-block offset as usable.
     */
    public static final int ETHERWARP_RANGE = 56;

    /**
     * Longest etherwarp offset the planner will generate. Held below {@link #ETHERWARP_RANGE} so
     * candidates remain castable once sub-block position is accounted for.
     */
    public static final int ETHERWARP_PLAN_RANGE = ETHERWARP_RANGE - 2;

    public static final int TRANSMISSION_MANA = 27;
    public static final int ETHERWARP_MANA = 108;

    public static final double GOAL_REACHED_RADIUS = 1.8;
}
