package ru.baritoneru.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;
import ru.baritoneru.Settings;

import java.util.List;
import java.util.Set;

/**
 * Read-only view of the client world with the movement rules. One instance per thread:
 * it keeps a mutable pos and the last chunk to make lookups cheap.
 */
public final class World {
    public static final double INF = 1_000_000;

    public static final Set<Block> AVOID_BREAKING = Set.of(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL, Blocks.SPAWNER,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE, Blocks.BEACON,
            Blocks.END_PORTAL_FRAME, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.BUDDING_AMETHYST,
            Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE, Blocks.INFESTED_COBBLESTONE, Blocks.INFESTED_STONE_BRICKS);

    public final Level level;
    private final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
    private final int minY, maxY;
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private LevelChunk lastChunk;
    private final List<ItemStack> tools;
    private final boolean hasThrowaway;
    /** blocks that should never be broken by the pathfinder (e.g. target ores are fine, chests are not) */
    private final Set<Block> extraAvoid;

    public World(Level level, List<ItemStack> hotbarCopy, boolean hasThrowaway, Set<Block> extraAvoid) {
        this.level = level;
        this.minY = level.getMinY();
        this.maxY = level.getMaxY();
        this.tools = hotbarCopy;
        this.hasThrowaway = hasThrowaway;
        this.extraAvoid = extraAvoid;
        this.placed = ru.baritoneru.PlacedBlocks.current();
    }

    /** blocks players built; the pathfinder walks around them instead of breaking through */
    private final Set<Long> placed;
    private static final double BUILT_PENALTY = 400;

    public boolean hasThrowaway() { return hasThrowaway; }

    /** null = chunk not loaded */
    public BlockState get(int x, int y, int z) {
        if (y < minY) return Blocks.BEDROCK.defaultBlockState();
        if (y > maxY) return Blocks.AIR.defaultBlockState();
        int cx = x >> 4, cz = z >> 4;
        if (cx != lastCx || cz != lastCz) {
            lastChunk = (LevelChunk) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            lastCx = cx;
            lastCz = cz;
        }
        if (lastChunk == null) return null;
        return lastChunk.getBlockState(mut.set(x, y, z));
    }

    public BlockPos.MutableBlockPos pos(int x, int y, int z) { return mut.set(x, y, z); }

    public static boolean isWater(BlockState s) { return s.getFluidState().is(FluidTags.WATER); }

    public static boolean isLava(BlockState s) { return s.getFluidState().is(FluidTags.LAVA); }

    public static boolean isClimbable(BlockState s) {
        Block b = s.getBlock();
        return b instanceof LadderBlock || b instanceof VineBlock || b == Blocks.SCAFFOLDING
                || b == Blocks.TWISTING_VINES || b == Blocks.TWISTING_VINES_PLANT
                || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT;
    }

    public static boolean dangerous(BlockState s) {
        Block b = s.getBlock();
        return isLava(s) || b instanceof BaseFireBlock || b == Blocks.COBWEB || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.POWDER_SNOW || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK || b == Blocks.CAMPFIRE
                || b == Blocks.SOUL_CAMPFIRE || b == Blocks.WITHER_ROSE || b == Blocks.NETHER_PORTAL
                || b == Blocks.END_PORTAL || b == Blocks.END_GATEWAY || b == Blocks.POINTED_DRIPSTONE;
    }

    /** Player body can occupy this block. */
    public boolean passable(int x, int y, int z) {
        BlockState s = get(x, y, z);
        if (s == null || dangerous(s)) return false;
        if (isClimbable(s)) return true;
        if (isWater(s)) return s.getCollisionShape(level, mut).isEmpty();
        if (!s.getFluidState().isEmpty()) return false;
        return s.getCollisionShape(level, mut).isEmpty();
    }

    public boolean water(int x, int y, int z) {
        BlockState s = get(x, y, z);
        return s != null && isWater(s) && s.getCollisionShape(level, mut).isEmpty();
    }

    public boolean climbable(int x, int y, int z) {
        BlockState s = get(x, y, z);
        return s != null && isClimbable(s);
    }

    /** Player can stand on top of this block. */
    public boolean standable(int x, int y, int z) {
        BlockState s = get(x, y, z);
        if (s == null || s.isAir() || dangerous(s)) return false;
        VoxelShape shape = s.getCollisionShape(level, mut);
        if (shape.isEmpty()) return false;
        if (s.isFaceSturdy(level, mut, Direction.UP)) return true;
        double top = shape.max(Direction.Axis.Y);
        return top >= 0.8 && top <= 1.0 && shape.bounds().getXsize() >= 0.8 && shape.bounds().getZsize() >= 0.8;
    }

    /** Can a block be placed here (to bridge / pillar)? */
    public boolean replaceable(int x, int y, int z) {
        BlockState s = get(x, y, z);
        return s != null && !isLava(s) && (s.isAir() || s.canBeReplaced());
    }

    /** Ticks needed to clear this block so the player fits, 0 if already clear, INF if impossible. */
    public double clearCost(int x, int y, int z) {
        BlockState s = get(x, y, z);
        if (s == null) return INF;
        if (passable(x, y, z)) return 0;
        return breakCost(s, x, y, z);
    }

    public double breakCost(BlockState s, int x, int y, int z) {
        if (!Settings.allowBreak || s.isAir() || isLava(s)) return INF;
        if (!s.getFluidState().isEmpty() && s.getCollisionShape(level, pos(x, y, z)).isEmpty()) return INF;
        Block b = s.getBlock();
        if (AVOID_BREAKING.contains(b) || extraAvoid.contains(b) || b == Blocks.COBWEB) return INF;
        float hardness = s.getDestroySpeed(level, pos(x, y, z));
        if (hardness < 0) return INF;
        // never open a hole into lava, and don't break into water from above
        for (Direction d : Direction.values()) {
            BlockState n = get(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            if (n == null) return INF;
            if (isLava(n)) return INF;
        }
        double extra = 0;
        if (Settings.ignorePlaced && placed.contains(BlockPos.asLong(x, y, z))) extra += BUILT_PENALTY;
        BlockState above = get(x, y + 1, z);
        if (above != null) {
            if (isWater(above)) extra += 40;
            if (above.getBlock() instanceof FallingBlock) extra += 25;
        }
        return breakTicks(s, hardness) + Settings.blockBreakPenalty + extra;
    }

    public double breakTicks(BlockState s, float hardness) {
        if (hardness <= 0) return 1;
        double best = 0;
        for (ItemStack st : tools) {
            float speed = st.isEmpty() ? 1 : Math.max(1, st.getDestroySpeed(s));
            boolean harvest = !s.requiresCorrectToolForDrops() || (!st.isEmpty() && st.isCorrectToolForDrops(s));
            best = Math.max(best, speed / hardness / (harvest ? 30.0 : 100.0));
        }
        if (best <= 0) best = 1 / hardness / 100.0;
        if (best >= 1) return 1;
        return Math.ceil(1 / best);
    }

    public int minY() { return minY; }

    public int maxY() { return maxY; }
}
