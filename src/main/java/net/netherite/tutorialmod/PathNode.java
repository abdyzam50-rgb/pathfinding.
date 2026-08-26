package net.netherite.tutorialmod;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.Objects;

public class PathNode {
    public enum Type {
        WALK,
        JUMP,
        SPRINT_JUMP, // Sprint + jump over a 2-4 block gap at the same Y level
        BOOST_PLACE, // Sprint-jump and place a block at interactPos (landing floor) mid-arc
        MINE,        // Mine block at interactPos before advancing to pos
        DROP,
        WATER_DROP,  // Fall from any height; bot places water bucket just before impact
        BOUNCE,      // Land on slime block; bounce arc carries player to next node
        CLIMB,       // Climbing ladders / vines / scaffolding (up or down)
        EDGE,        // Last safe block before a drop — highlighted for visibility
        INTERACT,    // Must right-click interactPos before advancing (door, fence gate, etc.)
        PILLAR,      // Jump and place block at interactPos (player feet level) to rise 1 block
        BRIDGE       // Place block at interactPos (gap floor below target) before stepping forward
    }

    public final BlockPos pos;
    public final Vec3d precisePos;
    public final PathNode parent;
    public final Type type;
    /** INTERACT=door; BOOST_PLACE=block to place; MINE=block to break */
    public final BlockPos interactPos;
    public double gCost;

    public PathNode(BlockPos pos, PathNode parent, Type type) {
        this.pos = pos;
        this.precisePos = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        this.parent = parent;
        this.type = type;
        this.interactPos = null;
        this.gCost = (parent != null) ? parent.gCost + 1.0 : 0.0;
    }

    public PathNode(BlockPos pos, Vec3d precisePos, PathNode parent, Type type) {
        this.pos = pos;
        this.precisePos = precisePos;
        this.parent = parent;
        this.type = type;
        this.interactPos = null;
        this.gCost = (parent != null) ? parent.gCost + 1.0 : 0.0;
    }

    public PathNode(BlockPos pos, Vec3d precisePos, PathNode parent, Type type, BlockPos interactPos) {
        this.pos = pos;
        this.precisePos = precisePos;
        this.parent = parent;
        this.type = type;
        this.interactPos = interactPos;
        this.gCost = (parent != null) ? parent.gCost + 1.0 : 0.0;
    }

    /** Block-center X/Z, feet Y — the actual player origin used by physics and rendering. */
    public Vec3d getFeetPos() {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** Legacy: block center including Y+0.5 offset. Prefer getFeetPos() for new code. */
    public Vec3d getCenterPos() {
        return precisePos;
    }

    public boolean isEdgeNode() {
        return type == Type.EDGE || type == Type.DROP || type == Type.WATER_DROP || type == Type.BOUNCE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PathNode pathNode = (PathNode) o;
        return Objects.equals(pos, pathNode.pos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos);
    }
}
