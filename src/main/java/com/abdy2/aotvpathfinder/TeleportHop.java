package com.abdy2.aotvpathfinder;

import net.minecraft.util.math.BlockPos;

public record TeleportHop(BlockPos landing, HopType type, int manaCost) {
    public boolean requiresShift() {
        return type == HopType.SHIFT;
    }

    public boolean isWalk() {
        return type == HopType.WALK;
    }

    public enum HopType {
        NORMAL,
        SHIFT,
        WALK
    }
}
