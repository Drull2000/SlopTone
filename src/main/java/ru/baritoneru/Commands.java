package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import ru.baritoneru.path.Goal;
import ru.baritoneru.process.ExploreProcess;
import ru.baritoneru.process.FarmProcess;
import ru.baritoneru.process.FollowProcess;
import ru.baritoneru.process.GotoProcess;
import ru.baritoneru.process.MineProcess;
import ru.baritoneru.process.TunnelProcess;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Chat commands. Every command has English (Baritone-compatible) and Russian names. */
public final class Commands {
    private interface Handler {
        void run(Bot bot, List<String> args) throws CommandException;
    }

    private static final class CommandException extends Exception {
        CommandException(String m) { super(m); }
    }

    private record Cmd(String usage, String help, Handler handler) {}

    private static final Map<String, Cmd> BY_NAME = new HashMap<>();
    private static final List<Cmd> ORDER = new ArrayList<>();

    private static void reg(String usage, String help, Handler h, String... names) {
        Cmd c = new Cmd(usage, help, h);
        ORDER.add(c);
        for (String n : names) BY_NAME.put(n, c);
    }

    static {
        reg("иди <x y z | x z | y | блок | метка>", "идти к координатам, блоку или метке",
                Commands::cmdGoto, "goto", "go", "иди", "идти", "пойти", "путь", "к");
        reg("копай <блок...> [кол-во]", "искать и добывать блоки, напр. #копай алмазы 10",
                Commands::cmdMine, "mine", "копай", "копать", "добывай", "добыть", "майни", "шахта");
        reg("следуй [ник]", "следовать за игроком (без ника — за ближайшим)",
                Commands::cmdFollow, "follow", "следуй", "следовать", "за");
        reg("стоп", "остановить всё", (b, a) -> b.stop("Остановлено."),
                "stop", "cancel", "s", "стоп", "отмена", "хватит", "стой");
        reg("пауза", "приостановить", (b, a) -> {
            b.setPaused(true);
            Bot.msg("Пауза. §e#продолжить§r — продолжить.");
        }, "pause", "пауза");
        reg("продолжить", "снять с паузы", (b, a) -> {
            b.setPaused(false);
            Bot.msg("Продолжаю.");
        }, "resume", "unpause", "продолжить", "продолжи", "дальше");
        reg("исследуй [x z]", "исследовать мир по спирали",
                Commands::cmdExplore, "explore", "исследуй", "исследовать", "разведка");
        reg("ферма [радиус]", "собирать урожай и сажать семена",
                Commands::cmdFarm, "farm", "ферма", "фермер", "урожай");
        reg("тоннель [высота ширина длина]", "копать прямой тоннель туда, куда смотришь",
                Commands::cmdTunnel, "tunnel", "тоннель", "туннель");
        reg("наверх", "выбраться на поверхность", Commands::cmdSurface,
                "surface", "top", "наверх", "поверхность", "вверх");
        reg("туда [дистанция]", "идти вперёд по направлению взгляда", Commands::cmdThisway,
                "thisway", "forward", "туда", "вперед", "вперёд");
        reg("ось", "идти к ближайшей оси (x=0, z=0 или диагональ)", (b, a) ->
                b.start(new GotoProcess(new Goal.Axis(), "Ось")), "axis", "highway", "ось");
        reg("дом", "запомнить текущее место как дом", (b, a) -> {
            b.waypoints.save("дом", b.controls.feet());
            Bot.msg("Дом сохранён: " + b.controls.feet().toShortString());
        }, "sethome", "дом", "сохранитьдом");
        reg("домой", "идти домой", (b, a) -> gotoWaypoint(b, "дом"), "home", "домой");
        reg("назад", "вернуться к месту последней смерти", (b, a) -> gotoWaypoint(b, "смерть"),
                "back", "death", "назад", "смерть");
        reg("метка <сохранить|иди|список|удалить> [имя]", "метки (точки) на карте",
                Commands::cmdWaypoint, "waypoint", "waypoints", "wp", "метка", "метки", "точка", "точки");
        reg("настройка [имя] [значение]", "показать/изменить настройки",
                Commands::cmdSet, "set", "setting", "settings", "настройка", "настройки", "опция");
        reg("статус", "что сейчас делаю", (b, a) -> Bot.msg(b.status()), "status", "proc", "статус", "что");
        reg("помощь", "список команд", (b, a) -> help(), "help", "помощь", "справка", "команды", "?");
        reg("версия", "версия мода", (b, a) -> Bot.msg("SlopTone 1.0.4 by manstruk, Minecraft 26.3 (Forge)"), "version", "версия");
    }

