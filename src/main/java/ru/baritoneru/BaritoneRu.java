package ru.baritoneru;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BaritoneRu.MOD_ID)
public final class BaritoneRu {
    public static final String MOD_ID = "sloptone";
    public static final Logger LOG = LogManager.getLogger("SlopTone");

    public BaritoneRu() {
        LOG.info("SlopTone: constructing ({})", FMLEnvironment.dist);
        Settings.load();
        // the "who placed this block" records work on dedicated servers too
        ServerRecorder.register();
        // everything else is the bot, which only exists on the client
        if (FMLEnvironment.dist == Dist.CLIENT) ClientInit.init();
    }
}
