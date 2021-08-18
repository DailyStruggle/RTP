package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.commands.*;
import leafcraft.rtp.events.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.Metrics;
import leafcraft.rtp.tools.TPS;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class RTP extends JavaPlugin {
    private Configs configs;

    public Cache cache;

    public ConcurrentHashMap<UUID,BukkitTask> fillers = new ConcurrentHashMap<>();

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
        getCommand("rtp setRegion").setExecutor(new SetRegion(this,configs));
        getCommand("rtp setWorld").setExecutor(new SetWorld(this,configs));
//        getCommand("rtp fill").setExecutor(new Fill(this,this.config));

        getCommand("rtp").setTabCompleter(new TabComplete(this.configs));
        getCommand("wild").setTabCompleter(new TabComplete(this.configs));

        getServer().getPluginManager().registerEvents(new OnPlayerMove(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerTeleport(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerDeath(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerRespawn(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerJoin(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerChangeWorld(this,configs,cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(cache),this);
        getServer().getPluginManager().registerEvents(new OnChunkUnload(cache),this);
        getServer().getPluginManager().registerEvents(new OnChunkLoad(configs,cache),this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);



//        Bukkit.getScheduler().runTaskTimer(this, () -> {
//            Player me = Bukkit.getPlayer("leaf26");
//            if(me!=null && !cache.todoTP.containsKey(me.getName())) {
//                me.performCommand("rtp");
//            }
//
//            me = Bukkit.getPlayer("leaf_26");
//            if(me!=null && !cache.todoTP.containsKey(me.getName())) {
//                me.performCommand("rtp");
//            }
//        }, 240, 10);
    }

    @Override
    public void onDisable() {
        if(this.cache == null) {
            super.onDisable();
            return;
        }
        for(BukkitTask task : fillers.values()) {
            task.cancel();
        }

        this.cache.shutdown();
        super.onDisable();
    }
}
