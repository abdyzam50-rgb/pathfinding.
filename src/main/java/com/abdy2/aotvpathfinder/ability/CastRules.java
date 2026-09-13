package com.abdy2.aotvpathfinder.ability;

import com.abdy2.aotvpathfinder.path.HopType;
import com.abdy2.aotvpathfinder.path.PathHop;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The single definition of what each ability can do and where it must be aimed.
 *
 * <p>This class exists because the planner and the executor previously answered these questions
 * separately, and every one of those answers eventually drifted apart:
 *
 * <ul>
 *   <li>the aim height differed by a block, so landings on solid ground were judged unreachable
 *       by the executor while the planner considered their corridor clear;
 *   <li>range was measured eye-to-aim in one place and feet-to-feet in the other, so hops the
 *       planner had just emitted were rejected as too far;
 *   <li>the cast test demanded a clear miss for both abilities, though etherwarp must <i>hit</i>
 *       the block it targets, so every shift hop was reported blocked;
 *   <li>a route-shortening pass merged hops without a range check the executor did apply, and
 *       produced hops nothing could perform.
 * </ul>
 *
 * <p>Each was fixed on one side of a duplicated pair. Keeping the rules here means a change is made
 * once and both sides move together, so that class of disagreement cannot recur.
 */
public final class CastRules {
    private CastRules() {}

    // --- Ability limits -------------------------------------------------------------------

    /** Aspect of the Void right-click teleport, in blocks. */
    public static final int TRANSMISSION_RANGE = 12;

    /**
     * Etherwarp's true maximum, in blocks, measured from the eye to the targeted block.
     *
     * <p>An exact cutoff, applied to real distance rather than the block grid. A block nominally
     * 56 away is around 56.5 when standing on the far edge of your own block, and the cast simply
     * fails, so anything planning an etherwarp must leave room for sub-block position.
     */
    public static final int ETHERWARP_RANGE = 56;

    public static final int TRANSMISSION_MANA = 27;
    public static final int ETHERWARP_MANA = 108;

    /** How close to the goal counts as arrived. */
    public static final double GOAL_REACHED_RADIUS = 1.8;

    /**
     * Margin between what the planner will emit and what the executor will accept.
     *
     * <p>The planner stays this far inside the true limit and the executor allows this far past it,
     * so a hop the planner produced always passes the executor's check. Without the gap the two
     * rounded the same hop differently and healthy routes were rebuilt on their first node.
     */
    private static final int PLAN_MARGIN = 2;

    /** Longest hop of this type the planner may produce. */
    public static int planRange(HopType type) {
        return Math.max(1, maxRange(type) - PLAN_MARGIN);
    }

    /** True maximum reach of the ability performing this hop type. */
    public static int maxRange(HopType type) {
        return switch (type) {
            case SHIFT -> ETHERWARP_RANGE;
            case NORMAL -> TRANSMISSION_RANGE;
            case WALK -> TRANSMISSION_RANGE;
        };
    }

    public static int manaCost(HopType type) {
        return switch (type) {
            case SHIFT -> ETHERWARP_MANA;
            case NORMAL -> TRANSMISSION_MANA;
            case WALK -> 0;
        };
    }

    public static String abilityName(HopType type) {
        return switch (type) {
            case SHIFT -> "etherwarp";
            case NORMAL -> "transmission";
            case WALK -> "walk";
        };
    }

    // --- Aiming ---------------------------------------------------------------------------

    /** Height within the targeted block that both planning and casting aim at. */
    private static final double AIM_HEIGHT = 0.92;
    /** Eye height used when the planner reasons about a position it is not standing at. */
    public static final double EYE_HEIGHT = 1.62;
    /** How far the aim point is pulled back toward the caster, to stay clear of the far face. */
    private static final double AIM_PULL_BACK = 0.22;

    /**
     * The exact point to aim at to perform {@code hop}, as seen from {@code eye}.
     *
     * <p>Deliberately the only place this is computed. The aim height is measured from the targeted
     * block, so for a transmission hop it lands inside the open block being teleported into, and
     * for an etherwarp it lands inside the solid block being warped onto — which is what each
     * ability needs, and what {@link #castLineClear} is written against.
     */
    public static Vec3 aimPoint(PathHop hop, Vec3 eye) {
        return aimPoint(hop.target(), eye);
    }

