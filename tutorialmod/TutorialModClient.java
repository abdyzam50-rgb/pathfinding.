package net.netherite.tutorialmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.text.Text;
import net.netherite.tutorialmod.client.BlockOverlayRenderer;
import net.netherite.tutorialmod.client.TessellatorRenderer;
import net.netherite.tutorialmod.client.ClientTickHandler;

public class TutorialModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        TessellatorRenderer.register();
        ClientTickHandler.register();
        BlockOverlayRenderer.register();

        // /toggleoverlay — client-side overlay toggle
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("toggleoverlay")
                .executes(ctx -> {
                    BlockOverlayRenderer.toggle();
                    String state = BlockOverlayRenderer.isEnabled() ? "enabled" : "disabled";
                    ctx.getSource().sendFeedback(Text.literal("Block overlay " + state + "."));
                    return 1;
                }))
        );
    }
}
