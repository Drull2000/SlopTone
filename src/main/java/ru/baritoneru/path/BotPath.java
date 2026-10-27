package ru.baritoneru.path;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class BotPath {
    public enum MoveType { TRAVERSE, DIAGONAL, ASCEND, DESCEND, PILLAR, DOWNWARD, SWIM_UP, SWIM_DOWN }

    /** One movement between two feet positions. toBreak is ordered top-down. */
    public record Step(MoveType type, BlockPos src, BlockPos dest, List<BlockPos> toBreak, boolean place, double cost) {
        public int timeoutTicks() {
            return (int) Math.min(20 * 60, cost * 2.5 + 60);
        }
    }

    public final BlockPos start;
    public final List<Step> steps;
    public final boolean reachesGoal;
    public final Goal goal;

    public BotPath(BlockPos start, List<Step> steps, boolean reachesGoal, Goal goal) {
        this.start = start;
        this.steps = steps;
        this.reachesGoal = reachesGoal;
        this.goal = goal;
    }

    public BlockPos end() {
        return steps.isEmpty() ? start : steps.get(steps.size() - 1).dest();
    }
}
