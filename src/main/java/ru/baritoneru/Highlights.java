package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

/**
 * Which entities get the glowing outline (seen through walls, like the Glowing effect).
 * Used by the mixins in ru.baritoneru.mixin; only affects what this client draws.
 */
public final class Highlights {
    public static volatile boolean players;
    public static volatile boolean mobs;
    public static volatile boolean animals;

    public static boolean anyOn() {
        return players || mobs || animals;
    }

    public static boolean shouldGlow(Entity e) {
        if (XRay.isFake(e)) return true;
        if (!anyOn() || !e.level().isClientSide()) return false;
        if (e == Minecraft.getInstance().player) return false;
        if (e instanceof Player) return players;
        if (e instanceof Enemy) return mobs;
        return animals && e instanceof LivingEntity;
    }

    /** outline colour, or -1 to keep the vanilla one */
    public static int colorFor(Entity e) {
        if (XRay.isFake(e)) return XRay.colorOf(e);
        if (!shouldGlow(e)) return -1;
        if (e instanceof Player p) return Commands.colorOf(p.getName().getString());
        if (e instanceof Enemy) return 0xFF5555;
        return 0x55FF55;
    }
}
