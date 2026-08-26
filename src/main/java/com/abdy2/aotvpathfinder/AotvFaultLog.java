package com.abdy2.aotvpathfinder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Records why a routing attempt failed, with enough geometry to work out what the node had wrong.
 *
 * <p>Deliberately records faults only. A successful hop tells us nothing we need; the interesting
 * question is always what distinguished the node that did not work from the many that did. Each
 * entry therefore captures both ends of the attempt — where the player actually stood and what they
 * stood on, against where the node sat and what it sat on — so the two can be compared afterwards.
 *
 * <p>Kept append-only and failure-triggered so it stays small enough to read by hand and cannot
 * meaningfully affect frame time.
 */
public final class AotvFaultLog {
    private static final String FILE_NAME = "aotvpathfinder-faults.log";
    /** Entries held for in-game recall. The file keeps everything. */
    private static final int MEMORY_LIMIT = 40;

    private final Deque<String> recent = new ArrayDeque<>();
    private final Path file;
    private boolean fileUsable = true;
    private boolean headerWritten;
    private int faultCount;

    private AotvFaultLog(Path file) {
        this.file = file;
    }

    public static AotvFaultLog create(Path runDir) {
        return new AotvFaultLog(runDir.resolve(FILE_NAME));
    }

    public Path file() {
        return file;
    }

    public int faultCount() {
        return faultCount;
    }

    /**
     * Captures one failed attempt.
     *
     * @param reason     why the router gave up on this node
     * @param attempt    which consecutive rebuild this is
     * @param stepIndex  index of the node in the current route
     * @param stepCount  length of the current route
     * @param step       the node that failed, or null if the route had no current node
     * @param player     the player, read for position and footing
     * @param goal       the destination, or null
     * @param extra      any check results the caller already computed
     */
    public void record(String reason, int attempt, int stepIndex, int stepCount,
                       TeleportHop step, LocalPlayer player, BlockPos goal, String extra) {
        try {
            String line = build(reason, attempt, stepIndex, stepCount, step, player, goal, extra);
            faultCount++;
            recent.addLast(line);
            while (recent.size() > MEMORY_LIMIT) {
                recent.removeFirst();
            }
            append(line);
        } catch (Exception ignored) {
            // Diagnostics must never take the game down with them.
        }
    }

    private String build(String reason, int attempt, int stepIndex, int stepCount,
                         TeleportHop step, LocalPlayer player, BlockPos goal, String extra) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("FAULT #").append(faultCount + 1)
          .append(" reason=").append(reason)
          .append(" attempt=").append(attempt)
          .append(" step=").append(stepIndex).append('/').append(stepCount);

        Level level = player.level();
        Vec3 feet = player.position();
        BlockPos playerBlock = player.blockPosition();
        BlockPos playerFloor = playerBlock.below();

        // Where the player really is, and what is under their feet. Sub-block position matters:
        // arrival and range are both decided on it, and it is invisible in a block coordinate.
        sb.append(" | player pos=").append(fmt(feet))
          .append(" block=").append(fmt(playerBlock))
          .append(" onGround=").append(player.onGround())
          .append(" floor=").append(fmt(playerFloor))
          .append(' ').append(describe(level, playerFloor));

        if (step != null) {
            BlockPos landing = step.landing();
            BlockPos nodeFloor = landing.below();

            sb.append(" | node=").append(fmt(landing))
              .append(" type=").append(step.type())
              .append(" mana=").append(step.manaCost())
              .append(" floor=").append(fmt(nodeFloor))
              .append(' ').append(describe(level, nodeFloor));

            // The landing rule the abilities need: solid underneath, two air blocks above it.
            sb.append(" feetAir=").append(isAir(level, landing))
              .append(" headAir=").append(isAir(level, landing.above()))
              .append(" floorSolid=").append(isSolid(level, nodeFloor));

            double dx = feet.x - (landing.getX() + 0.5);
            double dy = (landing.getY()) - feet.y;
            double dz = feet.z - (landing.getZ() + 0.5);
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            sb.append(" | horiz=").append(round(horizontal))
              .append(" vert=").append(round(dy))
              .append(" straight=").append(round(Math.sqrt(horizontal * horizontal + dy * dy)));
        } else {
            sb.append(" | node=none");
        }

        if (goal != null) {
            sb.append(" | goal=").append(fmt(goal))
              .append(" goalDist=").append(round(Math.sqrt(
                  player.position().distanceToSqr(Vec3.atBottomCenterOf(goal)))));
        }

        if (extra != null && !extra.isEmpty()) {
            sb.append(" | ").append(extra);
        }
        return sb.toString();
    }

    private static boolean isAir(Level level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        return level.getBlockState(pos).isSolid();
    }

    /** Block id plus the two properties that decide whether it can be stood on or warped to. */
    private static String describe(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "[unloaded]";
        }
        BlockState state = level.getBlockState(pos);
        String id = state.getBlock().getDescriptionId();
        if (id.startsWith("block.minecraft.")) {
            id = id.substring("block.minecraft.".length());
        }
        return "[" + id
            + (state.isSolid() ? " solid" : " nonsolid")
            + (state.getCollisionShape(level, pos).isEmpty() ? " nocollide" : "")
            + "]";
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String fmt(Vec3 vec) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", vec.x, vec.y, vec.z);
    }

    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void append(String line) {
        if (!fileUsable) {
            return;
        }
        try {
            if (!headerWritten) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, "=== AOTV pathfinder fault log ===\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                headerWritten = true;
            }
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Stop retrying rather than spamming on every fault.
            fileUsable = false;
        }
    }

    public List<String> recent(int limit) {
        List<String> all = new ArrayList<>(recent);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    public void clear() {
        recent.clear();
        faultCount = 0;
        try {
            Files.deleteIfExists(file);
            headerWritten = false;
            fileUsable = true;
        } catch (IOException ignored) {
            // Leaving the old file in place is harmless.
        }
    }
}
