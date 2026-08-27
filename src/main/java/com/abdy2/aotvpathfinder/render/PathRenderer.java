package com.abdy2.aotvpathfinder.render;

import java.util.List;

import com.abdy2.aotvpathfinder.ability.CastRules;
import com.abdy2.aotvpathfinder.path.HopType;
import com.abdy2.aotvpathfinder.path.PathHop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/**
 * Draws the route in the world and the status block in the corner of the screen.
 *
 * <p>Reads routing state through {@link RouteView} and never changes it. Keeping the drawing
 * separate from the running of a route means the executor can be reasoned about without the
 * rendering in the way, and the wording of the status text stays with the code that knows what the
 * router is actually doing rather than being reconstructed here.
 */
public final class PathRenderer {

    /** The parts of a run this renderer needs to see. */
    public interface RouteView {
        /** The route to draw, already chosen between the live and prebuilt ones. */
        List<PathHop> renderRoute();

        /** Index within {@link #renderRoute()} that the run is currently working on. */
        int renderStartIndex();

        /** Fallback route drawn when nothing is actively running. */
        List<PathHop> previewRoute();

        /** Destination, or null when none is set. */
        BlockPos goal();

        /** Status lines for the corner of the screen, top to bottom. */
        List<String> hudLines();
    }

    private final RouteView view;

    public PathRenderer(RouteView view) {
        this.view = view;
    }

    private static final AABB NORMAL_NODE_SHAPE = new AABB(0.12, 0.0, 0.12, 0.88, 0.95, 0.88);
    private static final AABB SHIFT_NODE_SHAPE  = new AABB(0.08, 0.0, 0.08, 0.92, 0.72, 0.92);
    private static final AABB WALK_NODE_SHAPE   = new AABB(0.28, 0.0, 0.28, 0.72, 0.28, 0.72);
    private static final AABB NORMAL_GLOW_SHAPE = new AABB(0.04, -0.08, 0.04, 0.96, 1.03, 0.96);
    private static final AABB SHIFT_GLOW_SHAPE  = new AABB(0.0,  -0.08, 0.0,  1.0,  0.80, 1.0);
    private static final AABB WALK_GLOW_SHAPE   = new AABB(0.20, -0.05, 0.20, 0.80, 0.35, 0.80);
    private static final AABB CURRENT_BEACON    = new AABB(0.05, 0.0, 0.05, 0.95, 1.4, 0.95);
    private static final AABB CURRENT_CORE      = new AABB(0.20, 0.08, 0.20, 0.80, 1.20, 0.80);
    private static final AABB GOAL_SHAPE        = new AABB(0.0, 0.0, 0.0, 1.0, 1.5, 1.0);

public void renderPathEsp(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        List<PathHop> route = List.copyOf(view.renderRoute());
        int start = view.renderStartIndex();

        if (route.isEmpty()) {
            List<PathHop> preview = view.previewRoute();
            if (!preview.isEmpty()) {
                route = List.copyOf(preview);
                start = 0;
            } else {
                return;
            }
        }

        if (start >= route.size()) start = 0;
        double pulse = (Math.sin(System.currentTimeMillis() * 0.004) + 1.0) * 0.5;
        int end = Math.min(route.size(), start + 70);
        Vec3 previousCenter = null;

        // Gizmos take world coordinates and handle the camera transform themselves.
        try (var ignored = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
            for (int i = start; i < end; i++) {
                PathHop hop = route.get(i);
                BlockPos p = hop.landing();
                int color = colorForHop(hop.type());
                boolean isCurrent = (i == start);
                double renderY = hop.isWalk() ? 0.0 : 1.0;

                if (isCurrent) {
                    double pad = 0.08 + pulse * 0.05;
                    AABB halo = new AABB(
                        -pad, -0.06, -pad,
                        1.0 + pad, 1.5 + pulse * 0.25, 1.0 + pad);
                    box(halo, p, renderY, dimColor(0xFFFFFF, 0.55), 255, 1.4F);
                    box(CURRENT_BEACON, p, renderY, color, 255, 4.2F);
                    box(CURRENT_CORE, p, renderY, 0xFFFFFF, 255, 2.6F);
                } else {
                    box(glowShapeForHop(hop.type()), p, renderY, dimColor(color, 0.45), 255, 1.3F);
                    box(shapeForHop(hop.type()), p, renderY, color, 255, 2.8F);
                }

                Vec3 center = Vec3.atCenterOf(p).add(0.0, renderY + 0.45, 0.0);
                if (previousCenter != null) {
                    float w = isCurrent ? 3.2F : 2.0F;
                    line(previousCenter, center, color, 230, w);
                    line(previousCenter.add(0.0, 0.025, 0.0),
                         center.add(0.0, 0.025, 0.0),
                         color, 180, Math.max(1.0F, w - 0.8F));
                    line(previousCenter.add(0.0, -0.025, 0.0),
                         center.add(0.0, -0.025, 0.0),
                         dimColor(color, 0.55), 140, Math.max(1.0F, w - 1.0F));

                    // Direction-of-travel marker at the midpoint.
                    Vec3 seg = center.subtract(previousCenter);
                    double segLen = seg.length();
                    if (segLen > 0.5) {
                        Vec3 d = seg.scale(1.0 / segLen);
                        Vec3 mid = previousCenter.add(seg.scale(0.45));
                        Gizmos.arrow(mid, mid.add(d.scale(0.45)), ARGB.color(200, 0xFFFFFF), 1.6F)
                              .setAlwaysOnTop();
                    }
                }
                previousCenter = center;
            }

            if (start < route.size()) {
                Vec3 playerPos = new Vec3(client.player.getX(), client.player.getY() + 0.5, client.player.getZ());
                PathHop firstHop = route.get(start);
                double firstY = firstHop.isWalk() ? 0.0 : 1.0;
                Vec3 firstCenter = Vec3.atCenterOf(firstHop.landing()).add(0.0, firstY + 0.45, 0.0);
                line(playerPos, firstCenter, 0xFF4444, 255, 3.8F);
                line(playerPos.add(0.0, 0.025, 0.0),
                     firstCenter.add(0.0, 0.025, 0.0),
                     0xFF8888, 180, 2.0F);
            }

            if (view.goal() != null) {
                double gp = (Math.sin(System.currentTimeMillis() * 0.003 + 1.0) + 1.0) * 0.5;
                double gPad = 0.1 + gp * 0.08;
                AABB goalHalo = new AABB(
                    -gPad, -0.1, -gPad, 1.0 + gPad, 1.6 + gp * 0.3, 1.0 + gPad);
                box(goalHalo, view.goal(), 0.0, dimColor(0x00FF44, 0.5), 255, 1.5F);
                box(GOAL_SHAPE, view.goal(), 0.0, 0x00FF44, 255, 4.5F);
                box(GOAL_SHAPE, view.goal(), 1.0, 0x00FF44, 255, 2.5F);
            }
        }
    }

