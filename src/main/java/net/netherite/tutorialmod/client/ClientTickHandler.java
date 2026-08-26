package net.netherite.tutorialmod.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.netherite.tutorialmod.PathFollower;
import net.netherite.tutorialmod.PlannedBridgeExecutor;
import net.netherite.tutorialmod.SpeedBridger;
import net.netherite.tutorialmod.background.BackgroundManager;
import net.netherite.tutorialmod.crafting.CraftingExecutor;
import net.netherite.tutorialmod.goal.GoalEngine;

/**
 * Handles client-side tick events for path following.
 */
public class ClientTickHandler {
    
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            // Goal engine orchestrates crafting, mining, pathing, and PvP
            if (GoalEngine.isActive()) {
                GoalEngine.tick(mc);
            } else if (CraftingExecutor.isActive()) {
                CraftingExecutor.tick(mc);
            } else if (PlannedBridgeExecutor.isActive()) {
                PlannedBridgeExecutor.tick();
            } else if (SpeedBridger.isActive()) {
                SpeedBridger.tick();
            }

            // PathFollower must be ticked unconditionally — goals and commands both
            // call startFollowing() and rely on it being driven every game tick,
            // regardless of which system (GoalEngine, CraftingExecutor, etc.) is active.
            if (PathFollower.isFollowing() && mc.player != null) {
                PathFollower.tick(mc.player);
            }

            // BackgroundManager runs every tick regardless of active goal:
            // handles eating, inventory cleanup, etc.
            BackgroundManager.tick(mc);
        });
    }
}