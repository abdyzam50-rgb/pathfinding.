package com.abdy2.aotvloader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs a staged update, before anything has a chance to open the jar being replaced.
 *
 * <p>Runs at pre-launch, which is the one useful moment: Fabric has read mod metadata but has not
 * yet loaded any mod's classes, so the pathfinder jar is not open and can still be replaced. Doing
 * this later — once its entrypoint has run — would fail on Windows, where an open jar is locked.
 *
 * <p>A failure here is not worth interrupting startup for. The staged file is left alone and the
 * install is simply retried next launch.
 */
public final class Installer implements PreLaunchEntrypoint {
    private static final Logger LOG = LoggerFactory.getLogger("aotvloader");

    @Override
    public void onPreLaunch() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        JarSource source = new JarSource(gameDir.resolve("mods"), gameDir.resolve("aotv"));

        Path staged = source.stagedJar();
        if (!Files.isRegularFile(staged)) {
            return;
        }

        try {
            Files.createDirectories(source.installedJar().getParent());
            Files.move(staged, source.installedJar(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Installed pathfinder update ({})",
                JarSource.shortHash(JarSource.hashOf(source.installedJar())));
        } catch (Exception atomicFailed) {
            // ATOMIC_MOVE is unsupported when the staging folder and mods/ are on different
            // volumes, which is ordinary rather than exceptional. Fall back to a plain move.
            try {
                Files.move(staged, source.installedJar(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Installed pathfinder update ({})",
                    JarSource.shortHash(JarSource.hashOf(source.installedJar())));
            } catch (Exception e) {
                LOG.warn("Could not install the staged update yet ({}); will retry next launch",
                    e.getClass().getSimpleName());
            }
        }
    }
}
