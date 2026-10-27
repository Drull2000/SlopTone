package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import ru.baritoneru.Bot;
import ru.baritoneru.path.Goal;

import java.util.HashSet;
import java.util.Set;

/** Walks an outward square spiral, skipping areas already visited. */
public final class ExploreProcess extends BotProcess {
    private static final int STEP = 64;
    private final int ox, oz;
    private final Set<Long> visited = new HashSet<>();
    private int ring = 0, index = 0;
    private Goal goal;
    private int visitedCount;

    public ExploreProcess(int ox, int oz) {
        this.ox = ox;
        this.oz = oz;
    }

    @Override
    public String name() { return "Исследование"; }

    @Override
    public Goal tick(Bot bot) {
        BlockPos f = bot.controls.feet();
        visited.add(cell(f.getX(), f.getZ()));
        if (goal == null || goal.isInGoal(f) || bot.pathing.failures() >= 3) {
            if (goal != null) visitedCount++;
            goal = nextGoal();
            bot.pathing.reset();
        }
        return goal;
    }

    private long cell(int x, int z) {
        return ((long) Math.floorDiv(x - ox + STEP / 2, STEP) << 32) ^ (Math.floorDiv(z - oz + STEP / 2, STEP) & 0xffffffffL);
    }

    private Goal nextGoal() {
        while (true) {
            int[] p = spiral();
            int x = ox + p[0] * STEP, z = oz + p[1] * STEP;
            if (!visited.contains(cell(x, z))) return new Goal.NearXZ(x, z, 6);
        }
    }

    /** Next point of a square spiral around the origin, in grid units. */
    private int[] spiral() {
        if (ring == 0) {
            ring = 1;
            index = 0;
            return new int[]{0, 0};
        }
        int side = ring * 2;
        int per = side * 4;
        int i = index++;
        if (index >= per) {
            ring++;
            index = 0;
        }
        int seg = i / side, off = i % side;
        return switch (seg) {
            case 0 -> new int[]{-ring + off, -ring};
            case 1 -> new int[]{ring, -ring + off};
            case 2 -> new int[]{ring - off, ring};
            default -> new int[]{-ring, ring - off};
        };
    }

    @Override
    public String status() { return "точек пройдено: " + visitedCount + ", кольцо " + ring; }
}