    /** @return true when the message was a bot command (it is then not sent to the server). */
    public static boolean handleChat(String message) {
        String prefix = Settings.prefix;
        if (!message.startsWith(prefix)) return false;
        BaritoneRu.LOG.info("SlopTone command: {}", message);
        String body = message.substring(prefix.length()).trim();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;
        if (body.isEmpty()) {
            help();
            return true;
        }
        // commas separate words too: "#копай birch_log, birch_wood 3", "#иди 100, 64, -20"
        List<String> parts = new ArrayList<>(Arrays.asList(body.split("[\\s,]+")));
        String name = parts.remove(0).toLowerCase(Locale.ROOT).replace('ё', 'е');
        Cmd cmd = BY_NAME.get(name);
        if (cmd == null) {
            Bot.msg("§cНеизвестная команда§r «" + name + "». Напиши §e" + prefix + "помощь§r.");
            return true;
        }
        try {
            cmd.handler().run(Bot.get(), parts);
        } catch (CommandException e) {
            Bot.msg("§c" + e.getMessage() + "§r\nИспользование: §e" + prefix + cmd.usage());
        } catch (RuntimeException e) {
            Bot.msg("§cОшибка:§r " + e);
        }
        return true;
    }

    private static void help() {
        String p = Settings.prefix;
        StringBuilder sb = new StringBuilder("§bКоманды§r (можно на русском или английском):");
        for (Cmd c : ORDER) sb.append("\n§e").append(p).append(c.usage()).append("§r — ").append(c.help());
        Bot.msg(sb.toString());
    }

    // ------------------------------------------------------------------ helpers

