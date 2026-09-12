package com.abdy2.aotvloader;

import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks for a newer pathfinder build and stages it for the next launch.
 *
 * <p>This mod is the only thing that needs installing by hand. It keeps
 * {@code mods/aotvpathfinder.jar} current on its own, which means the jar is a real mod as far as
 * Fabric is concerned: it appears in the mod list, crash reports attribute it properly, and it
 * would keep working if the pathfinder ever gained a mixin.
 *
 * <p>The cost of that is the usual one launch of delay. The jar cannot be replaced while it is
 * open, so a build fetched during one session is installed at the start of the next. {@link
 * Installer} does that half.
 */
public final class AotvLoader implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("aotvloader");

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        JarSource source = new JarSource(gameDir.resolve("mods"), gameDir.resolve("aotv"));
        boolean installed = Files.isRegularFile(source.installedJar());

        JarSource.Result result = source.checkForUpdate();
        switch (result.outcome()) {
            case ALREADY_CURRENT -> LOG.info("Pathfinder is up to date (build {})", result.detail());
            case STAGED, ALREADY_STAGED -> {
                if (installed) {
                    LOG.info("Pathfinder update ready (build {}) — restart to apply", result.detail());
                } else {
                    LOG.info("Pathfinder downloaded (build {}) — restart to install", result.detail());
                }
            }
            case UNREACHABLE -> {
                LOG.warn("Could not check for updates ({})", result.detail());
                if (!installed) {
                    LOG.warn("No pathfinder is installed yet. Connect and restart, or set jar_url in {}",
                        gameDir.resolve("aotv").resolve("loader.properties"));
                }
            }
        }
    }
}
