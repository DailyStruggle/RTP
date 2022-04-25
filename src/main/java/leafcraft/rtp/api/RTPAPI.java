package leafcraft.rtp.api;

import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.MultiConfigParser;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.playerData.TeleportData;
import leafcraft.rtp.api.selection.SelectionAPI;
import leafcraft.rtp.api.selection.region.selectors.memory.shapes.Circle;
import leafcraft.rtp.api.selection.region.selectors.memory.shapes.Square;
import leafcraft.rtp.api.selection.region.selectors.shapes.Shape;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import leafcraft.rtp.api.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * class to hold relevant API functions, outside of Bukkit functionality
 */
public class RTPAPI {
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
    private static RTPAPI instance;

    public RTPAPI(@NotNull Configs configs,
                  @NotNull RTPServerAccessor serverAccessor,
                  @NotNull BiConsumer<Level,String> logMethod) {
        instance = this;
        RTPAPI.logMethod = logMethod;
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

    public static RTPAPI getInstance() {
        return instance;
    }

    private static BiConsumer<Level,String> logMethod;
    protected static void setLogMethod(BiConsumer<Level,String> logMethod) {
        RTPAPI.logMethod = logMethod;
    }
    public static void log(Level level, String str) {
        logMethod.accept(level, str);
    }

    public final SelectionAPI selectionAPI = new SelectionAPI();

    public final ConcurrentHashMap<UUID, TeleportData> latestTeleportData = new ConcurrentHashMap<>();
    public final ConcurrentSkipListSet<UUID> queuedPlayers = new ConcurrentSkipListSet<>();

    public final ConcurrentLinkedQueue<Runnable> setupTeleportPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Runnable> loadChunksPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<Runnable> teleportPipeline = new ConcurrentLinkedQueue<>();

    //todo: set up regions on config init
    //todo: get region by name and parameters
}
