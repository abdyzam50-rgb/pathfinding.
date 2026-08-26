package net.netherite.tutorialmod.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.netherite.tutorialmod.PathNode;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

public class TessellatorRenderer {
    private static final List<PathNode> clientPath = new ArrayList<>();

    // 4 corners of the targeted block face (12 floats: x,y,z per corner).
    // Null when inactive. Set from the game's crosshair raycast each tick.
    private static volatile float[] bridgeFaceCorners = null;

    // AABB of the air block that will be placed next.
    private static volatile int[] bridgeBlockBox = null;

    // Block the player is currently standing/shifting on (orange outline).
    private static volatile int[] standingBlockPos = null;

    // Predicted future bridge blocks: up to 5 {x,y,z} entries.
    // Index 0 = next block, index 4 = furthest predicted.
    private static volatile int[][] predictedBlocks = null;

    // Node markers: {x,y,z} centres for SHIFT_EDGE and SHIFT_STEP nodes.
    private static volatile int[][] nodeMarkers = null;  // [0]=EDGE, [1]=STEP

    // Bridge-plan overlay: waypoint line + three categories of blocks-to-place.
    //   towerBlocks   = blue  — vertical climb (TOWER_UP phase)
    //   straightBlocks= green — bridge floor in the primary travel direction (BRIDGE phase)
    //   lateralBlocks = orange— bridge floor in the secondary/lateral direction (BRIDGE phase)
    private static volatile int[][] bridgePlanWaypoints = null;
    private static volatile int[][] bridgePlanBlocks    = null;
    private static volatile int[][] bridgeTowerBlocks   = null;
    private static volatile int[][] bridgeStraightBlocks= null;
    private static volatile int[][] bridgeLateralBlocks = null;

    // Bridge execution nodes: one per horizontal waypoint the bot must visit.
    //   activeIdx = index currently being targeted (yellow).
    //   < activeIdx = already visited (dim gray).
    //   > activeIdx = pending (white).
    private static volatile int[][] bridgeExecNodes  = null;
    private static volatile int     bridgeExecActive = -1;

    // ── Goal-bot overlays ─────────────────────────────────────────────────────
    // goalTargetBlock: wireframe box around the block the bot is currently targeting.
    //   orange = log being mined
    //   cyan   = crafting table destination
    //   gold   = log item drop to collect
    private static volatile int[]   goalTargetBlock = null;
    private static volatile float[] goalTargetColor = null;

    public static void setPredictedBlocks(int[][] blocks) { predictedBlocks = blocks; }
    public static void clearPredictedBlocks()             { predictedBlocks = null;   }

    public static void setNodeMarkers(int[] edgeNode, int[] stepNode) {
        nodeMarkers = (edgeNode == null && stepNode == null) ? null
                    : new int[][]{ edgeNode, stepNode };
    }
    public static void clearNodeMarkers() { nodeMarkers = null; }

    public static void setBridgePlan(int[][] waypoints, int[][] blocks) {
        bridgePlanWaypoints  = waypoints;
        bridgePlanBlocks     = blocks;
        bridgeTowerBlocks    = null;
        bridgeStraightBlocks = null;
        bridgeLateralBlocks  = null;
    }

    /** Sets the coloured bridge preview: blue=tower, green=straight, orange=lateral. */
    public static void setColoredBridgePlan(int[][] waypoints,
                                             int[][] tower, int[][] straight, int[][] lateral) {
        bridgePlanWaypoints  = waypoints;
        bridgePlanBlocks     = null; // replaced by typed arrays
        bridgeTowerBlocks    = tower;
        bridgeStraightBlocks = straight;
        bridgeLateralBlocks  = lateral;
    }

    public static void clearBridgePlan() {
        bridgePlanWaypoints  = null;
        bridgePlanBlocks     = null;
        bridgeTowerBlocks    = null;
        bridgeStraightBlocks = null;
        bridgeLateralBlocks  = null;
    }

    public static void setBridgeBlockHighlight(int x, int y, int z) {
        bridgeBlockBox = new int[]{ x, y, z };
    }

    public static void clearBridgeBlockHighlight() {
        bridgeBlockBox = null;
    }

    public static void setStandingBlockHighlight(int x, int y, int z) {
        standingBlockPos = new int[]{ x, y, z };
    }

    public static void clearStandingBlockHighlight() {
        standingBlockPos = null;
    }

    /**
     * Stores the 4 corners of the block face the crosshair is aimed at.
     * @param corners 12 floats — (x,y,z) × 4 corners in CCW order.
     */
    public static void setBridgeFaceHighlight(float[] corners) {
        bridgeFaceCorners = corners;
    }

    public static void clearBridgeFaceHighlight() {
        bridgeFaceCorners = null;
    }

