package io.github.dailystruggle.rtp.bukkit;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.effectsapi.EffectFactory;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.events.*;
import io.github.dailystruggle.rtp.bukkit.server.BukkitServerAccessor;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.*;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.*;
import io.papermc.lib.PaperLib;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
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
    private static final EffectsAPI effectsAPI = null;

    public static RTPBukkitPlugin getInstance() {
        return instance;
    }

    public final ConcurrentHashMap<UUID,Location> todoTP = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID,Location> lastTP = new ConcurrentHashMap<>();

    public BukkitTask commandTimer = null;
    public BukkitTask commandProcessing = null;
    public BukkitTask teleportTimer = null;
    public BukkitTask asyncTeleportProcessing = null;
    public BukkitTask syncTeleportProcessing = null;

    public Map<List<Integer>,CompletableFuture<Chunk>> chunkLoads = new ConcurrentHashMap<>();

    @Override
    public void onLoad() {
        instance = this;
        RTP.serverAccessor = new BukkitServerAccessor();

        new RTP(); //constructor updates API instance
    }

    @Override
    public void onEnable() {
        PaperLib.suggestPaper(this);
        metrics = new Metrics(this,12277);

        RTP.getInstance().miscSyncTasks.execute(Long.MAX_VALUE);
        RTP.getInstance().miscAsyncTasks.execute(Long.MAX_VALUE);

        RTPCmdBukkit mainCommand = new RTPCmdBukkit(this);
        RTP.baseCommand = mainCommand;

        Objects.requireNonNull(getCommand("rtp")).setExecutor(mainCommand);
        Objects.requireNonNull(getCommand("rtp")).setTabCompleter(mainCommand);
        Objects.requireNonNull(getCommand("wild")).setExecutor(mainCommand);
        Objects.requireNonNull(getCommand("wild")).setTabCompleter(mainCommand);

        commandTimer = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            long avgTime = TPS.timeSinceTick(20) / 20;
            long currTime = TPS.timeSinceTick(1);
            CommandsAPI.execute(avgTime - currTime);
        }, 40, 1);

        teleportTimer = Bukkit.getScheduler().runTaskTimer(this, () -> {
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

        setupBukkitEvents();
        RTP.getInstance().miscAsyncTasks.add(this::setupEffects);

        Bukkit.getScheduler().runTaskTimer(this, new TPS(),0,1);

        RTP.getInstance().executeSyncTasks(Long.MAX_VALUE);
        RTP.getInstance().executeAsyncTasks(Long.MAX_VALUE);

        SendMessage.sendMessage(null,null);
    }

    @Override
    public void onDisable() {
        if(commandTimer!=null) commandTimer.cancel();
        try {
            TimeUnit.MILLISECONDS.sleep(20);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(commandProcessing!=null) commandProcessing.cancel();
        if(teleportTimer!=null) teleportTimer.cancel();
        try {
            TimeUnit.MILLISECONDS.sleep(20);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(asyncTeleportProcessing!=null) asyncTeleportProcessing.cancel();
        if(syncTeleportProcessing!=null) syncTeleportProcessing.cancel();

//        onChunkLoad.shutdown();
        metrics = null;

        for(CompletableFuture<Chunk> chunk : chunkLoads.values()) {
            try {
                chunk.cancel(true);
            } catch (CancellationException ignored) {

            }
        }

        RTP.stop();

        super.onDisable();
    }

    private void setupBukkitEvents() {
        Bukkit.getPluginManager().registerEvents(new OnEventTeleports(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerChangeWorld(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerDamage(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerJoin(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerMove(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerQuit(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerRespawn(), this);
        Bukkit.getPluginManager().registerEvents(new OnPlayerTeleport(), this);

        EffectsAPI.init(this);
    }

    private void setupEffects() {
        FactoryValue<PerformanceKeys> parser = RTP.getInstance().configs.getParser(PerformanceKeys.class);

        boolean effectParsing = Boolean.parseBoolean(parser.getData().get(PerformanceKeys.effectParsing).toString());

        SetupTeleport.preActions.add(task -> {
            PreSetupTeleportEvent event = new PreSetupTeleportEvent(task);
            Bukkit.getPluginManager().callEvent(event);
            if(event.isCancelled()) task.setCancelled(true);
            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.presetup", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        SetupTeleport.postActions.add((task, aBoolean) -> {
            if(!aBoolean) return;
            PostSetupTeleportEvent event = new PostSetupTeleportEvent(task);
            Bukkit.getPluginManager().callEvent(event);
            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.postSetup", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        LoadChunks.preActions.add(task -> {
            PreLoadChunksEvent event = new PreLoadChunksEvent(task);
            Bukkit.getPluginManager().callEvent(event);

            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.presetup", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        LoadChunks.postActions.add(task -> {
            PostLoadChunksEvent event = new PostLoadChunksEvent(task);
            Bukkit.getPluginManager().callEvent(event);

            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.postload", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        DoTeleport.preActions.add(task -> {
            PreTeleportEvent event = new PreTeleportEvent(task);
            Bukkit.getPluginManager().callEvent(event);

            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.preteleport", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        DoTeleport.postActions.add(task -> {
            PostTeleportEvent event = new PostTeleportEvent(task);
            Bukkit.getPluginManager().callEvent(event);

            if(effectParsing && task.player() instanceof BukkitRTPPlayer rtpPlayer) {
                Player player = rtpPlayer.player();
                Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                    EffectFactory.buildEffects("rtp.effect.postteleport", player.getEffectivePermissions()).forEach(effect -> {
                        effect.setTarget(player);
                        effect.run();
                    });
                });
            }
        });

        RTPTeleportCancel.postActions.add(task -> {
            UUID uuid = task.getPlayerId();
            Player player = Bukkit.getPlayer(uuid);

            if(player == null) return;

            TeleportCancelEvent event = new TeleportCancelEvent(uuid);
            Bukkit.getPluginManager().callEvent(event);

            if(!effectParsing) return;

            Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                EffectFactory.buildEffects("rtp.effect.cancel", player.getEffectivePermissions()).forEach(effect -> {
                    effect.setTarget(player);
                    effect.run();
                });
            });
        });

        Region.onPlayerQueuePush.add((region, uuid) -> {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null) return;

            PlayerQueuePushEvent event = new PlayerQueuePushEvent(region,uuid);
            Bukkit.getPluginManager().callEvent(event);

            if(!effectParsing) return;

            Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                EffectFactory.buildEffects("rtp.effect.queuepush", player.getEffectivePermissions()).forEach(effect -> {
                    effect.setTarget(player);
                    effect.run();
                });
            });
        });

        Region.onPlayerQueuePop.add((region, uuid) -> {
            Player player = Bukkit.getPlayer(uuid);
            if(player == null) return;

            PlayerQueuePopEvent event = new PlayerQueuePopEvent(region,uuid);
            Bukkit.getPluginManager().callEvent(event);

            if(!effectParsing) return;

            Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
                EffectFactory.buildEffects("rtp.effect.queuepop", player.getEffectivePermissions()).forEach(effect -> {
                    effect.setTarget(player);
                    effect.run();
                });
            });
        });

        if(effectParsing) {
            EffectFactory.addPermissions("rtp.effect.preSetup");
            EffectFactory.addPermissions("rtp.effect.postSetup");
            EffectFactory.addPermissions("rtp.effect.preLoad");
            EffectFactory.addPermissions("rtp.effect.postLoad");
            EffectFactory.addPermissions("rtp.effect.preTeleport");
            EffectFactory.addPermissions("rtp.effect.postTeleport");
            EffectFactory.addPermissions("rtp.effect.cancel");
            EffectFactory.addPermissions("rtp.effect.queuePush");
        }
    }

    public static Region getRegion(Player player) {
        //get region from world name, check for overrides
        Set<String> worldsAttempted = new HashSet<>();
        Set<String> regionsAttempted = new HashSet<>();

        String worldName = player.getWorld().getName();
        MultiConfigParser<WorldKeys> worldParsers = (MultiConfigParser<WorldKeys>) RTP.getInstance().configs.multiConfigParserMap.get(WorldKeys.class);
        ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
        boolean requirePermission = Boolean.parseBoolean(worldParser.getConfigValue(WorldKeys.requirePermission,false).toString());

        while(requirePermission && !player.hasPermission("rtp.worlds."+worldName)) {
            if(worldsAttempted.contains(worldName)) throw new IllegalStateException("infinite override loop detected at world - " + worldName);
            worldsAttempted.add(worldName);

            worldName = String.valueOf(worldParser.getConfigValue(WorldKeys.override,"default"));
            worldParser = worldParsers.getParser(worldName);
            requirePermission = Boolean.parseBoolean(worldParser.getConfigValue(WorldKeys.requirePermission,false).toString());
        }

        String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
        MultiConfigParser<RegionKeys> regionParsers = (MultiConfigParser<RegionKeys>) RTP.getInstance().configs.multiConfigParserMap.get(RegionKeys.class);
        ConfigParser<RegionKeys> regionParser = regionParsers.getParser(regionName);
        requirePermission = Boolean.parseBoolean(regionParser.getConfigValue(RegionKeys.requirePermission,false).toString());

        while(requirePermission && !player.hasPermission("rtp.regions."+regionName)) {
            if(regionsAttempted.contains(regionName)) throw new IllegalStateException("infinite override loop detected at region - " + regionName);
            regionsAttempted.add(regionName);

            regionName = String.valueOf(regionParser.getConfigValue(RegionKeys.override,"default"));
            regionParser = regionParsers.getParser(regionName);
            requirePermission = Boolean.parseBoolean(regionParser.getConfigValue(RegionKeys.requirePermission,false).toString());
        }
        return RTP.getInstance().selectionAPI.permRegionLookup.get(regionName);
    }
}