    private static int coord(String s, double base) throws CommandException {
        try {
            if (s.startsWith("~")) return Mth.floor(base + (s.length() > 1 ? Double.parseDouble(s.substring(1)) : 0));
            return Mth.floor(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            throw new CommandException("Не число: " + s);
        }
    }

    private static boolean isNum(String s) {
        return s.matches("~|~?-?\\d+(\\.\\d+)?");
    }

    private static int intArg(List<String> a, int i, int def) throws CommandException {
        if (a.size() <= i) return def;
        try {
            return Integer.parseInt(a.get(i));
        } catch (NumberFormatException e) {
            throw new CommandException("Не число: " + a.get(i));
        }
    }

    private static void gotoWaypoint(Bot b, String name) throws CommandException {
        BlockPos p = b.waypoints.get(name);
        if (p == null) throw new CommandException("Метка «" + name + "» не найдена в этом мире/измерении.");
        b.start(new GotoProcess(new Goal.Block(p), "Путь к «" + name + "»"));
        Bot.msg("Иду к метке «" + name + "»: " + p.toShortString());
    }

    // ------------------------------------------------------------------ commands

    private static void cmdGoto(Bot b, List<String> a) throws CommandException {
        LocalPlayer p = b.mc.player;
        Goal g;
        String label = "Путь";
        if (a.size() == 3 && a.stream().allMatch(Commands::isNum)) {
            g = new Goal.Block(coord(a.get(0), p.getX()), coord(a.get(1), p.getY()), coord(a.get(2), p.getZ()));
        } else if (a.size() == 2 && a.stream().allMatch(Commands::isNum)) {
            g = new Goal.XZ(coord(a.get(0), p.getX()), coord(a.get(1), p.getZ()));
        } else if (a.size() == 1 && isNum(a.get(0))) {
            g = new Goal.YLevel(coord(a.get(0), p.getY()));
        } else if (a.size() >= 1) {
            String name = String.join("_", a);
            if (b.waypoints.get(name) != null) {
                gotoWaypoint(b, name);
                return;
            }
            Set<Block> blocks = BlockNames.resolve(name);
            if (blocks == null) throw new CommandException("Не знаю такой блок или метку: " + name);
            List<BlockPos> found = MineProcess.find(b.mc.level, b.controls.feet(), blocks, Settings.mineScanRadius, false);
            if (found.isEmpty()) throw new CommandException("Рядом нет: " + BlockNames.describe(blocks));
            List<Goal> goals = new ArrayList<>();
            for (BlockPos f : found.subList(0, Math.min(8, found.size()))) goals.add(new Goal.Adjacent(f));
            g = new Goal.Composite(goals);
            label = "Путь к " + BlockNames.describe(blocks);
        } else {
            throw new CommandException("Куда идти?");
        }
        b.start(new GotoProcess(g, label));
        Bot.msg("Иду: " + g.describe());
    }

    private static void cmdMine(Bot b, List<String> a) throws CommandException {
        if (a.isEmpty()) throw new CommandException("Что копать?");
        int count = 0;
        List<String> words = new ArrayList<>(a);
        if (words.size() > 1 && words.get(words.size() - 1).matches("\\d+")) count = Integer.parseInt(words.remove(words.size() - 1));
        Set<Block> targets = new LinkedHashSet<>();
        // allow multi-word names like "алмазная руда"
        Set<Block> joined = BlockNames.resolve(String.join("_", words));
        if (joined != null) targets.addAll(joined);
        else for (String w : words) {
            Set<Block> s = BlockNames.resolve(w);
            if (s == null) throw new CommandException("Не знаю такой блок: " + w);
            targets.addAll(s);
        }
        String label = BlockNames.describe(targets);
        b.start(new MineProcess(targets, label, count));
        Bot.msg("Копаю: §e" + label + "§r" + (count > 0 ? ", нужно " + count : ""));
    }

    private static void cmdFollow(Bot b, List<String> a) {
        String name = a.isEmpty() ? null : a.get(0);
        b.start(new FollowProcess(name));
        Bot.msg(name == null ? "Следую за ближайшим игроком." : "Следую за " + name + ".");
    }

    private static void cmdExplore(Bot b, List<String> a) throws CommandException {
        LocalPlayer p = b.mc.player;
        int x = a.size() >= 2 ? coord(a.get(0), p.getX()) : p.getBlockX();
        int z = a.size() >= 2 ? coord(a.get(1), p.getZ()) : p.getBlockZ();
        b.start(new ExploreProcess(x, z));
        Bot.msg("Исследую мир от " + x + " " + z + ".");
    }

    private static void cmdFarm(Bot b, List<String> a) throws CommandException {
        int r = intArg(a, 0, Settings.farmRadius);
        b.start(new FarmProcess(b.controls.feet(), r));
        Bot.msg("Фермерство в радиусе " + r + ".");
    }

    private static void cmdTunnel(Bot b, List<String> a) throws CommandException {
        int h = intArg(a, 0, 2), w = intArg(a, 1, 1), len = intArg(a, 2, 0);
        if (h < 2 || h > 8 || w < 1 || w > 7) throw new CommandException("Высота 2–8, ширина 1–7.");
        Direction d = b.mc.player.getDirection();
        b.start(new TunnelProcess(b.controls.feet(), d, h, w, len));
        Bot.msg("Копаю тоннель " + w + "x" + h + " на " + d.getName() + (len > 0 ? ", длина " + len : "") + ".");
    }

    private static void cmdSurface(Bot b, List<String> a) {
        BlockPos f = b.controls.feet();
        int y = b.mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, f.getX(), f.getZ());
        if (y <= f.getY()) {
            Bot.msg("Ты уже на поверхности.");
            return;
        }
        b.start(new GotoProcess(new Goal.YLevel(y), "Наверх"));
        Bot.msg("Выбираюсь наверх (Y=" + y + ").");
    }

