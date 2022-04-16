package io.github.dailystruggle.rtp_chunkyborder_example;

import leafcraft.rtp.bukkit.tools.selection.TeleportRegion;
import org.bukkit.plugin.java.JavaPlugin;

public final class RTP_ChunkyBorder_Example extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        TeleportRegion.worldBorderInterface = new ChunkyBorder_Interface();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