    /**
     * Sets the per-waypoint execution nodes shown while the bridge bot is running.
     * @param nodes    One {x,y,z} entry per horizontal waypoint (feet positions).
     * @param activeIdx Index of the waypoint currently being targeted (rendered yellow).
     */
    public static void setBridgeExecNodes(int[][] nodes, int activeIdx) {
        bridgeExecNodes  = nodes;
        bridgeExecActive = activeIdx;
    }

    public static void clearBridgeExecNodes() {
        bridgeExecNodes  = null;
        bridgeExecActive = -1;
    }

    /** Clears the active path visualization. */
    public static void clearPath() {
        synchronized (clientPath) {
            clientPath.clear();
        }
    }

    /**
     * Highlights a single block in the world with a colored wireframe.
     * Call from goal logic to show what the bot is currently targeting:
     *   orange (1,0.5,0)  = log being mined
     *   cyan   (0,1,1)    = crafting table being walked to
     *   gold   (1,0.85,0) = log item drop to collect
     */
    public static void setGoalTargetBlock(int x, int y, int z, float r, float g, float b) {
        goalTargetBlock = new int[]{x, y, z};
        goalTargetColor = new float[]{r, g, b};
    }

    /** Removes the goal target block highlight. */
    public static void clearGoalTargetBlock() {
        goalTargetBlock = null;
        goalTargetColor = null;
    }

    // Cube dimensions for nodes (0.5 block size cubes)
    private static final float CUBE_SIZE = 0.25f; // Half-size from center (0.5 block total)

    // Larger cube for EDGE nodes
    private static final float EDGE_CUBE_SIZE = 0.4f; // Larger cube for edge highlighting

    private static final RenderPipeline DEBUG_LINES_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("tutorialmod", "pipeline/debug_lines"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
            .withCull(false)
            .build()
    );

    private static final RenderLayer DEBUG_LINES_LAYER = RenderLayer.of(
        "debug_lines",
        RenderSetup.builder(DEBUG_LINES_PIPELINE).expectedBufferSize(8192).build()
    );

    public static void register() {
        WorldRenderEvents.END_MAIN.register(context -> {
            Vec3d camPos = context.gameRenderer().getCamera().getCameraPos();
            synchronized (clientPath) {
                if (!clientPath.isEmpty()) {
                    renderPath(context.matrices(), camPos);
                }
            }
            float[] face = bridgeFaceCorners;
            if (face != null) {
                renderBridgeFaceHighlight(context.matrices(), camPos, face);
            }
            int[] block = bridgeBlockBox;
            if (block != null) {
                renderBridgeBlockHighlight(context.matrices(), camPos,
                        block[0], block[1], block[2]);
            }
            int[] standing = standingBlockPos;
            if (standing != null) {
                renderStandingBlockHighlight(context.matrices(), camPos,
                        standing[0], standing[1], standing[2]);
            }
            int[][] predicted = predictedBlocks;
            if (predicted != null) {
                renderPredictedBlocks(context.matrices(), camPos, predicted);
            }
            int[][] nodes = nodeMarkers;
            if (nodes != null) {
                renderNodeMarkers(context.matrices(), camPos, nodes);
            }
            int[][] bpw = bridgePlanWaypoints;
            int[][] bpb = bridgePlanBlocks;
            if (bpw != null) renderBridgePlanPath(context.matrices(), camPos, bpw);
            if (bpb != null) renderBridgePlanBlocks(context.matrices(), camPos, bpb);
            // Coloured bridge plan (tower=blue, straight=green, lateral=orange)
            int[][] bt = bridgeTowerBlocks, bs = bridgeStraightBlocks, bl = bridgeLateralBlocks;
            if (bt != null) renderColoredBridgeBlocks(context.matrices(), camPos, bt, 0.2f, 0.5f, 1.0f);
            if (bs != null) renderColoredBridgeBlocks(context.matrices(), camPos, bs, 0.1f, 0.9f, 0.2f);
            if (bl != null) renderColoredBridgeBlocks(context.matrices(), camPos, bl, 1.0f, 0.55f, 0.0f);
            // Bridge execution waypoint nodes (shown while bot is running)
            int[][] execNodes = bridgeExecNodes;
            if (execNodes != null) {
                renderBridgeExecNodes(context.matrices(), camPos, execNodes, bridgeExecActive);
            }
            // Goal-bot target block (log / crafting table / item drop)
            int[]   gtb = goalTargetBlock;
            float[] gtc = goalTargetColor;
            if (gtb != null && gtc != null) {
                renderGoalTargetBlock(context.matrices(), camPos, gtb[0], gtb[1], gtb[2], gtc[0], gtc[1], gtc[2]);
            }
        });
    }

    public static void setPath(List<PathNode> nodes) {
        synchronized (clientPath) {
            clientPath.clear();
            clientPath.addAll(nodes);
        }
    }

