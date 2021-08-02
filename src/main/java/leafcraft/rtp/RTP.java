package leafcraft.rtp;

import leafcraft.rtp.commands.*;
import leafcraft.rtp.events.OnPlayerMove;
import leafcraft.rtp.events.OnPlayerQuit;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.Metrics;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import java.io.File;


public final class RTP extends JavaPlugin {
    private Config config;
    public Cache cache;

    private Metrics metrics;

    public RTP()
    {
        super();
    }

    protected RTP(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file)
    {
        super(loader, description, dataFolder, file);
    }


    @Override
    public void onEnable() {
        //PaperLib.suggestPaper(this);

        this.metrics = new Metrics(this, 12277);
        this.cache = new Cache();
        this.config = new Config(this, cache);

        getCommand("wild").setExecutor(new RTPCmd(this, this.config, this.cache));
        getCommand("rtp").setExecutor(new RTPCmd(this, this.config, this.cache));
        getCommand("rtp set").setExecutor(new SetCmd(this,this.config));
        getCommand("rtp help").setExecutor(new Help(this.config));
        getCommand("rtp reload").setExecutor(new Reload(this.config));

        getCommand("rtp").setTabCompleter(new TabComplete(this.config));
        getCommand("wild").setTabCompleter(new TabComplete(this.config));

        getServer().getPluginManager().registerEvents(new OnPlayerMove(this.config,this.cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(this.cache),this);
    }

    @Override
    public void onDisable() {
        this.cache.shutdown();
    }
}
