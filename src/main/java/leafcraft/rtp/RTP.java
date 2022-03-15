package leafcraft.rtp;

import io.github.dailystruggle.effectsapi.EffectBuilder;
import io.papermc.lib.PaperLib;
import leafcraft.rtp.API.commands.CommandAPI;
import leafcraft.rtp.API.RTPAPI;
import leafcraft.rtp.customEventListeners.*;
import leafcraft.rtp.spigotEventListeners.*;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.softdepends.LandsChecker;
import leafcraft.rtp.tools.softdepends.PAPI_expansion;
import leafcraft.rtp.tools.softdepends.VaultChecker;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Random Teleportation Spigot/Paper plugin, optimized for operators
 */
@SuppressWarnings("unused")
public final class RTP extends JavaPlugin {
    private static Configs configs = null;
    private static Cache cache = null;
    private static RTP instance = null;
    private static Metrics metrics;

    public static RTP getInstance() {
        return instance;
    }

    public final ConcurrentHashMap<UUID,Location> todoTP = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID,Location> lastTP = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        PaperLib.suggestPaper(this);
        metrics = new Metrics(this,12277);

        RTP.instance = this;

        //initialize server version
        RTPAPI.getServerIntVersion();

        CommandAPI.initDefaultCommands();

        if(leafcraft.rtp.tools.configuration.Configs.config.onEventParsing) {
            getServer().getPluginManager().registerEvents(new OnEvent(),this);
        }

        if(leafcraft.rtp.tools.configuration.Configs.config.effectParsing) {
            getServer().getPluginManager().registerEvents(new TeleportEffects(),this);
        }

        if(leafcraft.rtp.tools.configuration.Configs.config.checkChunks) {
            getServer().getPluginManager().registerEvents(new OnChunkLoad(),this);
        }

        getServer().getPluginManager().registerEvents(new OnPlayerDeath(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerRespawn(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerJoin(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerChangeWorld(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerMove(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerDamage(),this);
        getServer().getPluginManager().registerEvents(new OnPlayerQuit(),this);
        getServer().getPluginManager().registerEvents(new OnRandomPreTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnRandomTeleport(),this);
        getServer().getPluginManager().registerEvents(new OnTeleportCancel(),this);
        getServer().getPluginManager().registerEvents(new PlayerQueueListener(),this);

        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new TPS(), 100L, 1L);

        VaultChecker.setupEconomy();
        VaultChecker.setupPermissions();

        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PAPI_expansion(this,configs,cache).register();
        }

        if (Configs.config.effectParsing) {
            EffectBuilder.initializePermissions("rtp.command");
            EffectBuilder.initializePermissions("rtp.preTeleport");
            EffectBuilder.initializePermissions("rtp.teleport");
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
        Configs = null;
        metrics = null;

        super.onDisable();
    }
}