    private static void renderPath(MatrixStack matrices, Vec3d cameraPos) {
        Tessellator tessellator = Tessellator.getInstance();

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // ── Pass 1: Front-corner segment edges ───────────────────────────────
        // For each segment, find the 2 actual hitbox corners (from the 4 options ±0.3 in
        // XZ) that face most toward the next node — the "front face" corners — by ranking
        // their dot product with the travel direction.  The 4 segment lines connect these
        // corners (bottom + top Y ring) at the source to the same corners at the target.
        // This ensures lines always land exactly on hitbox corners (not floating midpoints),
        // giving a clean 3D tube appearance from any viewing angle including flat horizontal paths.
        // 4 XZ hitbox corners used to rank dot-products and pick the 2 "front face" corners.
        final double[][] XZ_CORNERS = {{-0.3,-0.3},{0.3,-0.3},{0.3,0.3},{-0.3,0.3}};
        // Guard: only open the buffer when there are at least 2 nodes (≥1 segment).
        // BufferBuilder.end() throws IllegalStateException when the buffer is empty.
        if (clientPath.size() >= 2) {
        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < clientPath.size() - 1; i++) {
            PathNode current = clientPath.get(i);
            PathNode next    = clientPath.get(i + 1);
            float[] color    = getNodeColor(next.type);

            Vec3d from = current.getCenterPos();
            Vec3d to   = next.getCenterPos();

            double segDx = to.x - from.x, segDz = to.z - from.z;
            double segLen2 = segDx*segDx + segDz*segDz;
            if (segLen2 < 1e-6) continue;
            double segLen = Math.sqrt(segLen2);
            double nx = segDx / segLen, nz = segDz / segLen;

            // Pick the 2 front-face XZ corners (highest dot with travel direction).
            // Renders only the near side of the tube from any viewing angle — no overlap.
            double[] dots = new double[4];
            for (int k = 0; k < 4; k++)
                dots[k] = nx * XZ_CORNERS[k][0] + nz * XZ_CORNERS[k][1];
            int f0 = 0;
            for (int k = 1; k < 4; k++) if (dots[k] > dots[f0]) f0 = k;
            int f1 = (f0 == 0) ? 1 : 0;
            for (int k = 0; k < 4; k++) if (k != f0 && dots[k] > dots[f1]) f1 = k;

            // 4 edges: the 2 front corners at Y=0 (bottom) and Y=1.8 (top)
            double[][] edges = {
                {XZ_CORNERS[f0][0], 0.0, XZ_CORNERS[f0][1]},
                {XZ_CORNERS[f0][0], 1.8, XZ_CORNERS[f0][1]},
                {XZ_CORNERS[f1][0], 0.0, XZ_CORNERS[f1][1]},
                {XZ_CORNERS[f1][0], 1.8, XZ_CORNERS[f1][1]},
            };

            // All segment types (walk AND ballistic) rendered as straight lines.
            // Jump arc curves were removed — straight lines are cleaner and the hitbox
            // boxes at each node already mark the start/end of every jump.
            float[] lineColor = color;
            if (isBallisticSegment(next.type)
                    && (next.type == PathNode.Type.SPRINT_JUMP || next.type == PathNode.Type.JUMP)
                    && requiredInitialSpeed(from, to, next.type) > MAX_ACHIEVABLE_SPRINT_SPEED) {
                lineColor = new float[]{1.0f, 0.0f, 0.0f}; // red = physically impossible
            }
            for (double[] off : edges) {
                Vec3d a = from.add(off[0], off[1], off[2]);
                Vec3d b = to.add(off[0], off[1], off[2]);
                addVertex(buffer,matrix,(float)a.x,(float)a.y,(float)a.z,lineColor[0],lineColor[1],lineColor[2]);
                addVertex(buffer,matrix,(float)b.x,(float)b.y,(float)b.z,lineColor[0],lineColor[1],lineColor[2]);
            }
        }
        BuiltBuffer builtBuffer = buffer.end();
        if (builtBuffer != null) DEBUG_LINES_LAYER.draw(builtBuffer);
        } // end guard: clientPath.size() >= 2

        // ── Pass 2: Player-hitbox wireframes at each node ────────────────────
        if (!clientPath.isEmpty()) {
            BufferBuilder buffer2 = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            for (PathNode node : clientPath) {
                float[] color = getNodeColor(node.type);
                Vec3d feet = node.getCenterPos();
                renderPlayerHitbox(buffer2, matrix,
                        (float)feet.x, (float)feet.y, (float)feet.z,
                        color[0], color[1], color[2]);
            }
            BuiltBuffer builtBuffer2 = buffer2.end();
            if (builtBuffer2 != null) DEBUG_LINES_LAYER.draw(builtBuffer2);
        }

