package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.level.BlockEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers blocks placed by players, so the bot does not mine people's builds.
 * Minecraft itself does not store who placed a block, so this is collected while the mod runs:
 * - in singleplayer / when hosting LAN, the built-in server reports every placement by any player (exact);
 * - on remote servers, only the local player's own placements (and the bot's) are seen.
 * Logs get an extra check that works everywhere: a tree trunk always has natural (non-persistent) leaves nearby.
 */
public final class PlacedBlocks {
    /** who placed a block and when (epoch millis); who is "?" for entries saved by older versions */
    public record Info(String who, long time) {}

    private static final Map<String, Map<Long, Info>> BY_WORLD = new ConcurrentHashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static volatile boolean dirty;
    private static boolean loaded;
    private static int saveTimer;

    private record Pending(BlockPos[] candidates, Block block, int expires) {}

    public static void register() {
        BlockEvent.EntityPlaceEvent.BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getEntity() instanceof Player p && e.getLevel() instanceof ServerLevel l)
                add(serverKey(l), e.getPos(), p.getName().getString());
        });
        BlockEvent.BreakEvent.BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getLevel() instanceof ServerLevel l) remove(serverKey(l), e.getPos());
        });
        InputEvent.InteractionKeyMappingTriggered.UseItem.BUS.addListener((InputEvent.InteractionKeyMappingTriggered.UseItem e) -> {
            onUse(e.getHand());
        });
    }

    private static String serverKey(ServerLevel l) {
        return Waypoints.worldKey("sp:" + l.getServer().getWorldData().getLevelName(), l.dimension().toString());
    }

    private static Map<Long, Info> map(String key) {
        return BY_WORLD.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
    }

    private static void add(String key, BlockPos pos, String who) {
        map(key).put(pos.asLong(), new Info(who, System.currentTimeMillis()));
        dirty = true;
    }

    private static void remove(String key, BlockPos pos) {
        Map<Long, Info> m = BY_WORLD.get(key);
        if (m != null && m.remove(pos.asLong()) != null) dirty = true;
    }

    /** Placed blocks of the world the client is in now. Safe to read from the pathfinder thread. */
    public static Set<Long> current() {
        return map(Waypoints.world()).keySet();
    }

    public static boolean isPlaced(BlockPos pos) {
        return current().contains(pos.asLong());
    }

    /** Who placed the block, or null if nobody is known to have placed it. */
    public static Info info(BlockPos pos) {
        return map(Waypoints.world()).get(pos.asLong());
    }

    private static void markPlaced(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        add(Waypoints.world(), pos, mc.player == null ? "?" : mc.player.getName().getString());
    }

    /** Is this a log that has no natural leaves around it (i.e. most likely part of a build)? */
    public static boolean isLogWithoutTree(Level level, BlockPos pos, BlockState state) {
        return isLog(state.getBlock()) && !hasNaturalLeavesNear(level, pos);
    }

    public static boolean isLogBlock(BlockState state) {
        return isLog(state.getBlock());
    }

    /** positions the bot placed itself; the built-in server reports them a moment later, so keep removing them */
    private static final Map<Long, Integer> FORGET = new ConcurrentHashMap<>();

    public static void forgetSoon(BlockPos pos) {
        FORGET.put(pos.asLong(), 40);
    }

    /** Should the bot leave this block alone because someone built it? */
    public static boolean isBuilt(Level level, BlockPos pos, BlockState state) {
        if (isPlaced(pos)) return true;
        return isLog(state.getBlock()) && !hasNaturalLeavesNear(level, pos);
    }

    private static boolean isLog(Block b) {
        String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
        return !path.startsWith("stripped_") && (path.endsWith("_log") || path.endsWith("_wood"));
    }

    /** Tree trunks always have leaves that the game generated (PERSISTENT=false) around their top. */
    private static boolean hasNaturalLeavesNear(Level level, BlockPos log) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 14; dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockState s = level.getBlockState(m.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz));
                    if (s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) return true;
                }
        return false;
    }

    // ---------------------------------------------------------------- local player's own placements

    private static void onUse(net.minecraft.world.InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.hitResult instanceof BlockHitResult hit)) return;
        ItemStack stack = mc.player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem bi)) return;
        BlockPos p = hit.getBlockPos();
        PENDING.add(new Pending(new BlockPos[]{p, p.relative(hit.getDirection())}, bi.getBlock(), 6));
    }

    // ---------------------------------------------------------------- other players (works on any server)

    /** a spot another player is looking at, and what was there before */
    private record Watch(String who, int playerId, BlockState before, long expires) {}

    private static final Map<Long, Watch> WATCH = new java.util.HashMap<>();
    /** solid blocks other players are looking at, to notice them breaking it */
    private static final Map<Long, Watch> BREAKS = new java.util.HashMap<>();
    private static final Map<Integer, Long> LAST_SWING = new java.util.HashMap<>();
    private static long clientTicks;

    /**
     * The client never learns who placed a block, but it does see other players swing their arm and the block
     * appear where they were looking. Watch the spots players look at; when an empty spot gets a block within a
     * few ticks of that player swinging, it was them.
     */
    private static void observeOthers(Minecraft mc) {
        clientTicks++;
        for (var pl : mc.level.players()) {
            if (pl == mc.player || pl.distanceToSqr(mc.player) > 96 * 96) continue;
            if (pl.isSwinging()) LAST_SWING.put(pl.getId(), clientTicks);
            if (!(pl.pick(pl.blockInteractionRange(), 1.0f, false) instanceof BlockHitResult hit)
                    || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
            String name = pl.getName().getString();
            BlockState target = mc.level.getBlockState(hit.getBlockPos());
            if (!target.canBeReplaced()) BREAKS.put(hit.getBlockPos().asLong(), new Watch(name, pl.getId(), target, clientTicks + 8));
            for (BlockPos c : new BlockPos[]{hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection())}) {
                BlockState s = mc.level.getBlockState(c);
                if (!s.canBeReplaced()) continue; // only empty / replaceable spots can receive a placed block
                WATCH.put(c.asLong(), new Watch(name, pl.getId(), s, clientTicks + 8));
            }
        }
        for (var it = WATCH.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Watch w = e.getValue();
            BlockState now = mc.level.getBlockState(BlockPos.of(e.getKey()));
            if (now != w.before() && !now.canBeReplaced() && now.getFluidState().isEmpty()) {
                Long swung = LAST_SWING.get(w.playerId());
                if (swung != null && clientTicks - swung <= 8) {
                    BlockPos p = BlockPos.of(e.getKey());
                    add(Waypoints.world(), p, w.who());
                    LogBook.add(LogBook.Type.BLOCKS, w.who() + " поставил " + now.getBlock().getName().getString() + " на " + p.toShortString());
                }
                it.remove();
            } else if (clientTicks > w.expires()) {
                it.remove();
            }
        }
        for (var it = BREAKS.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Watch w = e.getValue();
            BlockPos p = BlockPos.of(e.getKey());
            BlockState now = mc.level.getBlockState(p);
            if (now.isAir() && !w.before().isAir()) {
                Long swung = LAST_SWING.get(w.playerId());
                if (swung != null && clientTicks - swung <= 8) {
                    remove(Waypoints.world(), p);
                    LogBook.add(LogBook.Type.BLOCKS, w.who() + " сломал " + w.before().getBlock().getName().getString() + " на " + p.toShortString());
                }
                it.remove();
            } else if (clientTicks > w.expires()) {
                it.remove();
            }
        }
        if (clientTicks % 1200 == 0) LAST_SWING.values().removeIf(t -> clientTicks - t > 1200);
    }

    /** Blocks within radius of center with known placers, optionally only one player's. */
    public static Map<BlockPos, Info> near(BlockPos center, int radius, String player) {
        Map<BlockPos, Info> out = new java.util.LinkedHashMap<>();
        long r2 = (long) radius * radius;
        for (var e : map(Waypoints.world()).entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            if (p.distSqr(center) > r2) continue;
            if (player != null && !e.getValue().who().equalsIgnoreCase(player)) continue;
            out.put(p, e.getValue());
        }
        return out;
    }

    /** Client tick: confirm pending placements, watch other players, load and save the file. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!loaded) {
            loaded = true;
            load();
        }
        if (mc.level != null && mc.player != null) observeOthers(mc);
        if (mc.level != null && !PENDING.isEmpty()) {
            List<Pending> next = new ArrayList<>();
            for (Pending p : PENDING) {
                boolean found = false;
                for (BlockPos c : p.candidates()) {
                    if (mc.level.getBlockState(c).is(p.block())) {
                        markPlaced(c);
                        found = true;
                        break;
                    }
                }
                if (!found && p.expires() > 1) next.add(new Pending(p.candidates(), p.block(), p.expires() - 1));
            }
            PENDING.clear();
            PENDING.addAll(next);
        }
        if (!FORGET.isEmpty() && mc.level != null) {
            Set<Long> cur = current();
            FORGET.replaceAll((pos, ticks) -> {
                if (cur.remove(pos)) dirty = true;
                return ticks - 1;
            });
            FORGET.values().removeIf(t -> t <= 0);
        }
        if (dirty && ++saveTimer >= 200) {
            saveTimer = 0;
            save();
        }
    }

    private static Path file() {
        return Settings.dir().resolve("placed.txt");
    }

    private static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.lastIndexOf('|');
                if (i <= 0) continue;
                Map<Long, Info> m = map(line.substring(0, i));
                // entry = pos[:who:time]; 1.0.4 saved only the position
                for (String n : line.substring(i + 1).split(",")) {
                    if (n.isBlank()) continue;
                    String[] p = n.trim().split(":", 3);
                    String who = p.length > 1 ? p[1] : "?";
                    long time = p.length > 2 ? Long.parseLong(p[2]) : 0;
                    m.put(Long.parseLong(p[0]), new Info(who, time));
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        dirty = false;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (var e : BY_WORLD.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                StringBuilder sb = new StringBuilder(e.getKey()).append('|');
                for (Iterator<Map.Entry<Long, Info>> it = e.getValue().entrySet().iterator(); it.hasNext(); ) {
                    var en = it.next();
                    // player names are [A-Za-z0-9_], so ':' and ',' are safe separators
                    sb.append(en.getKey()).append(':').append(en.getValue().who().replaceAll("[:,|]", "_"))
                            .append(':').append(en.getValue().time());
                    if (it.hasNext()) sb.append(',');
                }
                lines.add(sb.toString());
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
