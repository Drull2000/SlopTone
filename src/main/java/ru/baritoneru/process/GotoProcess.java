package ru.baritoneru.process;

import ru.baritoneru.Bot;
import ru.baritoneru.path.Goal;

/** Plain "go to goal" (goto, thisway, surface, axis, waypoints, home...). */
public final class GotoProcess extends BotProcess {
    private final Goal goal;
    private final String label;

    public GotoProcess(Goal goal, String label) {
        this.goal = goal;
        this.label = label;
    }

    @Override
    public String name() { return label; }

    @Override
    public Goal tick(Bot bot) {
        if (goal.isInGoal(bot.controls.feet()) && bot.controls.grounded()) {
            finish("§aПрибыл§r: " + goal.describe());
            return null;
        }
        if (bot.pathing.failures() >= 4) {
            finish("§cНе могу найти путь§r к " + goal.describe());
            return null;
        }
        return goal;
    }

    @Override
    public String status() { return "иду к " + goal.describe(); }
}
