package io.github.dailystruggle.rtp_glide.configuration;

import io.github.dailystruggle.rtp_glide.RTP_Glide;

//route for all config classes
public class Configs {
    private final RTP_Glide plugin;
    public Worlds worlds;
    public String version;

    public Configs( RTP_Glide plugin ) {
        this.plugin = plugin;
        String name = plugin.getServer().getClass().getPackage().getName();
        version = name.substring( name.indexOf( '-' )+1 );
        worlds = new Worlds( plugin );
    }

    public void refresh() {
        String name = plugin.getServer().getClass().getPackage().getName();
        version = name.substring( name.indexOf( '-' )+1 );
        worlds = new Worlds( plugin );
    }
}
