package leafcraft.rtp.api;

import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.playerData.TeleportData;
import leafcraft.rtp.api.selection.SelectionAPI;
import leafcraft.rtp.api.tasks.RTPTask;
import org.jetbrains.annotations.NotNull;

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
        vertAdjustor,
    }
    public EnumMap<factoryNames, Factory<?>> factoryMap = new EnumMap<>(factoryNames.class);

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

    public RTPAPI(@NotNull Configs configs, @NotNull RTPServerAccessor serverAccessor, @NotNull BiConsumer<Level,String> logMethod) {
        RTPAPI.logMethod = logMethod;
        this.configs = configs;
        this.serverAccessor = serverAccessor;
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

    public final ConcurrentLinkedQueue<RTPTask> setupTeleportPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<RTPTask> loadChunksPipeline = new ConcurrentLinkedQueue<>();
    public final ConcurrentLinkedQueue<RTPTask> teleportPipeline = new ConcurrentLinkedQueue<>();

}
