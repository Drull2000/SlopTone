package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import ru.baritoneru.Bot;
import ru.baritoneru.Settings;
import ru.baritoneru.path.Goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds and mines the given blocks; goes to the best Y level and branch-mines when none are known. */
public final class MineProcess extends BotProcess {
    private static final Map<Block, Integer> BEST_Y = new HashMap<>();

    static {
        BEST_Y.put(Blocks.DIAMOND_ORE, -58);
        BEST_Y.put(Blocks.DEEPSLATE_DIAMOND_ORE, -58);
        BEST_Y.put(Blocks.REDSTONE_ORE, -58);
        BEST_Y.put(Blocks.DEEPSLATE_REDSTONE_ORE, -58);
        BEST_Y.put(Blocks.GOLD_ORE, -16);
        BEST_Y.put(Blocks.DEEPSLATE_GOLD_ORE, -16);
        BEST_Y.put(Blocks.IRON_ORE, 16);
        BEST_Y.put(Blocks.DEEPSLATE_IRON_ORE, 16);
        BEST_Y.put(Blocks.LAPIS_ORE, 0);
        BEST_Y.put(Blocks.DEEPSLATE_LAPIS_ORE, 0);
        BEST_Y.put(Blocks.COPPER_ORE, 48);
        BEST_Y.put(Blocks.DEEPSLATE_COPPER_ORE, 48);
        BEST_Y.put(Blocks.COAL_ORE, 45);
        BEST_Y.put(Blocks.ANCIENT_DEBRIS, 15);
        BEST_Y.put(Blocks.NETHER_QUARTZ_ORE, 15);
        BEST_Y.put(Blocks.NETHER_GOLD_ORE, 15);
    }

    private final Set<Block> targets;
    private final String label;
    private final int wanted; // 0 = forever
    private int mined;
    private final List<BlockPos> known = new ArrayList<>();
    private final Set<BlockPos> blacklist = new HashSet<>();
    /** cached "placed by a player" verdicts, by position */
    private final Map<Long, Boolean> built = new HashMap<>();
    private int scanCooldown;
    private Goal goal;
    private Goal branchGoal;
    private Direction branchDir;
    private BlockPos lastFailedCheck;
    private int lastFailures;
    private String state = "ищу";

    public MineProcess(Set<Block> targets, String label, int wanted) {
        this.targets = targets;
        this.label = label;
        this.wanted = wanted;
    }

    @Override
    public String name() { return "Добыча " + label; }

