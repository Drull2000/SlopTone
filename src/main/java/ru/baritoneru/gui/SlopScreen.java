package ru.baritoneru.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import ru.baritoneru.BlockNames;
import ru.baritoneru.Bot;
import ru.baritoneru.Commands;
import ru.baritoneru.Highlights;
import ru.baritoneru.LogBook;
import ru.baritoneru.Settings;
import ru.baritoneru.XRay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static ru.baritoneru.gui.Ui.t;

/**
 * The SlopTone menu (Insert). Drawn immediate-mode: every frame records clickable areas, and small
 * per-element animation values make hover, toggles, tab switches, scrolling and opening smooth.
 * The window can be dragged by its header and resized from the bottom-right corner.
 */
public final class SlopScreen extends Screen {
    // palette: dark glass with a violet → cyan accent
    private static final int PANEL = 0xF2121520, SIDE = 0xF20D1017, CARD = 0xFF191D28, CARD_HOVER = 0xFF232838;
    private static final int LINE = 0xFF272C3A, ACCENT = 0xFF8B5CF6, ACCENT2 = 0xFF22D3EE, ACCENT_SOFT = 0x388B5CF6;
    private static final int TEXT = 0xFFE9EBF3, MUTED = 0xFF8C92A5, FAINT = 0xFF555B6C, RED = 0xFFF05252, GREEN = 0xFF34D399;
    private static final int OFF = 0xFF353B4A, AMBER = 0xFFF59E0B, CONSOLE = 0xFF0A0C11;
    private static final int MIN_W = 330, MIN_H = 210;

    private enum Cat {
        MOVE("➜", "Движение", "Movement"), MINE("⛏", "Добыча", "Mining"), PLAYERS("☺", "Игроки", "Players"),
        MARKS("⚑", "Метки", "Waypoints"), LOGS("≡", "Логи", "Logs"), SETTINGS("⚙", "Настройки", "Settings");
        final String icon, ru, en;

        Cat(String icon, String ru, String en) {
            this.icon = icon;
            this.ru = ru;
            this.en = en;
        }

        String title() { return t(ru, en); }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    private static final List<String> HISTORY = new ArrayList<>();
    private static LogBook.Type logFilter;

    private Cat cat = Cat.values()[Math.max(0, Math.min(Cat.values().length - 1, Ui.lastTab))];
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> contentHits = new ArrayList<>();
    private final Map<String, Float> anim = new HashMap<>();
    private String input = "";
    private List<String> suggestions = List.of();
    private int selected = -1;
    private int historyIdx = -1;

    // animation / interaction state
    private long openedAt, tabAt, lastFrame;
    private float dt, openAnim, alphaMul = 1f;
    private boolean closing, closeScheduled;
    private long closingAt;
    private float scroll, scrollTarget;
    private int contentHeight;
    private float logScroll; // lines from the bottom
    private float indicatorY = -1;
    private boolean dragging, resizing;
    private double dragMX, dragMY;
    private int dragX, dragY, dragW, dragH;
    private int mouseX, mouseY;

    // layout of the current frame
    private int x0, y0, x1, y1, contentTop, contentBottom, contentLeft, consoleTop, consoleBottom;

    public SlopScreen() {
        super(Component.literal("SlopTone"));
    }

    /** Test hook: open the menu on a category with some text typed into the command line. */
    public static void debugOpen(String args) {
        SlopScreen s = new SlopScreen();
        String[] parts = args.split(" ", 2);
        if (!parts[0].isEmpty()) s.cat = Cat.values()[Integer.parseInt(parts[0])];
        if (parts.length > 1) s.input = parts[1];
        Bot.get().mc.gui.setScreen(s);
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = tabAt = System.currentTimeMillis();
        updateSuggestions();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // the bot keeps working while the menu is open
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        closingAt = System.currentTimeMillis();
        Ui.lastTab = cat.ordinal();
        Ui.save();
    }

    // ================================================================ animation helpers

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** smoothly follows a target value per element id */
    private float anim(String id, float target, float speed) {
        float v = anim.getOrDefault(id, target);
        v += (target - v) * Math.min(1f, dt * speed);
        if (Math.abs(target - v) < 0.002f) v = target;
        anim.put(id, v);
        return v;
    }

    private float hover(String id, boolean on) {
        return anim(id, on ? 1f : 0f, 16f);
    }

    private static int lerp(int a, int b, float t) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return (int) (aa + (ba - aa) * t) << 24 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    /** applies the open/close fade to a colour */
    private int a(int argb) {
        int al = (int) ((argb >>> 24) * alphaMul);
        return Math.max(al, 0) << 24 | (argb & 0xFFFFFF);
    }

    private int ca(int argb) { // content colours also fade in after a tab switch
        int al = (int) ((argb >>> 24) * alphaMul * ease((System.currentTimeMillis() - tabAt) / 180f));
        return Math.max(al, 0) << 24 | (argb & 0xFFFFFF);
    }

    private void fill(GuiGraphicsExtractor g, int x, int y, int x2, int y2, int c) {
        g.fill(x, y, x2, y2, a(c));
    }

    private void text(GuiGraphicsExtractor g, String s, int x, int y, int c) {
        int col = a(c);
        if ((col >>> 24) > 8) g.text(getFont(), s, x, y, col, false);
    }

