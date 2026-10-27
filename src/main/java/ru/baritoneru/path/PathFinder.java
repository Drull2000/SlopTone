package ru.baritoneru.path;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import ru.baritoneru.Settings;
import ru.baritoneru.path.BotPath.MoveType;
import ru.baritoneru.path.BotPath.Step;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A* over feet positions. Runs on a background thread; reads the client world through {@link World}. */
public final class PathFinder {
    public static final double WALK = 4.633, SPRINT = 3.564, SNEAK = 15.385, SWIM = 9.091;
    public static final double ASCEND_EXTRA = 3.0, PILLAR = 12.0, CLIMB = 5.0;
    public static final double[] FALL = new double[257];

    static {
        double v = 0, y = 0;
        int t = 0, n = 1;
        while (n < FALL.length) {
            v = (v - 0.08) * 0.98;
            y += v;
            t++;
            while (n < FALL.length && -y >= n) FALL[n++] = t;
        }
    }

    private static final int MAX_NODES = 600_000;
    private static final double INF = World.INF;

    private final World w;
    private final Goal goal;
    private final BlockPos start;
    private volatile boolean cancelled;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>(1 << 16);
    private final Heap open = new Heap();
    private Node best;
    private double bestScore = Double.MAX_VALUE;
    public int expanded;

    public PathFinder(World w, Goal goal, BlockPos start) {
        this.w = w;
        this.goal = goal;
        this.start = start;
    }

    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    public BotPath calculate(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Node s = node(start.getX(), start.getY(), start.getZ());
        s.g = 0;
        s.f = s.h;
        open.add(s);
        best = s;
        bestScore = s.h;
        while (!open.isEmpty()) {
            if ((++expanded & 127) == 0 && (cancelled || System.currentTimeMillis() > deadline)) break;
            if (nodes.size() > MAX_NODES) break;
            Node n = open.poll();
            if (goal.isInGoal(n.x, n.y, n.z)) return build(n, true);
            expand(n);
        }
        if (cancelled || best == s) return null;
        return build(best, false);
    }

