package ru.baritoneru;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Named positions, stored per server/world and dimension in config/sloptone/waypoints.txt. */
public final class Waypoints {
    private final Map<String, BlockPos> all = new LinkedHashMap<>(); // key = world|name
    private boolean loaded;

    public void tick(Bot bot) {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    /** Identifies the server/world and dimension the client is in, e.g. "sp:Новый мир/ResourceKey[...overworld]". */
    static String world() {
        Minecraft mc = Minecraft.getInstance();
        String server;
        if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        else if (mc.getSingleplayerServer() != null) server = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
        else server = "?";
        String dim = mc.level == null ? "?" : mc.level.dimension().toString();
        return worldKey(server, dim);
    }

    static String worldKey(String server, String dim) {
        return (server + "/" + dim).replace('|', '_');
    }

    private static String key(String name) {
        return world() + "|" + name.toLowerCase(Locale.ROOT);
    }

    public void save(String name, BlockPos pos) {
        all.put(key(name), pos.immutable());
        write();
    }

    public BlockPos get(String name) {
        return all.get(key(name));
    }

    public boolean remove(String name) {
        boolean r = all.remove(key(name)) != null;
        if (r) write();
        return r;
    }

    public Map<String, BlockPos> list() {
        String prefix = world() + "|";
        Map<String, BlockPos> out = new LinkedHashMap<>();
        for (var e : all.entrySet()) if (e.getKey().startsWith(prefix)) out.put(e.getKey().substring(prefix.length()), e.getValue());
        return out;
    }

    private static Path file() {
        return Settings.dir().resolve("waypoints.txt");
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\|");
                if (parts.length != 3) continue;
                String[] xyz = parts[2].split(" ");
                if (xyz.length != 3) continue;
                all.put(parts[0] + "|" + parts[1], new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])));
            }
        } catch (Exception ignored) {
        }
    }

    private void write() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (var e : all.entrySet()) {
                BlockPos p = e.getValue();
                lines.add(e.getKey() + "|" + p.getX() + " " + p.getY() + " " + p.getZ());
            }
            Files.write(f, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
