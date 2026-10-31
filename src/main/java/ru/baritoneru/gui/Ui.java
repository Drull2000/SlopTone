package ru.baritoneru.gui;

import ru.baritoneru.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Menu preferences: language and window position/size, kept in config/sloptone/ui.txt. */
public final class Ui {
    public static boolean en;
    /** -1 = centred */
    public static int winX = -1, winY = -1, winW = 470, winH = 280;
    public static int lastTab;

    public static String t(String ru, String english) {
        return en ? english : ru;
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
                switch (kv[0].trim()) {
                    case "lang" -> en = kv[1].trim().equals("en");
                    case "x" -> winX = Integer.parseInt(kv[1].trim());
                    case "y" -> winY = Integer.parseInt(kv[1].trim());
                    case "w" -> winW = Integer.parseInt(kv[1].trim());
                    case "h" -> winH = Integer.parseInt(kv[1].trim());
                    case "tab" -> lastTab = Integer.parseInt(kv[1].trim());
                    default -> { }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.write(file(), List.of("lang=" + (en ? "en" : "ru"), "x=" + winX, "y=" + winY, "w=" + winW, "h=" + winH,
                    "tab=" + lastTab), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
