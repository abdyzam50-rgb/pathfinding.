package net.netherite.tutorialmod.mining;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Client-side block breaking: selects the correct tool, faces the block, holds attack.
 */
public final class MiningExecutor {

    private static BlockPos target     = null;
    private static boolean  active     = false;
    private static int      noProgress = 0;

    private MiningExecutor() {}

    public static void start(BlockPos pos) {
        target     = pos;
        active     = true;
        noProgress = 0;
    }

    public static void stop() {
        active = false;
        target = null;
        releaseAttack();
    }

    public static boolean isActive() { return active; }
    public static BlockPos getTarget() { return target; }

    public static boolean tick(MinecraftClient mc) {
        if (!active || target == null || mc.player == null || mc.world == null) {
            stop();
            return false;
        }
        ClientPlayerEntity player = mc.player;
        BlockState state = mc.world.getBlockState(target);

        if (state.isAir()) {
            stop();
            return true; // broken
        }

        // equipBestTool searches the full inventory (not just hotbar) and swaps the
        // tool into the hotbar via a SWAP packet if it was in the main inventory.
        int toolSlot = ToolSelector.equipBestTool(mc, player, state);
        if (toolSlot >= 0 && player.getInventory().selectedSlot != toolSlot) {
            player.getInventory().selectedSlot = toolSlot;
            player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(toolSlot));
        }

        faceBlock(player, target);
        attackBlock(mc, target);
        mc.options.attackKey.setPressed(true);

        // With a proper tool equipped, keep mining; only time out when unbreakable / wrong tool.
        ItemStack held = player.getMainHandStack();
        float speed = held.getMiningSpeedMultiplier(state);
        if (toolSlot >= 0 && speed > 1.0f) {
            noProgress = 0;
        } else if (++noProgress > 60) {
            stop();
            return false;
        }
        return false;
    }

    public static void resetProgress() { noProgress = 0; }

    private static void faceBlock(ClientPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double h  = Math.sqrt(dx * dx + dz * dz);
        player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setPitch(h > 0.001 ? (float) Math.toDegrees(Math.atan2(-dy, h)) : 0f);
    }

    public static BlockHitResult raycast(MinecraftClient mc, BlockPos pos) {
        Vec3d hit = Vec3d.ofCenter(pos);
        return new BlockHitResult(hit, Direction.UP, pos, false);
    }

    public static void attackBlock(MinecraftClient mc, BlockPos pos) {
        if (mc.interactionManager == null || mc.player == null) return;
        BlockHitResult bhr = raycast(mc, pos);
        mc.interactionManager.attackBlock(pos, bhr.getSide());
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private static void releaseAttack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) mc.options.attackKey.setPressed(false);
    }
}
