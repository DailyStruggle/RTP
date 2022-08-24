package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPEconomy;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.RTPTeleportCancel;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * class to hold relevant API functions, outside of Bukkit functionality
 */
public class RTP {
    public enum factoryNames {
        shape,
        vert,
        singleConfig,
        multiConfig
    }
    public static EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>(factoryNames.class);
    static {
        Factory<Shape<?>> shapeFactory = new Factory<>();
        factoryMap.put(factoryNames.shape, shapeFactory);

        Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
        factoryMap.put(factoryNames.vert, verticalAdjustorFactory);
        factoryMap.put(factoryNames.singleConfig, new Factory<ConfigParser<?>>());
        factoryMap.put(factoryNames.multiConfig, new Factory<MultiConfigParser<?>>());
    }


    /**
     * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking during lag spikes
     */
    public static int minRTPExecutions = 1;

    public Configs configs;
    public static RTPServerAccessor serverAccessor;
    public static RTPEconomy economy = null;

    public static TreeCommand baseCommand;

    /**
     * only one instance will exist at a time, reset on plugin load
     */
    private static RTP instance;

    public RTP() {
        if(serverAccessor == null) throw new IllegalStateException("null serverAccessor");
        instance = this;

        RTPAPI.addShape(new Circle());
        RTPAPI.addShape(new Square());
        new LinearAdjustor(new ArrayList<>());
        new JumpAdjustor(new ArrayList<>());

        startupTasks.add(() -> configs = new Configs(serverAccessor.getPluginDirectory()));
    }

    public static RTP getInstance() {
        return instance;
    }

    public static void log(Level level, String str) {
        serverAccessor.log(level, str);
    }

    public static void log(Level level, String str, Exception exception) {
        serverAccessor.log(level, str, exception);
    }

    public final SelectionAPI selectionAPI = new SelectionAPI();

    public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();

    public final RTPTaskPipe setupTeleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe loadChunksPipeline = new RTPTaskPipe();
    public final RTPTaskPipe teleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe chunkCleanupPipeline = new RTPTaskPipe();

    public final RTPTaskPipe miscSyncTasks = new RTPTaskPipe();
    public final RTPTaskPipe miscAsyncTasks = new RTPTaskPipe();
    public final RTPTaskPipe startupTasks = new RTPTaskPipe();

    public final RTPTaskPipe cancelTasks = new RTPTaskPipe();

    public final Map<String, FillTask> fillTasks = new ConcurrentHashMap<>();

    public final ConcurrentSkipListSet<UUID> invulnerablePlayers = new ConcurrentSkipListSet<>();


    public static RTPWorld getWorld(RTPPlayer player) {
        //get region from world name, check for overrides
        Set<String> worldsAttempted = new HashSet<>();
        String worldName = player.getLocation().world().name();
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

        return serverAccessor.getRTPWorld(worldName);
    }

    public static void stop() {
        RTP instance = RTP.instance;
        if(instance == null) return;

        for(var e : instance.latestTeleportData.entrySet()) {
            TeleportData data = e.getValue();
            if(data == null || data.completed) continue;
            new RTPTeleportCancel(e.getKey()).run();
        }

        instance.chunkCleanupPipeline.stop();
        instance.miscAsyncTasks.stop();
        instance.miscSyncTasks.stop();
        instance.setupTeleportPipeline.stop();
        instance.loadChunksPipeline.stop();
        instance.teleportPipeline.stop();

        for(var r : instance.selectionAPI.permRegionLookup.values()) {
            r.shutDown();
        }


        FillTask.kill();

        serverAccessor.stop();
    }
}
