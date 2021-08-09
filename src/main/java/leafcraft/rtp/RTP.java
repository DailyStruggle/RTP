package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.commands.*;
import leafcraft.rtp.events.OnChunkUnload;
import leafcraft.rtp.events.OnPlayerMove;
import leafcraft.rtp.events.OnPlayerQuit;
import leafcraft.rtp.events.OnPlayerTeleport;
import leafcraft.rtp.tasks.QueueLocation;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import leafcraft.rtp.tools.Metrics;
import leafcraft.rtp.tools.TPS;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class RTP extends JavaPlugin {
    private Config config;

    public Cache cache;

    public ConcurrentHashMap<String,BukkitTask> timers = new ConcurrentHashMap<>();
    public ConcurrentLinkedQueue<BukkitTask> queueTasks = new ConcurrentLinkedQueue<>();

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
        RTP plugin = this;
        PaperLib.suggestPaper(this);


        this.metrics = new Metrics(this, 12277);
        this.config = new Config(this, cache);
        this.cache = new Cache(this.config);
        this.config.setCache(cache);

        getCommand("wild").setExecutor(new RTPCmd(this, this.config, this.cache));
        getCommand("rtp").setExecutor(new RTPCmd(this, this.config, this.cache));
        getCommand("rtp set").setExecutor(new SetCmd(this,this.config));
        getCommand("rtp help").setExecutor(new Help(this.config));
        getCommand("rtp reload").setExecutor(new Reload(this.config, this.cache));

        getCommand("rtp").setTabCompleter(new TabComplete(this.config));
        getCommand("wild").setTabCompleter(new TabComplete(this.config));

        getServer().getPluginManager().registerEvents(new OnPlayerMove(this,this.config,this.cache),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(this.cache),this);
        getServer().getPluginManager().registerEvents(new OnChunkUnload(this.cache),this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);

        int period = 20*(Integer)(config.getConfigValue("queuePeriod",30));
        if(period > 0) {
            int i = 0;
            int iter_len = period / Bukkit.getWorlds().size();
            for (World world : Bukkit.getWorlds()) {
                timers.put(world.getName(), Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                    double tps = TPS.getTPS();
                    double minTps = (Double)config.getConfigValue("minTPS",19.0);
                    if(tps < minTps) return;
                    Integer queueLen = (Integer)config.getWorldSetting(world.getName(),"queueLen",10);
                    if (!cache.locationQueue.containsKey(world.getName()))
                        cache.locationQueue.put(world.getUID(), new ConcurrentLinkedQueue<>());
                    if (cache.locationQueue.get(world.getUID()).size() < queueLen) {
                        BukkitTask task = new QueueLocation(config, cache, world).runTaskAsynchronously(plugin);
                        queueTasks.add(task);
                    }
                }, 100 + i, period));
                i += iter_len;
            }
        }
    }

    @Override
    public void onDisable() {
        if(this.cache == null) {
            super.onDisable();
            return;
        }
        this.cache.shutdown();
        for(Map.Entry<String,BukkitTask> entry : timers.entrySet()) {
            entry.getValue().cancel();
            timers.remove(entry);
        }
        for(BukkitTask task : queueTasks) {
            task.cancel();
        }
        super.onDisable();
    }
}
