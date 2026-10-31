package ru.baritoneru;

import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.TickEvent;

/** Client-only setup, kept apart so a dedicated server never loads client classes. */
final class ClientInit {
    static void init() {
        TickEvent.ClientTickEvent.Pre.BUS.addListener(e -> Bot.get().tick());
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> Bot.get().postTick());
        // returning true cancels the chat message, so commands are never sent to the server
        ClientChatEvent.BUS.addListener((ClientChatEvent e) -> Commands.handleChat(e.getMessage()));
        PlacedBlocks.register();
        BaritoneRu.LOG.info("SlopTone: client listeners registered, command prefix '{}'", Settings.prefix);
    }
}