    private static void cmdThisway(Bot b, List<String> a) throws CommandException {
        int dist = intArg(a, 0, 100);
        LocalPlayer p = b.mc.player;
        double yaw = Math.toRadians(p.getYRot());
        int x = Mth.floor(p.getX() - Math.sin(yaw) * dist);
        int z = Mth.floor(p.getZ() + Math.cos(yaw) * dist);
        b.start(new GotoProcess(new Goal.XZ(x, z), "Вперёд"));
        Bot.msg("Иду вперёд на " + dist + " блоков: X " + x + " Z " + z);
    }

    private static void cmdWaypoint(Bot b, List<String> a) throws CommandException {
        String sub = a.isEmpty() ? "список" : a.get(0).toLowerCase(Locale.ROOT);
        String name = a.size() > 1 ? String.join("_", a.subList(1, a.size())) : null;
        switch (sub) {
            case "save", "s", "add", "сохранить", "добавить", "запомнить", "+" -> {
                if (name == null) throw new CommandException("Укажи имя метки.");
                BlockPos f = b.controls.feet();
                b.waypoints.save(name, f);
                Bot.msg("Метка «" + name + "» сохранена: " + f.toShortString());
            }
            case "goto", "go", "иди", "идти", "к" -> {
                if (name == null) throw new CommandException("Укажи имя метки.");
                gotoWaypoint(b, name);
            }
            case "list", "l", "список", "все" -> {
                var all = b.waypoints.list();
                if (all.isEmpty()) {
                    Bot.msg("Меток нет. §e" + Settings.prefix + "метка сохранить <имя>");
                    return;
                }
                StringBuilder sb = new StringBuilder("Метки:");
                all.forEach((n, p) -> sb.append("\n§e").append(n).append("§r — ").append(p.toShortString()));
                Bot.msg(sb.toString());
            }
            case "del", "delete", "remove", "удалить", "убрать", "-" -> {
                if (name == null) throw new CommandException("Укажи имя метки.");
                Bot.msg(b.waypoints.remove(name) ? "Метка «" + name + "» удалена." : "Нет такой метки.");
            }
            default -> {
                // "#метка база" = go to it
                gotoWaypoint(b, String.join("_", a));
            }
        }
    }

    private static void cmdSet(Bot b, List<String> a) throws CommandException {
        if (a.isEmpty()) {
            StringBuilder sb = new StringBuilder("Настройки (имя / русское имя = значение):");
            for (var e : Settings.ENTRIES.values()) {
                String ru = Settings.ALIASES.entrySet().stream().filter(x -> x.getValue().equals(e.name().toLowerCase(Locale.ROOT)))
                        .map(Map.Entry::getKey).findFirst().orElse("");
                sb.append("\n§e").append(e.name()).append(ru.isEmpty() ? "" : " / " + ru).append("§r = §a").append(e.get().get())
                        .append("§r — ").append(e.description());
            }
            Bot.msg(sb.toString());
            return;
        }
        Settings.Entry e = Settings.find(a.get(0));
        if (e == null) throw new CommandException("Нет такой настройки: " + a.get(0));
        if (a.size() == 1) {
            Object v = e.get().get();
            if (v instanceof Boolean bool) {
                e.set().apply(String.valueOf(!bool)); // "#настройка бег" toggles
            } else {
                Bot.msg(e.name() + " = " + v + " — " + e.description());
                return;
            }
        } else {
            String err = e.set().apply(a.get(1));
            if (err != null) throw new CommandException(err);
        }
        Settings.save();
        Bot.msg(e.name() + " = §a" + e.get().get());
    }
}
