package ru.baritoneru.path;

import net.minecraft.core.BlockPos;

import java.util.List;

/** Where the pathfinder should get the player's feet to. Implementations are value objects (records). */
public interface Goal {
    boolean isInGoal(int x, int y, int z);

    /** Estimated remaining cost in ticks. */
    double heuristic(int x, int y, int z);

    String describe();

    default boolean isInGoal(BlockPos p) {
        return isInGoal(p.getX(), p.getY(), p.getZ());
    }

    double SPRINT = 3.564;
    double SQRT2 = Math.sqrt(2);

    static double horizontal(int dx, int dz) {
        dx = Math.abs(dx);
        dz = Math.abs(dz);
        int diag = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diag;
        return (diag * SQRT2 + straight) * SPRINT;
    }

    static double vertical(int dy) {
        // dy > 0: goal is above (needs ascend/pillar), dy < 0: goal is below (falls are cheap)
        return dy > 0 ? dy * 6.0 : -dy * 2.5;
    }

    record Block(int x, int y, int z) implements Goal {
        public Block(BlockPos p) { this(p.getX(), p.getY(), p.getZ()); }
        public boolean isInGoal(int x, int y, int z) { return x == this.x && y == this.y && z == this.z; }
        public double heuristic(int x, int y, int z) { return horizontal(this.x - x, this.z - z) + vertical(this.y - y); }
        public String describe() { return x + " " + y + " " + z; }
    }

    record XZ(int x, int z) implements Goal {
        public boolean isInGoal(int x, int y, int z) { return x == this.x && z == this.z; }
        public double heuristic(int x, int y, int z) { return horizontal(this.x - x, this.z - z); }
        public String describe() { return "X " + x + " Z " + z; }
    }

    record NearXZ(int x, int z, int r) implements Goal {
        public boolean isInGoal(int x, int y, int z) { int dx = x - this.x, dz = z - this.z; return dx * dx + dz * dz <= r * r; }
        public double heuristic(int x, int y, int z) { return Math.max(0, horizontal(this.x - x, this.z - z) - r * SPRINT); }
        public String describe() { return "X " + x + " Z " + z + " (±" + r + ")"; }
    }

    record YLevel(int y) implements Goal {
        public boolean isInGoal(int x, int y, int z) { return y == this.y; }
        public double heuristic(int x, int y, int z) { return vertical(this.y - y); }
        public String describe() { return "высота Y=" + y; }
    }

    record Near(int x, int y, int z, int r) implements Goal {
        public Near(BlockPos p, int r) { this(p.getX(), p.getY(), p.getZ(), r); }
        public boolean isInGoal(int x, int y, int z) {
            int dx = x - this.x, dy = y - this.y, dz = z - this.z;
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
        public double heuristic(int x, int y, int z) {
            return Math.max(0, horizontal(this.x - x, this.z - z) + vertical(this.y - y) - r * SPRINT);
        }
        public String describe() { return x + " " + y + " " + z + " (±" + r + ")"; }
    }

    /** Feet or head inside the block - walking "into" it breaks it. Used for mining. */
    record TwoBlocks(int x, int y, int z) implements Goal {
        public TwoBlocks(BlockPos p) { this(p.getX(), p.getY(), p.getZ()); }
        public boolean isInGoal(int x, int y, int z) { return x == this.x && z == this.z && (y == this.y || y == this.y - 1); }
        public double heuristic(int x, int y, int z) {
            int ty = y < this.y - 1 ? this.y - 1 : this.y;
            return horizontal(this.x - x, this.z - z) + vertical(ty - y);
        }
        public String describe() { return x + " " + y + " " + z; }
    }

    /** Standing next to the block (can touch it). */
    record Adjacent(int x, int y, int z) implements Goal {
        public Adjacent(BlockPos p) { this(p.getX(), p.getY(), p.getZ()); }
        public boolean isInGoal(int x, int y, int z) {
            int dx = Math.abs(x - this.x), dz = Math.abs(z - this.z), dy = y - this.y;
            return dx + dz <= 1 && dy >= -2 && dy <= 1 && !(dx == 0 && dz == 0 && (dy == 0 || dy == -1));
        }
        public double heuristic(int x, int y, int z) { return horizontal(this.x - x, this.z - z) + vertical(this.y - y); }
        public String describe() { return "рядом с " + x + " " + y + " " + z; }
    }

    record Axis() implements Goal {
        public boolean isInGoal(int x, int y, int z) { return x == 0 || z == 0 || Math.abs(x) == Math.abs(z); }
        public double heuristic(int x, int y, int z) {
            int ax = Math.abs(x), az = Math.abs(z);
            int best = Math.min(Math.min(ax, az), Math.abs(ax - az) / 2);
            return best * SPRINT;
        }
        public String describe() { return "ближайшая ось"; }
    }

    record Composite(List<Goal> goals) implements Goal {
        public boolean isInGoal(int x, int y, int z) {
            for (Goal g : goals) if (g.isInGoal(x, y, z)) return true;
            return false;
        }
        public double heuristic(int x, int y, int z) {
            double best = Double.MAX_VALUE;
            for (Goal g : goals) best = Math.min(best, g.heuristic(x, y, z));
            return best;
        }
        public String describe() { return goals.size() == 1 ? goals.get(0).describe() : goals.size() + " целей"; }
    }
}
