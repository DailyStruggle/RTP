package io.github.dailystruggle.helpers.stresstestrtp;

import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.server.api.ServerAccessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Platform-neutral host SPI for {@code stresstest-core} (ADR-122 Decision 2 & 3).
 *
 * <p>Extends {@link ServerAccessor} from {@code api/server-api} for server
 * identity and logging, and provides the only platform seam consumed by the
 * core harness. Any operation a platform does not offer (such as listener/task
 * censuses or chunk tickets on Fabric, or pending-task census on Folia) is
 * reported via {@link Census#notMeasured()} ({@link #NOT_MEASURED}) and never
 * as zero or an empty measurement.
 */
public interface StressHost extends ServerAccessor {

    /** Canonical sentinel string for operations a platform does not offer (ADR-122 Decision 2). */
    String NOT_MEASURED = "not measured";

    /**
     * Result of a per-plugin/mod census operation (listeners, pending tasks, or
     * chunk tickets). When {@link #measured()} is {@code false}, {@link #status()}
     * is {@link #NOT_MEASURED} and callers must not emit {@code 0} in place of
     * a measurement.
     */
    record Census(boolean measured, String status, Map<String, Integer> counts) {
        public Census {
            if (status == null) {
                status = measured ? "measured" : NOT_MEASURED;
            }
            counts = counts != null ? Map.copyOf(counts) : Map.of();
        }

        public static Census measured(Map<String, Integer> counts) {
            return new Census(true, "measured", counts);
        }

        public static Census notMeasured() {
            return new Census(false, NOT_MEASURED, Map.of());
        }
    }

    /**
     * Metadata for a loaded plugin or mod on the host server (ADR-122 Decision 2 & 5).
     */
    record LoadedPlugin(
            String name,
            String version,
            ClassLoader classLoader,
            Path jarPath,
            List<Path> originPaths,
            Path dataFolder,
            boolean enabled,
            String fallbackPackage) {

        public LoadedPlugin {
            name = name != null ? name : "unknown";
            version = version != null ? version : "unknown";
            if (originPaths != null) {
                originPaths = List.copyOf(originPaths);
            } else {
                originPaths = jarPath != null ? List.of(jarPath) : List.of();
            }
            fallbackPackage = fallbackPackage != null ? fallbackPackage : "";
        }

        public LoadedPlugin(
                String name,
                String version,
                ClassLoader classLoader,
                Path jarPath,
                Path dataFolder,
                boolean enabled,
                String fallbackPackage) {
            this(name, version, classLoader, jarPath,
                    jarPath != null ? List.of(jarPath) : List.of(),
                    dataFolder, enabled, fallbackPackage);
        }
    }

    /** Player teleport notification delivered to core probes. */
    record TeleportEvent(StressPlayer player, StressLocation from, StressLocation to, String cause) {}

    /** Chunk load/unload notification delivered to core probes. */
    record ChunkEvent(
            String worldName,
            int cx,
            int cz,
            boolean newChunk,
            Collection<String> ticketHolders) {
        public ChunkEvent {
            worldName = worldName != null ? worldName : "";
            ticketHolders = ticketHolders != null ? List.copyOf(ticketHolders) : List.of();
        }
    }

    // --- Logging, configuration & server identity (ServerAccessor) ------------

    default String getName() {
        return "StressTestRTP";
    }

    Logger getLogger();

    default Logger logger() {
        return getLogger();
    }

    StressConfig getStressConfig();

    default StressConfig config() {
        return getStressConfig();
    }

    Path dataFolder();

    default File getDataFolder() {
        Path p = dataFolder();
        return p != null ? p.toFile() : new File(".");
    }

    default void saveDefaultConfig() {
    }

    default void reloadStressConfig() {
    }

    @Override
    default void log(Level level, String message) {
        Logger l = getLogger();
        if (l != null) l.log(level, message);
    }

    @Override
    default void log(Level level, String message, Throwable throwable) {
        Logger l = getLogger();
        if (l != null) l.log(level, message, throwable);
    }

    @Override
    default File getPluginDirectory() {
        return getDataFolder();
    }

    @Override
    default boolean isPrimaryThread() {
        return isTickThread();
    }

    @Override
    default String getServerVersion() {
        return "unknown";
    }

    @Override
    default String getPlatform() {
        return isFolia() ? "Folia" : "Paper";
    }

    @Override
    default Integer getServerIntVersion() {
        return 21;
    }

    @Override
    default PlatformFamily getPlatformFamily() {
        return PlatformFamily.BUKKIT;
    }

    @Override
    default String getServerName() {
        return getPlatform();
    }

    // --- Command registration & dispatch -------------------------------------

    default void registerCommand(String name, StressCommand command) {
    }

    @Override
    void dispatchConsoleCommand(String commandLine);

    default void dispatchPlayerCommand(UUID playerId, String commandLine) {
        StressPlayer p = player(playerId);
        if (p != null) p.performCommand(commandLine);
    }

    default boolean isCommandRegistered(String name) {
        return false;
    }

    // --- Online players, worlds & direct teleport ----------------------------

    Collection<StressPlayer> onlinePlayers();

    StressPlayer player(UUID uuid);

    StressPlayer playerExact(String name);

    List<StressWorld> worlds();

    StressWorld world(String name);

    default boolean canTeleportDirect() {
        return false;
    }

    default void teleportDirect(StressPlayer player, int x, int z, Consumer<String> onFail) {
        if (onFail != null) onFail.accept(NOT_MEASURED);
    }

    // --- Scheduling (global, region, entity/player, async) -------------------

    default boolean isFolia() {
        return false;
    }

    default boolean isPaper() {
        return false;
    }

    void runGlobal(Runnable task);

    Object runGlobalLater(Runnable task, long delayTicks);

    Object runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    default void runOnRegion(String worldName, int cx, int cz, Runnable task) {
        runGlobal(task);
    }

    default Object runOnRegionLater(String worldName, int cx, int cz, Runnable task, long delayTicks) {
        return runGlobalLater(task, delayTicks);
    }

    default Object runOnRegionTimer(String worldName, int cx, int cz, Runnable task, long delayTicks, long periodTicks) {
        return runGlobalTimer(task, delayTicks, periodTicks);
    }

    default void runOnPlayer(UUID playerId, Runnable task) {
        runGlobal(task);
    }

    default Object runOnPlayerLater(UUID playerId, Runnable task, long delayTicks) {
        return runGlobalLater(task, delayTicks);
    }

    default Object runOnPlayerTimer(UUID playerId, Runnable task, long delayTicks, long periodTicks) {
        return runGlobalTimer(task, delayTicks, periodTicks);
    }

    void runAsync(Runnable task);

    default Object runAsyncLater(Runnable task, long delayMs) {
        return null;
    }

    default Object runAsyncTimer(Runnable task, long delayMs, long periodMs) {
        return null;
    }

    boolean cancelTask(Object handle);

    // --- Tick-thread check, TPS & MSPT readings ------------------------------

    boolean isTickThread();

    default boolean ownsChunk(String worldName, int cx, int cz) {
        return isTickThread();
    }

    default boolean regionOwnershipAvailable() {
        return false;
    }

    default double nativeTps() {
        return -1.0;
    }

    default double nativeMspt() {
        return -1.0;
    }

    @SuppressWarnings("java:S1168")
    default double[] regionTps(String worldName, int cx, int cz) {
        return null;
    }

    default int viewDistance() {
        return 10;
    }

    // --- Loaded plugins / mods -----------------------------------------------

    List<LoadedPlugin> loadedPlugins();

    // --- Event subscription --------------------------------------------------

    default Runnable onPlayerJoin(Consumer<StressPlayer> listener) {
        return () -> {};
    }

    default Runnable onPlayerQuit(Consumer<StressPlayer> listener) {
        return () -> {};
    }

    default Runnable onPlayerTeleport(Consumer<TeleportEvent> listener) {
        return () -> {};
    }

    default Runnable onChunkLoad(Consumer<ChunkEvent> listener) {
        return () -> {};
    }

    default Runnable onChunkUnload(Consumer<ChunkEvent> listener) {
        return () -> {};
    }

    default Runnable registerDirectTeleportEvents(
            Class<?> preClass,
            Consumer<Object> onPre,
            Class<?> postClass,
            Consumer<Object> onPost) {
        return null;
    }

    // --- Censuses & chunk tickets ("not measured" when unsupported) ----------

    default Census listenerCensus() {
        return Census.notMeasured();
    }

    default Census pendingTaskCensus() {
        return Census.notMeasured();
    }

    default Census chunkTicketCensus() {
        return Census.notMeasured();
    }

    default boolean supportsChunkTickets() {
        return false;
    }

    default boolean addChunkTicket(String worldName, int cx, int cz) {
        return false;
    }

    default boolean removeChunkTicket(String worldName, int cx, int cz) {
        return false;
    }

    // --- Active harness run state (matches original StressHost contract) ------

    Runner runner();

    MetricsRecorder recorder();

    TpsMsptHeapSampler sampler();

    default TicketFootprintProbe ticketFootprintProbe() {
        return null;
    }

    default LeakProbe leakProbe() {
        return null;
    }

    default CoverageProbe coverageProbe() {
        return null;
    }

    default HarnessTeleporter harnessTeleporter() {
        return null;
    }

    void beginRun() throws IOException;
}
