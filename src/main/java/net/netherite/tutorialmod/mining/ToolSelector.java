package net.netherite.tutorialmod.mining;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.netherite.tutorialmod.goal.EarlyGameProgress;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Picks the best hotbar tool for breaking a block (pickaxe / axe / shovel / hoe / sword).
 */
public final class ToolSelector {

    private ToolSelector() {}

    /**
     * Returns the hotbar slot (0-8) of the best tool for breaking {@code state},
     * or -1 if no suitable tool is in the hotbar.
     */
    public static int findBestToolSlot(ClientPlayerEntity player, BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;

        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !EarlyGameProgress.isToolUsable(stack)) continue;
            if (!isGoodTool(stack, state)) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * Ensures the best tool for mining {@code state} is in the hotbar and returns its slot.
     *
     * <p>If the best tool is already in the hotbar, returns that slot immediately.
     * If it is in the main inventory (slots 9-35), swaps it into the hotbar via a
     * {@link SlotActionType#SWAP} packet (the same action as pressing 1-9 while hovering
     * over a slot in the inventory screen) and returns the target hotbar slot.
     *
     * @return hotbar slot 0-8 containing the best tool, or -1 if no tool found anywhere.
     */
    public static int equipBestTool(MinecraftClient mc, ClientPlayerEntity player, BlockState state) {
        // 1. Check hotbar first
        int hotbarSlot = findBestToolSlot(player, state);
        if (hotbarSlot >= 0) return hotbarSlot;

        // 2. Search full main inventory (slots 9-35)
        var inv = player.getInventory();
        int bestInvSlot = -1;
        float bestSpeed  = 1.0f;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty() || !EarlyGameProgress.isToolUsable(stack)) continue;
            if (!isGoodTool(stack, state)) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestInvSlot = i; }
        }
        if (bestInvSlot < 0) return -1; // no tool anywhere

        // 3. Pick a target hotbar slot — prefer an empty slot, fall back to slot 8
        int targetHotbar = 8;
        for (int i = 8; i >= 0; i--) {
            if (inv.getStack(i).isEmpty()) { targetHotbar = i; break; }
        }

        // 4. Swap via SWAP packet (server-synced, no GUI needed).
        //    PlayerScreenHandler slot layout:
        //      0      = crafting output
        //      1-4    = crafting grid
        //      5-8    = armor
        //      9-35   = main inventory rows  (inv index == screen index for these)
        //      36-44  = hotbar               (inv 0-8  → screen 36-44)
        //    SlotActionType.SWAP button 0-8 swaps the hovered slot with hotbar slot N.
        if (mc.interactionManager != null) {
            mc.interactionManager.clickSlot(
                    player.playerScreenHandler.syncId,
                    bestInvSlot,   // screen slot = inv slot for main inventory (9-35)
                    targetHotbar,  // button = hotbar slot to swap with (0-8)
                    SlotActionType.SWAP,
                    player);
        }
        return targetHotbar;
    }

    public static boolean canMineWithCurrentTool(ClientPlayerEntity player, BlockState state) {
        ItemStack held = player.getMainHandStack();
        return EarlyGameProgress.isToolUsable(held) && isGoodTool(held, state)
                && held.getMiningSpeedMultiplier(state) > 1.0f;
    }

    private static boolean isGoodTool(ItemStack stack, BlockState state) {
        if (!EarlyGameProgress.isToolUsable(stack)) return false;
        if (stack.isIn(ItemTags.SWORDS) && (state.isIn(BlockTags.SWORD_EFFICIENT)
                || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.WOOL))) {
            return true;
        }
        if (stack.isIn(ItemTags.PICKAXES) && state.isIn(BlockTags.PICKAXE_MINEABLE)) return true;
        if (stack.isIn(ItemTags.AXES)     && state.isIn(BlockTags.AXE_MINEABLE))     return true;
        if (stack.isIn(ItemTags.SHOVELS) && state.isIn(BlockTags.SHOVEL_MINEABLE)) return true;
        if (stack.isIn(ItemTags.HOES)    && state.isIn(BlockTags.HOE_MINEABLE))    return true;
        return !state.isToolRequired() || stack.isSuitableFor(state);
    }
}
