package leafcraft.rtp.bukkit;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.papermc.lib.PaperLib;
import leafcraft.rtp.common.RTP;
import leafcraft.rtp.bukkit.commonBukkitImpl.BukkitServerAccessor;
import leafcraft.rtp.bukkit.commonBukkitImpl.config.BukkitConfigs;
import leafcraft.rtp.bukkit.commands.commands.RTPCmd;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.common.tasks.TPS;
import org.bstats.bukkit.Metrics;
import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A Random Teleportation Spigot/Paper plugin, optimized for operators
 */
@SuppressWarnings("unused")
public final class RTPBukkitPlugin extends JavaPlugin {
    private static RTPBukkitPlugin instance = null;
    private static Metrics metrics;

    public static RTPBukkitPlugin getInstance() {
        return instance;
    }

    public final ConcurrentHashMap<UUID,Location> todoTP = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID,Location> lastTP = new ConcurrentHashMap<>();

    private BukkitTask commandProcessing = null;
    private BukkitTask teleportProcessing = null;

    @Override
    public void onEnable() {
        PaperLib.suggestPaper(this);
        metrics = new Metrics(this,12277);

        instance = this;
        new RTP(new BukkitConfigs(getDataFolder()), new BukkitServerAccessor(), SendMessage::log); //constructor updates API instance

        BukkitTreeCommand mainCommand = new RTPCmd(this);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(mainCommand);
        Objects.requireNonNull(getCommand("rtp")).setTabCompleter(mainCommand);
        Objects.requireNonNull(getCommand("wild")).setExecutor(mainCommand);
        Objects.requireNonNull(getCommand("wild")).setTabCompleter(mainCommand);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);

            if(commandProcessing == null) {
                commandProcessing = Bukkit.getScheduler().runTaskAsynchronously(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            CommandsAPI.execute(avgTime - currTime);
                            RTPBukkitPlugin.getInstance().commandProcessing = null;
                        }
                );
            }
        }, 40, 1);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);

            if(teleportProcessing == null) {
                teleportProcessing = Bukkit.getScheduler().runTaskAsynchronously(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            RTP.getInstance().execute(avgTime - currTime);
                            RTPBukkitPlugin.getInstance().teleportProcessing = null;
                        }
                );
            }
        }, 80, 1);

        Bukkit.getScheduler().runTaskTimer(this, new TPS(),0,1);
    }

    @Override
    public void onDisable() {
        commandProcessing.cancel();
//        onChunkLoad.shutdown();
        metrics = null;

        super.onDisable();
    }

    public static void doOnEnable(Consumer<RTPBukkitPlugin> action) {
    }
}
