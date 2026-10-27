package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import ru.baritoneru.Bot;
import ru.baritoneru.path.Goal;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A task such as "go there" or "mine diamonds". Each tick it either returns a goal for the pathfinder,
 * or sets {@link #manual} and drives the controls itself (breaking a block in reach, placing seeds...).
 */
public abstract class BotProcess {
    protected boolean done;
    protected String doneMessage;
    protected boolean manual;
    private final Set<Integer> ignoredItems = new HashSet<>();
    private int pickupId = -1, pickupTicks;

    public abstract String name();

    /** Called every client tick while active. */
    public abstract Goal tick(Bot bot);

    public abstract String status();

    public void onStop(Bot bot) { }

    public boolean isDone() { return done; }

    public boolean isManual() { return manual; }

    public String doneMessage() { return doneMessage; }

    protected void finish(String message) {
        done = true;
        doneMessage = message;
    }

    /** Goal towards a nearby dropped item worth picking up, or null. */
    protected Goal pickupGoal(Bot bot, int radius) {
        var p = bot.mc.player;
        if (p.getInventory().getFreeSlot() < 0) return null;
        List<ItemEntity> items = bot.mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(radius),
                e -> e.isAlive() && e.onGround() && !ignoredItems.contains(e.getId()));
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : items) {
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        if (best == null) {
            pickupId = -1;
            return null;
        }
        if (best.getId() != pickupId) {
            pickupId = best.getId();
            pickupTicks = 0;
        }
        if (++pickupTicks > 200) {
            ignoredItems.add(best.getId());
            pickupId = -1;
            return null;
        }
        BlockPos b = best.blockPosition();
        return new Goal.Near(b.getX(), b.getY(), b.getZ(), 0);
    }
}
