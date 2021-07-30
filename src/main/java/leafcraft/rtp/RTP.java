package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.commands.Help;
import leafcraft.rtp.commands.RTPCmd;
import leafcraft.rtp.commands.Reload;
import leafcraft.rtp.events.OnPlayerMove;
import leafcraft.rtp.events.OnPlayerQuit;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.Metrics;
import org.bukkit.plugin.java.JavaPlugin;


public final class RTP extends JavaPlugin {
    private Config config;
    private Cache cache;

    private Metrics metrics;

    @Override
    public void onEnable() {
        PaperLib.suggestPaper(this);

        this.metrics = new Metrics(this, 12212);
        this.cache = new Cache();
        this.config = new Config(this, cache);

        getCommand("rtp").setExecutor(new RTPCmd(this, this.config, this.cache));
        getCommand("rtp help").setExecutor(new Help(this.config));
        getCommand("rtp reload").setExecutor(new Reload(this.config));

        getServer().getPluginManager().registerEvents(new OnPlayerMove(this.config,this.cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(this.cache),this);
    }

    @Override
    public void onDisable() {
        this.cache.shutdown();
    }
}
