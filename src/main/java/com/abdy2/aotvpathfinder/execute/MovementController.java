package com.abdy2.aotvpathfinder.execute;

import com.abdy2.aotvpathfinder.path.PathHop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Walking, and releasing the inputs walking holds down.
 *
 * <p>These are movement primitives: they read the world and drive the keyboard, and none of them
 * knows anything about a route. Deciding <em>which</em> node to walk to, or whether to abandon one,
 * stays with the code that owns the route — only the act of getting there lives here.
 *
 * <p>{@link #releaseAllInputs} exists separately from {@link #stopWalking} because sneak is
 * deliberately left alone by the latter: shift hops toggle it mid-cast, and clearing it there would
 * cancel the hop being aimed. Anything ending a run must use the former, or the player is left
 * crouched.
 */
public final class MovementController {

    private final AimController aim;

    public MovementController(AimController aim) {
        this.aim = aim;
    }


    public boolean walkToStep(Minecraft client, LocalPlayer player, PathHop step) {
        Vec3 target = Vec3.atCenterOf(step.landing()).add(0.0, 0.62, 0.0);
        aim.lookAtWalkHuman(player, target);

        Vec3 here = new Vec3(player.getX(), player.getY(), player.getZ());
        double dist = here.distanceTo(target);
        if (dist < 1.15) {
            stopWalking(client);
            return true;
        }

        if (client.options != null) {
            client.options.keyUp.setDown(true);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyShift.setDown(false);
            client.options.keyJump.setDown(false);

            boolean inWater = player.level().getBlockState(player.blockPosition()).getFluidState().is(FluidTags.WATER)
                || player.level().getBlockState(player.blockPosition().above()).getFluidState().is(FluidTags.WATER);
            if (inWater) {
                boolean targetHigher = step.landing().getY() >= player.getY() - 0.05;
                client.options.keyJump.setDown(targetHigher);
                return false;
            }

            double hereFloor = floorTopY(player, player.blockPosition());
            double nextFloor = floorTopY(player, step.landing().below());
            double floorDelta = nextFloor - hereFloor;
            boolean uphillStep = floorDelta > 0.78;
            var aheadDir = player.getDirection();
            BlockPos ahead = player.blockPosition().relative(aheadDir);
            BlockState aheadState = player.level().getBlockState(ahead);
            boolean stepLikeAhead = aheadState.getBlock() instanceof SlabBlock || aheadState.getBlock() instanceof StairBlock;
            boolean oneBlockObstacleAhead = !stepLikeAhead
                && aheadState.isSolid()
                && player.level().getBlockState(ahead.above()).isAir();

            int cliffDropAhead = dropDistanceToFloor(player, ahead, 24);
            boolean cliffAhead = cliffDropAhead > 3;
            if (cliffAhead) {
                client.options.keyUp.setDown(false);
                client.options.keyShift.setDown(true);
                client.options.keyJump.setDown(false);
                return false;
            }

            boolean shouldJump = (uphillStep || oneBlockObstacleAhead) && dist < 2.35 && floorDelta >= 0.78;
            client.options.keyJump.setDown(shouldJump);
            if (shouldJump && player.onGround()) {
                player.jumpFromGround();
            }
        }

        return false;
    }

    public void stopWalking(Minecraft client) {
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
    }


    /**
     * Releases every input the router can hold, including sneak.
     *
     * <p>{@link #stopWalking} deliberately leaves sneak alone because shift-hops toggle it
     * mid-cast, but that means a run aborting between "shift down" and "cast complete" leaves the
     * player crouched. Anything that ends or restarts a run must come through here instead.
     */
    public void releaseAllInputs(Minecraft client) {
        stopWalking(client);
        if (client.options != null) {
            client.options.keyShift.setDown(false);
            client.options.keySprint.setDown(false);
        }
        if (client.player != null) {
            client.player.setShiftKeyDown(false);
        }
    }

    public double floorTopY(LocalPlayer player, BlockPos floorPos) {
        BlockState below = player.level().getBlockState(floorPos);
        var shape = below.getCollisionShape(player.level(), floorPos);
        if (shape.isEmpty()) {
            return floorPos.getY();
        }
        return floorPos.getY() + shape.max(Direction.Axis.Y);
    }

    public int dropDistanceToFloor(LocalPlayer player, BlockPos pos, int maxDrop) {
        BlockPos cursor = pos;
        for (int drop = 0; drop <= maxDrop; drop++) {
            if (player.level().getBlockState(cursor.below()).isSolid()) {
                return drop;
            }
            cursor = cursor.below();
        }
        return maxDrop + 1;
    }

    public boolean isSafeLanding(LocalPlayer player, BlockPos pos) {
        return player.level().getBlockState(pos).isAir()
            && player.level().getBlockState(pos.above()).isAir()
            && player.level().getBlockState(pos.below()).isSolid();
    }
}
