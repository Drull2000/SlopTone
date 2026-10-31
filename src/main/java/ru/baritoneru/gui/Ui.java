package ru.baritoneru.gui;

import ru.baritoneru.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Menu preferences (language, look, window position/size), kept in config/sloptone/ui.txt. */
public final class Ui {
    public static boolean en;
    /** window in menu units; -1 = automatic (fits the screen, centred) */
    public static int winX = -1, winY = -1, winW = -1, winH = -1;
    public static int lastTab;
    /** scale the whole menu to the screen size, or use {@link #scale} */
    public static boolean autoScale = true;
    public static float scale = 1f;
    public static int accent;
    /** panel opacity, percent */
    public static int opacity = 95;
    public static boolean animations = true;

    /** accent presets: name ru, name en, main colour, second colour */
    public record Accent(String ru, String en, int main, int second) {}

    public static final Accent[] ACCENTS = {
            new Accent("Фиолетовый", "Violet", 0xFF8B5CF6, 0xFF22D3EE),
            new Accent("Небесный", "Sky", 0xFF38BDF8, 0xFF818CF8),
            new Accent("Изумруд", "Emerald", 0xFF10B981, 0xFF84CC16),
            new Accent("Рубин", "Ruby", 0xFFF43F5E, 0xFFF59E0B),
            new Accent("Янтарь", "Amber", 0xFFF59E0B, 0xFFEF4444),
            new Accent("Розовый", "Pink", 0xFFEC4899, 0xFF8B5CF6),
            new Accent("Лёд", "Ice", 0xFFE2E8F0, 0xFF60A5FA),
    };

    public static Accent accent() {
        return ACCENTS[Math.floorMod(accent, ACCENTS.length)];
    }

    public static String t(String ru, String english) {
        return en ? english : ru;
    }

    public static void resetWindow() {
        winX = winY = winW = winH = -1;
        save();
    }

    private static Path file() {
        return Settings.dir().resolve("ui.txt");
    }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file(), StandardCharsets.UTF_8)) {
                String[] kv = line.split("=", 2);
                if (kv.length != 2) continue;
                String v = kv[1].trim();
                switch (kv[0].trim()) {
                    case "lang" -> en = v.equals("en");
                    // geometry keys changed in 1.0.9 (menu units + auto size); old x/y/w/h are ignored on purpose
                    case "wx" -> winX = Integer.parseInt(v);
                    case "wy" -> winY = Integer.parseInt(v);
                    case "ww" -> winW = Integer.parseInt(v);
                    case "wh" -> winH = Integer.parseInt(v);
                    case "tab" -> lastTab = Integer.parseInt(v);
                    case "autoScale" -> autoScale = Boolean.parseBoolean(v);
                    case "scale" -> scale = Float.parseFloat(v);
                    case "accent" -> accent = Integer.parseInt(v);
                    case "opacity" -> opacity = Integer.parseInt(v);
                    case "animations" -> animations = Boolean.parseBoolean(v);
                    default -> { }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), List.of("lang=" + (en ? "en" : "ru"), "wx=" + winX, "wy=" + winY, "ww=" + winW, "wh=" + winH,
                    "tab=" + lastTab, "autoScale=" + autoScale, "scale=" + scale, "accent=" + accent,
                    "opacity=" + opacity, "animations=" + animations), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
