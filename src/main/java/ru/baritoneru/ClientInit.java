package ru.baritoneru;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import ru.baritoneru.gui.SlopScreen;

/** Client-only setup, kept apart so a dedicated server never loads client classes. */
public final class ClientInit {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sloptone", "main"));
    /** main menu key: Right Shift (like Impact); many keyboards have no Insert */
    public static final KeyMapping MENU_KEY = new KeyMapping("key.sloptone.open", InputConstants.KEY_RSHIFT, CATEGORY);
    public static final KeyMapping MENU_KEY_ALT = new KeyMapping("key.sloptone.open_alt", InputConstants.KEY_INSERT, CATEGORY);
    private static boolean openRequested;

    /** Opens the menu as soon as no other screen (e.g. the chat) is open. */
    public static void requestMenu() {
        openRequested = true;
    }

    static void init() {
        RegisterKeyMappingsEvent.BUS.addListener((RegisterKeyMappingsEvent e) -> {
            e.register(MENU_KEY);
            e.register(MENU_KEY_ALT);
        });
        TickEvent.ClientTickEvent.Pre.BUS.addListener(e -> Bot.get().tick());
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> {
            Bot.get().postTick();
            XRay.tick();
            LogBook.tick();
            Minecraft mc = Minecraft.getInstance();
            boolean pressed = false;
            while (MENU_KEY.consumeClick()) pressed = true;
            while (MENU_KEY_ALT.consumeClick()) pressed = true;
            if (pressed) openRequested = true;
            if (openRequested && mc.player != null && mc.gui.screen() == null) {
                openRequested = false;
                mc.gui.setScreen(new SlopScreen());
            }
        });
        // returning true cancels the chat message, so commands are never sent to the server
        ClientChatEvent.BUS.addListener((ClientChatEvent e) -> Commands.handleChat(e.getMessage()));
        PlacedBlocks.register();
        LogBook.register();
        ru.baritoneru.gui.Ui.load();
        BaritoneRu.LOG.info("SlopTone: client listeners registered, command prefix '{}'", Settings.prefix);
    }
}
