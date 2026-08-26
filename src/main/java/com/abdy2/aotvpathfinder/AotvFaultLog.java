package com.abdy2.aotvpathfinder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * Writes a crash-report style file describing routing failures.
 *
 * <p>Records faults only. A hop that worked tells us nothing; the useful question is always what
 * set the failing node apart from the many around it that were fine. Each report therefore captures
 * both ends of the attempt — where the player stood and what they stood on, against where the node
 * sat and what it sat on — so the two can be compared after the fact.
 *
 * <p>Laid out like a Minecraft crash report: a header, then indented {@code -- Section --} blocks.
 * The point is that a report can be read straight out of the file, or pasted somewhere, without
 * needing the code open beside it to decode a dense single line.
 */
public final class AotvFaultLog {
    private static final String DIR_NAME = "aotv-faults";
    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
    private static final DateTimeFormatter ENTRY_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    /** Summaries held for in-game recall. The file keeps the full reports. */
    private static final int MEMORY_LIMIT = 20;

    private final Path file;
    private final Deque<String> summaries = new ArrayDeque<>();
    private boolean fileUsable = true;
    private boolean headerWritten;
    private int faultCount;

    private AotvFaultLog(Path file) {
        this.file = file;
    }

    public static AotvFaultLog create(Path runDir) {
        String name = "aotv-faults-" + LocalDateTime.now().format(FILE_STAMP) + ".txt";
        return new AotvFaultLog(runDir.resolve(DIR_NAME).resolve(name));
    }

    public Path file() {
        return file;
    }

    public int faultCount() {
        return faultCount;
    }

    /** Everything the router knew at the moment it gave up on a node. */
    public record Fault(
        String reason,
        int attempt,
        int maxAttempts,
        int stepIndex,
        int stepCount,
        TeleportHop step,
        BlockPos goal,
        int currentMana,
        double abilityRange,
        String abilityName,
        Boolean inRange,
        Boolean castLineClear,
        Boolean stepReached,
        String movementMode,
        String teleportMode
    ) {}

    public void record(Fault fault, LocalPlayer player) {
        try {
            faultCount++;
            String report = buildReport(fault, player);

            summaries.addLast(summarise(fault, player));
            while (summaries.size() > MEMORY_LIMIT) {
                summaries.removeFirst();
            }
            append(report);
        } catch (Exception ignored) {
            // Diagnostics must never take the game down with them.
        }
    }

    private String buildReport(Fault f, LocalPlayer player) {
        Level level = player.level();
        Vec3 feet = player.position();
        BlockPos playerBlock = player.blockPosition();
        BlockPos playerFloor = playerBlock.below();

        StringBuilder sb = new StringBuilder(1024);
        sb.append("---- AOTV Pathfinder Fault #").append(faultCount).append(" ----\n\n");
        sb.append("Time: ").append(LocalDateTime.now().format(ENTRY_STAMP)).append('\n');
        sb.append("Reason: ").append(f.reason()).append('\n');
        sb.append("Rebuild attempt: ").append(f.attempt()).append(" of ").append(f.maxAttempts()).append("\n\n");

        section(sb, "Route");
        detail(sb, "Step", f.stepIndex() + " of " + f.stepCount());
        detail(sb, "Movement mode", f.movementMode());
        detail(sb, "Teleport mode", f.teleportMode());
        if (f.goal() != null) {
            detail(sb, "Goal", pos(f.goal()));
            detail(sb, "Distance to goal",
                num(Math.sqrt(feet.distanceToSqr(Vec3.atBottomCenterOf(f.goal())))));
        } else {
            detail(sb, "Goal", "none");
        }
        sb.append('\n');

        TeleportHop step = f.step();
        section(sb, "Failing node");
        if (step == null) {
            detail(sb, "Node", "none (route had no current step)");
            sb.append('\n');
        } else {
            BlockPos landing = step.landing();
            BlockPos nodeFloor = landing.below();
            detail(sb, "Landing", pos(landing));
            detail(sb, "Hop type", step.type().name());
            detail(sb, "Mana cost", String.valueOf(step.manaCost()));
            detail(sb, "Floor block", pos(nodeFloor));
            detail(sb, "Floor state", describe(level, nodeFloor));
            // The landing rule both abilities need: solid underneath, two air blocks above it.
            detail(sb, "Feet air", bool(isAir(level, landing)));
            detail(sb, "Head air", bool(isAir(level, landing.above())));
            detail(sb, "Floor solid", bool(isSolid(level, nodeFloor)));
            sb.append('\n');
        }

        section(sb, "Player");
        // Sub-block position matters: arrival and range are both decided on it, and it is
        // invisible in a block coordinate.
        detail(sb, "Position", vec(feet));
        detail(sb, "Block position", pos(playerBlock));
        detail(sb, "On ground", bool(player.onGround()));
        detail(sb, "Floor block", pos(playerFloor));
        detail(sb, "Floor state", describe(level, playerFloor));
        detail(sb, "Mana", String.valueOf(f.currentMana()));
        sb.append('\n');

        if (step != null) {
            BlockPos landing = step.landing();
            double dx = feet.x - (landing.getX() + 0.5);
            double dz = feet.z - (landing.getZ() + 0.5);
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double vertical = landing.getY() - feet.y;

            section(sb, "Geometry");
            // Kept apart on purpose: which of the three a guard measures has repeatedly been the
            // difference between a hop being accepted and rejected.
            detail(sb, "Horizontal", num(horizontal));
            detail(sb, "Vertical", num(vertical));
            detail(sb, "Straight line", num(Math.sqrt(horizontal * horizontal + vertical * vertical)));
            detail(sb, "Ability range", num(f.abilityRange()) + " (" + f.abilityName() + ")");
            sb.append('\n');

            section(sb, "Router verdicts");
            detail(sb, "In range", bool(f.inRange()));
            detail(sb, "Cast line clear", bool(f.castLineClear()));
            detail(sb, "Step reached", bool(f.stepReached()));
            detail(sb, "Mana sufficient", bool(f.currentMana() < 0 || f.currentMana() >= step.manaCost()));
            sb.append('\n');
        }

        return sb.toString();
    }

