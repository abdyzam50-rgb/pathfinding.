package com.abdy2.aotvpathfinder.path;

import net.minecraft.core.BlockPos;

/**
 * One step of a route.
 *
 * <p>The important distinction this record exists to make is between <b>where you aim</b> and
 * <b>where you end up</b>. They are not the same block, and they differ in a different way for each
 * ability:
 *
 * <ul>
 *   <li><b>NORMAL</b> teleports you along the look vector into open space, and you then fall. The
 *       target is the block that was aimed at; the landing is where gravity left you, potentially
 *       many blocks below.
 *   <li><b>SHIFT</b> is aimed at a solid block and places you on top of it. The target is that
 *       solid block; the landing is the air block above it.
 *   <li><b>WALK</b> aims at nothing. Target and landing are the same.
 * </ul>
 *
 * <p>Carrying the target explicitly matters because the planner decides it while validating the
 * hop. When only the landing was stored, the executor had to reconstruct the target from it, and
 * reconstructed it differently — which is what made planner and executor disagree about whether a
 * hop was in range and whether its line was clear.
 *
 * @param landing  where the player ends up once the hop and any resulting fall are complete
 * @param target   the block the ability is aimed at to produce that landing
 * @param type     which ability performs the hop
 * @param manaCost mana the hop is expected to consume
 */
public record PathHop(BlockPos landing, BlockPos target, HopType type, int manaCost) {

    /** A hop whose target follows from its landing by the usual rule for its type. */
    public static PathHop of(BlockPos landing, HopType type, int manaCost) {
        return new PathHop(landing, defaultTarget(landing, type), type, manaCost);
    }

    /**
     * A hop that was aimed at one place and settled somewhere else.
     *
     * <p>Used for NORMAL hops that fall after arriving, so the aim point the planner validated is
     * preserved rather than being re-derived from the settled landing.
     */
    public static PathHop settled(BlockPos landing, BlockPos aimedAt, HopType type, int manaCost) {
        return new PathHop(landing, aimedAt, type, manaCost);
    }

    /** Where a hop of this type is aimed when nothing displaced the landing. */
    public static BlockPos defaultTarget(BlockPos landing, HopType type) {
        // Etherwarp is aimed at the block underneath the landing, since it puts you on top of it.
        return type == HopType.SHIFT ? landing.below() : landing;
    }

    public boolean requiresShift() {
        return type == HopType.SHIFT;
    }

    public boolean isWalk() {
        return type == HopType.WALK;
    }

    public boolean isTeleport() {
        return type != HopType.WALK;
    }

    /** True when gravity moved the landing away from the point that was aimed at. */
    public boolean settledAfterAiming() {
        return !landing.equals(target) && type == HopType.NORMAL;
    }
}
