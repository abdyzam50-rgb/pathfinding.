package net.netherite.tutorialmod;

import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LineRenderer {
    private static final List<PathNode> pathNodes = new ArrayList<>();
    private static ScheduledExecutorService renderThread = null;

    public static synchronized void setPath(ServerWorld world, List<PathNode> nodes) {
        // Particle rendering disabled in favor of TessellatorRenderer
        clearPath();
    }

    public static synchronized void clearPath() {
        pathNodes.clear();
        if (renderThread != null) {
            renderThread.shutdownNow();
            renderThread = null;
        }
    }

    private static void drawBlockOutline(ServerWorld world, DustParticleEffect particle, BlockPos pos) {
        double x = pos.getX(); double y = pos.getY(); double z = pos.getZ();
        // Base plane
        drawDenseLine(world, particle, x, y, z, x + 1, y, z);
        drawDenseLine(world, particle, x + 1, y, z, x + 1, y, z + 1);
        drawDenseLine(world, particle, x + 1, y, z + 1, x, y, z + 1);
        drawDenseLine(world, particle, x, y, z + 1, x, y, z);
        // Roof plane
        drawDenseLine(world, particle, x, y + 1, z, x + 1, y + 1, z);
        drawDenseLine(world, particle, x + 1, y + 1, z, x + 1, y + 1, z + 1);
        drawDenseLine(world, particle, x + 1, y + 1, z + 1, x, y + 1, z + 1);
        drawDenseLine(world, particle, x, y + 1, z + 1, x, y + 1, z);
        // Vertebra pillars
        drawDenseLine(world, particle, x, y, z, x, y + 1, z);
        drawDenseLine(world, particle, x + 1, y, z, x + 1, y + 1, z);
        drawDenseLine(world, particle, x + 1, y, z + 1, x + 1, y + 1, z + 1);
        drawDenseLine(world, particle, x, y, z + 1, x, y + 1, z + 1);
    }

    private static void drawDenseLine(ServerWorld world, DustParticleEffect particle, double x1, double y1, double z1, double x2, double y2, double z2) {
        double distance = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        int points = (int) (distance * 6);
        for (int i = 0; i <= points; i++) {
            double t = (double) i / points;
            world.spawnParticles(particle, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 1, 0, 0, 0, 0);
        }
    }
}