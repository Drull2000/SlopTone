package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import ru.baritoneru.Bot;
import ru.baritoneru.Settings;
import ru.baritoneru.path.Goal;

/** Follows a player (by name) or the nearest player. */
public final class FollowProcess extends BotProcess {
    private final String targetName; // null = nearest player
    private Goal goal;
    private BlockPos goalPos;
    private int lostTicks;
    private String currentName = "?";

    public FollowProcess(String targetName) {
        this.targetName = targetName;
    }

    @Override
    public String name() { return "Следование"; }

    private Entity find(Bot bot) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player pl : bot.mc.level.players()) {
            if (pl == bot.mc.player) continue;
            if (targetName != null) {
                if (pl.getName().getString().equalsIgnoreCase(targetName)) return pl;
                continue;
            }
            double d = pl.distanceToSqr(bot.mc.player);
            if (d < bd) {
                bd = d;
                best = pl;
            }
        }
        return best;
    }

    @Override
    public Goal tick(Bot bot) {
        Entity e = find(bot);
        if (e == null) {
            if (++lostTicks > 200) finish("Цель для следования потеряна.");
            return goal;
        }
        lostTicks = 0;
        currentName = e.getName().getString();
        BlockPos pos = e.blockPosition();
        int r = Settings.followRadius;
        if (goalPos == null || goalPos.distSqr(pos) > 4) {
            goalPos = pos;
            goal = new Goal.Near(pos, r);
        }
        if (bot.mc.player.distanceTo(e) <= r + 0.5) {
            // close enough: stand and look at the target
            manual = true;
            bot.controls.take();
            bot.controls.lookAt(e.getEyePosition(), true);
            return null;
        }
        manual = false;
        return goal;
    }

    @Override
    public String status() { return "следую за " + currentName; }
}
