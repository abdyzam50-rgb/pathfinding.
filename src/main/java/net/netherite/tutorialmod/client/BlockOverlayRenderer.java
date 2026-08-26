package net.netherite.tutorialmod.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.StairShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Real-time block overlay rendered within a 10-block radius, color-coded:
 *
 *   Green        (WALKABLE_FLAT)   — same level, walkable directly
 *   Yellow       (WALKABLE_JUMP)   — reachable by jumping up
 *   Amber        (WALKABLE_DROP)   — reachable by dropping down
 *   Pink         (SLAB)            — half-height surface (bottom slabs, snow, etc.)
 *   Teal         (STAIR_WALKABLE)  — stair approachable from its open/lower side (prism shape)
 *   Orange       (TALL_OBSTACLE)   — fence/wall (1.5 blocks); top cap shown in pink to
 *                                    indicate it is step-up-accessible from adjacent 1-block height
 *   Purple       (INTERACTABLE)    — openable door, fence gate, or trapdoor
 *   Blue         (CLIMBABLE)       — ladder, vine, scaffold
 *   Red          (HAZARDOUS)       — lava, fire, cactus, berry bush
 *   Cyan         (WATER)           — swimmable water surface
 *
 * Toggle with /toggleoverlay
 */
public class BlockOverlayRenderer {

    public enum Category {
        WALKABLE_FLAT,
        WALKABLE_JUMP,
        WALKABLE_DROP,
        SLAB,            // Half-height surface (0.5 blocks) — pink wireframe at correct height
        STAIR_WALKABLE,  // Stair accessible from the open/lower side — teal prism
        TALL_OBSTACLE,   // Fence / wall — orange + pink top cap
        INTERACTABLE,    // Openable door/gate/trapdoor — purple full-box
        CLIMBABLE,       // Ladder / vine / scaffold — blue full-box
        HAZARDOUS,       // Lava / fire / cactus — red full-box
        WATER            // Water surface — cyan top face
    }

    // stairFacing is non-null only for STAIR_WALKABLE entries.
    private record CachedEntry(BlockPos pos, Category cat, Direction stairFacing) {
        CachedEntry(BlockPos pos, Category cat) { this(pos, cat, null); }
    }

    private static final List<CachedEntry> CACHE = new ArrayList<>();
    private static boolean enabled          = true;
    private static int     scanTimer        = 0;
    private static final int SCAN_INTERVAL  = 5;
    private static final int RADIUS         = 10;
    private static final int Y_RANGE        = 8;

    // PINK color shared by slabs and fence top caps
    private static final float[] SLAB_COLOR = {1.00f, 0.40f, 0.70f, 0.80f};

