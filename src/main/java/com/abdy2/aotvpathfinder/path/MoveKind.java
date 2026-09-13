package com.abdy2.aotvpathfinder.path;

/**
 * How a step has to be performed.
 *
 * <p>The planner decides this while building the route and the executor obeys it. That division
 * matters: the alternative, which this replaces, was an executor inferring intent from block
 * heights at run time, and it could not tell an obstacle in the way from a node one block up. The
 * planner had already answered the question properly and then discarded the answer.
 *
 * <p>The kinds mirror what the kinematic planner distinguishes, since flattening them here would
 * throw away the reason for using it.
 */
public enum MoveKind {
    /** Walk into it. Covers anything the game's own step-up handles, slabs included. */
    WALK,
    /** A standing jump, to clear a block or a short gap. */
    JUMP,
    /** A running jump; sprint has to be held for the distance to be made. */
    SPRINT_JUMP,
    /** Sprint-jump while placing a block mid-arc to land on. */
    BOOST_PLACE,
    /** Walk off and fall. No jump: jumping lengthens the fall. */
    DROP,
    /** A fall survived by placing water just before impact. */
    WATER_DROP,
    /** Land on slime; the bounce carries you to the next node. */
    BOUNCE,
    /** Ladders, vines or scaffolding, up or down. */
    CLIMB,
    /** The last safe block before a drop. Marked so it is visible, walked like any other. */
    EDGE,
    /** Right-click something -- a door or gate -- before carrying on. */
    INTERACT,
    /** Jump and place a block underfoot to gain a level. */
    PILLAR,
    /** Place a block across a gap before stepping onto it. */
    BRIDGE;

    /** Whether the jump key is part of performing this. */
    public boolean needsJump() {
        return switch (this) {
            case JUMP, SPRINT_JUMP, BOOST_PLACE, PILLAR -> true;
            default -> false;
        };
    }

    /** Whether sprint has to be held to cover the distance. */
    public boolean needsSprint() {
        return this == SPRINT_JUMP || this == BOOST_PLACE;
    }

    /** Whether a block has to be placed to make the step possible. */
    public boolean placesBlock() {
        return switch (this) {
            case BOOST_PLACE, PILLAR, BRIDGE, WATER_DROP -> true;
            default -> false;
        };
    }

    /** Whether something has to be used or opened first. */
    public boolean interacts() {
        return this == INTERACT;
    }

    /** Whether this step needs an item in hand rather than only movement keys. */
    public boolean needsItem() {
        return placesBlock() || interacts();
    }
}
