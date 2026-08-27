package com.abdy2.aotvpathfinder.path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class WalkNode {
    enum Type {
        WALK,
        JUMP,
        SPRINT_JUMP,
        DROP,
        CLIMB,
        EDGE
    }

    final BlockPos pos;
    final Vec3 precisePos;
    WalkNode parent;
    final Type type;
    double gCost;

    WalkNode(BlockPos pos, Vec3 precisePos, WalkNode parent, Type type) {
        this.pos = pos;
        this.precisePos = (precisePos != null) ? precisePos
                : new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        this.parent = parent;
        this.type = type;
        this.gCost = 0.0;
    }

    Vec3 getFeetPos() {
        return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
