package ru.baritoneru;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** All tunables. Values are plain static fields so the pathfinder thread can read them cheaply. */
public final class Settings {
    public static volatile boolean allowBreak = true;
    public static volatile boolean allowPlace = true;
    public static volatile boolean allowSprint = true;
    public static volatile boolean allowDownward = true;
    public static volatile int maxFallHeight = 3;
    public static volatile boolean autoTool = true;
    public static volatile boolean autoEat = true;
    public static volatile int autoEatHunger = 14;
    public static volatile boolean renderPath = true;
    public static volatile boolean smoothLook = true;
    public static volatile int mineScanRadius = 64;
    public static volatile boolean mineGoToY = true;
    public static volatile boolean legitMine = false;
    public static volatile boolean ignorePlaced = true;
    public static volatile boolean stopWhenInventoryFull = true;
    public static volatile int followRadius = 3;
    public static volatile int farmRadius = 32;
    public static volatile int blockPlacementPenalty = 20;
    public static volatile int blockBreakPenalty = 2;
    public static volatile int planTimeoutMs = 2000;
    public static volatile boolean protectTools = true;
    public static volatile String prefix = "#";

    public static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    /** russian alias -> english setting name */
    public static final Map<String, String> ALIASES = new LinkedHashMap<>();

    public record Entry(String name, String description, java.util.function.Supplier<Object> get,
                        java.util.function.Function<String, String> set) {}

    static {
        bool("allowBreak", "ломать блоки на пути", "ломать", () -> allowBreak, v -> allowBreak = v);
        bool("allowPlace", "ставить блоки (мосты, столбы)", "ставить", () -> allowPlace, v -> allowPlace = v);
        bool("allowSprint", "бегать", "бег", () -> allowSprint, v -> allowSprint = v);
        bool("allowDownward", "копать прямо вниз", "вниз", () -> allowDownward, v -> allowDownward = v);
        integer("maxFallHeight", "макс. высота падения без воды", "падение", 1, 20, () -> maxFallHeight, v -> maxFallHeight = v);
        bool("autoTool", "автовыбор лучшего инструмента", "инструмент", () -> autoTool, v -> autoTool = v);
        bool("autoEat", "автоматически есть", "еда", () -> autoEat, v -> autoEat = v);
        integer("autoEatHunger", "есть, когда голод ниже (0-20)", "голод", 1, 19, () -> autoEatHunger, v -> autoEatHunger = v);
        bool("renderPath", "показывать путь частицами", "показ", () -> renderPath, v -> renderPath = v);
        bool("smoothLook", "плавный поворот камеры при ходьбе", "плавно", () -> smoothLook, v -> smoothLook = v);
        integer("mineScanRadius", "радиус поиска руды", "радиус", 16, 160, () -> mineScanRadius, v -> mineScanRadius = v);
        bool("mineGoToY", "идти на лучшую высоту для руды и копать ветку", "высота", () -> mineGoToY, v -> mineGoToY = v);
        bool("legitMine", "копать только видимую руду (без x-ray)", "честно", () -> legitMine, v -> legitMine = v);
        bool("ignorePlaced", "не копать и не ломать постройки игроков", "постройки", () -> ignorePlaced, v -> ignorePlaced = v);
        bool("stopWhenInventoryFull", "останавливаться при полном инвентаре", "полный", () -> stopWhenInventoryFull, v -> stopWhenInventoryFull = v);
        integer("followRadius", "дистанция следования", "дистанция", 1, 16, () -> followRadius, v -> followRadius = v);
        integer("farmRadius", "радиус фермы", "ферма", 4, 96, () -> farmRadius, v -> farmRadius = v);
        integer("blockPlacementPenalty", "штраф за установку блока", "штрафустановки", 0, 200, () -> blockPlacementPenalty, v -> blockPlacementPenalty = v);
        integer("blockBreakPenalty", "штраф за ломание блока", "штрафломания", 0, 200, () -> blockBreakPenalty, v -> blockBreakPenalty = v);
        integer("planTimeoutMs", "время на расчёт пути (мс)", "время", 200, 20000, () -> planTimeoutMs, v -> planTimeoutMs = v);
        bool("protectTools", "не ломать почти сломанные инструменты", "беречь", () -> protectTools, v -> protectTools = v);
        ENTRIES.put("prefix", new Entry("prefix", "префикс команд в чате", () -> prefix, s -> {
            if (s.isBlank() || s.contains(" ") || s.startsWith("/")) return "Префикс не может быть пустым, с пробелом или начинаться с /";
            prefix = s;
            return null;
        }));
        ALIASES.put("префикс", "prefix");
    }

    private static void bool(String name, String desc, String ru, java.util.function.Supplier<Boolean> g, java.util.function.Consumer<Boolean> s) {
        ENTRIES.put(name.toLowerCase(Locale.ROOT), new Entry(name, desc, g::get, str -> {
            Boolean b = parseBool(str);
            if (b == null) return "Нужно true/false (да/нет, вкл/выкл)";
            s.accept(b);
            return null;
        }));
        ALIASES.put(ru, name.toLowerCase(Locale.ROOT));
    }

    private static void integer(String name, String desc, String ru, int min, int max, java.util.function.Supplier<Integer> g, java.util.function.IntConsumer s) {
        ENTRIES.put(name.toLowerCase(Locale.ROOT), new Entry(name, desc, g::get, str -> {
            try {
                int v = Integer.parseInt(str);
                if (v < min || v > max) return "Значение должно быть от " + min + " до " + max;
                s.accept(v);
                return null;
            } catch (NumberFormatException e) {
                return "Нужно целое число";
            }
        }));
        ALIASES.put(ru, name.toLowerCase(Locale.ROOT));
    }

    public static Boolean parseBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes", "1", "да", "вкл", "включить", "+" -> Boolean.TRUE;
            case "false", "off", "no", "0", "нет", "выкл", "выключить", "-" -> Boolean.FALSE;
            default -> null;
        };
    }

    public static Entry find(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry e = ENTRIES.get(key);
        if (e == null && ALIASES.containsKey(key)) e = ENTRIES.get(ALIASES.get(key));
        return e;
    }

    public static Path dir() {
        Path config = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
        Path dir = config.resolve("sloptone");
        // the mod used to be called "Baritone RU": keep its settings and waypoints
        Path old = config.resolve("baritoneru");
        if (!Files.exists(dir) && Files.isDirectory(old)) {
            try {
                Files.createDirectories(dir);
                try (var files = Files.list(old)) {
                    for (Path f : (Iterable<Path>) files::iterator) Files.copy(f, dir.resolve(f.getFileName()));
                }
            } catch (IOException ignored) {
            }
        }
        return dir;
    }

    private static Path file() {
        return dir().resolve("settings.txt");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int i = line.indexOf('=');
                if (i <= 0 || line.startsWith("#")) continue;
                Entry e = ENTRIES.get(line.substring(0, i).trim().toLowerCase(Locale.ROOT));
                if (e != null) e.set().apply(line.substring(i + 1).trim());
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# SlopTone settings");
            for (Entry e : ENTRIES.values()) lines.add(e.name() + "=" + e.get().get());
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
