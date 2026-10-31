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
final class ClientInit {
    static final KeyMapping MENU_KEY = new KeyMapping("key.sloptone.menu", InputConstants.KEY_INSERT,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sloptone", "main")));

    static void init() {
        RegisterKeyMappingsEvent.BUS.addListener((RegisterKeyMappingsEvent e) -> e.register(MENU_KEY));
        TickEvent.ClientTickEvent.Pre.BUS.addListener(e -> Bot.get().tick());
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> {
            Bot.get().postTick();
            XRay.tick();
            LogBook.tick();
            Minecraft mc = Minecraft.getInstance();
            while (MENU_KEY.consumeClick()) {
                if (mc.player != null && mc.gui.screen() == null) mc.gui.setScreen(new SlopScreen());
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
