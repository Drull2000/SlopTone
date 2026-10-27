package ru.baritoneru;

import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BaritoneRu.MOD_ID)
public final class BaritoneRu {
    public static final String MOD_ID = "sloptone";
    public static final Logger LOG = LogManager.getLogger("SlopTone");

    public BaritoneRu() {
        LOG.info("SlopTone: constructing");
        Settings.load();
        TickEvent.ClientTickEvent.Pre.BUS.addListener(e -> Bot.get().tick());
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> Bot.get().postTick());
        // returning true cancels the chat message, so commands are never sent to the server
        ClientChatEvent.BUS.addListener((ClientChatEvent e) -> Commands.handleChat(e.getMessage()));
        LOG.info("SlopTone: listeners registered, command prefix '{}'", Settings.prefix);
    }
}
