package ru.baritoneru.path;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import ru.baritoneru.Controls;
import ru.baritoneru.path.BotPath.MoveType;
import ru.baritoneru.path.BotPath.Step;

/** Follows a computed path one step at a time. */
public final class PathExecutor {
    public enum Status { RUNNING, DONE, FAILED }

    public final BotPath path;
    private int idx;
    private int ticksOnStep;
    private int offPathTicks;
    private int placeCooldown;
    public PathExecutor(BotPath path) {
        this.path = path;
    }

    public int index() { return idx; }

    public int remaining() { return path.steps.size() - idx; }

    public Status tick(Controls c, World world) {
        LocalPlayer p = c.player();
        if (idx >= path.steps.size()) return Status.DONE;
        BlockPos feet = c.feet();

        // we may have skipped ahead (e.g. fell or cut a corner)
        if (c.grounded()) {
            for (int j = idx; j < Math.min(idx + 4, path.steps.size()); j++) {
                if (path.steps.get(j).dest().equals(feet)) {
                    idx = j + 1;
                    ticksOnStep = 0;
                    if (idx >= path.steps.size()) return Status.DONE;
                    break;
                }
            }
        }
        Step s = path.steps.get(idx);

        if (!nearStep(feet, s)) {
            if (++offPathTicks > 20) return Status.FAILED;
        } else {
            offPathTicks = 0;
        }
        if (++ticksOnStep > s.timeoutTicks()) return Status.FAILED;
        if (placeCooldown > 0) placeCooldown--;

        c.take();
        BlockPos src = s.src(), dest = s.dest();

        // 1. clear the way
        for (BlockPos b : s.toBreak()) {
            if (world.passable(b.getX(), b.getY(), b.getZ())) continue;
            if (world.clearCost(b.getX(), b.getY(), b.getZ()) >= World.INF) return Status.FAILED;
            if ((s.type() == MoveType.DOWNWARD || s.type() == MoveType.PILLAR) && !c.centered(src, 0.22)) {
                c.walkTo(src.getX() + 0.5, src.getZ() + 0.5, false);
                c.sneak = true;
                return Status.RUNNING;
            }
            if (!c.canReach(b)) {
                c.walkTo(src.getX() + 0.5, src.getZ() + 0.5, false);
                return Status.RUNNING;
            }
            c.breakBlock(b);
            return Status.RUNNING;
        }

        // 2. place blocks
        if (s.place() && s.type() == MoveType.TRAVERSE && !world.standable(dest.getX(), dest.getY() - 1, dest.getZ())) {
            Direction d = Direction.getApproximateNearest(dest.getX() - src.getX(), 0, dest.getZ() - src.getZ());
            if (!c.centered(src, 0.3)) {
                c.walkTo(src.getX() + 0.5, src.getZ() + 0.5, false);
                c.sneak = true;
                return Status.RUNNING;
            }
            c.sneak = true;
            if (placeCooldown == 0) {
                c.placeAgainst(src.below(), d, Controls::isThrowaway, true);
                placeCooldown = 4;
            } else {
                c.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(src.below()).add(d.getStepX() * 0.5, 0, d.getStepZ() * 0.5), false);
            }
            return Status.RUNNING;
        }
        if (s.type() == MoveType.PILLAR) {
            if (!world.standable(src.getX(), src.getY(), src.getZ())) {
                if (!c.centered(src, 0.25)) {
                    c.walkTo(src.getX() + 0.5, src.getZ() + 0.5, false);
                    c.sneak = true;
                    return Status.RUNNING;
                }
                c.look(p.getYRot(), 90f, false);
                c.sneak = true;
                if (p.onGround()) c.jump = true;
                if (p.getY() > src.getY() + 1.0 && placeCooldown == 0) {
                    if (c.placeAgainst(src.below(), Direction.UP, Controls::isThrowaway, false)) placeCooldown = 3;
                }
                return Status.RUNNING;
            }
            c.walkTo(dest.getX() + 0.5, dest.getZ() + 0.5, false);
            c.forward = false;
            return Status.RUNNING;
        }

        // 3. move
        double tx = dest.getX() + 0.5, tz = dest.getZ() + 0.5;
        switch (s.type()) {
            case TRAVERSE, DIAGONAL -> {
                boolean sprint = s.toBreak().isEmpty() && !s.place();
                c.walkTo(tx, tz, sprint);
                if (p.isInWater() && dest.getY() >= feet.getY()) c.jump = true;
                else if (p.horizontalCollision && p.onGround()) c.jump = true;
            }
            case ASCEND -> {
                double d = c.walkTo(tx, tz, false);
                if ((p.onGround() || p.isInWater() || p.onClimbable()) && feet.getY() < dest.getY() && (d < 1.4 || p.horizontalCollision))
                    c.jump = true;
            }
            case DESCEND -> {
                if (feet.getX() == dest.getX() && feet.getZ() == dest.getZ()) {
                    // falling down the column: steer to the middle, no momentum
                    if (c.horizDist(tx, tz) > 0.25) c.walkTo(tx, tz, false);
                } else {
                    c.walkTo(tx, tz, false);
                }
            }
            case DOWNWARD -> {
                if (!c.centered(src, 0.25)) c.walkTo(tx, tz, false);
            }
            case SWIM_UP -> {
                if (c.horizDist(tx, tz) > 0.3) c.walkTo(tx, tz, false);
                if (world.climbable(src.getX(), src.getY(), src.getZ())) c.forward = true;
                c.jump = true;
            }
            case SWIM_DOWN -> {
                if (c.horizDist(tx, tz) > 0.3) c.walkTo(tx, tz, false);
                c.sneak = true;
            }
            default -> { }
        }
        return Status.RUNNING;
    }

    private static boolean nearStep(BlockPos f, Step s) {
        BlockPos a = s.src(), b = s.dest();
        int minX = Math.min(a.getX(), b.getX()) - 1, maxX = Math.max(a.getX(), b.getX()) + 1;
        int minZ = Math.min(a.getZ(), b.getZ()) - 1, maxZ = Math.max(a.getZ(), b.getZ()) + 1;
        int minY = Math.min(a.getY(), b.getY()) - 1, maxY = Math.max(a.getY(), b.getY()) + 2;
        return f.getX() >= minX && f.getX() <= maxX && f.getZ() >= minZ && f.getZ() <= maxZ && f.getY() >= minY && f.getY() <= maxY;
    }
}