    private Node node(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.heuristic(x, y, z);
            nodes.put(key, n);
        }
        return n;
    }

    private void relax(Node from, int x, int y, int z, double cost, MoveType type, boolean place) {
        if (cost >= INF || y < w.minY() || y > w.maxY()) return;
        Node m = node(x, y, z);
        double g = from.g + cost;
        if (g + 0.01 < m.g) {
            m.g = g;
            m.f = g + m.h;
            m.parent = from;
            m.type = type;
            m.place = place;
            m.moveCost = cost;
            if (m.heapIdx >= 0) open.decrease(m);
            else open.add(m);
            double score = m.h + g / 5.0;
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
    }

    private void expand(Node n) {
        int x = n.x, y = n.y, z = n.z;
        boolean srcWater = w.water(x, y, z);
        boolean srcClimb = w.climbable(x, y, z);
        for (int i = 0; i < 4; i++) {
            int dx = i == 0 ? 1 : i == 1 ? -1 : 0;
            int dz = i == 2 ? 1 : i == 3 ? -1 : 0;
            traverse(n, x, y, z, dx, dz, srcWater);
            ascend(n, x, y, z, dx, dz);
            descend(n, x, y, z, dx, dz);
            diagonal(n, x, y, z, i < 2 ? 1 : -1, i % 2 == 0 ? 1 : -1, srcWater);
        }
        pillar(n, x, y, z, srcWater || srcClimb);
        downward(n, x, y, z, srcWater);
        if (srcWater || srcClimb) {
            if ((w.water(x, y + 1, z) || w.climbable(x, y + 1, z)) && w.passable(x, y + 2, z))
                relax(n, x, y + 1, z, CLIMB, MoveType.SWIM_UP, false);
            if (w.passable(x, y - 1, z) && (w.water(x, y - 1, z) || w.climbable(x, y - 1, z) || w.standable(x, y - 2, z)))
                relax(n, x, y - 1, z, CLIMB, MoveType.SWIM_DOWN, false);
        }
    }

    private void traverse(Node n, int x, int y, int z, int dx, int dz, boolean srcWater) {
        int nx = x + dx, nz = z + dz;
        double c1 = w.clearCost(nx, y, nz);
        if (c1 >= INF) return;
        double c2 = w.clearCost(nx, y + 1, nz);
        if (c2 >= INF) return;
        boolean breaking = c1 > 0 || c2 > 0;
        double base;
        boolean place = false;
        if (w.water(nx, y, nz)) {
            base = SWIM;
        } else if (w.standable(nx, y - 1, nz) || w.climbable(nx, y, nz)) {
            base = srcWater ? SWIM : (Settings.allowSprint && !breaking ? SPRINT : WALK);
        } else if (Settings.allowPlace && w.hasThrowaway() && !srcWater && w.replaceable(nx, y - 1, nz) && w.standable(x, y - 1, z)) {
            base = SNEAK + Settings.blockPlacementPenalty;
            place = true;
        } else {
            return;
        }
        relax(n, nx, y, nz, base + c1 + c2, MoveType.TRAVERSE, place);
    }

    private void ascend(Node n, int x, int y, int z, int dx, int dz) {
        int nx = x + dx, nz = z + dz;
        if (!w.standable(nx, y, nz)) return;
        if (!w.standable(x, y - 1, z) && !w.water(x, y, z) && !w.climbable(x, y, z)) return;
        double c0 = w.clearCost(x, y + 2, z);
        if (c0 >= INF) return;
        double c1 = w.clearCost(nx, y + 1, nz);
        if (c1 >= INF) return;
        double c2 = w.clearCost(nx, y + 2, nz);
        if (c2 >= INF) return;
        relax(n, nx, y + 1, nz, WALK + ASCEND_EXTRA + c0 + c1 + c2, MoveType.ASCEND, false);
    }

    private void descend(Node n, int x, int y, int z, int dx, int dz) {
        int nx = x + dx, nz = z + dz;
        double c1 = w.clearCost(nx, y + 1, nz);
        if (c1 >= INF) return;
        double c2 = w.clearCost(nx, y, nz);
        if (c2 >= INF) return;
        double walk = WALK * 0.8 + c1 + c2;
        int maxFall = Settings.maxFallHeight;
        for (int k = 1; k < 64; k++) {
            int yy = y - k;
            if (yy < w.minY()) return;
            if (w.water(nx, yy, nz)) {
                relax(n, nx, yy, nz, walk + FALL[k], MoveType.DESCEND, false);
                return;
            }
            if (w.passable(nx, yy, nz)) {
                if (w.climbable(nx, yy, nz) || w.standable(nx, yy - 1, nz)) {
                    if (k <= maxFall || w.climbable(nx, yy, nz)) relax(n, nx, yy, nz, walk + FALL[k], MoveType.DESCEND, false);
                    return;
                }
                continue;
            }
            if (k == 1) {
                // dig a staircase down
                double cb = w.clearCost(nx, yy, nz);
                if (cb < INF && w.standable(nx, yy - 1, nz)) relax(n, nx, yy, nz, walk + cb + FALL[1], MoveType.DESCEND, false);
            }
            return;
        }
    }

    private void diagonal(Node n, int x, int y, int z, int dx, int dz, boolean srcWater) {
        int nx = x + dx, nz = z + dz;
        if (!w.passable(nx, y, nz) || !w.passable(nx, y + 1, nz)) return;
        if (!w.passable(x + dx, y, z) || !w.passable(x + dx, y + 1, z)) return;
        if (!w.passable(x, y, z + dz) || !w.passable(x, y + 1, z + dz)) return;
        double base;
        if (w.water(nx, y, nz)) base = SWIM;
        else if (w.standable(nx, y - 1, nz)) base = srcWater ? SWIM : (Settings.allowSprint ? SPRINT : WALK);
        else return;
        relax(n, nx, y, nz, base * Goal.SQRT2, MoveType.DIAGONAL, false);
    }

    private void pillar(Node n, int x, int y, int z, boolean fluidOrClimb) {
        if (!Settings.allowPlace || !w.hasThrowaway() || fluidOrClimb) return;
        if (!w.standable(x, y - 1, z) || !w.replaceable(x, y, z)) return;
        double c = w.clearCost(x, y + 2, z);
        if (c >= INF) return;
        relax(n, x, y + 1, z, PILLAR + Settings.blockPlacementPenalty + c, MoveType.PILLAR, true);
    }

    private void downward(Node n, int x, int y, int z, boolean srcWater) {
        if (!Settings.allowDownward || srcWater) return;
        double cb = w.clearCost(x, y - 1, z);
        if (cb <= 0 || cb >= INF) return;
        if (!w.standable(x, y - 2, z)) return;
        relax(n, x, y - 1, z, cb + FALL[1], MoveType.DOWNWARD, false);
    }

    private BotPath build(Node end, boolean reached) {
        List<Step> steps = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            BlockPos src = new BlockPos(n.parent.x, n.parent.y, n.parent.z);
            BlockPos dest = new BlockPos(n.x, n.y, n.z);
            steps.add(new Step(n.type, src, dest, toBreak(w, n.type, src, dest), n.place, n.moveCost));
        }
        Collections.reverse(steps);
        return new BotPath(start, steps, reached, goal);
    }

    public static List<BlockPos> toBreak(World w, MoveType type, BlockPos src, BlockPos dest) {
        List<BlockPos> all = switch (type) {
            case TRAVERSE, DIAGONAL -> Arrays.asList(dest.above(), dest);
            case ASCEND -> Arrays.asList(src.above(2), dest.above(), dest);
            case DESCEND -> {
                List<BlockPos> l = new ArrayList<>();
                for (int yy = src.getY() + 1; yy >= dest.getY(); yy--) l.add(new BlockPos(dest.getX(), yy, dest.getZ()));
                yield l;
            }
            case PILLAR -> List.of(src.above(2));
            case DOWNWARD -> List.of(dest);
            default -> List.of();
        };
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : all) if (!w.passable(p.getX(), p.getY(), p.getZ())) out.add(p);
        return out;
    }

    static final class Node {
        final int x, y, z;
        double g = INF, h, f;
        Node parent;
        MoveType type;
        boolean place;
        double moveCost;
        int heapIdx = -1;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class Heap {
        private Node[] a = new Node[1024];
        private int size;

        boolean isEmpty() { return size == 0; }

        void add(Node n) {
            if (size == a.length) a = Arrays.copyOf(a, size * 2);
            a[size] = n;
            n.heapIdx = size;
            up(size++);
        }

        void decrease(Node n) { up(n.heapIdx); }

        Node poll() {
            Node top = a[0];
            a[0] = a[--size];
            a[0].heapIdx = 0;
            a[size] = null;
            if (size > 0) down(0);
            top.heapIdx = -1;
            return top;
        }

        private void up(int i) {
            Node n = a[i];
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (a[p].f <= n.f) break;
                a[i] = a[p];
                a[i].heapIdx = i;
                i = p;
            }
            a[i] = n;
            n.heapIdx = i;
        }

        private void down(int i) {
            Node n = a[i];
            while (true) {
                int l = 2 * i + 1;
                if (l >= size) break;
                int c = l + 1 < size && a[l + 1].f < a[l].f ? l + 1 : l;
                if (a[c].f >= n.f) break;
                a[i] = a[c];
                a[i].heapIdx = i;
                i = c;
            }
            a[i] = n;
            n.heapIdx = i;
        }
    }
}
