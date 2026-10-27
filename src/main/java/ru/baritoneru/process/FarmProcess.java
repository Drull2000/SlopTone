package ru.baritoneru.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import ru.baritoneru.Bot;
import ru.baritoneru.Settings;
import ru.baritoneru.path.Goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Harvests ripe crops, pumpkins, melons, sugar cane and replants seeds on empty farmland. */
public final class FarmProcess extends BotProcess {
    private static final Set<Item> SEEDS = Set.of(Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS);

    private final BlockPos center;
    private final int radius;
    private List<BlockPos> harvest = new ArrayList<>();
    private List<BlockPos> plant = new ArrayList<>();
    private int scanCooldown;
    private int harvested, planted, placeCooldown;
    private String state = "ищу урожай";

    public FarmProcess(BlockPos center, int radius) {
        this.center = center;
        this.radius = radius > 0 ? radius : Settings.farmRadius;
    }

    @Override
    public String name() { return "Ферма"; }

    @Override
    public Goal tick(Bot bot) {
        var c = bot.controls;
        var level = bot.mc.level;
        manual = false;
        // keep the pathfinder from trampling through crops
        bot.pathing.avoid = Set.of(Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.NETHER_WART,
                Blocks.FARMLAND, Blocks.MELON_STEM, Blocks.PUMPKIN_STEM, Blocks.ATTACHED_MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM);
        if (placeCooldown > 0) placeCooldown--;
        if (--scanCooldown <= 0) {
            scanCooldown = 20;
            scan(level, c.feet());
        }
        if (Settings.stopWhenInventoryFull && bot.mc.player.getInventory().getFreeSlot() < 0) {
            finish("§eИнвентарь полон§r. Собрано " + harvested + ", посажено " + planted + ".");
            return null;
        }

        BlockPos feet = c.feet();
        harvest.removeIf(p -> {
            if (harvestable(level.getBlockState(p), level, p)) return false;
            if (level.getBlockState(p).isAir() && p.distSqr(feet) < 36) harvested++;
            return true;
        });
        plant.removeIf(p -> !plantable(level, p));

        for (BlockPos p : harvest) {
            if (c.canReach(p) && c.visible(p)) {
                manual = true;
                state = "собираю";
                c.breakBlock(p);
                return null;
            }
        }
        for (BlockPos p : plant) {
            if (!c.canReach(p)) continue;
            boolean soul = level.getBlockState(p).is(Blocks.SOUL_SAND);
            if (!c.hasItem(s -> soul ? s.is(Items.NETHER_WART) : SEEDS.contains(s.getItem()))) continue;
            manual = true;
            state = "сажаю";
            if (placeCooldown == 0) {
                placeCooldown = 4;
                if (c.placeAgainst(p, Direction.UP, s -> soul ? s.is(Items.NETHER_WART) : SEEDS.contains(s.getItem()), false)) planted++;
            }
            return null;
        }

        Goal pickup = pickupGoal(bot, 10);
        if (pickup != null) {
            state = "подбираю урожай";
            return pickup;
        }
        List<Goal> goals = new ArrayList<>();
        for (BlockPos p : harvest) {
            goals.add(new Goal.Near(p, 2));
            if (goals.size() >= 8) break;
        }
        if (goals.isEmpty()) {
            for (BlockPos p : plant) {
                if (!c.hasItem(s -> SEEDS.contains(s.getItem()) || s.is(Items.NETHER_WART))) break;
                goals.add(new Goal.Near(p.above(), 2));
                if (goals.size() >= 8) break;
            }
        }
        if (goals.isEmpty()) {
            state = "жду, пока вырастет";
            return null;
        }
        state = "иду к грядкам";
        return new Goal.Composite(goals);
    }

    private void scan(Level level, BlockPos feet) {
        List<BlockPos> h = new ArrayList<>(), pl = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++)
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++)
                for (int y = center.getY() - 6; y <= center.getY() + 6; y++) {
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) continue;
                    BlockState s = level.getBlockState(m);
                    if (s.isAir()) continue;
                    if (harvestable(s, level, m)) h.add(m.immutable());
                    else if (plantable(level, m)) pl.add(m.immutable());
                }
        Comparator<BlockPos> byDist = Comparator.comparingDouble(p -> p.distSqr(feet));
        h.sort(byDist);
        pl.sort(byDist);
        harvest = h;
        plant = pl;
    }

    private static boolean harvestable(BlockState s, Level level, BlockPos p) {
        Block b = s.getBlock();
        if (b instanceof CropBlock crop) return crop.isMaxAge(s);
        if (b instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= 3;
        if (b == Blocks.PUMPKIN || b == Blocks.MELON) {
            // only grown from a stem, so we don't eat decorations
            for (Direction d : Direction.Plane.HORIZONTAL) {
                Block n = level.getBlockState(p.relative(d)).getBlock();
                if (n == Blocks.ATTACHED_MELON_STEM || n == Blocks.ATTACHED_PUMPKIN_STEM) return true;
            }
            return false;
        }
        if (b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO) return level.getBlockState(p.below()).is(b) && !level.getBlockState(p.below(2)).is(b);
        return false;
    }

    private static boolean plantable(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (!s.is(Blocks.FARMLAND) && !s.is(Blocks.SOUL_SAND)) return false;
        return level.getBlockState(p.above()).isAir();
    }

    @Override
    public String status() { return state + ", собрано " + harvested + ", посажено " + planted; }

    @SuppressWarnings("unused")
    private static boolean any(ItemStack s) { return !s.isEmpty(); }
}
