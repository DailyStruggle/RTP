package leafcraft.rtp.tools.Configuration;

import leafcraft.rtp.RTP;

//route for all config classes
public class Configs {
    private final RTP plugin;
    public Config config;
    public Lang lang;
    public Regions regions;
    public Worlds worlds;
    public String version;

    public Configs(RTP plugin) {
        this.plugin = plugin;
        String name = plugin.getServer().getClass().getPackage().getName();
        this.version = name.substring(name.lastIndexOf('.')+1);
        lang = new Lang(plugin);
        config = new Config(plugin,lang);
        worlds = new Worlds(plugin,lang);
        regions = new Regions(plugin,lang);
    }

    public void refresh() {
        String name = plugin.getServer().getClass().getPackage().getName();
        this.version = name.substring(name.lastIndexOf('.')+1);
        lang = new Lang(plugin);
        config = new Config(plugin,lang);
        worlds = new Worlds(plugin,lang);
        regions = new Regions(plugin,lang);
    }

}
