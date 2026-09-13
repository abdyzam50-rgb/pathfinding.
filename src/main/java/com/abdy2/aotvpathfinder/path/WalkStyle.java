package com.abdy2.aotvpathfinder.path;

/**
 * How a walk step has to be performed.
 *
 * <p>The planner already knows this: it validates a jump arc before offering a two-block step, and
 * the walk search labels every node it produces. Dropping that on the way out left the executor
 * inferring it from block heights at run time, which is guesswork about a question that had already
 * been answered properly.
 */
public enum WalkStyle {
    /** Walk straight there. Covers anything the game's own step-up handles, slabs included. */
    STEP,
    /** A jump is needed, to clear a full block or a gap. */
    JUMP,
    /** A running jump: sprint has to be held for the distance to be made. */
    SPRINT_JUMP;

    public boolean needsJump() {
        return this != STEP;
    }

    public boolean needsSprint() {
        return this == SPRINT_JUMP;
    }
}
