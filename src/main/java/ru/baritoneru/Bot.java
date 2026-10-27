package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import ru.baritoneru.path.BotPath;
import ru.baritoneru.path.Goal;
import ru.baritoneru.path.Pathing;
import ru.baritoneru.process.BotProcess;

import java.util.Set;

/** Central state: the active process, pathing, controls and small automatic behaviours. */
public final class Bot {
    private static final Bot INSTANCE = new Bot();

    public static Bot get() { return INSTANCE; }

    public final Minecraft mc = Minecraft.getInstance();
    public final Controls controls = new Controls();
    public final Pathing pathing = new Pathing();
    public final Waypoints waypoints = new Waypoints();
    private BotProcess process;
    private boolean paused;
    private boolean wasDead;
    private int eatTicks;
    private int prevSlot = -1;
    private long tickCount;

    public BotProcess process() { return process; }

    public boolean isPaused() { return paused; }

    public static void msg(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§3[§bSlopTone§3]§r " + text));
    }

    public void start(BotProcess p) {
        if (process != null) process.onStop(this);
        process = p;
        paused = false;
        pathing.avoid = Set.of();
        pathing.setGoal(null);
        pathing.reset();
    }

    public void stop(String reason) {
        if (process != null) process.onStop(this);
        process = null;
        pathing.setGoal(null);
        pathing.reset();
        controls.clear();
        controls.releaseAll();
        if (reason != null) msg(reason);
    }

    public void setPaused(boolean p) {
        paused = p;
        if (p) {
            pathing.reset();
            controls.clear();
            controls.releaseAll();
        }
    }

    public void tick() {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            if (process != null) {
                process = null;
                pathing.setGoal(null);
            }
            return;
        }
        tickCount++;
        if (tickCount == 1) BaritoneRu.LOG.info("SlopTone: first in-world tick");
        // test hook: -Dsloptone.autorun="#cmd1;#cmd2" sends these through the real chat path
        String auto = System.getProperty("sloptone.autorun");
        if (auto != null && tickCount == 100) for (String s : auto.split(";")) p.connection.sendChat(s.trim());
        controls.clear();
        waypoints.tick(this);

        if (p.isDeadOrDying()) {
            if (!wasDead) {
                wasDead = true;
                waypoints.save("смерть", p.blockPosition());
                if (process != null) stop("Ты умер. Точка смерти сохранена: §e#назад§r — вернуться.");
            }
            controls.apply();
            return;
        }
        wasDead = false;

        if (process != null && !paused) {
            if (!autoEat()) {
                Goal g;
                try {
                    g = process.tick(this);
                } catch (RuntimeException e) {
                    stop("Ошибка процесса: " + e);
                    g = null;
                }
                if (process != null) {
                    if (process.isDone()) {
                        String m = process.doneMessage();
                        stop(m);
                    } else if (process.isManual()) {
                        pathing.setGoal(null);
                        controls.take();
                    } else if (g == null) {
                        pathing.setGoal(null);
                        controls.take();
                    } else {
                        pathing.setGoal(g);
                        pathing.tick(controls);
                    }
                }
            }
        }
        controls.apply();
        if (Settings.renderPath && tickCount % 5 == 0) renderPath();
    }

    public void postTick() {
    }

    // ------------------------------------------------------------------ auto eat

    private boolean autoEat() {
        LocalPlayer p = mc.player;
        if (eatTicks > 0) {
            eatTicks--;
            controls.take();
            if (!p.isUsingItem() || eatTicks == 0) {
                eatTicks = 0;
                if (prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
                prevSlot = -1;
                return false;
            }
            controls.use = true;
            return true;
        }
        if (!Settings.autoEat || p.getFoodData().getFoodLevel() >= Settings.autoEatHunger || p.isUsingItem()) return false;
        if (p.isInWater() || !p.onGround()) return false;
        int slot = controls.ensureHotbar(Bot::isGoodFood);
        if (slot < 0) return false;
        prevSlot = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(slot);
        controls.take();
        mc.gameMode.stopDestroyBlock();
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        controls.use = true;
        eatTicks = 60;
        return true;
    }

    public static boolean isGoodFood(ItemStack s) {
        if (s.isEmpty()) return false;
        FoodProperties f = s.get(DataComponents.FOOD);
        if (f == null) return false;
        return !(s.is(Items.ROTTEN_FLESH) || s.is(Items.SPIDER_EYE) || s.is(Items.POISONOUS_POTATO) || s.is(Items.PUFFERFISH)
                || s.is(Items.CHORUS_FRUIT) || s.is(Items.SUSPICIOUS_STEW) || s.is(Items.CHICKEN));
    }

    // ------------------------------------------------------------------ rendering

    private void renderPath() {
        BotPath path = pathing.path();
        if (path != null) {
            int from = pathing.pathIndex();
            for (int i = from; i < Math.min(path.steps.size(), from + 60); i++) {
                BlockPos d = path.steps.get(i).dest();
                mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, d.getX() + 0.5, d.getY() + 0.15, d.getZ() + 0.5, 0, 0, 0);
            }
        }
        Goal g = pathing.goal();
        if (g instanceof Goal.Block b) {
            mc.level.addParticle(ParticleTypes.FLAME, b.x() + 0.5, b.y() + 0.5, b.z() + 0.5, 0, 0.02, 0);
        }
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        if (process == null) return "Ничего не делаю.";
        sb.append(process.name()).append(": ").append(process.status());
        if (paused) sb.append(" §e(пауза)§r");
        Goal g = pathing.goal();
        if (g != null) sb.append("\nЦель: ").append(g.describe());
        BotPath path = pathing.path();
        if (path != null)
            sb.append("\nПуть: ").append(path.steps.size() - pathing.pathIndex()).append(" шагов")
                    .append(path.reachesGoal ? "" : " (частичный, досчитываю)");
        else if (pathing.isPlanning()) sb.append("\nРассчитываю путь…");
        return sb.toString();
    }
}
