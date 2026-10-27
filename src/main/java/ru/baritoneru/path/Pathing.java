package ru.baritoneru.path;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import ru.baritoneru.Controls;
import ru.baritoneru.Settings;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the goal, plans paths on a worker thread and runs them with {@link PathExecutor}. */
public final class Pathing {
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SlopTone-pathfinder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Minecraft mc = Minecraft.getInstance();
    private Goal goal;
    private PathExecutor exec;
    private BotPath next;
    private CompletableFuture<BotPath> pending;
    private PathFinder pendingFinder;
    private boolean pendingIsNext;
    private int failures;
    private int waitTicks;
    /** blocks the pathfinder must not break (e.g. while farming, the crops) */
    public Set<Block> avoid = Set.of();

    public Goal goal() { return goal; }

    public BotPath path() { return exec == null ? null : exec.path; }

    public int pathIndex() { return exec == null ? 0 : exec.index(); }

    public boolean isPlanning() { return pending != null; }

    /** Consecutive failed searches; processes give up when this gets large. */
    public int failures() { return failures; }

    public void setGoal(Goal g) {
        if (Objects.equals(g, goal)) return;
        goal = g;
        reset();
    }

    public void reset() {
        cancelPending();
        exec = null;
        next = null;
        failures = 0;
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
    }

    private void cancelPending() {
        if (pendingFinder != null) pendingFinder.cancel();
        pending = null;
        pendingFinder = null;
    }

    public World mainWorld() {
        return new World(mc.level, List.of(), true, Set.of());
    }

    /** @return true when the player is standing in the goal */
    public boolean tick(Controls c) {
        if (goal == null) return false;
        BlockPos feet = c.feet();
        if (exec == null && goal.isInGoal(feet) && c.grounded()) {
            cancelPending();
            failures = 0;
            return true;
        }

        if (pending != null && pending.isDone()) {
            BotPath r = pending.getNow(null);
            boolean wasNext = pendingIsNext;
            pending = null;
            pendingFinder = null;
            if (r == null || r.steps.isEmpty()) {
                if (!wasNext) failures++;
                waitTicks = 10;
            } else if (wasNext) {
                next = r;
            } else if (exec == null) {
                exec = new PathExecutor(r);
            }
        }

        if (exec == null) {
            if (pending == null && waitTicks-- <= 0 && c.grounded()) plan(feet, false);
            if (c.grounded()) c.take(); // stand still while thinking
            return false;
        }

        World w = mainWorld();
        PathExecutor.Status st = exec.tick(c, w);
        if (st == PathExecutor.Status.DONE) {
            boolean reached = exec.path.reachesGoal;
            exec = null;
            failures = 0;
            if (!reached && next != null && next.start.equals(c.feet())) {
                exec = new PathExecutor(next);
            }
            next = null;
        } else if (st == PathExecutor.Status.FAILED) {
            exec = null;
            next = null;
            cancelPending();
            failures++;
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        } else if (!exec.path.reachesGoal && next == null && pending == null && exec.remaining() <= 6) {
            plan(exec.path.end(), true); // plan the next segment while walking this one
        }
        return false;
    }

    private void plan(BlockPos from, boolean asNext) {
        Controls c = new Controls();
        World w = new World(mc.level, c.hotbarCopy(), c.hasItem(Controls::isThrowaway), avoid);
        PathFinder pf = new PathFinder(w, goal, from);
        pendingFinder = pf;
        pendingIsNext = asNext;
        long timeout = Settings.planTimeoutMs;
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                return pf.calculate(timeout);
            } catch (Throwable t) {
                return null;
            }
        }, THREAD);
    }
}
