package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
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
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    public EnumMap<factoryNames, Factory<?>> factoryMap;

    /**
     * minimum number of teleportations to executeAsyncTasks per gametick, to prevent bottlenecking during lag spikes
     */
    public static int minRTPExecutions = 1;

    public final Configs configs;
    public final RTPServerAccessor serverAccessor;

    /**
     * only one instance will exist at a time, reset on plugin load
     */
    private static RTP instance;

    public RTP(@NotNull Configs configs,
               @NotNull RTPServerAccessor serverAccessor) {
        instance = this;
        this.configs = configs;
        this.serverAccessor = serverAccessor;

        factoryMap = new EnumMap<>(factoryNames.class);

        Factory<Shape<?>> shapeFactory = new Factory<>();
        factoryMap.put(factoryNames.shape, shapeFactory);
        new Circle();
        new Square();

        Factory<VerticalAdjustor<?>> verticalAdjustorFactory = new Factory<>();
        factoryMap.put(factoryNames.vert, verticalAdjustorFactory);
        new LinearAdjustor(new ArrayList<>());
        new JumpAdjustor(new ArrayList<>());

        factoryMap.put(factoryNames.singleConfig, new Factory<ConfigParser<?>>());
        factoryMap.put(factoryNames.multiConfig, new Factory<MultiConfigParser<?>>());
    }

    public static RTP getInstance() {
        return instance;
    }

    public static void log(Level level, String str) {
        getInstance().serverAccessor.log(level, str);
    }

    public static void log(Level level, String str, Exception exception) {
        getInstance().serverAccessor.log(level, str);
    }

    public final SelectionAPI selectionAPI = new SelectionAPI();

    public final ConcurrentHashMap<UUID, TeleportData> priorTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentSkipListSet<UUID> processingPlayers = new ConcurrentSkipListSet<>();

    public final RTPTaskPipe setupTeleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe loadChunksPipeline = new RTPTaskPipe();
    public final RTPTaskPipe teleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe chunkCleanupPipeline = new RTPTaskPipe();

    /**
     * @param availableTime when to stop, in nanos
     */
    public void executeAsyncTasks(long availableTime) {
        long start = System.nanoTime();

        loadChunksPipeline.execute(availableTime);
        setupTeleportPipeline.execute(availableTime-(start-System.nanoTime()));

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

        chunkCleanupPipeline.execute(availableTime);
        teleportPipeline.execute(availableTime-(start-System.nanoTime()));
    }

    public Map<int[], RTPChunk> forceLoads = new ConcurrentHashMap<>();

    public void cancelTeleport(UUID uuid) {
        if(!latestTeleportData.containsKey(uuid)) return;
        TeleportData teleportData = latestTeleportData.get(uuid);
        if(!teleportData.nextTask.isCancelled())
            teleportData.nextTask.setCancelled(true);
        teleportData.completed = true;

        ConfigParser<EconomyKeys> eco = (ConfigParser<EconomyKeys>) configs.configParserMap.get(EconomyKeys.class);
        boolean refund;
        Object refundObj = eco.getConfigValue(EconomyKeys.refundOnCancel, true);
        if(refundObj instanceof Boolean b) refund = b;
        else {
            refund = Boolean.parseBoolean(String.valueOf(refundObj));
        }

        TeleportData priorData = priorTeleportData.getOrDefault(uuid, new TeleportData());
        priorData.completed = true;
        if(!refund) {
            priorData.time = teleportData.time;
            priorData.cost = teleportData.cost;
        }
        latestTeleportData.put(uuid, priorData);


    }
}
