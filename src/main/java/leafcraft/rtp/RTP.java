package leafcraft.rtp;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.commands.*;
import leafcraft.rtp.customEventListeners.OnRandomPreTeleport;
import leafcraft.rtp.customEventListeners.OnRandomTeleport;
import leafcraft.rtp.customEventListeners.OnTeleportCancel;
import leafcraft.rtp.spigotEventListeners.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.LandsChecker;
import leafcraft.rtp.tools.softdepends.PAPI_expansion;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A Random Teleportation Spigot/Paper plugin, optimized for operators
 */
public final class RTP extends JavaPlugin {
    private static Configs configs = null;
    private static Cache cache = null;
    private static RTP plugin = null;
    private static Metrics metrics;
    private static int bukkitVersion;

//    private OnChunkLoad onChunkLoad;

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
        String version = Bukkit.getServer().getBukkitVersion().split("-")[0];
        version = version.substring(version.indexOf('.')+1,version.lastIndexOf('.'));
        bukkitVersion = Integer.parseInt(version);
        PaperLib.suggestPaper(this);
        metrics = new Metrics(this,12277);
//        try {
//            PaperLib.suggestPaper(this);
//        } catch (NoClassDefFoundError ignored) {
//
//        }

        RTP.plugin = this;
        RTP.configs = new Configs();
        RTP.cache = new Cache();

        RTPCmd rtpCmd = new RTPCmd();
        Help help = new Help();
        Reload reload = new Reload();
        SetRegion setRegion = new SetRegion();
        SetWorld setWorld = new SetWorld();
        Fill fill = new Fill();

        try {
            Objects.requireNonNull(getCommand("wild")).setExecutor(rtpCmd);
            Objects.requireNonNull(getCommand("rtp")).setExecutor(rtpCmd);
        }
        catch (NullPointerException ignored) { }

        try {
            TabComplete tabComplete = new TabComplete();
            Objects.requireNonNull(getCommand("rtp")).setTabCompleter(tabComplete);
            Objects.requireNonNull(getCommand("wild")).setTabCompleter(tabComplete);
        }
        catch (NullPointerException ignored) { }

        rtpCmd.addCommandHandle("help", "rtp.help", help);
        rtpCmd.addCommandHandle("reload", "rtp.reload", reload);
        rtpCmd.addCommandHandle("setRegion", "rtp.setRegion", setRegion);
        rtpCmd.addCommandHandle("setWorld", "rtp.setWorld", setWorld);
        rtpCmd.addCommandHandle("fill", "rtp.fill", fill);

//        try {
//            Objects.requireNonNull(getCommand("rtp help")).setExecutor(help);
//            Objects.requireNonNull(getCommand("rtp reload")).setExecutor(reload);
//            Objects.requireNonNull(getCommand("rtp setRegion")).setExecutor(setRegion);
//            Objects.requireNonNull(getCommand("rtp setWorld")).setExecutor(setWorld);
//            Objects.requireNonNull(getCommand("rtp fill")).setExecutor(fill);
//        } catch (NullPointerException ignored) { }

        getServer().getPluginManager().registerEvents(new OnPlayerMove(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerDeath(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerRespawn(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerJoin(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerChangeWorld(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(),this);
        getServer().getPluginManager().registerEvents(new OnRandomPreTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnRandomTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnTeleportCancel(),this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);

        VaultChecker.setupChat();
        VaultChecker.setupEconomy();
        VaultChecker.setupPermissions();

        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PAPI_expansion(this,configs,cache).register();
        }

        LandsChecker.landsSetup(this);
    }

    @Override
    public void onDisable() {
//        onChunkLoad.shutdown();
        if(cache != null) {
            cache.shutdown();
        }
        cache = null;
        configs = null;
        metrics = null;

        super.onDisable();
    }

    /**
     * get a region by name
     * @param regionName - name of region
     * @return region by that name, or null if none
     */
    public static TeleportRegion getRegion(String regionName) {
        Map<String,String> params = new HashMap<>();
        params.put("region",regionName);

        String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params,configs);
        if(!cache.permRegions.containsKey(randomSelectParams)) return null;
        return cache.permRegions.get(randomSelectParams);
    }

    /**
     * add or update a region by name
     * @param regionName - name of region
     * @param params - mapped parameters, based on parameters in regions.yml
     * @return the corresponding TeleportRegion
     */
    public static TeleportRegion setRegion(String regionName, Map<String,String> params) {
        params.put("region",regionName);

        String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
        if (worldName == null || worldName.equals("") || !configs.worlds.checkWorldExists(worldName)) {
            return null;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),params,configs);
        if(cache.permRegions.containsKey(randomSelectParams)) {
            cache.permRegions.get(randomSelectParams).shutdown();
        }

        configs.regions.setRegion(regionName,randomSelectParams);
        return cache.permRegions.put(randomSelectParams,
                new TeleportRegion(regionName,params));
    }

    /**
     * add any location check, after plugin initialization
     * @param methodHandle - a method that takes an org.bukkit.Location and returns a boolean
     */
    public static void addLocationCheck(@NotNull MethodHandle methodHandle) {
        configs.addLocationCheck(methodHandle);
    }

    @NotNull
    public static RTP getPlugin() {
        return plugin;
    }

    @NotNull
    public static Configs getConfigs() {
        return configs;
    }

    @NotNull
    public static Cache getCache() {
        return cache;
    }

    public static int getBukkitVersion() {
        return bukkitVersion;
    }
}
