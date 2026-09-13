package com.abdy2.aotvloader;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports whether the pathfinder is actually running.
 *
 * <p>Does no work of its own — {@link Installer} has already finished by this point. It exists so
 * the log answers the only question worth asking after a launch: is the mod loaded or not. Without
 * it, a freshly installed build that has not been picked up yet looks identical to one that failed
 * to install.
 */
public final class AotvLoader implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("aotvloader");

    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("aotvpathfinder")) {
            String version = FabricLoader.getInstance().getModContainer("aotvpathfinder")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
            LOG.info("Pathfinder {} is loaded and running", version);
        } else {
            LOG.info("Pathfinder is not loaded this launch. If it was just installed, restart once.");
        }
    }
}