    public static Vec3 aimPoint(BlockPos target, Vec3 eye) {
        double x = target.getX() + 0.5;
        double z = target.getZ() + 0.5;
        double dx = x - eye.x;
        double dz = z - eye.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.0001) {
            x -= (dx / len) * AIM_PULL_BACK;
            z -= (dz / len) * AIM_PULL_BACK;
        }
        return new Vec3(x, target.getY() + AIM_HEIGHT, z);
    }

    /** Where the eye sits when standing in {@code block}, for planning from a hypothetical spot. */
    public static Vec3 eyeIn(BlockPos block) {
        return new Vec3(block.getX() + 0.5, block.getY() + EYE_HEIGHT, block.getZ() + 0.5);
    }

    // --- Range ----------------------------------------------------------------------------

    /**
     * Whether {@code hop} is close enough to cast from {@code feet}.
     *
     * <p>Horizontal only, and deliberately so. A transmission landing is settled by gravity after
     * the hop and can sit far below the point that was aimed at, so straight-line distance to the
     * landing overstates how far the ability actually had to reach. Settling moves the landing in Y
     * alone and never in X/Z, so horizontal separation is the part that reflects the real reach.
     */
    public static boolean withinRange(Vec3 feet, PathHop hop) {
        return withinRange(feet, hop.target(), hop.type());
    }

    public static boolean withinRange(Vec3 feet, BlockPos target, HopType type) {
        double dx = feet.x - (target.getX() + 0.5);
        double dz = feet.z - (target.getZ() + 0.5);
        double max = maxRange(type);
        return dx * dx + dz * dz <= max * max;
    }

    /** Range test for the planner, which works in block positions and stays inside the margin. */
    public static boolean withinPlanRange(BlockPos from, BlockPos target, HopType type) {
        double dx = from.getX() - target.getX();
        double dz = from.getZ() - target.getZ();
        double max = planRange(type);
        return dx * dx + dz * dz <= max * max;
    }

    // --- Line of sight --------------------------------------------------------------------

    /**
     * Whether the aim ray for {@code hop} actually permits the cast.
     *
     * <p>The two abilities need opposite outcomes from the same ray, which is why one shared test
     * for both was wrong: transmission drops you into open space so the ray must reach the target
     * without hitting anything, while etherwarp is aimed at a solid block and must hit precisely
     * that block. Any face of it will do.
     */
    public static boolean castLineClear(Level level, Entity caster, Vec3 eye, PathHop hop) {
        Vec3 aim = aimPoint(hop, eye);
        if (hop.type() == HopType.SHIFT) {
            return rayHits(level, caster, eye, aim, hop.target());
        }
        return rayReaches(level, caster, eye, aim);
    }

    /** True when nothing at all stands between {@code from} and {@code to}. */
    public static boolean rayReaches(Level level, Entity caster, Vec3 from, Vec3 to) {
        if (clip(level, caster, from, to, ClipContext.Block.COLLIDER).getType() != HitResult.Type.MISS) {
            return false;
        }
        return clip(level, caster, from, to, ClipContext.Block.OUTLINE).getType() == HitResult.Type.MISS;
    }

    /** True when the ray lands on {@code expected} rather than something in front of it. */
    public static boolean rayHits(Level level, Entity caster, Vec3 from, Vec3 to, BlockPos expected) {
        HitResult hit = clip(level, caster, from, to, ClipContext.Block.OUTLINE);
        return hit instanceof BlockHitResult blockHit
            && blockHit.getType() == HitResult.Type.BLOCK
            && blockHit.getBlockPos().equals(expected);
    }

    private static HitResult clip(Level level, Entity caster, Vec3 from, Vec3 to, ClipContext.Block shape) {
        return level.clip(new ClipContext(from, to, shape, ClipContext.Fluid.NONE, caster));
    }

    // --- Landing validity -----------------------------------------------------------------

    /**
     * Whether a player can stand at {@code landing}: solid footing with two blocks of air above.
     *
     * <p>This is also exactly what etherwarp requires of a warp target, so the same rule serves
     * both "can I stand here" and "can I warp onto the block beneath here".
     */
    public static boolean isStandable(Level level, BlockPos landing) {
        if (!level.isLoaded(landing)) {
            return false;
        }
        return level.getBlockState(landing).isAir()
            && level.getBlockState(landing.above()).isAir()
            && level.getBlockState(landing.below()).isSolid();
    }

    /** Whether {@code hop}'s landing satisfies what its ability needs of it. */
    public static boolean isValidLanding(Level level, PathHop hop) {
        // A transmission hop may legitimately end in mid-air as part of an air chain, so only the
        // abilities that place you on ground demand solid footing.
        if (hop.type() == HopType.SHIFT) {
            return isStandable(level, hop.landing());
        }
        return level.isLoaded(hop.landing());
    }
}