    /** rectangle with cut corners (r = 1 or 2), which reads as rounded at GUI scale */
    private static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        if (w < 2 * r + 1 || h < 2 * r + 1) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + r, y + h - r, color);
        g.fill(x + w - r, y + r, x + w, y + h - r, color);
        if (r >= 2) {
            g.fill(x + 1, y + 1, x + 2, y + h - 1, color);
            g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, color);
        }
    }

    // ================================================================ drawing

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
        long nowNs = System.nanoTime();
        dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (nowNs - lastFrame) / 1e9f);
        lastFrame = nowNs;
        long ms = System.currentTimeMillis();
        openAnim = closing ? 1 - ease((ms - closingAt) / 140f) : ease((ms - openedAt) / 220f);
        if (closing && openAnim <= 0.01f) {
            if (!closeScheduled) {
                closeScheduled = true;
                var mc = Bot.get().mc;
                mc.execute(() -> {
                    if (mc.gui.screen() == this) mc.gui.setScreen(null);
                });
            }
            return;
        }
        alphaMul = openAnim;
        mouseX = mx;
        mouseY = my;
        hits.clear();
        contentHits.clear();
        Font font = getFont();

        // geometry (user-movable, clamped to the screen)
        int w = Math.max(MIN_W, Math.min(width - 8, Ui.winW)), h = Math.max(MIN_H, Math.min(height - 8, Ui.winH));
        w = Math.min(w, width - 8);
        h = Math.min(h, height - 8);
        x0 = Ui.winX < 0 ? (width - w) / 2 : Math.max(4, Math.min(width - w - 4, Ui.winX));
        y0 = Ui.winY < 0 ? (height - h) / 2 : Math.max(4, Math.min(height - h - 4, Ui.winY));
        x1 = x0 + w;
        y1 = y0 + h;
        int header = 24, side = 96, footer = 42;
        contentLeft = x0 + side;
        contentTop = y0 + header;
        contentBottom = y1 - footer;

        // open/close: fade + slight zoom from the centre
        g.fill(0, 0, width, height, a(0x70000000));
        float s = 0.94f + 0.06f * openAnim;
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(s, s);
        g.pose().translate(-cx, -cy);

        // soft shadow + panel
        for (int i = 4; i >= 1; i--) round(g, x0 - i, y0 - i + 2, w + 2 * i, h + 2 * i, 2, a(0x14000000));
        round(g, x0, y0, w, h, 2, a(PANEL));

        drawHeader(g, font, w, header);
        drawSidebar(g, font, side);

        // content
        g.enableScissor(contentLeft, contentTop, x1, contentBottom);
        scroll += (scrollTarget - scroll) * Math.min(1f, dt * 14f);
        int slide = (int) ((1 - ease((ms - tabAt) / 180f)) * 10);
        int top = contentTop + 8 - Math.round(scroll) + slide;
        int end = switch (cat) {
            case MOVE -> drawMove(g, top);
            case MINE -> drawMine(g, top);
            case PLAYERS -> drawPlayers(g, top);
            case MARKS -> drawMarks(g, top);
            case LOGS -> drawLogs(g, contentTop + 8 + slide);
            case SETTINGS -> drawSettings(g, top);
        };
        g.disableScissor();
        if (cat != Cat.LOGS) {
            contentHeight = end + Math.round(scroll) - slide - contentTop;
            int view = contentBottom - contentTop;
            float max = Math.max(0, contentHeight - view);
            scrollTarget = Math.max(0, Math.min(scrollTarget, max));
            if (contentHeight > view) {
                int barH = Math.max(18, view * view / contentHeight);
                int barY = contentTop + (int) ((view - barH) * (scroll / Math.max(1, max)));
                boolean hv = mx >= x1 - 6 && mx < x1 && my >= contentTop && my < contentBottom;
                round(g, x1 - 5, barY + 2, 3, barH - 4, 1, a(hv ? ACCENT : LINE));
            }
        }

        drawFooter(g, font);

        // resize grip
        float gh = hover("grip", mx >= x1 - 10 && my >= y1 - 10 && mx < x1 && my < y1) ;
        int gc = lerp(FAINT, ACCENT, Math.max(gh, resizing ? 1 : 0));
        for (int i = 0; i < 3; i++) for (int j = 0; j <= i; j++)
            fill(g, x1 - 4 - j * 3, y1 - 4 - (2 - i) * 3, x1 - 3 - j * 3, y1 - 3 - (2 - i) * 3, gc);

        g.pose().popMatrix();
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, int w, int header) {
        // accent gradient bar on top
        int bar = 2;
        for (int i = 0; i < w - 4; i += 4) {
            float t = i / (float) (w - 4);
            fill(g, x0 + 2 + i, y0, x0 + 2 + Math.min(i + 4, w - 4), y0 + bar, lerp(ACCENT, ACCENT2, t));
        }
        text(g, "Slop", x0 + 10, y0 + 9, ACCENT);
        text(g, "Tone", x0 + 10 + font.width("Slop"), y0 + 9, TEXT);
        text(g, "1.0.8", x0 + 14 + font.width("SlopTone"), y0 + 9, FAINT);

        Bot bot = Bot.get();
        boolean active = bot.process() != null, paused = bot.isPaused();
        int bx = x1 - 8;
        bx -= headerButton(g, bx, y0 + 6, "■ " + t("Стоп", "Stop"), RED, () -> run("стоп"));
        bx -= 4;
        bx -= headerButton(g, bx, y0 + 6, paused ? "▶ " + t("Дальше", "Resume") : "❚❚ " + t("Пауза", "Pause"),
                paused ? GREEN : MUTED, () -> run(paused ? "продолжить" : "пауза"));
        bx -= 6;
        // RU | EN switch
        int lw = 34, lx = bx - lw, ly = y0 + 6;
        round(g, lx, ly, lw, 14, 2, a(CARD));
        float k = anim("lang", Ui.en ? 1 : 0, 14f);
        int knobX = lx + 1 + Math.round(k * 16);
        round(g, knobX, ly + 1, 16, 12, 2, a(ACCENT));
        text(g, "RU", lx + 4, ly + 3, Ui.en ? MUTED : 0xFFFFFFFF);
        text(g, "EN", lx + 20, ly + 3, Ui.en ? 0xFFFFFFFF : MUTED);
        hits.add(new Hit(lx, ly, lw, 14, () -> {
            Ui.en = !Ui.en;
            Ui.save();
            updateSuggestions();
        }));
        bx = lx - 10;
        // status
        String st = active ? bot.process().name() : t("свободен", "idle");
        st = font.plainSubstrByWidth(st, Math.max(20, bx - (x0 + 70 + font.width("SlopTone")) - 12));
        int sw = font.width(st);
        int dotX = bx - sw - 9;
        float pulse = active ? 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 250.0) : 1f;
        int dot = active ? (paused ? AMBER : GREEN) : OFF;
        round(g, dotX, y0 + 10, 5, 5, 1, a(lerp(dot & 0x00FFFFFF, dot, pulse)));
        text(g, st, dotX + 9, y0 + 9, active ? TEXT : MUTED);

        fill(g, x0, y0 + header - 1, x1, y0 + header, LINE);
        // drag area = header minus buttons
        hits.add(new Hit(x0, y0, Math.max(0, dotX - x0 - 4), header, null));
    }

    private void drawSidebar(GuiGraphicsExtractor g, Font font, int side) {
        fill(g, x0, contentTop, contentLeft, y1 - 2, SIDE);
        fill(g, contentLeft - 1, contentTop, contentLeft, y1, LINE);
        int cy = contentTop + 7;
        int targetY = cy + cat.ordinal() * 21;
        if (indicatorY < 0) indicatorY = targetY;
        indicatorY += (targetY - indicatorY) * Math.min(1f, dt * 18f);
        int iy = Math.round(indicatorY);
        round(g, x0 + 5, iy, side - 11, 18, 2, a(ACCENT_SOFT));
        round(g, x0 + 5, iy + 3, 2, 12, 1, a(ACCENT));
        for (Cat c : Cat.values()) {
            boolean on = c == cat;
            boolean hv = mouseX >= x0 && mouseX < contentLeft && mouseY >= cy && mouseY < cy + 18;
            float h = hover("cat" + c.ordinal(), hv && !on);
            if (h > 0.01f) round(g, x0 + 5, cy, side - 11, 18, 2, a(lerp(0x00191D28, CARD, h)));
            int tc = on ? TEXT : lerp(MUTED, TEXT, h);
            text(g, c.icon, x0 + 12 + Math.round(h * 2), cy + 5, on ? ACCENT : tc);
            text(g, c.title(), x0 + 25 + Math.round(h * 2), cy + 5, tc);
            if (c == Cat.LOGS) {
                int n = LogBook.size();
                if (n > 0) {
                    String cnt = n > 999 ? "999+" : String.valueOf(n);
                    int cw = font.width(cnt) + 6;
                    round(g, contentLeft - 10 - cw, cy + 4, cw, 10, 1, a(OFF));
                    text(g, cnt, contentLeft - 7 - cw, cy + 5, MUTED);
                }
            }
            final Cat target = c;
            hits.add(new Hit(x0, cy, side, 18, () -> switchTab(target)));
            cy += 21;
        }
        text(g, "Ins / Esc", x0 + 10, y1 - 30, FAINT);
        text(g, t("закрыть", "close"), x0 + 10, y1 - 19, 0xFF40455A);
    }

    private void switchTab(Cat c) {
        if (c == cat) return;
        cat = c;
        Ui.lastTab = c.ordinal();
        tabAt = System.currentTimeMillis();
        scroll = scrollTarget = 0;
        logScroll = 0;
    }

    private void drawFooter(GuiGraphicsExtractor g, Font font) {
        int fy = contentBottom + 6, fx = contentLeft + 8, fw = x1 - fx - 10;
        fill(g, contentLeft, contentBottom, x1, contentBottom + 1, LINE);
        round(g, fx, fy, fw, 18, 2, a(CARD));
        float focusGlow = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 400.0);
        fill(g, fx + 2, fy + 17, fx + fw - 2, fy + 18, lerp(ACCENT, ACCENT2, focusGlow * 0.5f));
        text(g, Settings.prefix, fx + 6, fy + 5, ACCENT);
        int tx = fx + 8 + font.width(Settings.prefix);
        String shown = input.isEmpty() ? "" : font.plainSubstrByWidth(input, fw - 30, true);
        if (input.isEmpty()) {
            String ph = t("команда…   Tab — дополнить   Enter — выполнить", "command…   Tab — complete   Enter — run");
            text(g, font.plainSubstrByWidth(ph, fw - 24 - font.width(Settings.prefix)), tx + 3, fy + 5, FAINT);
        } else text(g, shown, tx, fy + 5, TEXT);
        if ((System.currentTimeMillis() - openedAt) / 500 % 2 == 0) {
            int cxp = tx + font.width(shown);
            fill(g, cxp, fy + 4, cxp + 1, fy + 14, ACCENT2);
        }
        String hint = currentUsage();
        String line = hint != null ? Settings.prefix + hint : lastMessage();
        text(g, font.plainSubstrByWidth(LogBook.stripCodes(line), fw), fx + 2, fy + 23, hint != null ? FAINT : MUTED);

        if (!suggestions.isEmpty()) {
            int sh = suggestions.size() * 12 + 4, sy = fy - sh - 3;
            int maxW = 0;
            for (String s : suggestions) maxW = Math.max(maxW, font.width(s));
            int sx = tx - 4, sWidth = Math.min(fw - (sx - fx), maxW + 18);
            round(g, sx - 1, sy - 1, sWidth + 2, sh + 2, 2, a(LINE));
            round(g, sx, sy, sWidth, sh, 2, a(0xFA181C26));
            for (int i = 0; i < suggestions.size(); i++) {
                int ry = sy + 2 + i * 12;
                boolean hv = mouseX >= sx && mouseX < sx + sWidth && mouseY >= ry && mouseY < ry + 12;
                float h = hover("sug" + i, hv || i == selected);
                if (h > 0.01f) round(g, sx + 2, ry, sWidth - 4, 12, 1, a(lerp(0x008B5CF6, ACCENT_SOFT, h)));
                text(g, suggestions.get(i), sx + 7 + Math.round(h * 2), ry + 2, lerp(MUTED, TEXT, h));
                final String s = suggestions.get(i);
                hits.add(0, new Hit(sx, ry, sWidth, 12, () -> applySuggestion(s)));
            }
        }
    }

    // ---------------------------------------------------------------- categories

    private int drawMove(GuiGraphicsExtractor g, int y) {
        y = section(g, y, t("Навигация", "Navigation"));
        y = card(g, y, t("Идти к координатам", "Go to coordinates"), t("x y z, x z или только высота y", "x y z, x z or just height y"), true, () -> fill("иди "));
        y = card(g, y, t("Идти к блоку", "Go to a block"), t("ближайший блок: иди верстак", "nearest block: goto crafting_table"), true, () -> fill("иди "));
        y = card(g, y, t("Вперёд на 100", "Forward 100"), t("туда, куда смотришь", "where you are looking"), false, () -> run("туда 100"));
        y = card(g, y, t("Наверх", "Surface"), t("выбраться на поверхность", "get out to the surface"), false, () -> run("наверх"));
        y = card(g, y, t("К ближайшей оси", "Nearest axis"), "x = 0, z = 0 " + t("или диагональ", "or diagonal"), false, () -> run("ось"));
        y = card(g, y, t("Исследовать мир", "Explore"), t("ходить по спирали", "walk in a spiral"), false, () -> run("исследуй"));
        y = section(g, y, t("Дом", "Home"));
        y = chips(g, y, new String[][]{{"⌂ " + t("Сохранить дом", "Set home"), "дом"}, {t("Домой", "Go home"), "домой"},
                {"☠ " + t("К месту смерти", "Back to death"), "назад"}});
        return y;
    }

    private int drawMine(GuiGraphicsExtractor g, int y) {
        y = section(g, y, t("Руда", "Ores"));
        y = chips(g, y, new String[][]{{t("Алмазы", "Diamonds"), "копай алмазы"}, {t("Железо", "Iron"), "копай железо"},
                {t("Золото", "Gold"), "копай золото"}, {t("Уголь", "Coal"), "копай уголь"}, {t("Медь", "Copper"), "копай медь"},
                {t("Редстоун", "Redstone"), "копай редстоун"}, {t("Лазурит", "Lapis"), "копай лазурит"},
                {t("Изумруды", "Emeralds"), "копай изумруды"}, {t("Незерит", "Netherite"), "копай незерит"}, {t("Кварц", "Quartz"), "копай кварц"}});
        y = section(g, y, "X-Ray — " + t("руды сквозь стены", "ores through walls"));
        y = toggle(g, y, "X-Ray", t("радиус ", "radius ") + XRay.radius + ", " + t("на экране: ", "shown: ") + XRay.count()
                        + t(" · на серверах это чит", " · cheating on servers"),
                () -> XRay.enabled, () -> run("xray " + (XRay.enabled ? "выкл" : "вкл")));
        y = oreChips(g, y);
        y = stepper(g, y, t("Радиус X-Ray", "X-Ray radius"), t("8–64 блока", "8–64 blocks"), XRay.radius,
                () -> XRay.radius = Math.max(8, XRay.radius - 8), () -> XRay.radius = Math.min(64, XRay.radius + 8));
        y = section(g, y, t("Дерево", "Wood"));
        y = chips(g, y, new String[][]{{t("Любое", "Any"), "копай дерево"}, {t("Дуб", "Oak"), "копай дуб"}, {t("Берёза", "Birch"), "копай береза"},
                {t("Ель", "Spruce"), "копай ель"}, {t("Акация", "Acacia"), "копай акация"}, {t("Тёмный дуб", "Dark oak"), "копай темный_дуб"},
                {t("Вишня", "Cherry"), "копай вишня"}});
        y = section(g, y, t("Своё", "Custom"));
        y = card(g, y, t("Копать…", "Mine…"), t("блок и сколько нужно: алмазы 10", "block and amount: diamond 10"), true, () -> fill("копай "));
        y = section(g, y, t("Работа", "Jobs"));
        y = card(g, y, t("Тоннель 1×2", "Tunnel 1×2"), t("прямо, пока не остановишь", "straight until stopped"), false, () -> run("тоннель"));
        y = card(g, y, t("Тоннель 3×3 на 64 блока", "Tunnel 3×3, 64 blocks"), "", false, () -> run("тоннель 3 3 64"));
        y = card(g, y, t("Ферма", "Farm"), t("собирать урожай и сажать семена", "harvest and replant"), false, () -> run("ферма"));
        return y;
    }

    /** one chip per ore family; on = shown by X-Ray, coloured like its outline */
    private int oreChips(GuiGraphicsExtractor g, int y) {
        Map<Integer, List<Block>> byColor = new java.util.LinkedHashMap<>();
        for (var e : XRay.ORES.entrySet()) byColor.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        int x = contentLeft + 8, maxX = x1 - 10;
        for (var e : byColor.entrySet()) {
            List<Block> blocks = e.getValue();
            String name = blocks.get(0).getName().getString().replace(" Ore", "").replace("Руда", "").replace("руда", "").trim();
            if (name.isEmpty()) name = BuiltInRegistries.BLOCK.getKey(blocks.get(0)).getPath();
            boolean on = XRay.targets.containsAll(blocks);
            int w = getFont().width(name) + 18;
            if (x + w > maxX) {
                x = contentLeft + 8;
                y += 18;
            }
            float h = hover("ore" + e.getKey(), inContent(x, y, w, 15));
            int bg = on ? lerp(0xFF2A2440, 0xFF3A3060, h) : lerp(CARD, CARD_HOVER, h);
            round(g, x, y, w, 15, 2, ca(bg));
            round(g, x + 5, y + 5, 5, 5, 1, ca(0xFF000000 | e.getKey()));
            g.text(getFont(), name, x + 13, y + 4, ca(on ? TEXT : MUTED), false);
            contentHits.add(new Hit(x, y, w, 15, () -> {
                if (on) XRay.targets.removeAll(blocks);
                else XRay.targets.addAll(blocks);
            }));
            x += w + 4;
        }
        return y + 21;
    }

    private int drawPlayers(GuiGraphicsExtractor g, int y) {
        y = section(g, y, t("Подсветка — видно сквозь стены", "Glow — visible through walls"));
        y = toggle(g, y, t("Игроки", "Players"), t("свой цвет у каждого игрока", "own colour per player"), () -> Highlights.players, () -> Highlights.players = !Highlights.players);
        y = toggle(g, y, t("Мобы", "Monsters"), t("красный контур", "red outline"), () -> Highlights.mobs, () -> Highlights.mobs = !Highlights.mobs);
        y = toggle(g, y, t("Животные", "Animals"), t("зелёный контур", "green outline"), () -> Highlights.animals, () -> Highlights.animals = !Highlights.animals);
        y = section(g, y, t("Постройки", "Builds"));
        y = chips(g, y, new String[][]{{t("Чьи блоки рядом", "Whose blocks nearby"), "чьи"}, {t("Кто поставил (прицел)", "Who placed (crosshair)"), "кто"}});
        var mc = Bot.get().mc;
        List<net.minecraft.world.entity.player.Player> others = new ArrayList<>();
        if (mc.level != null) for (var p : mc.level.players()) if (p != mc.player) others.add(p);
        others.sort(Comparator.comparingDouble(p -> p.distanceTo(mc.player)));
        int online = mc.player == null ? 0 : mc.player.connection.getOnlinePlayers().size();
        y = section(g, y, t("Игроки рядом", "Players nearby") + " (" + others.size() + ") · " + t("онлайн ", "online ") + online);
        if (others.isEmpty()) {
            g.text(getFont(), t("никого не видно", "nobody in sight"), contentLeft + 12, y, ca(MUTED), false);
            y += 14;
        }
        for (var p : others) {
            String name = p.getName().getString();
            int rx = contentLeft + 8, rw = x1 - rx - 10;
            float h = hover("pl" + name, inContent(rx, y, rw, 20));
            round(g, rx, y, rw, 20, 2, ca(lerp(CARD, CARD_HOVER, h)));
            round(g, rx + 6, y + 7, 6, 6, 1, ca(0xFF000000 | Commands.colorOf(name)));
            g.text(getFont(), name, rx + 18, y + 6, ca(TEXT), false);
            g.text(getFont(), (int) p.distanceTo(mc.player) + " " + t("м", "m"), rx + 24 + getFont().width(name), y + 6, ca(MUTED), false);
            BlockPos bp = p.blockPosition();
            int bx = rx + rw - 4;
            bx -= miniButton(g, bx, y + 3, t("Идти", "Go"), () -> run("иди " + bp.getX() + " " + bp.getY() + " " + bp.getZ()));
            bx -= 3;
            miniButton(g, bx, y + 3, t("Следовать", "Follow"), () -> run("следуй " + name));
            y += 23;
        }
        return y;
    }

    private int drawMarks(GuiGraphicsExtractor g, int y) {
        y = card(g, y, t("Сохранить метку здесь", "Save waypoint here"), t("впиши имя и нажми Enter", "type a name and press Enter"), true, () -> fill("метка сохранить "));
        Map<String, BlockPos> all = Bot.get().waypoints.list();
        y = section(g, y, t("Метки в этом мире", "Waypoints in this world") + " (" + all.size() + ")");
        if (all.isEmpty()) {
            g.text(getFont(), t("пока нет — сохрани первую", "none yet — save one"), contentLeft + 12, y, ca(MUTED), false);
            y += 14;
        }
        for (var e : all.entrySet()) {
            String name = e.getKey();
            BlockPos p = e.getValue();
            int rx = contentLeft + 8, rw = x1 - rx - 10;
            float h = hover("wp" + name, inContent(rx, y, rw, 20));
            round(g, rx, y, rw, 20, 2, ca(lerp(CARD, CARD_HOVER, h)));
            g.text(getFont(), "⚑ " + name, rx + 6, y + 6, ca(TEXT), false);
            g.text(getFont(), p.toShortString(), rx + 14 + getFont().width("⚑ " + name), y + 6, ca(MUTED), false);
            int bx = rx + rw - 4;
            bx -= miniButton(g, bx, y + 3, "✕", () -> run("метка удалить " + name));
            bx -= 3;
            miniButton(g, bx, y + 3, t("Идти", "Go"), () -> run("метка иди " + name));
            y += 23;
        }
        return y;
    }

    /** console-style log of everything the client got from the server */
    private int drawLogs(GuiGraphicsExtractor g, int y) {
        Font font = getFont();
        // filters
        int x = contentLeft + 8;
        List<LogBook.Type> types = new ArrayList<>();
        types.add(null);
        types.addAll(List.of(LogBook.Type.values()));
        for (LogBook.Type ty : types) {
            String label = ty == null ? t("Все", "All") : (Ui.en ? ty.en : ty.ru);
            int w = font.width(label) + (ty == null ? 10 : 16);
            boolean on = logFilter == ty;
            float h = hover("lf" + label, inContent(x, y, w, 14));
            round(g, x, y, w, 14, 2, ca(on ? ACCENT : lerp(CARD, CARD_HOVER, h)));
            if (ty != null) round(g, x + 5, y + 5, 4, 4, 1, ca(ty.color));
            g.text(font, label, x + (ty == null ? 5 : 12), y + 3, ca(on ? 0xFFFFFFFF : MUTED), false);
            final LogBook.Type f = ty;
            contentHits.add(new Hit(x, y, w, 14, () -> {
                logFilter = f;
                logScroll = 0;
            }));
            x += w + 3;
        }
        y += 18;
        // actions
        int bx = x1 - 10;
        bx -= miniButton(g, bx, y, t("Очистить", "Clear"), LogBook::clear) + 3;
        bx -= miniButton(g, bx, y, t("Копировать", "Copy"), this::copyLogs) + 3;
        bx -= miniButton(g, bx, y, t("Журнал сервера", "Server log"), () -> run("логи 40")) + 3;
        var mc = Bot.get().mc;
        String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : t("одиночная игра", "singleplayer");
        g.text(font, font.plainSubstrByWidth(t("Сервер: ", "Server: ") + where, Math.max(10, bx - contentLeft - 14)), contentLeft + 10, y + 3, ca(MUTED), false);
        y += 18;

        // console
        consoleTop = y;
        consoleBottom = contentBottom - 6;
        int cl = contentLeft + 8, cr = x1 - 10;
        round(g, cl, consoleTop, cr - cl, consoleBottom - consoleTop, 2, ca(CONSOLE));
        List<LogBook.Entry> all = LogBook.snapshot();
        List<LogBook.Entry> lines = new ArrayList<>();
        for (LogBook.Entry e : all) if (logFilter == null || e.type() == logFilter) lines.add(e);
        int lh = 10, visible = Math.max(1, (consoleBottom - consoleTop - 6) / lh);
        int maxScroll = Math.max(0, lines.size() - visible);
        logScroll = Math.max(0, Math.min(logScroll, maxScroll));
        int from = Math.max(0, lines.size() - visible - Math.round(logScroll));
        int ly = consoleTop + 4;
        if (lines.isEmpty()) g.text(font, t("пока пусто — тут появится чат, входы игроков, команды админов…",
                "empty — chat, joins, admin commands… will show up here"), cl + 6, ly, ca(FAINT), false);
        for (int i = from; i < Math.min(lines.size(), from + visible); i++) {
            LogBook.Entry e = lines.get(i);
            String time = e.time().toString();
            time = time.length() >= 8 ? time.substring(0, 8) : time;
            int tx = cl + 5;
            g.text(font, time, tx, ly, ca(FAINT), false);
            tx += font.width("00:00:00") + 5;
            String tag = Ui.en ? e.type().en : e.type().ru;
            g.text(font, tag, tx, ly, ca(e.type().color), false);
            tx += font.width("ИГРК") + 6;
            g.text(font, font.plainSubstrByWidth(e.text(), cr - tx - 6), tx, ly, ca(TEXT), false);
            ly += lh;
        }
        if (maxScroll > 0) {
            int view = consoleBottom - consoleTop - 4;
            int barH = Math.max(12, view * visible / lines.size());
            int barY = consoleBottom - 2 - barH - (int) ((view - barH) * (logScroll / maxScroll));
            round(g, cr - 4, barY, 2, barH, 1, ca(LINE));
        }
        return consoleBottom;
    }

    private void copyLogs() {
        StringBuilder sb = new StringBuilder();
        for (LogBook.Entry e : LogBook.snapshot())
            if (logFilter == null || e.type() == logFilter) sb.append(LogBook.format(e, Ui.en)).append('\n');
        Bot.get().mc.keyboardHandler.setClipboard(sb.toString());
        Bot.msg(t("Логи скопированы в буфер обмена.", "Logs copied to the clipboard."));
    }

    private int drawSettings(GuiGraphicsExtractor g, int y) {
        y = section(g, y, t("Настройки бота", "Bot settings"));
        for (Settings.Entry e : Settings.ENTRIES.values()) {
            Object v = e.get().get();
            String ru = Settings.ALIASES.entrySet().stream()
                    .filter(al -> al.getValue().equals(e.name().toLowerCase(Locale.ROOT))).map(Map.Entry::getKey).findFirst().orElse(e.name());
            String title = Ui.en ? words(e.name())
                    : (e.description().isEmpty() ? e.name() : Character.toUpperCase(e.description().charAt(0)) + e.description().substring(1));
            String sub = Ui.en ? "#set " + e.name() : "#настройка " + ru;
            if (v instanceof Boolean) {
                y = toggle(g, y, title, sub, () -> (Boolean) e.get().get(), () -> {
                    e.set().apply(String.valueOf(!(Boolean) e.get().get()));
                    Settings.save();
                });
            } else if (v instanceof Integer i) {
                int step = stepFor(e.name());
                y = stepper(g, y, title, sub, i, () -> {
                    if (e.set().apply(String.valueOf((Integer) e.get().get() - step)) == null) Settings.save();
                }, () -> {
                    if (e.set().apply(String.valueOf((Integer) e.get().get() + step)) == null) Settings.save();
                });
            } else {
                y = card(g, y, title + ": " + v, sub, true, () -> fill("настройка " + e.name() + " "));
            }
        }
        return y;
    }

    /** "allowBreak" → "Allow break" */
    private static String words(String camel) {
        String s = camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int stepFor(String name) {
        return switch (name) {
            case "planTimeoutMs" -> 250;
            case "mineScanRadius", "farmRadius" -> 8;
            case "blockPlacementPenalty", "blockBreakPenalty" -> 5;
            default -> 1;
        };
    }

    // ---------------------------------------------------------------- widgets

    private int section(GuiGraphicsExtractor g, int y, String title) {
        String up = title.toUpperCase(Locale.ROOT);
        g.text(getFont(), up, contentLeft + 10, y + 2, ca(ACCENT), false);
        g.fill(contentLeft + 14 + getFont().width(up), y + 6, x1 - 12, y + 7, ca(LINE));
        return y + 14;
    }

    private int card(GuiGraphicsExtractor g, int y, String title, String desc, boolean fill, Runnable action) {
        int rx = contentLeft + 8, rw = x1 - rx - 10, rh = desc.isEmpty() ? 18 : 24;
        float h = hover("card" + title, inContent(rx, y, rw, rh));
        round(g, rx, y, rw, rh, 2, ca(lerp(CARD, CARD_HOVER, h)));
        if (h > 0.01f) round(g, rx, y + 3, 2, Math.max(1, Math.round((rh - 6) * h)), 1, ca(ACCENT));
        int shift = Math.round(h * 2);
        g.text(getFont(), getFont().plainSubstrByWidth(title, rw - 30), rx + 8 + shift, y + (desc.isEmpty() ? 5 : 3), ca(TEXT), false);
        if (!desc.isEmpty())
            g.text(getFont(), getFont().plainSubstrByWidth(desc, rw - 30), rx + 8 + shift, y + 13, ca(MUTED), false);
        g.text(getFont(), fill ? "✎" : "▶", rx + rw - 12 + Math.round(h * 2), y + rh / 2 - 4, ca(lerp(FAINT, ACCENT, h)), false);
        contentHits.add(new Hit(rx, y, rw, rh, action));
        return y + rh + 3;
    }

    private int chips(GuiGraphicsExtractor g, int y, String[][] items) {
        int x = contentLeft + 8, maxX = x1 - 10;
        for (String[] it : items) {
            int w = getFont().width(it[0]) + 12;
            if (x + w > maxX) {
                x = contentLeft + 8;
                y += 18;
            }
            float h = hover("chip" + it[1], inContent(x, y, w, 15));
            round(g, x, y, w, 15, 2, ca(lerp(CARD, ACCENT, h)));
            g.text(getFont(), it[0], x + 6, y + 4, ca(lerp(TEXT, 0xFFFFFFFF, h)), false);
            final String cmd = it[1];
            contentHits.add(new Hit(x, y, w, 15, () -> run(cmd)));
            x += w + 4;
        }
        return y + 21;
    }

    private int toggle(GuiGraphicsExtractor g, int y, String title, String desc, BooleanSupplier value, Runnable flip) {
        int rx = contentLeft + 8, rw = x1 - rx - 10, rh = 24;
        boolean on = value.getAsBoolean();
        float h = hover("tgh" + title, inContent(rx, y, rw, rh));
        float k = anim("tg" + title, on ? 1 : 0, 14f);
        round(g, rx, y, rw, rh, 2, ca(lerp(CARD, CARD_HOVER, h)));
        g.text(getFont(), getFont().plainSubstrByWidth(title, rw - 40), rx + 8, y + 3, ca(TEXT), false);
        g.text(getFont(), getFont().plainSubstrByWidth(desc, rw - 44), rx + 8, y + 13, ca(MUTED), false);
        int sx = rx + rw - 30, sy = y + 7;
        round(g, sx, sy, 22, 10, 2, ca(lerp(OFF, ACCENT, k)));
        int kx = sx + 1 + Math.round(k * 12);
        round(g, kx, sy + 1, 8, 8, 2, ca(0xFFFFFFFF));
        contentHits.add(new Hit(rx, y, rw, rh, flip));
        return y + rh + 3;
    }

    private int stepper(GuiGraphicsExtractor g, int y, String title, String desc, int value, Runnable minus, Runnable plus) {
        int rx = contentLeft + 8, rw = x1 - rx - 10, rh = 24;
        float h = hover("st" + title, inContent(rx, y, rw, rh));
        round(g, rx, y, rw, rh, 2, ca(lerp(CARD, CARD_HOVER, h)));
        g.text(getFont(), getFont().plainSubstrByWidth(title, rw - 90), rx + 8, y + 3, ca(TEXT), false);
        g.text(getFont(), getFont().plainSubstrByWidth(desc, rw - 90), rx + 8, y + 13, ca(MUTED), false);
        String v = String.valueOf(value);
        int vw = Math.max(30, getFont().width(v) + 8);
        int bx = rx + rw - 6 - 14;
        stepButton(g, bx, y + 5, "+", title + "+", plus);
        bx -= vw;
        g.centeredText(getFont(), v, bx + vw / 2, y + 9, ca(ACCENT2));
        bx -= 14;
        stepButton(g, bx, y + 5, "−", title + "-", minus);
        return y + rh + 3;
    }

    private void stepButton(GuiGraphicsExtractor g, int x, int y, String label, String id, Runnable action) {
        float h = hover("sb" + id, inContent(x, y, 14, 14));
        round(g, x, y, 14, 14, 2, ca(lerp(OFF, ACCENT, h)));
        g.centeredText(getFont(), label, x + 7, y + 3, ca(TEXT));
        contentHits.add(new Hit(x, y, 14, 14, action));
    }

    private int miniButton(GuiGraphicsExtractor g, int right, int y, String label, Runnable action) {
        int w = getFont().width(label) + 10, x = right - w;
        float h = hover("mb" + label + y, inContent(x, y, w, 14));
        round(g, x, y, w, 14, 2, ca(lerp(OFF, ACCENT, h)));
        g.text(getFont(), label, x + 5, y + 3, ca(TEXT), false);
        contentHits.add(new Hit(x, y, w, 14, action));
        return w;
    }

    private int headerButton(GuiGraphicsExtractor g, int right, int y, String label, int color, Runnable action) {
        int w = getFont().width(label) + 12, x = right - w;
        float h = hover("hb" + label, mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 14);
        round(g, x, y, w, 14, 2, a(lerp(CARD, color, h)));
        text(g, label, x + 6, y + 3, lerp(color, 0xFFFFFFFF, h));
        hits.add(new Hit(x, y, w, 14, action));
        return w;
    }

    private boolean inContent(int x, int y, int w, int h) {
        return !dragging && !resizing && mouseY >= contentTop && mouseY < contentBottom
                && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String lastMessage() {
        String m = Bot.RECENT.peekFirst();
        if (m == null) return "";
        int nl = m.indexOf('\n');
        return nl >= 0 ? m.substring(0, nl) : m;
    }

    // ================================================================ actions

    private void run(String command) {
        Commands.handleChat(Settings.prefix + command);
    }

    private void fill(String template) {
        input = template;
        historyIdx = -1;
        updateSuggestions();
    }

    private void submit() {
        String cmd = input.trim();
        if (cmd.isEmpty()) return;
        HISTORY.remove(cmd);
        HISTORY.add(0, cmd);
        if (HISTORY.size() > 30) HISTORY.remove(HISTORY.size() - 1);
        run(cmd);
        input = "";
        historyIdx = -1;
        updateSuggestions();
    }

    // ---------------------------------------------------------------- autocomplete

    private String currentUsage() {
        if (input.isBlank()) return null;
        return Commands.usageOf(input.trim().split("\\s+")[0]);
    }

    private void updateSuggestions() {
        selected = -1;
        String text = input;
        boolean endsWithSpace = text.endsWith(" ");
        String[] tokens = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
        String last = endsWithSpace || tokens.length == 0 ? "" : tokens[tokens.length - 1].toLowerCase(Locale.ROOT);
        int index = endsWithSpace ? tokens.length : Math.max(0, tokens.length - 1);

        Set<String> pool = new LinkedHashSet<>();
        if (index == 0) {
            if (text.isEmpty()) {
                suggestions = List.of();
                return;
            }
            pool.addAll(Commands.names());
        } else {
            String usage = Commands.usageOf(tokens[0]);
            String kind = usage == null ? "" : usage.split(" ")[0];
            var mc = Bot.get().mc;
            switch (kind) {
                case "копай", "xray" -> {
                    pool.addAll(BlockNames.words());
                    if (kind.equals("xray")) pool.addAll(List.of("вкл", "выкл", "все"));
                    if (last.length() >= 2) for (var id : BuiltInRegistries.BLOCK.keySet()) pool.add(id.getPath());
                }
                case "иди" -> {
                    pool.addAll(Bot.get().waypoints.list().keySet());
                    pool.addAll(BlockNames.words());
                }
                case "метка" -> {
                    if (index == 1) pool.addAll(List.of("сохранить", "иди", "список", "удалить"));
                    else pool.addAll(Bot.get().waypoints.list().keySet());
                }
                case "следуй", "чьи" -> {
                    if (mc.level != null) for (var p : mc.level.players()) if (p != mc.player) pool.add(p.getName().getString());
                }
                case "настройка" -> {
                    if (index == 1) {
                        for (var e : Settings.ENTRIES.values()) pool.add(e.name());
                        pool.addAll(Settings.ALIASES.keySet());
                    } else pool.addAll(List.of("да", "нет", "true", "false"));
                }
                case "подсветка" -> pool.addAll(List.of("игроки", "мобы", "животные", "все", "выкл"));
                default -> { }
            }
        }
        List<String> out = new ArrayList<>();
        String key = last.replace('ё', 'е');
        for (String s : pool) {
            String low = s.toLowerCase(Locale.ROOT);
            if (low.startsWith(key) && !low.equals(key)) out.add(s);
        }
        out.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        suggestions = out.size() > 7 ? out.subList(0, 7) : out;
    }

    private void applySuggestion(String s) {
        String text = input;
        int cut = text.endsWith(" ") ? text.length() : text.lastIndexOf(' ') + 1;
        input = text.substring(0, cut) + s + " ";
        updateSuggestions();
    }

    // ================================================================ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closing) return true;
        double mx = e.x(), my = e.y();
        // resize grip
        if (mx >= x1 - 10 && my >= y1 - 10 && mx < x1 + 2 && my < y1 + 2) {
            resizing = true;
            startDrag(mx, my);
            return true;
        }
        for (Hit h : hits) {
            if (h.in(mx, my)) {
                if (h.action() == null) { // header: start dragging
                    dragging = true;
                    startDrag(mx, my);
                } else h.action().run();
                return true;
            }
        }
        if (my >= contentTop && my < contentBottom) {
            for (Hit h : contentHits) {
                if (h.in(mx, my)) {
                    h.action().run();
                    return true;
                }
            }
        }
        return true;
    }

    private void startDrag(double mx, double my) {
        dragMX = mx;
        dragMY = my;
        dragX = x0;
        dragY = y0;
        dragW = x1 - x0;
        dragH = y1 - y0;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) {
            Ui.winX = (int) Math.round(dragX + e.x() - dragMX);
            Ui.winY = (int) Math.round(dragY + e.y() - dragMY);
            return true;
        }
        if (resizing) {
            Ui.winW = Math.max(MIN_W, (int) Math.round(dragW + e.x() - dragMX));
            Ui.winH = Math.max(MIN_H, (int) Math.round(dragH + e.y() - dragMY));
            if (Ui.winX < 0) {
                Ui.winX = dragX;
                Ui.winY = dragY;
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging || resizing) {
            dragging = resizing = false;
            Ui.save();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (mx >= contentLeft && mx < x1 && my >= contentTop && my < contentBottom) {
            if (cat == Cat.LOGS) logScroll += (float) sy * 3;
            else scrollTarget -= (float) sy * 22;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (k == InputConstants.KEY_ESCAPE || k == InputConstants.KEY_INSERT) {
            onClose();
            return true;
        }
        if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
            if (selected >= 0 && selected < suggestions.size()) applySuggestion(suggestions.get(selected));
            else submit();
            return true;
        }
        if (k == InputConstants.KEY_TAB) {
            if (!suggestions.isEmpty()) applySuggestion(suggestions.get(Math.max(0, selected)));
            return true;
        }
        if (k == InputConstants.KEY_BACKSPACE) {
            if (!input.isEmpty()) {
                if (e.hasControlDown()) {
                    String tt = input.stripTrailing();
                    int cut = tt.lastIndexOf(' ');
                    input = cut < 0 ? "" : tt.substring(0, cut + 1);
                } else input = input.substring(0, input.length() - 1);
                updateSuggestions();
            }
            return true;
        }
        if (k == InputConstants.KEY_UP) {
            if (!suggestions.isEmpty()) selected = selected <= 0 ? suggestions.size() - 1 : selected - 1;
            else if (!HISTORY.isEmpty()) {
                historyIdx = Math.min(HISTORY.size() - 1, historyIdx + 1);
                input = HISTORY.get(historyIdx);
            }
            return true;
        }
        if (k == InputConstants.KEY_DOWN) {
            if (!suggestions.isEmpty()) selected = (selected + 1) % suggestions.size();
            else if (historyIdx > 0) input = HISTORY.get(--historyIdx);
            else {
                historyIdx = -1;
                input = "";
            }
            return true;
        }
        if (e.isPaste()) {
            input += Bot.get().mc.keyboardHandler.getClipboard().replace("\n", " ");
            if (input.startsWith(Settings.prefix)) input = input.substring(Settings.prefix.length());
            updateSuggestions();
            return true;
        }
        return super.keyPressed(e);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (!e.isAllowedChatCharacter()) return false;
        String c = e.codepointAsString();
        if (input.isEmpty() && c.equals(Settings.prefix)) return true; // "#" is implied
        if (input.length() < 200) input += c;
        historyIdx = -1;
        updateSuggestions();
        return true;
    }
}
