package io.github.dailystrugle.rtp_shape_example;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystrugle.rtp_shape_example.shapes.Square_Normal;
import org.bukkit.plugin.java.JavaPlugin;

public final class RTP_Shape_Example extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        RTPAPI.addShape(new Square_Normal());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
