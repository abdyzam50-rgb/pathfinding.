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
        boolean hadJar = Files.isRegularFile(source.installedJar());

        JarSource.Result result = source.checkForUpdate();
        switch (result.outcome()) {
            case ALREADY_CURRENT -> LOG.info("Pathfinder is up to date (build {})", result.detail());
            case STAGED, ALREADY_STAGED -> install(source, result.detail(), hadJar);
            case UNREACHABLE -> {
                LOG.warn("Could not check for updates ({})", result.detail());
                if (!hadJar) {
                    LOG.warn("No pathfinder installed yet. Connect and restart, or set jar_url in {}",
                        gameDir.resolve("aotv").resolve("loader.properties"));
                }
            }
        }
    }

    private void install(JarSource source, String build, boolean hadJar) {
        Path staged = source.stagedJar();
        if (!Files.isRegularFile(staged)) {
            return;
        }
        try {
            Files.createDirectories(source.installedJar().getParent());
            move(staged, source.installedJar());
            LOG.info("{} pathfinder build {} — it will be active next launch",
                hadJar ? "Updated to" : "Installed", build);
        } catch (Exception e) {
            // Leave the staged file alone and try again next launch rather than interrupting
            // startup. The jar being open is the usual cause, and that clears on restart.
            LOG.warn("Could not install build {} yet ({}); will retry next launch",
                build, e.getClass().getSimpleName());
        }
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
