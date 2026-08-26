package net.netherite.tutorialmod;
import net.netherite.tutorialmod.ModCommands;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TutorialMod implements ModInitializer {
	public static final String MOD_ID = "tutorial-mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Tutorial Mod Components!");

		// Delegate command setup initialization out of our main loop class
		ModCommands.register();
	}
}