    private static final RenderPipeline OVERLAY_PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("tutorialmod", "pipeline/block_overlay"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.DEBUG_LINES)
            .withCull(false)
            .build()
    );

    private static final RenderLayer OVERLAY_LAYER = RenderLayer.of(
        "block_overlay",
        RenderSetup.builder(OVERLAY_PIPELINE).expectedBufferSize(196608).build()
    );

    // -----------------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------------

    public static void register() {
        WorldRenderEvents.END_MAIN.register(context -> {
            if (!enabled) return;
            synchronized (CACHE) {
                if (CACHE.isEmpty()) return;
                renderOverlay(context.matrices(), context.gameRenderer().getCamera().getCameraPos());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!enabled) return;
            if (scanTimer++ >= SCAN_INTERVAL) {
                scanTimer = 0;
                scanBlocks(client);
            }
        });
    }

    public static void toggle() {
        enabled = !enabled;
        if (!enabled) synchronized (CACHE) { CACHE.clear(); }
    }

    public static boolean isEnabled() { return enabled; }

    // -----------------------------------------------------------------------
    // Block scan
    // -----------------------------------------------------------------------

    private static void scanBlocks(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return;
        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int groundY = (int) Math.floor(mc.player.getY() - 0.001);

        List<CachedEntry> newCache = new ArrayList<>();

        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                for (int dy = -Y_RANGE; dy <= Y_RANGE; dy++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    CachedEntry entry = categorize(world, pos, playerPos, groundY);
                    if (entry != null) newCache.add(entry);
                }
            }
        }

        synchronized (CACHE) {
            CACHE.clear();
            CACHE.addAll(newCache);
        }
    }

    // -----------------------------------------------------------------------
    // Block categorization
    // -----------------------------------------------------------------------

    private static CachedEntry categorize(ClientWorld world, BlockPos pos, BlockPos playerPos, int groundY) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return null;

        Block b = state.getBlock();

        // --- Hazardous (checked first so lava/fire is never listed as walkable) ---
        if (isHazardous(world, state)) return entry(pos, Category.HAZARDOUS);

        // --- Tall obstacles: fence / wall (1.5 blocks) ---
        if (b instanceof FenceBlock || b instanceof WallBlock)
            return entry(pos, Category.TALL_OBSTACLE);
        if (b instanceof FenceGateBlock && !state.get(FenceGateBlock.OPEN))
            return entry(pos, Category.TALL_OBSTACLE);

        // --- Interactable ---
        if (isInteractable(state)) return entry(pos, Category.INTERACTABLE);

        // --- Climbable ---
        if (isClimbable(state)) return entry(pos, Category.CLIMBABLE);

        // --- Water surface (topmost layer only) ---
        if (!state.getFluidState().isEmpty() && state.getFluidState().isIn(FluidTags.WATER)) {
            if (world.getBlockState(pos.up()).getFluidState().isEmpty())
                return entry(pos, Category.WATER);
            return null;
        }

        // --- Bottom slab + other exact-half-height surfaces → SLAB (pink) ---
        if (b instanceof SlabBlock) {
            SlabType t = state.get(SlabBlock.TYPE);
            if (t == SlabType.BOTTOM) {
                // Player feet would be at ~pos.Y+0.5; check pos.up() (body) and pos.up(2) (head)
                if (isPassableForPlayer(world, pos.up()) && isPassableForPlayer(world, pos.up().up()))
                    return entry(pos, Category.SLAB);
            }
            // Top slab / double slab: treated as full-height walkable surface (falls through below)
        }

        // --- Stair block — directional prism rendering ---
        if (b instanceof StairsBlock) {
            Direction facing = state.get(StairsBlock.FACING);
            boolean clear = isPassableForPlayer(world, pos.up()) && isPassableForPlayer(world, pos.up().up());
            if (clear) {
                if (canApproachStairFromPlayerSide(playerPos, pos, facing)) {
                    // Player is on the open/lower side — show as teal prism
                    return new CachedEntry(pos, Category.STAIR_WALKABLE, facing);
                } else {
                    // Player is on the solid/back side — classify same as any solid walkable surface
                    int heightDiff = pos.getY() - groundY;
                    Category c = heightDiff == 0 ? Category.WALKABLE_FLAT
                               : heightDiff  > 0 ? Category.WALKABLE_JUMP
                               :                   Category.WALKABLE_DROP;
                    return entry(pos, c);
                }
            }
            return null;
        }

        // --- Generic walkable surface ---
        if (hasSolidTop(world, pos, state)) {
            if (isPassableForPlayer(world, pos.up()) && isPassableForPlayer(world, pos.up().up())) {
                int heightDiff = pos.getY() - groundY;
                Category c = heightDiff == 0 ? Category.WALKABLE_FLAT
                           : heightDiff  > 0 ? Category.WALKABLE_JUMP
                           :                   Category.WALKABLE_DROP;
                return entry(pos, c);
            }
        }

        return null;
    }

    private static CachedEntry entry(BlockPos pos, Category cat) {
        return new CachedEntry(pos, cat);
    }

    // -----------------------------------------------------------------------
    // Block property helpers
    // -----------------------------------------------------------------------

    /**
     * True when the player is approaching the stair from its open/lower-step side.
     * StairsBlock.FACING = direction the HIGH/solid side faces (opposite of the open step).
     * If playerToStair dot facing > 0, player is on the open/low side (can walk up).
     */
    private static boolean canApproachStairFromPlayerSide(BlockPos playerPos, BlockPos stairPos,
                                                           Direction facing) {
        double dx = (stairPos.getX() + 0.5) - (playerPos.getX() + 0.5);
        double dz = (stairPos.getZ() + 0.5) - (playerPos.getZ() + 0.5);
        // Dot with the facing vector: positive → player on the open/low side
        double dot = dx * facing.getOffsetX() + dz * facing.getOffsetZ();
        return dot > 0.1; // small tolerance so directly-adjacent also works
    }

    private static boolean hasSolidTop(ClientWorld world, BlockPos pos, BlockState state) {
        Block b = state.getBlock();
        if (b instanceof SlabBlock) {
            SlabType t = state.get(SlabBlock.TYPE);
            return t == SlabType.TOP || t == SlabType.DOUBLE;
        }
        if (b instanceof StairsBlock)  return true;
        if (b instanceof FarmlandBlock || b instanceof DirtPathBlock) return true;
        if (b instanceof FenceBlock || b instanceof WallBlock) return false;
        VoxelShape shape = state.getCollisionShape(world, pos);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.5;
    }

    private static boolean isPassableForPlayer(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return true;
        Block b = state.getBlock();
        if (b instanceof FenceBlock || b instanceof WallBlock) return false;
        if (b instanceof FenceGateBlock) return state.get(FenceGateBlock.OPEN);
        if (b instanceof DoorBlock)      return state.get(DoorBlock.OPEN);
        if (b instanceof TrapdoorBlock)  return state.get(TrapdoorBlock.OPEN);
        if (b instanceof CarpetBlock || b instanceof AbstractPressurePlateBlock) return true;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isHazardous(ClientWorld world, BlockState state) {
        if (state.getFluidState().isIn(FluidTags.LAVA)) return true;
        Block b = state.getBlock();
        return b instanceof AbstractFireBlock
            || b instanceof CactusBlock
            || b instanceof SweetBerryBushBlock;
    }

    private static boolean isInteractable(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof DoorBlock)
            return !state.get(DoorBlock.OPEN) && !b.getTranslationKey().contains("iron");
        if (b instanceof FenceGateBlock) return !state.get(FenceGateBlock.OPEN);
        if (b instanceof TrapdoorBlock)  return !state.get(TrapdoorBlock.OPEN);
        return false;
    }

    private static boolean isClimbable(BlockState state) {
        return state.isIn(BlockTags.CLIMBABLE);
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private static void renderOverlay(MatrixStack matrices, Vec3d cameraPos) {
        Tessellator tessellator = Tessellator.getInstance();
        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        for (CachedEntry entry : CACHE) {
            float[] c = getColor(entry.cat());
            renderEntry(buffer, matrix, entry, c);
        }

        BuiltBuffer built = buffer.end();
        if (built != null) OVERLAY_LAYER.draw(built);
        matrices.pop();
    }

    private static void renderEntry(BufferBuilder buf, Matrix4f m, CachedEntry entry, float[] c) {
        BlockPos pos = entry.pos();
        float x  = pos.getX();
        float z  = pos.getZ();
        float y0 = pos.getY() + 0.008f;
        float y1 = pos.getY() + 1.008f;
        float a  = c[3];

        switch (entry.cat()) {
            case SLAB -> renderSlabShape(buf, m, x, y0, z, c, a);
            case STAIR_WALKABLE -> {
                Direction facing = entry.stairFacing();
                if (facing == null) {
                    renderTopFace(buf, m, x, y1, z, c, a);
                } else {
                    renderStairPrism(buf, m, x, y0, z, facing, c, a);
                }
            }
            case TALL_OBSTACLE -> {
                float y15 = pos.getY() + 1.508f;
                // Orange main fence body (bottom to y+1)
                renderTopFace(buf, m, x, y1, z, c, a);
                addLine(buf, m, x,   y1, z,   x,   y15, z,   c, a);
                addLine(buf, m, x+1, y1, z,   x+1, y15, z,   c, a);
                addLine(buf, m, x+1, y1, z+1, x+1, y15, z+1, c, a);
                addLine(buf, m, x,   y1, z+1, x,   y15, z+1, c, a);
                // Pink top cap — shows the fence top is a step-up surface from adjacent 1-block height
                renderTopFace(buf, m, x, y15, z, SLAB_COLOR, SLAB_COLOR[3]);
            }
            case CLIMBABLE, INTERACTABLE -> {
                // Full wireframe box
                renderTopFace(buf, m, x, y1, z, c, a);
                addLine(buf, m, x,   y0, z,   x+1, y0, z,   c, a);
                addLine(buf, m, x+1, y0, z,   x+1, y0, z+1, c, a);
                addLine(buf, m, x+1, y0, z+1, x,   y0, z+1, c, a);
                addLine(buf, m, x,   y0, z+1, x,   y0, z,   c, a);
                addLine(buf, m, x,   y0, z,   x,   y1, z,   c, a);
                addLine(buf, m, x+1, y0, z,   x+1, y1, z,   c, a);
                addLine(buf, m, x+1, y0, z+1, x+1, y1, z+1, c, a);
                addLine(buf, m, x,   y0, z+1, x,   y1, z+1, c, a);
            }
            case HAZARDOUS -> {
                // Full wireframe box
                renderTopFace(buf, m, x, y1, z, c, a);
                addLine(buf, m, x,   y0, z,   x+1, y0, z,   c, a);
                addLine(buf, m, x+1, y0, z,   x+1, y0, z+1, c, a);
                addLine(buf, m, x+1, y0, z+1, x,   y0, z+1, c, a);
                addLine(buf, m, x,   y0, z+1, x,   y0, z,   c, a);
                addLine(buf, m, x,   y0, z,   x,   y1, z,   c, a);
                addLine(buf, m, x+1, y0, z,   x+1, y1, z,   c, a);
                addLine(buf, m, x+1, y0, z+1, x+1, y1, z+1, c, a);
                addLine(buf, m, x,   y0, z+1, x,   y1, z+1, c, a);
            }
            default -> renderTopFace(buf, m, x, y1, z, c, a);
        }
    }

    /** Draws the four edges of a horizontal rectangle at the given Y. */
    private static void renderTopFace(BufferBuilder buf, Matrix4f m,
                                      float x, float y, float z, float[] c, float a) {
        addLine(buf, m, x,   y, z,   x+1, y, z,   c, a);
        addLine(buf, m, x+1, y, z,   x+1, y, z+1, c, a);
        addLine(buf, m, x+1, y, z+1, x,   y, z+1, c, a);
        addLine(buf, m, x,   y, z+1, x,   y, z,   c, a);
    }

    /**
     * Renders a bottom-slab shape: a thin box 0.5 blocks tall.
     * The top face is at pos.Y + 0.5 (not 1.0), so it visually sits at the correct height.
     */
    private static void renderSlabShape(BufferBuilder buf, Matrix4f m,
                                        float x, float y0, float z, float[] c, float a) {
        float y1 = y0 + 0.500f; // slab top at Y+0.5
        // Top face
        renderTopFace(buf, m, x, y1, z, c, a);
        // Bottom face
        addLine(buf, m, x,   y0, z,   x+1, y0, z,   c, a);
        addLine(buf, m, x+1, y0, z,   x+1, y0, z+1, c, a);
        addLine(buf, m, x+1, y0, z+1, x,   y0, z+1, c, a);
        addLine(buf, m, x,   y0, z+1, x,   y0, z,   c, a);
        // Four short vertical edges
        addLine(buf, m, x,   y0, z,   x,   y1, z,   c, a);
        addLine(buf, m, x+1, y0, z,   x+1, y1, z,   c, a);
        addLine(buf, m, x+1, y0, z+1, x+1, y1, z+1, c, a);
        addLine(buf, m, x,   y0, z+1, x,   y1, z+1, c, a);
    }

    /**
     * Renders a right-angle prism for a stair block.
     *
     * StairsBlock.FACING = the direction the HIGH/solid side faces.
     * - SOUTH (+Z): high block on south half (z+0.5 → z+1), low step on north (z → z+0.5)
     * - NORTH (-Z): high block on north half (z   → z+0.5), low step on south (z+0.5 → z+1)
     * - EAST  (+X): high block on east  half (x+0.5 → x+1), low step on west  (x → x+0.5)
     * - WEST  (-X): high block on west  half (x   → x+0.5), low step on east  (x+0.5 → x+1)
     */
    private static void renderStairPrism(BufferBuilder buf, Matrix4f m,
                                         float x, float y0, float z,
                                         Direction facing, float[] c, float a) {
        float xMid  = x + 0.5f;
        float zMid  = z + 0.5f;
        float yLow  = y0 + 0.500f; // lower step top at Y+0.5
        float yHigh = y0 + 1.000f; // upper portion top at Y+1

        switch (facing) {
            case SOUTH -> renderStairZ(buf, m, x, x+1f, z, z+1f, zMid, y0, yLow, yHigh, c, a, false);
            case NORTH -> renderStairZ(buf, m, x, x+1f, z, z+1f, zMid, y0, yLow, yHigh, c, a, true);
            case EAST  -> renderStairX(buf, m, x, x+1f, z, z+1f, xMid, y0, yLow, yHigh, c, a, false);
            case WEST  -> renderStairX(buf, m, x, x+1f, z, z+1f, xMid, y0, yLow, yHigh, c, a, true);
            default    -> renderTopFace(buf, m, x, yHigh, z, c, a); // UP/DOWN — shouldn't occur
        }
    }

    /**
     * Stair prism split along the Z axis.
     * lowOnPositiveSide=true → low step is on the +Z (south) half.
     */
    private static void renderStairZ(BufferBuilder buf, Matrix4f m,
                                     float x0, float x1, float z0, float z1, float zMid,
                                     float y0, float yLow, float yHigh,
                                     float[] c, float a, boolean lowOnPositiveSide) {
        float lZ0, lZ1, hZ0, hZ1;
        if (lowOnPositiveSide) { lZ0 = zMid; lZ1 = z1;  hZ0 = z0;  hZ1 = zMid; }
        else                   { lZ0 = z0;   lZ1 = zMid; hZ0 = zMid; hZ1 = z1; }

        // Bottom face
        addLine(buf, m, x0, y0, z0,  x1, y0, z0,  c, a);
        addLine(buf, m, x0, y0, z0,  x0, y0, z1,  c, a);
        addLine(buf, m, x1, y0, z0,  x1, y0, z1,  c, a);
        addLine(buf, m, x0, y0, z1,  x1, y0, z1,  c, a);
        // Low step top face
        addLine(buf, m, x0, yLow, lZ0,  x1, yLow, lZ0,  c, a);
        addLine(buf, m, x0, yLow, lZ0,  x0, yLow, lZ1,  c, a);
        addLine(buf, m, x1, yLow, lZ0,  x1, yLow, lZ1,  c, a);
        addLine(buf, m, x0, yLow, lZ1,  x1, yLow, lZ1,  c, a);
        // High block top face
        addLine(buf, m, x0, yHigh, hZ0,  x1, yHigh, hZ0,  c, a);
        addLine(buf, m, x0, yHigh, hZ0,  x0, yHigh, hZ1,  c, a);
        addLine(buf, m, x1, yHigh, hZ0,  x1, yHigh, hZ1,  c, a);
        addLine(buf, m, x0, yHigh, hZ1,  x1, yHigh, hZ1,  c, a);
        // Tall vertical edges (high side)
        addLine(buf, m, x0, y0, hZ0,  x0, yHigh, hZ0,  c, a);
        addLine(buf, m, x1, y0, hZ0,  x1, yHigh, hZ0,  c, a);
        // Short vertical edges (low side outer)
        addLine(buf, m, x0, y0, lZ1,  x0, yLow, lZ1,  c, a);
        addLine(buf, m, x1, y0, lZ1,  x1, yLow, lZ1,  c, a);
        // Step riser vertical edges at zMid
        addLine(buf, m, x0, yLow, zMid,  x0, yHigh, zMid,  c, a);
        addLine(buf, m, x1, yLow, zMid,  x1, yHigh, zMid,  c, a);
    }

    /**
     * Stair prism split along the X axis.
     * lowOnPositiveSide=true → low step is on the +X (east) half.
     */
    private static void renderStairX(BufferBuilder buf, Matrix4f m,
                                     float x0, float x1, float z0, float z1, float xMid,
                                     float y0, float yLow, float yHigh,
                                     float[] c, float a, boolean lowOnPositiveSide) {
        float lX0, lX1, hX0, hX1;
        if (lowOnPositiveSide) { lX0 = xMid; lX1 = x1;  hX0 = x0;  hX1 = xMid; }
        else                   { lX0 = x0;   lX1 = xMid; hX0 = xMid; hX1 = x1; }

        // Bottom face
        addLine(buf, m, x0, y0, z0,  x1, y0, z0,  c, a);
        addLine(buf, m, x0, y0, z0,  x0, y0, z1,  c, a);
        addLine(buf, m, x1, y0, z0,  x1, y0, z1,  c, a);
        addLine(buf, m, x0, y0, z1,  x1, y0, z1,  c, a);
        // Low step top face
        addLine(buf, m, lX0, yLow, z0,  lX0, yLow, z1,  c, a);
        addLine(buf, m, lX0, yLow, z0,  lX1, yLow, z0,  c, a);
        addLine(buf, m, lX0, yLow, z1,  lX1, yLow, z1,  c, a);
        addLine(buf, m, lX1, yLow, z0,  lX1, yLow, z1,  c, a);
        // High block top face
        addLine(buf, m, hX0, yHigh, z0,  hX0, yHigh, z1,  c, a);
        addLine(buf, m, hX0, yHigh, z0,  hX1, yHigh, z0,  c, a);
        addLine(buf, m, hX0, yHigh, z1,  hX1, yHigh, z1,  c, a);
        addLine(buf, m, hX1, yHigh, z0,  hX1, yHigh, z1,  c, a);
        // Tall vertical edges (high side)
        addLine(buf, m, hX0, y0, z0,  hX0, yHigh, z0,  c, a);
        addLine(buf, m, hX0, y0, z1,  hX0, yHigh, z1,  c, a);
        // Short vertical edges (low side outer)
        addLine(buf, m, lX1, y0, z0,  lX1, yLow, z0,  c, a);
        addLine(buf, m, lX1, y0, z1,  lX1, yLow, z1,  c, a);
        // Step riser vertical edges at xMid
        addLine(buf, m, xMid, yLow, z0,  xMid, yHigh, z0,  c, a);
        addLine(buf, m, xMid, yLow, z1,  xMid, yHigh, z1,  c, a);
    }

    private static void addLine(BufferBuilder buf, Matrix4f m,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float[] c, float a) {
        buf.vertex(m, x1, y1, z1).color(c[0], c[1], c[2], a);
        buf.vertex(m, x2, y2, z2).color(c[0], c[1], c[2], a);
    }

    private static float[] getColor(Category cat) {
        return switch (cat) {
            case WALKABLE_FLAT  -> new float[]{0.10f, 0.90f, 0.10f, 0.70f}; // Green
            case WALKABLE_JUMP  -> new float[]{1.00f, 1.00f, 0.10f, 0.70f}; // Yellow
            case WALKABLE_DROP  -> new float[]{1.00f, 0.70f, 0.10f, 0.70f}; // Amber
            case SLAB           -> SLAB_COLOR;                               // Pink
            case STAIR_WALKABLE -> new float[]{0.40f, 1.00f, 0.80f, 0.80f}; // Teal
            case TALL_OBSTACLE  -> new float[]{1.00f, 0.45f, 0.10f, 0.85f}; // Orange
            case INTERACTABLE   -> new float[]{0.75f, 0.10f, 1.00f, 0.85f}; // Purple
            case CLIMBABLE      -> new float[]{0.10f, 0.50f, 1.00f, 0.85f}; // Blue
            case HAZARDOUS      -> new float[]{1.00f, 0.05f, 0.05f, 0.90f}; // Red
            case WATER          -> new float[]{0.10f, 0.80f, 1.00f, 0.65f}; // Cyan
        };
    }
}
