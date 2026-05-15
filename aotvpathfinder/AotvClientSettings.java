package com.abdy2.aotvpathfinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class AotvClientSettings {
    private static final String FILE_NAME = "aotv-pathfinder.properties";

    private final Path path;
    private TeleportPathfinder.MovementMode movementMode = TeleportPathfinder.MovementMode.HYBRID;
    private TeleportPathfinder.TeleportMode teleportMode = TeleportPathfinder.TeleportMode.HYBRID_TELEPORT;
    private boolean airChainEnabled = true;
    private int walkPatchWindowBlocks = 6;
    private int teleportPatchLookaheadNodes = 2;
    private int maxPatchAttemptsPerSecond = 3;
    private int commitLockMs = 300;

    private AotvClientSettings(Path path) {
        this.path = path;
    }

    public static AotvClientSettings load(Path runDir) {
        Path configDir = runDir.resolve("config");
        AotvClientSettings settings = new AotvClientSettings(configDir.resolve(FILE_NAME));
        settings.read();
        return settings;
    }

    public TeleportPathfinder.MovementMode movementMode() {
        return movementMode;
    }

    public TeleportPathfinder.TeleportMode teleportMode() {
        return teleportMode;
    }

    public boolean airChainEnabled() {
        return airChainEnabled;
    }
    public int walkPatchWindowBlocks() {
        return walkPatchWindowBlocks;
    }
    public int teleportPatchLookaheadNodes() {
        return teleportPatchLookaheadNodes;
    }
    public int maxPatchAttemptsPerSecond() {
        return maxPatchAttemptsPerSecond;
    }
    public int commitLockMs() {
        return commitLockMs;
    }

    public void setMovementMode(TeleportPathfinder.MovementMode mode) {
        this.movementMode = mode;
        write();
    }

    public void setTeleportMode(TeleportPathfinder.TeleportMode mode) {
        this.teleportMode = mode;
        write();
    }

    public void setAirChainEnabled(boolean enabled) {
        this.airChainEnabled = enabled;
        write();
    }
    public void setWalkPatchWindowBlocks(int value) {
        this.walkPatchWindowBlocks = Math.max(4, Math.min(8, value));
        write();
    }
    public void setTeleportPatchLookaheadNodes(int value) {
        this.teleportPatchLookaheadNodes = Math.max(1, Math.min(3, value));
        write();
    }
    public void setMaxPatchAttemptsPerSecond(int value) {
        this.maxPatchAttemptsPerSecond = Math.max(1, Math.min(10, value));
        write();
    }
    public void setCommitLockMs(int value) {
        this.commitLockMs = Math.max(120, Math.min(1200, value));
        write();
    }

    private void read() {
        if (!Files.exists(path)) {
            write();
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            movementMode = parseMode(props.getProperty("movement_mode", movementMode.name()));
            teleportMode = parseTeleportMode(props.getProperty("teleport_mode", teleportMode.name()));
            airChainEnabled = Boolean.parseBoolean(props.getProperty("air_chain_enabled", "true"));
            walkPatchWindowBlocks = parseInt(props.getProperty("walk_patch_window_blocks"), 6, 4, 8);
            teleportPatchLookaheadNodes = parseInt(props.getProperty("teleport_patch_lookahead_nodes"), 2, 1, 3);
            maxPatchAttemptsPerSecond = parseInt(props.getProperty("max_patch_attempts_per_second"), 3, 1, 10);
            commitLockMs = parseInt(props.getProperty("commit_lock_ms"), 300, 120, 1200);
        } catch (IOException ignored) {
            // Keep defaults.
        }
    }

    private void write() {
        try {
            Files.createDirectories(path.getParent());
            Properties props = new Properties();
            props.setProperty("movement_mode", movementMode.name());
            props.setProperty("teleport_mode", teleportMode.name());
            props.setProperty("air_chain_enabled", String.valueOf(airChainEnabled));
            props.setProperty("walk_patch_window_blocks", String.valueOf(walkPatchWindowBlocks));
            props.setProperty("teleport_patch_lookahead_nodes", String.valueOf(teleportPatchLookaheadNodes));
            props.setProperty("max_patch_attempts_per_second", String.valueOf(maxPatchAttemptsPerSecond));
            props.setProperty("commit_lock_ms", String.valueOf(commitLockMs));
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "AOTV Pathfinder settings");
            }
        } catch (IOException ignored) {
            // Ignore write errors and continue runtime behavior.
        }
    }

    private static TeleportPathfinder.MovementMode parseMode(String raw) {
        if (raw == null) {
            return TeleportPathfinder.MovementMode.HYBRID;
        }
        try {
            return TeleportPathfinder.MovementMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TeleportPathfinder.MovementMode.HYBRID;
        }
    }
    private static TeleportPathfinder.TeleportMode parseTeleportMode(String raw) {
        if (raw == null) {
            return TeleportPathfinder.TeleportMode.HYBRID_TELEPORT;
        }
        try {
            return TeleportPathfinder.TeleportMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TeleportPathfinder.TeleportMode.HYBRID_TELEPORT;
        }
    }

    private static int parseInt(String raw, int fallback, int min, int max) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