    private String summarise(Fault f, LocalPlayer player) {
        TeleportHop step = f.step();
        if (step == null) {
            return "#" + faultCount + " " + f.reason() + " step=" + f.stepIndex() + "/" + f.stepCount();
        }
        Vec3 feet = player.position();
        BlockPos landing = step.landing();
        double dx = feet.x - (landing.getX() + 0.5);
        double dz = feet.z - (landing.getZ() + 0.5);
        return "#" + faultCount + " " + f.reason()
            + " step=" + f.stepIndex() + "/" + f.stepCount()
            + " " + step.type()
            + " node=" + pos(landing)
            + " horiz=" + num(Math.sqrt(dx * dx + dz * dz))
            + " vert=" + num(landing.getY() - feet.y);
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("-- ").append(title).append(" --\n").append("Details:\n");
    }

    private static void detail(StringBuilder sb, String key, String value) {
        sb.append('\t').append(key).append(": ").append(value).append('\n');
    }

    private static boolean isAir(Level level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        return level.getBlockState(pos).isSolid();
    }

    /** Block id plus the properties that decide whether it can be stood on or warped to. */
    private static String describe(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "<chunk not loaded>";
        }
        BlockState state = level.getBlockState(pos);
        String id = state.getBlock().getDescriptionId();
        if (id.startsWith("block.minecraft.")) {
            id = id.substring("block.minecraft.".length());
        }
        return id
            + (state.isSolid() ? " [solid" : " [non-solid")
            + (state.getCollisionShape(level, pos).isEmpty() ? ", no collision]" : "]");
    }

    private static String bool(Boolean value) {
        return value == null ? "unknown" : value.toString();
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static String vec(Vec3 vec) {
        return String.format(Locale.ROOT, "%.3f, %.3f, %.3f", vec.x, vec.y, vec.z);
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void append(String report) {
        if (!fileUsable) {
            return;
        }
        try {
            if (!headerWritten) {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, fileHeader(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                headerWritten = true;
            }
            Files.writeString(file, report, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Stop retrying rather than throwing on every subsequent fault.
            fileUsable = false;
        }
    }

    private static String fileHeader() {
        return """
            ---- AOTV Pathfinder Fault Log ----

            Routing failures only; successful hops are not recorded.
            Each report below is one node the router gave up on.

            Reading a report:
              Geometry lists horizontal, vertical and straight-line separately, because a
              guard measuring the wrong one is a common cause of a healthy node being
              refused. A large gap between horizontal and straight line means gravity
              settled the landing well below the point that was aimed at.

              A teleport node needs its floor solid with two air blocks above it. Floor
              solid = false on a SHIFT node means the planner produced a target the
              ability cannot use.

            -----------------------------------------------------------------------------

            """;
    }

    public List<String> recent(int limit) {
        List<String> all = new ArrayList<>(summaries);
        int from = Math.max(0, all.size() - limit);
        return all.subList(from, all.size());
    }

    public void clear() {
        summaries.clear();
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
