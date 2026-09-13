package com.abdy2.aotvloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches the current pathfinder build and puts it in {@code mods/}, ready for the next launch.
 *
 * <p>All of this happens at pre-launch, and the timing is the whole point. Fabric's startup runs in
 * this order:
 *
 * <pre>
 *   1. discover mods in mods/
 *   2. load their classes
 *   3. preLaunch entrypoints   &lt;-- here
 *   4. client entrypoints
 * </pre>
 *
 * <p>Discovery is step 1, so a jar written at step 3 is not seen until the following launch. That
 * is unavoidable — it is why an update always takes effect one launch later — but it does mean the
 * fetch and the install have to happen in the <em>same</em> run. Splitting them, downloading at
 * step 4 and installing at step 3 of the next launch, quietly costs an extra launch: the jar only
 * lands after discovery has already been and gone.
 *
 * <p>Pre-launch is also the one moment the file can be replaced at all. By step 4 the jar is open
 * and Windows will not let go of it.
 */
public final class Installer implements PreLaunchEntrypoint {
    private static final Logger LOG = LoggerFactory.getLogger("aotvloader");

    @Override
    public void onPreLaunch() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        JarSource source = new JarSource(gameDir.resolve("mods"), gameDir.resolve("aotv"));
        purgeSuperseded(source);
        boolean hadJar = Files.isRegularFile(source.installedJar());

        JarSource.Result result = source.checkForUpdate();
        switch (result.outcome()) {
            case ALREADY_CURRENT -> LOG.info("Pathfinder is up to date (build {})", result.detail());
            case STAGED, ALREADY_STAGED -> {
                if (install(source, result.detail(), hadJar)) {
                    closeSoTheNewBuildIsUsed(result.detail(), hadJar);
                }
            }
            case UNREACHABLE -> {
                LOG.warn("Could not check for updates ({})", result.detail());
                if (!hadJar) {
                    LOG.warn("No pathfinder installed yet. Connect and restart, or set jar_url in {}",
                        gameDir.resolve("aotv").resolve("loader.properties"));
                }
            }
        }
    }

    private boolean install(JarSource source, String build, boolean hadJar) {
        Path staged = source.stagedJar();
        if (!Files.isRegularFile(staged)) {
            return false;
        }
        Path installed = source.installedJar();

        try {
            Files.createDirectories(installed.getParent());
        } catch (Exception e) {
            LOG.warn("Could not prepare the mods folder ({})", e.toString());
            return false;
        }

        // Straight replace. This is the whole job when nothing is installed yet, which is why a
        // first install has always worked while updates did not.
        try {
            move(staged, installed);
            LOG.info("{} build {}", hadJar ? "Updated to" : "Installed", build);
            return true;
        } catch (Exception directFailed) {
            if (!hadJar) {
                LOG.warn("Could not write the pathfinder jar ({})", directFailed.toString());
                return false;
            }
            LOG.info("Could not overwrite the installed jar ({}); moving it aside instead",
                directFailed.getClass().getSimpleName());
        }

        // Fabric read the existing jar during startup and may still hold it, and an open jar cannot
        // be overwritten on Windows. Moving it out of the way often succeeds where overwriting does
        // not, and it has to leave mods/ regardless: two jars declaring the same mod id would stop
        // Fabric loading at all next launch.
        try {
            Path aside = source.supersededDir()
                .resolve("aotvpathfinder-" + System.currentTimeMillis() + ".jar");
            Files.createDirectories(aside.getParent());
            Files.move(installed, aside);
            move(staged, installed);
            LOG.info("Updated to build {} (previous jar moved to {})", build, aside.getParent());
            return true;
        } catch (Exception asideFailed) {
            LOG.warn("Could not install build {}: {}", build, asideFailed.toString());
            LOG.warn("The existing jar is locked. Close any other Minecraft instance and relaunch,");
            LOG.warn("or delete {} by hand.", installed);
            return false;
        }
    }

    /** Removes jars shifted aside by earlier updates, once nothing can be holding them. */
    private void purgeSuperseded(JarSource source) {
        Path dir = source.supersededDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var entries = Files.list(dir)) {
            for (Path old : entries.toList()) {
                try {
                    Files.deleteIfExists(old);
                } catch (Exception stillHeld) {
                    // Another launch will get it.
                }
            }
        } catch (Exception ignored) {
            // Tidying is optional; never let it affect startup.
        }
    }

    /**
     * Ends the launch so the build just written is the one that actually runs.
     *
     * <p>Fabric decided which mods it was loading before this ran, so carrying on would play the
     * previous build for a whole session while a newer one sat unused on disk -- looking like the
     * update worked when it did not. Stopping now costs nothing: no window has opened and no world
     * has loaded.
     *
     * <p>Exits zero deliberately. This is a chosen outcome, not a failure, and a launcher should
     * report it as an ordinary close rather than a crash.
     */
    private void closeSoTheNewBuildIsUsed(String build, boolean hadJar) {
        String action = hadJar ? "Updated to" : "Installed";
        LOG.warn("=================================================================");
        LOG.warn("  {} AOTV Pathfinder build {}", action, build);
        LOG.warn("");
        LOG.warn("  Closing now so the new build is the one that loads.");
        LOG.warn("  Start the instance again to play.");
        LOG.warn("=================================================================");
        System.exit(0);
    }

    private static void move(Path from, Path to) throws Exception {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            // Different volumes cannot be moved atomically, which is ordinary rather than a fault.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