    @Override
    public Goal tick(Bot bot) {
        var mc = bot.mc;
        var c = bot.controls;
        manual = false;

        // count blocks that disappeared near us
        BlockPos feet = c.feet();
        known.removeIf(p -> {
            if (p.distSqr(feet) > 49) return false;
            if (!mc.level.isLoaded(p)) return false;
            if (!targets.contains(mc.level.getBlockState(p).getBlock())) {
                mined++;
                return true;
            }
            return false;
        });
        if (wanted > 0 && mined >= wanted) {
            finish("§aГотово§r: добыто " + mined + " × " + label);
            return null;
        }
        if (Settings.stopWhenInventoryFull && mc.player.getInventory().getFreeSlot() < 0) {
            finish("§eИнвентарь полон§r, добыто " + mined + ". Остановлено.");
            return null;
        }

        if (--scanCooldown <= 0) {
            scanCooldown = 40;
            scan(bot);
        }

        // something in reach and visible: mine it right away
        BlockPos reachable = null;
        var world = bot.pathing.mainWorld();
        for (BlockPos p : known) {
            if (blacklist.contains(p)) continue;
            if (c.canReach(p) && c.visible(p)) {
                if (world.clearCost(p.getX(), p.getY(), p.getZ()) >= ru.baritoneru.path.World.INF) {
                    blacklist.add(p); // e.g. next to lava
                    continue;
                }
                reachable = p;
                break;
            }
        }
        if (reachable != null) {
            manual = true;
            state = "копаю " + reachable.toShortString();
            c.breakBlock(reachable);
            return null;
        }

        Goal pickup = pickupGoal(bot, 8);
        if (pickup != null) {
            state = "подбираю дроп";
            return pickup;
        }

        // remember targets the pathfinder keeps failing on
        int f = bot.pathing.failures();
        if (f >= 3 && f != lastFailures && goal instanceof Goal.Composite comp) {
            for (Goal g : comp.goals())
                if (g instanceof Goal.TwoBlocks t) blacklist.add(new BlockPos(t.x(), t.y(), t.z()));
            goal = null;
            scanCooldown = 0;
        }
        lastFailures = f;

        // keep the current goal while all of its blocks are still there, so the path is not thrown away
        if (goal instanceof Goal.Composite comp) {
            for (Goal g : comp.goals()) {
                if (g instanceof Goal.TwoBlocks t && !known.contains(new BlockPos(t.x(), t.y(), t.z()))) {
                    goal = null;
                    break;
                }
            }
        }
        if (goal == null) {
            List<Goal> goals = new ArrayList<>();
            for (BlockPos p : known) {
                if (blacklist.contains(p)) continue;
                goals.add(new Goal.TwoBlocks(p));
                if (goals.size() >= 16) break;
            }
            if (!goals.isEmpty()) goal = new Goal.Composite(goals);
        }
        if (goal != null) {
            state = "иду к руде (" + known.size() + " найдено)";
            return goal;
        }

        // nothing known: go to the best height and branch mine
        goal = null;
        Integer y = null;
        if (Settings.mineGoToY) for (Block b : targets) if (BEST_Y.containsKey(b)) { y = BEST_Y.get(b); break; }
        if (y == null) {
            if (branchGoal == null || branchGoal.isInGoal(feet)) {
                if (branchDir == null) branchDir = mc.player.getDirection();
                BlockPos t = feet.relative(branchDir, 48);
                branchGoal = new Goal.NearXZ(t.getX(), t.getZ(), 4);
            }
            state = "исследую в поисках руды";
            return branchGoal;
        }
        if (Math.abs(feet.getY() - y) > 1) {
            state = "спускаюсь на Y=" + y;
            branchGoal = null;
            return new Goal.YLevel(y);
        }
        if (branchGoal == null || branchGoal.isInGoal(feet) || bot.pathing.failures() >= 3) {
            if (branchDir == null || bot.pathing.failures() >= 3) {
                branchDir = branchDir == null ? mc.player.getDirection() : branchDir.getClockWise();
                if (branchDir.getAxis() == Direction.Axis.Y) branchDir = Direction.NORTH;
            }
            BlockPos t = new BlockPos(feet.getX(), y, feet.getZ()).relative(branchDir, 32);
            branchGoal = new Goal.Block(t);
        }
        state = "копаю ветку на Y=" + y;
        return branchGoal;
    }

    private void scan(Bot bot) {
        var level = bot.mc.level;
        List<BlockPos> found = find(level, bot.controls.feet(), targets, Settings.mineScanRadius, Settings.legitMine);
        known.clear();
        // nearest first; the "built by a player" check is not free, so only run it until the list is full
        for (BlockPos p : found) {
            if (known.size() >= 128) break;
            if (blacklist.contains(p)) continue;
            if (Settings.ignorePlaced && built.computeIfAbsent(p.asLong(),
                    k -> ru.baritoneru.PlacedBlocks.isBuilt(level, p, level.getBlockState(p)))) continue;
            known.add(p);
        }
    }

    /** All matching blocks in loaded chunks around {@code feet}, nearest first. */
    public static List<BlockPos> find(net.minecraft.world.level.Level level, BlockPos feet, Set<Block> targets, int radius, boolean legit) {
        int r = radius >> 4;
        int pcx = feet.getX() >> 4, pcz = feet.getZ() >> 4;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int cx = pcx - r; cx <= pcx + r; cx++) {
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                LevelChunk ch = (LevelChunk) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (ch == null) continue;
                LevelChunkSection[] secs = ch.getSections();
                for (int i = 0; i < secs.length; i++) {
                    LevelChunkSection s = secs[i];
                    if (s == null || s.hasOnlyAir()) continue;
                    if (!s.maybeHas(st -> targets.contains(st.getBlock()))) continue;
                    int baseY = ch.getSectionYFromSectionIndex(i) << 4;
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BlockState st = s.getBlockState(x, y, z);
                                if (!targets.contains(st.getBlock())) continue;
                                BlockPos p = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                                if (legit && !exposed(level, p, m)) continue;
                                found.add(p);
                            }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        return found;
    }

    private static boolean exposed(net.minecraft.world.level.Level level, BlockPos p, BlockPos.MutableBlockPos m) {
        for (Direction d : Direction.values()) {
            BlockState n = level.getBlockState(m.setWithOffset(p, d));
            if (n.isAir() || !n.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    @Override
    public String status() {
        return state + ", добыто " + mined + (wanted > 0 ? "/" + wanted : "");
    }
}
