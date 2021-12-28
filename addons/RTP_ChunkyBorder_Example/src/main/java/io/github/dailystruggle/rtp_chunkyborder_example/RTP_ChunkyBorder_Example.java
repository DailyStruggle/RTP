package io.github.dailystruggle.rtp_chunkyborder_example;

import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.plugin.java.JavaPlugin;

public final class RTP_ChunkyBorder_Example extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        ChunkyBorder_Interface cbi = new ChunkyBorder_Interface();
        TeleportRegion.worldBorderInterface = cbi;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
