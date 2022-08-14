package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
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
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;

/**
 * class to hold relevant API functions, outside of Bukkit functionality
 */
public class RTP {
    long step = 0;
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

    public final Configs configs;
    public static RTPServerAccessor serverAccessor;
    public static RTPEconomy economy = null;

    public static CommandsAPICommand baseCommand;

    /**
     * only one instance will exist at a time, reset on plugin load
     */
    private static RTP instance;

    public RTP() {
        if(serverAccessor == null) throw new IllegalStateException("null serverAccessor");

        new Circle();
        new Square();
        new LinearAdjustor(new ArrayList<>());
        new JumpAdjustor(new ArrayList<>());

        instance = this;
        this.configs = new Configs(serverAccessor.getPluginDirectory());
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

    public final RTPTaskPipe cancelTasks = new RTPTaskPipe();

    public final ConcurrentSkipListSet<UUID> invulnerablePlayers = new ConcurrentSkipListSet<>();

    /**
     * @param availableTime when to stop, in nanos
     */
    public void executeAsyncTasks(long availableTime) {
        long start = System.nanoTime();

        cancelTasks.execute(Long.MAX_VALUE);
        loadChunksPipeline.execute(availableTime-(start-System.nanoTime()));
        setupTeleportPipeline.execute(availableTime-(start-System.nanoTime()));
        miscAsyncTasks.execute(availableTime-(start-System.nanoTime()));

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) configs.getParser(PerformanceKeys.class);

        long period = perf.getNumber(PerformanceKeys.period,10).longValue();
        List<Region> regions = new ArrayList<>(selectionAPI.permRegionLookup.values());
        if(period>1) {
            //step computation according to period
            double increment = ((double) period)/regions.size();
            long location = (long) (increment*step);
            for( int i = 0; i < regions.size(); i++) {
                long l = (long) (((double)i)*increment);
                if(l == location) regions.get(i).execute(availableTime - (start - System.nanoTime()));
            }
            step = (step+1)%period;
        }
        else {
            for( int i = 0; i < regions.size(); i++) {
                regions.get(i).execute(availableTime - (start - System.nanoTime()));
            }
        }
    }


    /**
     * @param availableTime when to stop, in nanos
     */
    public void executeSyncTasks(long availableTime) {
        long start = System.nanoTime();

        cancelTasks.execute(Long.MAX_VALUE);
        chunkCleanupPipeline.execute(availableTime);
        teleportPipeline.execute(availableTime-(start-System.nanoTime()));
        miscSyncTasks.execute(availableTime-(start-System.nanoTime()));
    }

    public Map<List<Integer>, RTPChunk> forceLoads = new ConcurrentHashMap<>();


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
}