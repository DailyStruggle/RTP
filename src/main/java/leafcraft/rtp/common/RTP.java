package leafcraft.rtp.common;

import io.github.dailystruggle.commandsapi.common.CommandExecutor;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.common.configuration.ConfigParser;
import leafcraft.rtp.common.configuration.Configs;
import leafcraft.rtp.common.configuration.MultiConfigParser;
import leafcraft.rtp.common.configuration.enums.ConfigKeys;
import leafcraft.rtp.common.configuration.enums.LangKeys;
import leafcraft.rtp.common.configuration.enums.PerformanceKeys;
import leafcraft.rtp.common.configuration.enums.RegionKeys;
import leafcraft.rtp.common.factory.Factory;
import leafcraft.rtp.common.playerData.TeleportData;
import leafcraft.rtp.common.selection.SelectionAPI;
import leafcraft.rtp.common.selection.region.Region;
import leafcraft.rtp.common.selection.region.selectors.memory.shapes.Circle;
import leafcraft.rtp.common.selection.region.selectors.memory.shapes.Square;
import leafcraft.rtp.common.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import leafcraft.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import leafcraft.rtp.common.tasks.SetupTeleport;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;

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
     * minimum number of teleportations to execute per gametick, to prevent bottlenecking during lag spikes
     */
    public static int minRTPExecutions = 1;

    public final Configs configs;
    public final RTPServerAccessor serverAccessor;

    /**
     * only one instance will exist at a time, reset on plugin load
     */
    private static RTP instance;

    public RTP(@NotNull Configs configs,
               @NotNull RTPServerAccessor serverAccessor,
               @NotNull BiConsumer<Level,String> logMethod) {
        instance = this;
        RTP.logMethod = logMethod;
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

    private static BiConsumer<Level,String> logMethod;
    public static void log(Level level, String str) {
        logMethod.accept(level, str);
    }

    public final SelectionAPI selectionAPI = new SelectionAPI();

    public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentSkipListSet<UUID> queuedPlayers = new ConcurrentSkipListSet<>();

    public final RTPTaskPipe setupTeleportPipeline = new RTPTaskPipe();
    public final RTPTaskPipe loadChunksPipeline = new RTPTaskPipe();
    public final RTPTaskPipe teleportPipeline = new RTPTaskPipe();

    public long timeSinceLastTeleport(UUID player) {
        long lastTime;

        TeleportData teleportData = latestTeleportData.get(player);
        if(teleportData != null) lastTime = teleportData.time;
        else lastTime = 0;

        return System.nanoTime() - lastTime;
    }

    /**
     * @param availableTime when to stop, in nanos
     */
    public void execute(long availableTime) {
        //todo: loadChunks
        //todo: teleport
        long start = System.nanoTime();

        setupTeleportPipeline.execute(availableTime/2);
        loadChunksPipeline.execute(availableTime-(start-System.nanoTime()));
        teleportPipeline.execute(availableTime-(start-System.nanoTime()));

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
    }

    //todo: set up regions on config init
    //todo: get region by name and parameters
}
