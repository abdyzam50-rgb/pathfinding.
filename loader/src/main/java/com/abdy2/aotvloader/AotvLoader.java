package com.abdy2.aotvloader;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches the current pathfinder build and starts it, every launch.
 *
 * <p>This mod is the only thing that needs installing. The pathfinder itself is downloaded to
 * {@code <game>/aotv/} and loaded from there, so keeping it current never involves touching
 * {@code mods/} by hand.
 *
 * <p>Loading it here rather than letting Fabric do it is what makes an update apply on the run that
 * downloaded it. Fabric opens everything in {@code mods/} before any mod code runs, which is both
 * too early to have fetched anything and too late to swap what was already opened. The pathfinder
 * can be treated this way because it is a plain mod: one client entrypoint, no mixins, and an empty
 * access widener, so nothing about it needs to be woven into the game before startup. Were that to
 * change — were it to gain a mixin — this approach would stop working and the jar would have to go
 * back into {@code mods/} with updates applying a launch later.
 */
public final class AotvLoader implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("aotvloader");
    private static final String ENTRYPOINT = "com.abdy2.aotvpathfinder.AotvPathfinderClient";

    @Override
    public void onInitializeClient() {
        // If the pathfinder is also sitting in mods/, Fabric has already loaded and started it.
        // Starting a second copy from the downloaded jar would register every callback, key
        // binding and command twice, so stand down and let the installed one run.
        if (FabricLoader.getInstance().isModLoaded("aotvpathfinder")) {
            LOG.warn("aotvpathfinder is installed in mods/, so the loader will not manage it.");
            LOG.warn("Delete it from mods/ to have the loader keep it up to date instead.");
            return;
        }

        Path directory = FabricLoader.getInstance().getGameDir().resolve("aotv");
        JarSource source = new JarSource(directory);

        JarSource.Result result = source.fetchLatest();
        switch (result.outcome()) {
            case UPDATED -> LOG.info("Updated pathfinder to build {}", result.detail());
            case ALREADY_CURRENT -> LOG.info("Pathfinder is current (build {})", result.detail());
            case OFFLINE_USING_CACHE ->
                LOG.warn("Could not check for updates ({}); using the copy already downloaded", result.detail());
            case UNAVAILABLE -> {
                LOG.error("Could not download the pathfinder and none is cached ({})", result.detail());
                LOG.error("Nothing to load. Check the connection, or set jar_url in {}/loader.properties",
                    directory);
                return;
            }
        }

        start(result.jar());
    }

    /**
     * Loads the jar in a child loader and runs its client entrypoint.
     *
     * <p>The parent is this class's own loader, which is Fabric's, so the pathfinder resolves
     * Minecraft and the Fabric API through it and only its own classes come from the downloaded
     * file. Running the entrypoint from here means its registrations land at the same point in
     * startup they would have if Fabric had loaded it directly.
     */
    private void start(Path jar) {
        try {
            URL[] sources = { jar.toUri().toURL() };
            // Not closed: the classes stay live for the session, and closing the loader would
            // invalidate them.
            URLClassLoader loader = new URLClassLoader("aotvpathfinder", sources, getClass().getClassLoader());
            Class<?> entry = loader.loadClass(ENTRYPOINT);
            Object mod = entry.getDeclaredConstructor().newInstance();
            entry.getMethod("onInitializeClient").invoke(mod);
            LOG.info("Pathfinder started");
        } catch (ClassNotFoundException e) {
            LOG.error("Downloaded jar does not contain {} — is jar_url pointing at the right file?", ENTRYPOINT);
        } catch (Exception e) {
            LOG.error("Pathfinder failed to start", e);
        }
    }
}