    /** Draws a wireframe box whose coordinates are relative to {@code p} (offset up by {@code yOffset}). */
    private static void box(AABB local, BlockPos p, double yOffset, int rgb, int alpha, float width) {
        AABB world = local.move(p.getX(), p.getY() + yOffset, p.getZ());
        Gizmos.cuboid(world, GizmoStyle.stroke(ARGB.color(alpha, rgb), width)).setAlwaysOnTop();
    }
private static void line(Vec3 from, Vec3 to, int rgb, int alpha, float width) {
        Gizmos.line(from, to, ARGB.color(alpha, rgb), width).setAlwaysOnTop();
    }
private static int dimColor(int color, double factor) {
        int r = Math.min(255, (int) (((color >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((color >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((color & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }
private int colorForHop(HopType type) {
        if (type == HopType.SHIFT) {
            return 0xFF55FF;
        }
        if (type == HopType.WALK) {
            return 0xFFFFFF;
        }
        return 0xFFD700;
    }
private AABB shapeForHop(HopType type) {
        if (type == HopType.SHIFT) {
            return SHIFT_NODE_SHAPE;
        }
        if (type == HopType.WALK) {
            return WALK_NODE_SHAPE;
        }
        return NORMAL_NODE_SHAPE;
    }
private AABB glowShapeForHop(HopType type) {
        if (type == HopType.SHIFT) return SHIFT_GLOW_SHAPE;
        if (type == HopType.WALK) return WALK_GLOW_SHAPE;
        return NORMAL_GLOW_SHAPE;
    }
    public void renderHud(GuiGraphicsExtractor gui, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Font font = client.font;
        int xRight = gui.guiWidth() - 8;
        int y = 8;
        for (String line : view.hudLines()) {
            gui.text(font, line, xRight - font.width(line), y, 0xEDEDED);
            y += 10;
        }
    }
}
