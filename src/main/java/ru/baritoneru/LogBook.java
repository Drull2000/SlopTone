package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.SystemMessageReceivedEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the client learns from the server, as a console-style log: chat, system messages,
 * players joining/leaving and changing game mode, blocks other players place/break, command feedback.
 * Also appended to config/sloptone/logs/YYYY-MM-DD.log.
 */
public final class LogBook {
    public enum Type {
        CHAT("ЧАТ", "CHAT", 0xFFE8EAF2), SYSTEM("СИСТ", "SYS", 0xFFFACC15), PLAYERS("ИГРК", "PLYR", 0xFF34D399),
        BLOCKS("БЛОК", "BLCK", 0xFF22D3EE), SERVER("СЕРВ", "SRV", 0xFFF472B6), BOT("БОТ", "BOT", 0xFFA78BFA);

        public final String ru, en;
        public final int color;

        Type(String ru, String en, int color) {
            this.ru = ru;
            this.en = en;
            this.color = color;
        }
    }

    public record Entry(LocalTime time, Type type, String text) {}

    private static final int MAX = 4000;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Map<String, String> lastGameMode = new HashMap<>();
    private static int tick;

    public static void register() {
        ClientChatReceivedEvent.BUS.addListener((ClientChatReceivedEvent e) -> add(Type.CHAT, e.getMessage().getString()));
        SystemMessageReceivedEvent.BUS.addListener((SystemMessageReceivedEvent e) -> {
            if (e.isOverlay()) return;
            String s = stripCodes(e.getMessage().getString());
            add(classify(s), s);
        });
        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            Minecraft mc = Minecraft.getInstance();
            String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "одиночная игра / singleplayer";
            add(Type.SERVER, "Подключение: " + where);
            lastGameMode.clear();
        });
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            add(Type.SERVER, "Отключение от сервера");
            lastGameMode.clear();
        });
    }

    /** Joins, leaves, deaths and op command feedback ("[Admin: ...]") get their own colours. */
    private static Type classify(String s) {
        String l = s.toLowerCase();
        if (s.startsWith("[") && s.contains(": ") && s.endsWith("]")) return Type.SERVER; // op broadcast of an admin command
        if (l.contains("joined the game") || l.contains("left the game") || l.contains("присоединился") || l.contains("покинул"))
            return Type.PLAYERS;
        if (l.startsWith("[sloptone] сервер")) return Type.SERVER;
        return Type.SYSTEM;
    }

    public static synchronized void add(Type type, String text) {
        Entry e = new Entry(LocalTime.now(), type, text.replace('\n', ' '));
        ENTRIES.addLast(e);
        while (ENTRIES.size() > MAX) ENTRIES.removeFirst();
        append(e);
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    public static String format(Entry e, boolean en) {
        return "[" + e.time().format(HMS) + "] [" + (en ? e.type().en : e.type().ru) + "] " + stripCodes(e.text());
    }

    public static String stripCodes(String s) {
        return s.replaceAll("§.", "");
    }

    /** Called every client tick: notices players joining/leaving and game mode changes from the tab list. */
    public static void tick() {
        if (++tick % 20 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;
        Map<String, String> now = new HashMap<>();
        for (PlayerInfo info : mc.player.connection.getOnlinePlayers()) {
            String name = info.getProfile().name();
            String mode = info.getGameMode() == null ? "?" : info.getGameMode().getName();
            now.put(name, mode);
            String before = lastGameMode.get(name);
            if (before == null && !lastGameMode.isEmpty()) add(Type.PLAYERS, name + " зашёл (список игроков) / joined");
            else if (before != null && !before.equals(mode)) add(Type.PLAYERS, name + ": режим " + before + " → " + mode);
        }
        for (String name : lastGameMode.keySet())
            if (!now.containsKey(name)) add(Type.PLAYERS, name + " вышел (список игроков) / left");
        lastGameMode.clear();
        lastGameMode.putAll(now);
        if (lastGameMode.isEmpty()) lastGameMode.put("\0", ""); // marks "first scan done"
    }

    private static void append(Entry e) {
        try {
            Path dir = Settings.dir().resolve("logs");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(LocalDate.now() + ".log"), format(e, false) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
