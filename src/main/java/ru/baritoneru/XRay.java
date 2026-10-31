package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import ru.baritoneru.mixin.BlockDisplayAccessor;
import ru.baritoneru.mixin.DisplayAccessor;
import ru.baritoneru.process.MineProcess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ore X-Ray: every ore around the player gets a client-only block display with a coloured glowing outline,
 * which the game draws through walls. Nothing is sent to the server.
 */
public final class XRay {
    /** ore colours; deepslate variants share them */
    public static final Map<Block, Integer> ORES = new LinkedHashMap<>();

    static {
        put(0x4AEDD9, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE);
        put(0x17DD62, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
        put(0xFCEE4B, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE);
        put(0xD8AF93, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);
        put(0xFF3030, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        put(0x345EC3, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        put(0xE07B3B, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        put(0x3A3A3A, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        put(0xEDE6DD, Blocks.NETHER_QUARTZ_ORE);
        put(0x8B5A2B, Blocks.ANCIENT_DEBRIS);
    }

    private static void put(int color, Block... blocks) {
        for (Block b : blocks) ORES.put(b, color);
    }

    public static volatile boolean enabled;
    public static volatile int radius = 32;
    private static final int MAX = 600;
    /** which ores to show; default all except coal and copper (too many) */
    public static final Set<Block> targets = new HashSet<>(ORES.keySet());

    static {
        targets.removeAll(List.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE));
    }

    private static final Map<Long, Display.BlockDisplay> FAKES = new HashMap<>();
    private static final Map<Integer, Integer> COLOR_BY_ID = new HashMap<>();
    private static ClientLevel fakesLevel;
    private static int nextId = -2_000_000;
    private static int timer;

    public static boolean isFake(Entity e) {
        return COLOR_BY_ID.containsKey(e.getId()) && e instanceof Display.BlockDisplay;
    }

    public static int colorOf(Entity e) {
        Integer c = COLOR_BY_ID.get(e.getId());
        return c == null ? -1 : c;
    }

    public static int count() {
        return FAKES.size();
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        timer = 0;
        if (!on) clearAll();
    }

    /** Client tick: rescans ores around the player twice a second. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != fakesLevel) {
            FAKES.clear(); // the old level and its entities are gone
            COLOR_BY_ID.clear();
            fakesLevel = mc.level;
        }
        if (!enabled || mc.level == null || mc.player == null) return;
        if (--timer > 0) return;
        timer = 10;

        Set<Block> want = new HashSet<>(targets);
        List<BlockPos> found = MineProcess.find(mc.level, mc.player.blockPosition(), want, radius, false);
        Set<Long> keep = new HashSet<>();
        for (BlockPos p : found) {
            if (keep.size() >= MAX) break;
            if (p.distSqr(mc.player.blockPosition()) > (long) radius * radius) continue;
            long key = p.asLong();
            keep.add(key);
            if (FAKES.containsKey(key)) continue;
            BlockState state = mc.level.getBlockState(p);
            Display.BlockDisplay d = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, mc.level);
            d.setId(nextId--);
            d.setPos(p.getX(), p.getY(), p.getZ());
            ((BlockDisplayAccessor) d).sloptone$setBlockState(state);
            int color = ORES.getOrDefault(state.getBlock(), 0xFFFFFF);
            ((DisplayAccessor) d).sloptone$setGlowColorOverride(color);
            ((DisplayAccessor) d).sloptone$setViewRange(4f);
            mc.level.addEntity(d);
            FAKES.put(key, d);
            COLOR_BY_ID.put(d.getId(), color);
        }
        // ores that were mined or are now too far
        FAKES.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            remove(mc.level, e.getValue());
            return true;
        });
    }

    private static void remove(ClientLevel level, Display.BlockDisplay d) {
        COLOR_BY_ID.remove(d.getId());
        if (level != null) level.removeEntity(d.getId(), Entity.RemovalReason.DISCARDED);
    }

    private static void clearAll() {
        Minecraft mc = Minecraft.getInstance();
        for (Display.BlockDisplay d : FAKES.values()) remove(mc.level, d);
        FAKES.clear();
    }
}
