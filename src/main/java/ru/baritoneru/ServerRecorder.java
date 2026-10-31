package ru.baritoneru;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of SlopTone (dedicated server, LAN host or singleplayer): records who placed every block
 * and answers "/sloptone who x y z" and "/sloptone near [radius]". Clients with SlopTone ask it automatically.
 * Must not touch any client-only class: it also runs on dedicated servers.
 */
public final class ServerRecorder {
    public record Info(String who, long time) {}

    private static final Map<String, Map<Long, Info>> DATA = new ConcurrentHashMap<>();
    private static volatile boolean dirty, loaded;
    private static int saveTimer;

    public static void register() {
        BlockEvent.EntityPlaceEvent.BUS.addListener((BlockEvent.EntityPlaceEvent e) -> {
            if (e.getEntity() instanceof Player p && e.getLevel() instanceof ServerLevel l) {
                map(l).put(e.getPos().asLong(), new Info(p.getName().getString(), System.currentTimeMillis()));
                dirty = true;
            }
        });
        BlockEvent.BreakEvent.BUS.addListener((BlockEvent.BreakEvent e) -> {
            if (e.getLevel() instanceof ServerLevel l && map(l).remove(e.getPos().asLong()) != null) dirty = true;
        });
        // audit log: every command anyone runs (players, console, command blocks)
        CommandEvent.BUS.addListener((CommandEvent e) -> {
            var parse = e.getParseResults();
            String cmd = parse.getReader().getString();
            if (cmd.startsWith("sloptone log")) return;
            String who = parse.getContext().getSource().getTextName();
            logCommand(who, cmd);
        });
        RegisterCommandsEvent.BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                Commands.literal("sloptone")
                        .then(Commands.literal("log")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(c -> log(c, 30))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                        .executes(c -> log(c, IntegerArgumentType.getInteger(c, "count")))))
                        .then(Commands.literal("who")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ServerRecorder::who)))
                        .then(Commands.literal("near")
                                .executes(c -> near(c, 32, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius"), null))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(c -> near(c, IntegerArgumentType.getInteger(c, "radius"),
                                                        StringArgumentType.getString(c, "player"))))))));
        TickEvent.ServerTickEvent.Post.BUS.addListener((TickEvent.ServerTickEvent.Post e) -> {
            if (dirty && ++saveTimer >= 600) {
                saveTimer = 0;
                save();
            }
        });
        ServerStoppingEvent.BUS.addListener((ServerStoppingEvent e) -> {
            if (dirty) save();
            DATA.clear();
            loaded = false;
        });
    }

    private static String key(ServerLevel l) {
        return l.getServer().getWorldData().getLevelName() + "/" + l.dimension().toString();
    }

    private static Map<Long, Info> map(ServerLevel l) {
        if (!loaded) {
            loaded = true;
            load();
        }
        return DATA.computeIfAbsent(key(l), k -> new ConcurrentHashMap<>());
    }

    private static int who(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
        ServerLevel level = src.getLevel();
        String block = level.getBlockState(pos).getBlock().getName().getString();
        Info info = map(level).get(pos.asLong());
        String text = info == null
                ? "§3[§bSlopTone§3]§r Сервер: у " + block + " на " + pos.toShortString() + " нет записи (природный или поставлен до установки мода на сервер)."
                : "§3[§bSlopTone§3]§r Сервер: " + block + " на " + pos.toShortString() + " поставил §a" + info.who() + "§r, " + ago(info.time()) + ".";
        src.sendSuccess(() -> Component.literal(text), false);
        return info == null ? 0 : 1;
    }

    private static int near(CommandContext<CommandSourceStack> c, int radius, String player) {
        CommandSourceStack src = c.getSource();
        BlockPos center = BlockPos.containing(src.getPosition());
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        long r2 = (long) radius * radius;
        for (var e : map(src.getLevel()).entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            if (p.distSqr(center) > r2) continue;
            if (player != null && !e.getValue().who().equalsIgnoreCase(player)) continue;
            counts.merge(e.getValue().who(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("§3[§bSlopTone§3]§r Сервер, блоки игроков в радиусе " + radius + ": ");
        if (counts.isEmpty()) sb.append("нет записей.");
        else {
            List<String> parts = new ArrayList<>();
            counts.forEach((n, k) -> parts.add("§a" + n + "§r — " + k));
            sb.append(String.join(", ", parts));
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return counts.size();
    }

    // ---------------------------------------------------------------- command audit log (ops only)

    private static final java.util.ArrayDeque<String> COMMANDS = new java.util.ArrayDeque<>();
    private static final java.time.format.DateTimeFormatter TS = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

    private static synchronized void logCommand(String who, String cmd) {
        String line = "[" + java.time.LocalDateTime.now().format(TS) + "] " + who + ": /" + cmd;
        COMMANDS.addLast(line);
        while (COMMANDS.size() > 1000) COMMANDS.removeFirst();
        try {
            Path f = Settings.dir().resolve("server-commands.log");
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static int log(CommandContext<CommandSourceStack> c, int count) {
        List<String> lines;
        synchronized (ServerRecorder.class) {
            lines = new ArrayList<>(COMMANDS);
        }
        CommandSourceStack src = c.getSource();
        if (lines.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§3[§bSlopTone§3]§r Сервер: журнал команд пуст."), false);
            return 0;
        }
        List<String> last = lines.subList(Math.max(0, lines.size() - count), lines.size());
        src.sendSuccess(() -> Component.literal("§3[§bSlopTone§3]§r Сервер: последние команды (" + last.size() + "):"), false);
        for (String l : last) src.sendSuccess(() -> Component.literal("§7[SlopTone] Сервер §r" + l), false);
        return last.size();
    }

    static String ago(long time) {
        if (time <= 0) return "давно";
        long s = Math.max(0, (System.currentTimeMillis() - time) / 1000);
        if (s < 60) return "только что";
        if (s < 3600) return (s / 60) + " мин назад";
        if (s < 86400) return (s / 3600) + " ч назад";
        return (s / 86400) + " дн назад";
    }

    private static Path file() {
        return Settings.dir().resolve("server-placed.txt");
    }

    private static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.lastIndexOf('|');
                if (i <= 0) continue;
                Map<Long, Info> m = DATA.computeIfAbsent(line.substring(0, i), k -> new ConcurrentHashMap<>());
                for (String n : line.substring(i + 1).split(",")) {
                    String[] p = n.trim().split(":", 3);
                    if (p.length == 3) m.put(Long.parseLong(p[0]), new Info(p[1], Long.parseLong(p[2])));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void save() {
        dirty = false;
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (var e : DATA.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                StringBuilder sb = new StringBuilder(e.getKey().replace('|', '_')).append('|');
                boolean first = true;
                for (var en : e.getValue().entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(en.getKey()).append(':').append(en.getValue().who().replaceAll("[:,|]", "_"))
                            .append(':').append(en.getValue().time());
                }
                lines.add(sb.toString());
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
