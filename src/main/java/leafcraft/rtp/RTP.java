package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.commands.*;
import leafcraft.rtp.events.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.softdepends.PAPI_expansion;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import java.io.File;

public final class RTP extends JavaPlugin {
    private Configs configs;
    private Cache cache;

//    private OnChunkLoad onChunkLoad;

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
        PaperLib.suggestPaper(this);

        this.metrics = new Metrics(this, 12277);
        this.configs = new Configs(this);
        this.cache = new Cache(this,configs);

        getCommand("wild").setExecutor(new RTPCmd(this, configs, cache));
        getCommand("rtp").setExecutor(new RTPCmd(this, configs, cache));
        getCommand("rtp help").setExecutor(new Help(configs));
        getCommand("rtp reload").setExecutor(new Reload(configs, cache));
        getCommand("rtp setRegion").setExecutor(new SetRegion(this,configs, cache));
        getCommand("rtp setWorld").setExecutor(new SetWorld(this,configs, cache));
        getCommand("rtp fill").setExecutor(new Fill(this,configs, cache));

        getCommand("rtp").setTabCompleter(new TabComplete(this.configs));
        getCommand("wild").setTabCompleter(new TabComplete(this.configs));

        getServer().getPluginManager().registerEvents(new OnPlayerMove(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerTeleport(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerDeath(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerRespawn(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerJoin(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerChangeWorld(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(cache),this);

//        this.onChunkLoad = new OnChunkLoad(this,configs,cache);
//        getServer().getPluginManager().registerEvents(onChunkLoad,this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);

        VaultChecker.setupChat();
        VaultChecker.setupEconomy();
        VaultChecker.setupPermissions();

        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PAPI_expansion(this,configs,cache).register();
        }
    }

    @Override
    public void onDisable() {
//        onChunkLoad.shutdown();
        if(this.cache != null) {
            this.cache.shutdown();
        }

        super.onDisable();
    }
}
