package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.effectsapi.EffectFactory;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.rtp.bukkit.commands.commands.RTPCmd;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.BukkitServerAccessor;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.config.BukkitConfigs;
import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.DoTeleport;
import io.github.dailystruggle.rtp.common.tasks.LoadChunks;
import io.github.dailystruggle.rtp.common.tasks.SetupTeleport;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import io.papermc.lib.PaperLib;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A Random Teleportation Spigot/Paper plugin, optimized for operators
 */
@SuppressWarnings("unused")
public final class RTPBukkitPlugin extends JavaPlugin {
    private static RTPBukkitPlugin instance = null;
    private static Metrics metrics;
    private static EffectsAPI effectsAPI = null;

    public static RTPBukkitPlugin getInstance() {
        return instance;
    }

    public final ConcurrentHashMap<UUID,Location> todoTP = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID,Location> lastTP = new ConcurrentHashMap<>();

    private BukkitTask commandProcessing = null;
    private BukkitTask asyncTeleportProcessing = null;
    private BukkitTask syncTeleportProcessing = null;

    public Map<int[],CompletableFuture<Chunk>> chunkLoads = new ConcurrentHashMap<>();

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

            long availableTime = avgTime - currTime;
            availableTime = TimeUnit.MICROSECONDS.toNanos(availableTime);

            if(asyncTeleportProcessing == null) {
                long finalAvailableTime = availableTime;
                asyncTeleportProcessing = Bukkit.getScheduler().runTaskAsynchronously(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            RTP.getInstance().executeAsyncTasks(finalAvailableTime);
                            RTPBukkitPlugin.getInstance().asyncTeleportProcessing = null;
                        }
                );
            }

            if(syncTeleportProcessing == null) {
                long finalAvailableTime = availableTime;
                syncTeleportProcessing = Bukkit.getScheduler().runTask(
                        RTPBukkitPlugin.getInstance(),
                        () -> {
                            RTP.getInstance().executeSyncTasks(finalAvailableTime);
                            RTPBukkitPlugin.getInstance().syncTeleportProcessing = null;
                        }
                );
            }
        }, 80, 1);

        Bukkit.getScheduler().runTaskTimer(this, new TPS(),0,1);
    }

    @Override
    public void onDisable() {
        if(commandProcessing!=null) commandProcessing.cancel();
//        onChunkLoad.shutdown();
        metrics = null;

        for(CompletableFuture<Chunk> chunk : chunkLoads.values()) {
            try {
                chunk.cancel(true);
            } catch (CancellationException ignored) {

            }
        }

        for(var r : RTP.getInstance().selectionAPI.permRegionLookup.values()) {
            r.shutDown();
        }

        super.onDisable();
    }

    private void setupBukkitEvents() {
        SetupTeleport.preActions.add(setupTeleport -> {
            PreSetupTeleportEvent preSetupTeleportEvent = new PreSetupTeleportEvent(setupTeleport);
            Bukkit.getPluginManager().callEvent(preSetupTeleportEvent);
            if(preSetupTeleportEvent.isCancelled()) setupTeleport.setCancelled(true);
        });

        SetupTeleport.postActions.add(setupTeleport -> {
            PostSetupTeleportEvent postSetupTeleportEvent = new PostSetupTeleportEvent(setupTeleport);
            Bukkit.getPluginManager().callEvent(postSetupTeleportEvent);
        });

        LoadChunks.preActions.add(loadChunks -> {
            PreLoadChunksEvent preLoadChunksEvent = new PreLoadChunksEvent(loadChunks);
            Bukkit.getPluginManager().callEvent(preLoadChunksEvent);
            if(preLoadChunksEvent.isCancelled()) loadChunks.setCancelled(true);
        });

        LoadChunks.postActions.add(loadChunks -> {
            PostLoadChunksEvent postLoadChunksEvent = new PostLoadChunksEvent(loadChunks);
            Bukkit.getPluginManager().callEvent(postLoadChunksEvent);
        });

        DoTeleport.preActions.add(doTeleport -> {
            PreTeleportEvent preTeleportEvent = new PreTeleportEvent(doTeleport);
            Bukkit.getPluginManager().callEvent(preTeleportEvent);
            if(preTeleportEvent.isCancelled()) {
                doTeleport.setCancelled(true);
                return;
            }
            if(doTeleport.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                EffectFactory.buildEffects("rtp.effect.preTeleport",player.getEffectivePermissions());
            }
        });

        DoTeleport.postActions.add(doTeleport -> {
            PostTeleportEvent postTeleportEvent = new PostTeleportEvent(doTeleport);
            Bukkit.getPluginManager().callEvent(postTeleportEvent);
            if(doTeleport.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                EffectFactory.buildEffects("rtp.effect.postTeleport",player.getEffectivePermissions());
            }
        });

        Region.onPlayerQueuePush.add((region, uuid) -> {
            PlayerQueuePushEvent playerQueuePushEvent = new PlayerQueuePushEvent(region, uuid);
            Bukkit.getPluginManager().callEvent(playerQueuePushEvent);
        });

        Region.onPlayerQueuePop.add((region, uuid) -> {
            PlayerQueuePopEvent playerQueuePopEvent = new PlayerQueuePopEvent(region, uuid);
            Bukkit.getPluginManager().callEvent(playerQueuePopEvent);
        });

        if(effectsAPI == null) effectsAPI = EffectsAPI.getInstance();
        if(effectsAPI == null) {
            effectsAPI = new EffectsAPI(this);
        }
    }
}
