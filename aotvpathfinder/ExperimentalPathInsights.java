package com.abdy2.aotvpathfinder;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;

/**
 * Experimental-only diagnostics overlay for path candidate exploration.
 * Keep this separate from core logic so it can be removed easily later.
 */
final class ExperimentalPathInsights {
    enum StartCategory {
        LEFT_START,
        RIGHT_START,
        UPWARD_START,
        DOWNWARD_START,
        STRAIGHT_START
    }

    private record PreviewPath(int index, StartCategory category, List<BlockPos> points, int mana, int steps, boolean reached) {}

    private static final int MAX_RENDER_PATHS = 120;
    private static final int MAX_RENDER_STEPS_PER_PATH = 12;
    private static final Map<StartCategory, Integer> CATEGORY_COLORS = Map.of(
        StartCategory.LEFT_START, 0x4DD2FF,
        StartCategory.RIGHT_START, 0xFFB347,
        StartCategory.UPWARD_START, 0x7DFF7A,
        StartCategory.DOWNWARD_START, 0xFF6B6B,
        StartCategory.STRAIGHT_START, 0xDFA8FF
    );

    private static final EnumMap<StartCategory, Integer> counts = new EnumMap<>(StartCategory.class);
    private static List<PreviewPath> previews = new ArrayList<>();
    private static BlockPos origin;
    private static BlockPos goal;

    private ExperimentalPathInsights() {}

    static void buildFromCandidates(BlockPos start, BlockPos target, List<TeleportPathfinder.CandidatePath> candidates) {
        origin = start;
        goal = target;

        for (StartCategory category : StartCategory.values()) {
            counts.put(category, 0);
        }

        List<PreviewPath> next = new ArrayList<>();
        int idx = 0;
        for (TeleportPathfinder.CandidatePath candidate : candidates) {
            if (candidate.hops().isEmpty()) {
                idx++;
                continue;
            }

            StartCategory category = classifyStart(start, target, candidate.hops().get(0).landing());
            counts.put(category, counts.get(category) + 1);

            List<BlockPos> points = new ArrayList<>();
            points.add(start);
            int end = Math.min(candidate.hops().size(), MAX_RENDER_STEPS_PER_PATH);
            for (int i = 0; i < end; i++) {
                points.add(candidate.hops().get(i).landing());
            }

            int mana = candidate.hops().stream().mapToInt(TeleportHop::manaCost).sum();
            next.add(new PreviewPath(idx, category, points, mana, candidate.hops().size(), candidate.reachedGoal()));
            idx++;

            if (next.size() >= MAX_RENDER_PATHS) {
                break;
            }
        }

        previews = next;
    }

    static void clear() {
        previews = new ArrayList<>();
        origin = null;
        goal = null;
        counts.clear();
    }

    static void renderWorld(WorldRenderContext context, MinecraftClient client) {
        if (client.player == null || client.world == null || previews.isEmpty()) {
            return;
        }
        if (context.matrices() == null || context.consumers() == null || context.worldState().cameraRenderState.pos == null) {
            return;
        }

        Vec3d cam = context.worldState().cameraRenderState.pos;
        VertexConsumer lines = context.consumers().getBuffer(RenderLayers.linesTranslucent());
        context.matrices().push();
        context.matrices().translate(-cam.x, -cam.y, -cam.z);

        for (PreviewPath preview : previews) {
            int color = CATEGORY_COLORS.getOrDefault(preview.category(), 0xFFFFFF);
            float width = preview.reached() ? 1.9F : 1.2F;

            Vec3d last = null;
            for (BlockPos p : preview.points()) {
                Vec3d center = Vec3d.ofCenter(p).add(0.0, 0.08, 0.0);
                if (last != null) {
                    drawLine(context, lines, last, center, color, width);
                }
                last = center;
            }

            if (preview.points().size() > 1) {
                BlockPos first = preview.points().get(1);
                VertexRendering.drawOutline(
                    context.matrices(),
                    lines,
                    VoxelShapes.cuboid(0.22, 0.05, 0.22, 0.78, 0.62, 0.78),
                    first.getX(),
                    first.getY(),
                    first.getZ(),
                    color,
                    width + 0.6F
                );
            }
        }

        context.matrices().pop();
    }

    private static String shortCat(StartCategory category) {
        return switch (category) {
            case LEFT_START -> "L";
            case RIGHT_START -> "R";
            case UPWARD_START -> "U";
            case DOWNWARD_START -> "D";
            case STRAIGHT_START -> "S";
        };
    }

    private static StartCategory classifyStart(BlockPos start, BlockPos goal, BlockPos first) {
        Vec3d toFirst = Vec3d.ofCenter(first).subtract(Vec3d.ofCenter(start));
        double horiz = Math.hypot(toFirst.x, toFirst.z);

        if (toFirst.y > Math.max(1.4, horiz * 0.55)) {
            return StartCategory.UPWARD_START;
        }
        if (toFirst.y < -Math.max(1.4, horiz * 0.55)) {
            return StartCategory.DOWNWARD_START;
        }

        Vec3d toGoal = Vec3d.ofCenter(goal).subtract(Vec3d.ofCenter(start));
        Vec3d goalHoriz = new Vec3d(toGoal.x, 0.0, toGoal.z);
        Vec3d firstHoriz = new Vec3d(toFirst.x, 0.0, toFirst.z);

        if (goalHoriz.lengthSquared() < 0.001 || firstHoriz.lengthSquared() < 0.001) {
            return StartCategory.STRAIGHT_START;
        }

        Vec3d g = goalHoriz.normalize();
        Vec3d f = firstHoriz.normalize();
        double crossY = g.x * f.z - g.z * f.x;
        double dot = g.dotProduct(f);

        if (dot > 0.93) {
            return StartCategory.STRAIGHT_START;
        }
        if (crossY > 0.0) {
            return StartCategory.LEFT_START;
        }
        return StartCategory.RIGHT_START;
    }

    private static void drawLine(WorldRenderContext context, VertexConsumer lines, Vec3d from, Vec3d to, int color, float width) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        var entry = context.matrices().peek();
        lines.vertex(entry, (float) from.x, (float) from.y, (float) from.z).color(r, g, b, 180).normal(entry, 0.0F, 1.0F, 0.0F).lineWidth(width);
        lines.vertex(entry, (float) to.x, (float) to.y, (float) to.z).color(r, g, b, 180).normal(entry, 0.0F, 1.0F, 0.0F).lineWidth(width);
    }
}
