package com.abdy2.aotvpathfinder.execute;

import java.util.Locale;
import java.util.function.Supplier;

import com.abdy2.aotvpathfinder.ability.CastRules;
import com.abdy2.aotvpathfinder.config.PathfinderSettings;
import com.abdy2.aotvpathfinder.path.HopType;
import com.abdy2.aotvpathfinder.path.PathBuilder;
import com.abdy2.aotvpathfinder.path.PathHop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * What the ability can do from where the player is standing right now.
 *
 * <p>Answers the questions a route runner asks before committing to a hop — is it in reach, is the
 * line clear, is the wand held, has the cooldown elapsed, have we arrived — by deferring to
 * {@link CastRules} for the geometry. The value of gathering them here is that the route runner
 * reads as a sequence of decisions rather than a mix of decisions and the trigonometry behind them.
 *
 * <p>Settings arrive through a supplier because they are loaded after this is constructed.
 */
public final class CastController {

    private final Supplier<PathfinderSettings> settings;

    public CastController(Supplier<PathfinderSettings> settings) {
        this.settings = settings;
    }

    // Arrival tolerances, measured from the player's feet to the landing block's centre.
    // Horizontal is kept inside (or barely outside) the 1x1 block footprint so that a node only
    // retires once the player is genuinely standing on it, and vertical is bounded separately so a
    // node above or below the player never counts as reached.
    private static final double WALK_ARRIVE_HORIZONTAL_SQ = 0.55 * 0.55;
    private static final double WALK_ARRIVE_ABOVE = 1.2;
    private static final double WALK_ARRIVE_BELOW = 0.6;
    private static final double HOP_ARRIVE_HORIZONTAL_SQ = 1.0 * 1.0;
    private static final double HOP_ARRIVE_ABOVE = 2.0;
    private static final double HOP_ARRIVE_BELOW = 1.2;


    public boolean ensureAotvEquipped(Minecraft client, LocalPlayer player) {
        if (isHoldingAotv(player.getMainHandItem())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isHoldingAotv(player.getInventory().getItem(i))) {
                player.getInventory().setSelectedSlot(i);
                return false;
            }
        }
        return false;
    }

    public static boolean isHoldingAotv(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("aspect of the void") || name.contains("aspect of the end");
    }

    public long castCooldownMs(PathHop hop) {
        return useFastAirChainTiming(hop) ? 35L : 280L;
    }

    public boolean useFastAirChainTiming(PathHop hop) {
        return settings.get().airChainEnabled() && hop.type() == HopType.NORMAL;
    }

    public boolean hasCastLineFor(LocalPlayer player, PathHop hop, Vec3 target) {
        return CastRules.castLineClear(player.level(), player, player.getEyePosition(), hop);
    }


    /**
     * Whether {@code hop} is close enough to be cast from where the player stands.
     *
     * <p>Measured feet-to-landing, matching how the planner reasons about hop length. Measuring
     * eye-to-aim-point instead does not agree with it: the eye sits ~1.62 above the feet while the
     * transmission aim point sits just below the landing block, which shortens level hops and
     * noticeably lengthens downward ones. A downward hop well inside the ability's reach could
     * therefore measure past it and be rejected.
     */
    public boolean withinHopRange(LocalPlayer player, PathHop hop) {
        return CastRules.withinRange(player.position(), hop);
    }

    public static double maxHopRange(HopType type) {
        return CastRules.maxRange(type);
    }


    /**
     * True once the player has actually arrived at {@code step}.
     *
     * <p>This must be measured from the player's real (sub-block) position. Using
     * {@code blockPosition()} with a spherical {@link net.minecraft.core.Vec3i} radius snaps the
     * player to integer block coordinates and counts vertical distance the same as horizontal, so a
     * merely adjacent — or lower — block registers as "arrived". Because this check runs at the top
     * of every tick before any movement, that let a stationary player retire one node per tick.
     */
    public boolean isStepReached(LocalPlayer player, PathHop step) {
        Vec3 feet = player.position();
        BlockPos landing = step.landing();

        double dx = feet.x - (landing.getX() + 0.5);
        double dz = feet.z - (landing.getZ() + 0.5);
        double horizontalSq = dx * dx + dz * dz;
        double dy = feet.y - landing.getY();

        if (step.isWalk()) {
            return horizontalSq <= WALK_ARRIVE_HORIZONTAL_SQ
                && dy > -WALK_ARRIVE_BELOW
                && dy < WALK_ARRIVE_ABOVE;
        }
        return horizontalSq <= HOP_ARRIVE_HORIZONTAL_SQ
            && dy > -HOP_ARRIVE_BELOW
            && dy < HOP_ARRIVE_ABOVE;
    }

    public Vec3 aimTargetForHop(LocalPlayer player, PathHop hop) {
        // Teleport-only routing deliberately aims a block higher, to clear ledges on the way in.
        if (settings.get().teleportMode() == PathBuilder.TeleportMode.JUST_TELEPORT
                && hop.type() == HopType.NORMAL) {
            BlockPos above = hop.landing().above();
            if (player.level().getBlockState(above).isAir()) {
                return Vec3.atCenterOf(above).add(0.0, 0.62, 0.0);
            }
        }
        // Everything else comes from the shared rule, so the planner and this agree by
        // construction rather than by two implementations happening to match.
        return CastRules.aimPoint(hop, player.getEyePosition());
    }

    /** Whether the player is close enough to {@code goal} to call the route finished. */
    public boolean isAtGoal(LocalPlayer player, BlockPos goal) {
        if (goal == null) {
            return false;
        }
        Vec3 feet = player.position();
        double dx = feet.x - (goal.getX() + 0.5);
        double dz = feet.z - (goal.getZ() + 0.5);
        return dx * dx + dz * dz <= HOP_ARRIVE_HORIZONTAL_SQ
            && Math.abs(feet.y - goal.getY()) <= HOP_ARRIVE_ABOVE;
    }
}