        matrices.pop();
    }

    /**
     * Maximum initial horizontal speed a player can achieve at jump time:
     * full ground sprint (0.286) + sprint-jump boost (0.2) = 0.486 blocks/tick.
     * A small tolerance (+0.05) absorbs floating-point rounding.
     */
    private static final double MAX_ACHIEVABLE_SPRINT_SPEED = 0.286 + 0.2 + 0.05;

    /** True for node types where the player travels through the air between two path nodes. */
    private static boolean isBallisticSegment(PathNode.Type type) {
        return switch (type) {
            case SPRINT_JUMP, JUMP, DROP, WATER_DROP, BOUNCE -> true;
            default -> false;
        };
    }

    /**
     * Computes a ballistic arc from {@code from} to {@code to} using a binary search
     * for the initial horizontal speed that makes the arc land at {@code to}.
     * This naturally handles air acceleration and gives an arc that exactly lands at {@code to}.
     */
    private static List<Vec3d> computeBallisticArc(Vec3d from, Vec3d to, PathNode.Type type) {
        List<Vec3d> arc = new ArrayList<>();

        double dx    = to.x - from.x;
        double dz    = to.z - from.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);

        if (hDist < 0.01) {
            arc.add(from); arc.add(to);
            return arc;
        }

        // Yaw facing from → to
        float travelYaw = (float) Math.toDegrees(Math.atan2(-(dx / hDist), dz / hDist));

        double vy0 = (type == PathNode.Type.DROP || type == PathNode.Type.WATER_DROP) ? 0.0 : 0.42;
        double dy   = to.y - from.y;

        // Binary search for the initial horizontal speed that makes the arc land at hDist.
        double lo = 0.0, hi = 1.5;
        for (int iter = 0; iter < 25; iter++) {
            double mid = (lo + hi) / 2.0;
            if (simulateLandingDist(mid, travelYaw, vy0, dy) < hDist) lo = mid;
            else hi = mid;
        }
        double vH = (lo + hi) / 2.0;

        // Generate arc points using the solved initial speed
        double sinT = Math.sin(travelYaw * Math.PI / 180.0);
        double cosT = Math.cos(travelYaw * Math.PI / 180.0);
        double vx = -sinT * vH;
        double vz =  cosT * vH;
        double vy = vy0;
        double px = from.x, py = from.y, pz = from.z;

        arc.add(new Vec3d(px, py, pz));
        for (int t = 0; t < 40; t++) {
            // Air acceleration in travel direction (W held, facing target)
            vx += -sinT * 0.026;
            vz +=  cosT * 0.026;

            px += vx; py += vy; pz += vz;
            vx *= 0.91; vz *= 0.91;
            vy = (vy - 0.08) * 0.98;

            arc.add(new Vec3d(px, py, pz));
            if (py <= to.y) break;
        }

        return arc;
    }

    /**
     * Like {@link #computeBallisticArc} but uses a caller-supplied initial horizontal speed
     * {@code vH} instead of searching for one.  Used for DROP/WATER_DROP arcs where the
     * speed is determined by the player's movement state (walking, sprinting, or
     * sprint-jump carry-over) rather than the centre-to-centre geometry.
     */
    private static List<Vec3d> computeBallisticArcFixed(Vec3d from, Vec3d to,
                                                        PathNode.Type type, double vH) {
        List<Vec3d> arc = new ArrayList<>();
        double dx = to.x - from.x, dz = to.z - from.z;
        double hDist = Math.sqrt(dx*dx + dz*dz);
        if (hDist < 0.01) { arc.add(from); arc.add(to); return arc; }

        float travelYaw = (float) Math.toDegrees(Math.atan2(-(dx/hDist), dz/hDist));
        double vy0 = (type == PathNode.Type.DROP || type == PathNode.Type.WATER_DROP) ? 0.0 : 0.42;
        double sinT = Math.sin(travelYaw * Math.PI / 180.0);
        double cosT = Math.cos(travelYaw * Math.PI / 180.0);
        double vx = -sinT * vH, vz = cosT * vH, vy = vy0;
        double px = from.x, py = from.y, pz = from.z;

        arc.add(new Vec3d(px, py, pz));
        for (int t = 0; t < 80; t++) { // longer loop for tall falls
            vx += -sinT * 0.026;
            vz +=  cosT * 0.026;
            px += vx; py += vy; pz += vz;
            vx *= 0.91; vz *= 0.91;
            vy = (vy - 0.08) * 0.98;
            arc.add(new Vec3d(px, py, pz));
            if (py <= to.y) break;
        }
        return arc;
    }

    /**
     * Returns the initial horizontal speed the binary search found for this arc.
     * Used to detect physically impossible jumps (required speed > achievable max).
     */
    private static double requiredInitialSpeed(Vec3d from, Vec3d to, PathNode.Type type) {
        double dx    = to.x - from.x;
        double dz    = to.z - from.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) return 0;
        float travelYaw = (float) Math.toDegrees(Math.atan2(-(dx / hDist), dz / hDist));
        double vy0 = (type == PathNode.Type.DROP || type == PathNode.Type.WATER_DROP) ? 0.0 : 0.42;
        double dy   = to.y - from.y;
        double lo = 0.0, hi = 1.5;
        for (int iter = 0; iter < 25; iter++) {
            double mid = (lo + hi) / 2.0;
            if (simulateLandingDist(mid, travelYaw, vy0, dy) < hDist) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2.0;
    }

    /** Simulates horizontal distance traveled starting at speed vH in direction travelYaw. */
    private static double simulateLandingDist(double vH, float travelYaw, double vy0, double targetDy) {
        double sinT = Math.sin(travelYaw * Math.PI / 180.0);
        double cosT = Math.cos(travelYaw * Math.PI / 180.0);
        double vx = -sinT * vH;
        double vz =  cosT * vH;
        double vy = vy0;
        double px = 0, pz = 0, py = 0;

        for (int t = 0; t < 40; t++) {
            vx += -sinT * 0.026;
            vz +=  cosT * 0.026;

            px += vx; py += vy; pz += vz;
            vx *= 0.91; vz *= 0.91;
            vy = (vy - 0.08) * 0.98;

            if (py <= targetDy) break;
        }

        return Math.sqrt(px * px + pz * pz);
    }

    /**
     * Gets the color for a node type.
     * EDGE nodes are bright green to stand out prominently.
     */
    private static float[] getNodeColor(PathNode.Type type) {
        return switch (type) {
            case WALK        -> new float[]{1.0f, 1.0f, 1.0f};  // White
            case JUMP        -> new float[]{1.0f, 0.5f, 0.0f};  // Orange
            case SPRINT_JUMP -> new float[]{0.0f, 1.0f, 0.5f};  // Lime / spring green
            case BOOST_PLACE -> new float[]{0.2f, 1.0f, 1.0f};  // Cyan — place-while-jumping
            case MINE        -> new float[]{1.0f, 0.3f, 0.3f};  // Red — mining step
            case DROP        -> new float[]{1.0f, 1.0f, 0.0f};  // Yellow
            case WATER_DROP  -> new float[]{0.0f, 0.7f, 1.0f};  // Cyan — water bucket clutch
            case BOUNCE      -> new float[]{0.5f, 1.0f, 0.0f};  // Yellow-green — slime bounce
            case CLIMB       -> new float[]{0.0f, 0.5f, 1.0f};  // Sky Blue
            case EDGE        -> new float[]{0.0f, 1.0f, 0.0f};  // Bright Green
            case INTERACT    -> new float[]{0.8f, 0.0f, 1.0f};  // Purple
            case PILLAR      -> new float[]{1.0f, 0.6f, 0.9f};  // Pink — block placement upward
            case BRIDGE      -> new float[]{0.9f, 0.9f, 0.2f};  // Gold — block placement horizontal
        };
    }

    /**
     * Renders a larger cube for EDGE nodes (where player walks off).
     * This helps players clearly see where they'll be stepping off.
     */
    private static void renderEdgeHighlight(BufferBuilder buffer, Matrix4f matrix, BlockPos pos, float r, float g, float b) {
        float x = pos.getX() + 0.5f;
        float y = pos.getY() + 0.5f;
        float z = pos.getZ() + 0.5f;

        // Draw a larger cube for edge nodes
        // Bottom face edges
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        // Top face edges
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        // Vertical edges
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z - EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y - EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - EDGE_CUBE_SIZE, y + EDGE_CUBE_SIZE, z + EDGE_CUBE_SIZE, r, g, b);
    }

    /**
     * Renders a cube at the given precise position.
     */
    private static void renderBox(BufferBuilder buffer, Matrix4f matrix, float x, float y, float z, float r, float g, float b) {
        // Bottom face edges
        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        // Top face edges
        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        // Vertical edges connecting top and bottom
        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z - CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z - CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x + CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x + CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);

        addVertex(buffer, matrix, x - CUBE_SIZE, y - CUBE_SIZE, z + CUBE_SIZE, r, g, b);
        addVertex(buffer, matrix, x - CUBE_SIZE, y + CUBE_SIZE, z + CUBE_SIZE, r, g, b);
    }

    /**
     * Renders the exact Minecraft player hitbox: 0.6 wide × 1.8 tall × 0.6 deep.
     * {@code cx}/{@code cz} are the horizontal centre; {@code feetY} is the bottom (feet).
     * The arc lines connect to the bottom-centre of this box, matching where the
     * physics simulation places the player's origin.
     */
    private static void renderPlayerHitbox(BufferBuilder buffer, Matrix4f matrix,
                                           float cx, float feetY, float cz,
                                           float r, float g, float b) {
        final float hw = 0.3f;            // half-width  (0.6 / 2)
        final float h  = 1.8f;            // full height
        float x0 = cx - hw, x1 = cx + hw;
        float y0 = feetY,   y1 = feetY + h;
        float z0 = cz - hw, z1 = cz + hw;
        // bottom face
        addVertex(buffer,matrix,x0,y0,z0,r,g,b); addVertex(buffer,matrix,x1,y0,z0,r,g,b);
        addVertex(buffer,matrix,x1,y0,z0,r,g,b); addVertex(buffer,matrix,x1,y0,z1,r,g,b);
        addVertex(buffer,matrix,x1,y0,z1,r,g,b); addVertex(buffer,matrix,x0,y0,z1,r,g,b);
        addVertex(buffer,matrix,x0,y0,z1,r,g,b); addVertex(buffer,matrix,x0,y0,z0,r,g,b);
        // top face
        addVertex(buffer,matrix,x0,y1,z0,r,g,b); addVertex(buffer,matrix,x1,y1,z0,r,g,b);
        addVertex(buffer,matrix,x1,y1,z0,r,g,b); addVertex(buffer,matrix,x1,y1,z1,r,g,b);
        addVertex(buffer,matrix,x1,y1,z1,r,g,b); addVertex(buffer,matrix,x0,y1,z1,r,g,b);
        addVertex(buffer,matrix,x0,y1,z1,r,g,b); addVertex(buffer,matrix,x0,y1,z0,r,g,b);
        // verticals
        addVertex(buffer,matrix,x0,y0,z0,r,g,b); addVertex(buffer,matrix,x0,y1,z0,r,g,b);
        addVertex(buffer,matrix,x1,y0,z0,r,g,b); addVertex(buffer,matrix,x1,y1,z0,r,g,b);
        addVertex(buffer,matrix,x1,y0,z1,r,g,b); addVertex(buffer,matrix,x1,y1,z1,r,g,b);
        addVertex(buffer,matrix,x0,y0,z1,r,g,b); addVertex(buffer,matrix,x0,y1,z1,r,g,b);
    }

    /**
     * Draws a "cross-section ring" at a node: a bottom square and a top square
     * (8 edges total) that cap the longitudinal tube edges from Pass 1.
     * No vertical pillars — the tube corners already convey height.
     * When {@code special} is true, a centre spike is added so the node stands out.
     */
    private static void renderCrossRing(BufferBuilder buffer, Matrix4f matrix,
                                        float cx, float feetY, float cz,
                                        float r, float g, float b, boolean special) {
        final float hw = 0.3f;
        float x0 = cx-hw, x1 = cx+hw;
        float z0 = cz-hw, z1 = cz+hw;
        float y0 = feetY, y1 = feetY + 1.8f;
        // Bottom square
        addVertex(buffer,matrix,x0,y0,z0,r,g,b); addVertex(buffer,matrix,x1,y0,z0,r,g,b);
        addVertex(buffer,matrix,x1,y0,z0,r,g,b); addVertex(buffer,matrix,x1,y0,z1,r,g,b);
        addVertex(buffer,matrix,x1,y0,z1,r,g,b); addVertex(buffer,matrix,x0,y0,z1,r,g,b);
        addVertex(buffer,matrix,x0,y0,z1,r,g,b); addVertex(buffer,matrix,x0,y0,z0,r,g,b);
        // Top square
        addVertex(buffer,matrix,x0,y1,z0,r,g,b); addVertex(buffer,matrix,x1,y1,z0,r,g,b);
        addVertex(buffer,matrix,x1,y1,z0,r,g,b); addVertex(buffer,matrix,x1,y1,z1,r,g,b);
        addVertex(buffer,matrix,x1,y1,z1,r,g,b); addVertex(buffer,matrix,x0,y1,z1,r,g,b);
        addVertex(buffer,matrix,x0,y1,z1,r,g,b); addVertex(buffer,matrix,x0,y1,z0,r,g,b);
        // Centre spike for special nodes (JUMP, DROP, SPRINT_JUMP, etc.)
        if (special) {
            addVertex(buffer,matrix,cx,y0,cz,r,g,b);
            addVertex(buffer,matrix,cx,y1,cz,r,g,b);
        }
    }

    private static void addVertex(BufferBuilder buffer, Matrix4f matrix, float x, float y, float z, float r, float g, float b) {
        buffer.vertex(matrix, x, y, z).color(r, g, b, 1.0f);
    }

    /**
     * Renders a yellow wire-frame box around the air block that will be placed
     * next, giving clear visual confirmation of where the bridge block will appear.
     */
    private static void renderBridgeBlockHighlight(MatrixStack matrices, Vec3d cam,
                                                   int bx, int by, int bz) {
        float x0 = bx, y0 = by, z0 = bz;
        float x1 = bx+1f, y1 = by+1f, z1 = bz+1f;
        float r = 1f, g = 1f, b = 0f;  // yellow

        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        // bottom
        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
        // top
        addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        // pillars
        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);

        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Renders up to 5 predicted future bridge blocks as wire-frame boxes.
     * Colour fades from bright yellow (next block) to dark towards the furthest.
     */
    private static void renderPredictedBlocks(MatrixStack matrices, Vec3d cam, int[][] blocks) {
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null) continue;
            float t  = 1f - (float) i / blocks.length;
            float r = t, g = t, b = 0f;
            float x0 = blocks[i][0], y0 = blocks[i][1], z0 = blocks[i][2];
            float x1 = x0+1, y1 = y0+1, z1 = z0+1;
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
            addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
            addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        }
        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Renders node markers — a cross + vertical spike at the top of each node block.
     * SHIFT_EDGE → lime green.   SHIFT_STEP → sky blue.
     */
    private static void renderNodeMarkers(MatrixStack matrices, Vec3d cam, int[][] nodes) {
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        float[][] colours = { {0f,1f,0f}, {0f,0.7f,1f} };
        for (int i = 0; i < Math.min(nodes.length, 2); i++) {
            if (nodes[i] == null) continue;
            float cx = nodes[i][0]+0.5f, cy = nodes[i][1]+1.001f, cz = nodes[i][2]+0.5f;
            float h = 0.4f;
            float r = colours[i][0], g = colours[i][1], b = colours[i][2];
            addVertex(buf,m,cx-h,cy,cz-h,r,g,b); addVertex(buf,m,cx+h,cy,cz+h,r,g,b);
            addVertex(buf,m,cx+h,cy,cz-h,r,g,b); addVertex(buf,m,cx-h,cy,cz+h,r,g,b);
            addVertex(buf,m,cx,cy,cz,r,g,b);     addVertex(buf,m,cx,cy+0.5f,cz,r,g,b);
        }
        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Orange wire-frame outline of the block the player is currently standing on.
     * Serves as a visual anchor — all face and aim calculations derive from this block.
     */
    private static void renderStandingBlockHighlight(MatrixStack matrices, Vec3d cam,
                                                     int bx, int by, int bz) {
        float x0 = bx, y0 = by, z0 = bz;
        float x1 = bx+1f, y1 = by+1f, z1 = bz+1f;
        float r = 1f, g = 0.5f, b = 0f;  // orange

        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
        addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);

        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Renders a bright cyan outline of the actual block face the crosshair is
     * targeting (4 edges + 2 diagonals).  The corners come directly from the
     * game's own crosshair raycast, so the highlight is pixel-perfect.
     *
     * @param corners 12 floats — (x,y,z) × 4 corners in CCW order:
     *                bottom-left, bottom-right, top-right, top-left.
     */
    private static void renderBridgeFaceHighlight(MatrixStack matrices, Vec3d cameraPos, float[] corners) {
        if (corners == null || corners.length < 12) return;

        float ax = corners[0],  ay = corners[1],  az = corners[2];
        float bx = corners[3],  by = corners[4],  bz = corners[5];
        float cx = corners[6],  cy = corners[7],  cz = corners[8];
        float dx = corners[9],  dy = corners[10], dz = corners[11];

        // Bright cyan
        float r = 0.0f, g = 1.0f, b = 1.0f;

        Tessellator tessellator = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        // 4 edges of the face
        addVertex(buffer, matrix, ax, ay, az, r, g, b); addVertex(buffer, matrix, bx, by, bz, r, g, b);
        addVertex(buffer, matrix, bx, by, bz, r, g, b); addVertex(buffer, matrix, cx, cy, cz, r, g, b);
        addVertex(buffer, matrix, cx, cy, cz, r, g, b); addVertex(buffer, matrix, dx, dy, dz, r, g, b);
        addVertex(buffer, matrix, dx, dy, dz, r, g, b); addVertex(buffer, matrix, ax, ay, az, r, g, b);

        // 2 diagonals so the face is unmistakable from any viewing angle
        addVertex(buffer, matrix, ax, ay, az, r, g, b); addVertex(buffer, matrix, cx, cy, cz, r, g, b);
        addVertex(buffer, matrix, bx, by, bz, r, g, b); addVertex(buffer, matrix, dx, dy, dz, r, g, b);

        BuiltBuffer builtBuffer = buffer.end();
        if (builtBuffer != null) DEBUG_LINES_LAYER.draw(builtBuffer);

        matrices.pop();
    }

    /** Lime-green line connecting bridge-plan waypoints (centre of each feet block). */
    private static void renderBridgePlanPath(MatrixStack matrices, Vec3d cam, int[][] pts) {
        if (pts == null || pts.length < 2) return;
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        float r = 0.4f, g = 1.0f, b = 0.4f;
        for (int i = 0; i < pts.length - 1; i++) {
            float ax = pts[i][0]   + 0.5f, ay = pts[i][1]   + 0.5f, az = pts[i][2]   + 0.5f;
            float bx = pts[i+1][0] + 0.5f, by = pts[i+1][1] + 0.5f, bz = pts[i+1][2] + 0.5f;
            addVertex(buf, m, ax, ay, az, r, g, b);
            addVertex(buf, m, bx, by, bz, r, g, b);
        }
        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /** Lime-green wireframe box for each block that the bridge plan will place. */
    /** Renders bridge plan blocks with a caller-supplied RGB colour. */
    private static void renderColoredBridgeBlocks(MatrixStack matrices, Vec3d cam,
                                                   int[][] blocks, float r, float g, float b) {
        renderBridgePlanBlocksColored(matrices, cam, blocks, r, g, b);
    }

    private static void renderBridgePlanBlocksColored(MatrixStack matrices, Vec3d cam,
                                                       int[][] blocks, float r, float g, float b) {
        if (blocks == null || blocks.length == 0) return;
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        for (int[] p : blocks) {
            if (p == null) continue;
            float x0 = p[0], y0 = p[1], z0 = p[2];
            float x1 = x0+1, y1 = y0+1, z1 = z0+1;
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
            addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
            addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        }
        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Renders one cross+spike marker per bridge execution waypoint:
     *   yellow (large)   = current target node (activeIdx)
     *   white  (medium)  = pending nodes (> activeIdx)
     *   dark-gray (small)= already-visited nodes (< activeIdx)
     */
    private static void renderBridgeExecNodes(MatrixStack matrices, Vec3d cam,
                                               int[][] nodes, int activeIdx) {
        if (nodes == null || nodes.length == 0) return;
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (int i = 0; i < nodes.length; i++) {
            if (nodes[i] == null) continue;
            float cx = nodes[i][0] + 0.5f;
            float cy = nodes[i][1] + 1.001f;   // sit just above the top of the feet block
            float cz = nodes[i][2] + 0.5f;

            float r, g, b, h;
            if (i == activeIdx) {
                r = 1f;    g = 1f;    b = 0f;    h = 0.45f;   // yellow, large — current target
            } else if (i < activeIdx) {
                r = 0.25f; g = 0.25f; b = 0.25f; h = 0.15f;   // dark gray, small — visited
            } else {
                r = 0.9f;  g = 0.9f;  b = 0.9f;  h = 0.28f;   // white, medium — pending
            }

            // Cardinal cross (E-W + N-S lines at block-top level)
            addVertex(buf,m, cx-h, cy, cz,   r,g,b); addVertex(buf,m, cx+h, cy, cz,   r,g,b);
            addVertex(buf,m, cx,   cy, cz-h, r,g,b); addVertex(buf,m, cx,   cy, cz+h, r,g,b);
            // Vertical spike upward
            addVertex(buf,m, cx, cy, cz, r,g,b); addVertex(buf,m, cx, cy+0.65f, cz, r,g,b);
        }

        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    /**
     * Renders a full wireframe box + a diagonal cross on the top face at the given block.
     * Used to mark goal targets: log being mined (orange), crafting table (cyan), item drop (gold).
     */
    private static void renderGoalTargetBlock(MatrixStack matrices, Vec3d cam,
                                               int bx, int by, int bz, float r, float g, float b) {
        float x0 = bx, y0 = by, z0 = bz;
        float x1 = bx+1f, y1 = by+1f, z1 = bz+1f;

        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        // Bottom face
        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
        // Top face
        addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        // Pillars
        addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
        addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        // Top-face diagonals (X cross) so it's visible from any angle
        addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
        addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        // Vertical centre spike (rises 1 block above, easy to spot from far away)
        float cx = bx+0.5f, cz = bz+0.5f;
        addVertex(buf,m,cx,y1,cz,r,g,b); addVertex(buf,m,cx,y1+1.0f,cz,r,g,b);

        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }

    private static void renderBridgePlanBlocks(MatrixStack matrices, Vec3d cam, int[][] blocks) {
        if (blocks == null || blocks.length == 0) return;
        Tessellator tess = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = matrices.peek().getPositionMatrix();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        float r = 0.2f, g = 1.0f, b = 0.2f;
        for (int[] p : blocks) {
            if (p == null) continue;
            float x0 = p[0], y0 = p[1], z0 = p[2];
            float x1 = x0+1, y1 = y0+1, z1 = z0+1;
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y0,z1,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y0,z0,r,g,b);
            addVertex(buf,m,x0,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y1,z0,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x1,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
            addVertex(buf,m,x0,y1,z1,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x0,y0,z0,r,g,b); addVertex(buf,m,x0,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z0,r,g,b); addVertex(buf,m,x1,y1,z0,r,g,b);
            addVertex(buf,m,x1,y0,z1,r,g,b); addVertex(buf,m,x1,y1,z1,r,g,b);
            addVertex(buf,m,x0,y0,z1,r,g,b); addVertex(buf,m,x0,y1,z1,r,g,b);
        }
        BuiltBuffer bb = buf.end();
        if (bb != null) DEBUG_LINES_LAYER.draw(bb);
        matrices.pop();
    }
}
