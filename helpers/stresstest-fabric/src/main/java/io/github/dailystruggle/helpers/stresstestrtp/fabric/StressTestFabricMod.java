package io.github.dailystruggle.helpers.stresstestrtp.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.github.dailystruggle.helpers.stresstestrtp.CoverageProbe;
import io.github.dailystruggle.helpers.stresstestrtp.HarnessTeleporter;
import io.github.dailystruggle.helpers.stresstestrtp.LeakProbe;
import io.github.dailystruggle.helpers.stresstestrtp.MetricsRecorder;
import io.github.dailystruggle.helpers.stresstestrtp.Runner;
import io.github.dailystruggle.helpers.stresstestrtp.StressCommand;
import io.github.dailystruggle.helpers.stresstestrtp.StressConfig;
import io.github.dailystruggle.helpers.stresstestrtp.StressCoreRuntime;
import io.github.dailystruggle.helpers.stresstestrtp.StressHost;
import io.github.dailystruggle.helpers.stresstestrtp.StressLocation;
import io.github.dailystruggle.helpers.stresstestrtp.StressPlayer;
import io.github.dailystruggle.helpers.stresstestrtp.StressSender;
import io.github.dailystruggle.helpers.stresstestrtp.StressWorld;
import io.github.dailystruggle.helpers.stresstestrtp.TicketFootprintProbe;
import io.github.dailystruggle.helpers.stresstestrtp.TpsMsptHeapSampler;
import io.github.dailystruggle.server.api.PlatformFamily;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin Fabric carrier for StressTestRTP (ADR-122 Decision 1, 2, 3, 5).
 *
 * <p>Implements {@link ModInitializer} and {@link StressHost} (extending
 * {@code ServerAccessor} from {@code api/server-api}), maps Fabric lifecycle,
 * command, player, and chunk events into {@link StressCoreRuntime}, and uses
 * {@link FabricLoader} mod origins for jar ownership and JaCoCo coverage
 * {@code includes=}.
 */
public final class StressTestFabricMod implements ModInitializer, StressHost {

    private static final Logger LOG = Logger.getLogger("StressTestRTP");
    private static final Pattern MC_MINOR = Pattern.compile("(?:1\\.)?(\\d+)");

    private final Path dataDir;
    private volatile StressConfig config;
    private volatile StressCoreRuntime runtime;
    private volatile Object minecraftServer;
    private volatile Thread serverThread;
    private final AtomicLong tickCounter = new AtomicLong();

    private final List<Consumer<StressPlayer>> joinListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<StressPlayer>> quitListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TeleportEvent>> teleportListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChunkEvent>> chunkLoadListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<ChunkEvent>> chunkUnloadListeners = new CopyOnWriteArrayList<>();
    private final Map<UUID, StressLocation> lastLocations = new ConcurrentHashMap<>();
    private final List<ScheduledTickTask> tickTasks = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService asyncScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "StressTestRTP-Fabric-Async");
        t.setDaemon(true);
        return t;
    });

    private static final class ScheduledTickTask {
        final Runnable task;
        final long periodTicks;
        volatile long nextFireTick;
        volatile boolean cancelled;

        ScheduledTickTask(Runnable task, long nextFireTick, long periodTicks) {
            this.task = task;
            this.nextFireTick = nextFireTick;
            this.periodTicks = periodTicks;
        }

        public void cancel() {
            this.cancelled = true;
        }
    }

    public StressTestFabricMod() {
        this(resolveDefaultDataDir());
    }

    public StressTestFabricMod(Path dataDir) {
        this.dataDir = dataDir != null ? dataDir : Path.of("config", "StressTestRTP");
    }

    private static Path resolveDefaultDataDir() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("StressTestRTP");
        } catch (Throwable t) {
            return Path.of("config", "StressTestRTP");
        }
    }

    @Override
    public void onInitialize() {
        saveDefaultConfig();
        reloadStressConfig();
        registerFabricCallbacks();
        this.runtime = new StressCoreRuntime(this);
        LOG.info("[StressTestRTP] Fabric carrier initialized; dataFolder=" + dataDir.toAbsolutePath()
                + ", coverage.includes=" + CoverageProbe.includesForLoadedMods(this));
    }

    public void onServerStarted(Object server) {
        this.minecraftServer = server;
        this.serverThread = Thread.currentThread();
    }

    public void onServerStopping() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        tickTasks.clear();
        lastLocations.clear();
        minecraftServer = null;
    }

    public void onServerTick(Object server) {
        if (server != null && this.minecraftServer == null) {
            this.minecraftServer = server;
            this.serverThread = Thread.currentThread();
        }
        long tick = tickCounter.incrementAndGet();
        for (ScheduledTickTask t : tickTasks) {
            if (t.cancelled) {
                tickTasks.remove(t);
                continue;
            }
            if (tick >= t.nextFireTick) {
                try {
                    t.task.run();
                } catch (Throwable ex) {
                    LOG.log(Level.WARNING, "[StressTestRTP] scheduled Fabric tick task failed", ex);
                }
                if (t.periodTicks > 0 && !t.cancelled) {
                    t.nextFireTick = tick + t.periodTicks;
                } else {
                    t.cancelled = true;
                    tickTasks.remove(t);
                }
            }
        }
        pollPlayerTeleports();
    }

    private void pollPlayerTeleports() {
        if (teleportListeners.isEmpty()) return;
        for (StressPlayer p : onlinePlayers()) {
            StressLocation cur = p.location();
            if (cur == null) continue;
            StressLocation prev = lastLocations.put(p.uuid(), cur);
            if (prev == null) continue;
            boolean changedWorld = !prev.world().equals(cur.world());
            double dx = cur.x() - prev.x();
            double dz = cur.z() - prev.z();
            if (changedWorld || (dx * dx + dz * dz) >= 16.0) {
                TeleportEvent ev = new TeleportEvent(p, prev, cur, "COMMAND");
                for (Consumer<TeleportEvent> l : teleportListeners) {
                    try {
                        l.accept(ev);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    /**
     * Registers Brigadier {@code /rtpstress} command onto the given dispatcher.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void registerBrigadierCommand(CommandDispatcher<?> dispatcher) {
        CommandDispatcher<Object> d = (CommandDispatcher<Object>) (CommandDispatcher) dispatcher;
        LiteralArgumentBuilder<Object> root = LiteralArgumentBuilder.literal("rtpstress")
                .executes(ctx -> {
                    StressCommand cmd = runtime != null ? runtime.command() : null;
                    if (cmd != null) cmd.execute(wrapCommandSource(ctx.getSource()), new String[0]);
                    return 1;
                })
                .then(RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            StressCommand cmd = runtime != null ? runtime.command() : null;
                            if (cmd != null) {
                                String rem = builder.getRemaining();
                                String[] parts = rem.isEmpty() ? new String[]{""} : rem.split("\\s+", -1);
                                for (String s : cmd.tabComplete(wrapCommandSource(ctx.getSource()), parts)) {
                                    builder.suggest(s);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            StressCommand cmd = runtime != null ? runtime.command() : null;
                            if (cmd != null) {
                                String raw = StringArgumentType.getString(ctx, "args").trim();
                                String[] args = raw.isEmpty() ? new String[0] : raw.split("\\s+");
                                cmd.execute(wrapCommandSource(ctx.getSource()), args);
                            }
                            return 1;
                        }));
        d.register(root);
    }

    private void registerFabricCallbacks() {
        try {
            Class<?> lifecycle = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents");
            registerEventCallback(lifecycle, "SERVER_STARTED", (proxy, method, args) -> {
                if (args != null && args.length >= 1) onServerStarted(args[0]);
                return null;
            });
            registerEventCallback(lifecycle, "SERVER_STOPPING", (proxy, method, args) -> {
                onServerStopping();
                return null;
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> tickEvents = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents");
            registerEventCallback(tickEvents, "END_SERVER_TICK", (proxy, method, args) -> {
                onServerTick(args != null && args.length >= 1 ? args[0] : null);
                return null;
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cmdReg = Class.forName("net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback");
            registerEventCallback(cmdReg, "EVENT", (proxy, method, args) -> {
                if (args != null && args.length >= 1 && args[0] instanceof CommandDispatcher<?> disp) {
                    registerBrigadierCommand(disp);
                }
                return null;
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> connEvents = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents");
            registerEventCallback(connEvents, "JOIN", (proxy, method, args) -> {
                StressPlayer sp = extractPlayerFromHandler(args != null && args.length >= 1 ? args[0] : null);
                if (sp != null) {
                    lastLocations.put(sp.uuid(), sp.location());
                    for (Consumer<StressPlayer> l : joinListeners) l.accept(sp);
                }
                return null;
            });
            registerEventCallback(connEvents, "DISCONNECT", (proxy, method, args) -> {
                StressPlayer sp = extractPlayerFromHandler(args != null && args.length >= 1 ? args[0] : null);
                if (sp != null) {
                    lastLocations.remove(sp.uuid());
                    for (Consumer<StressPlayer> l : quitListeners) l.accept(sp);
                }
                return null;
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> chunkEvents = Class.forName("net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents");
            registerEventCallback(chunkEvents, "CHUNK_LOAD", (proxy, method, args) -> {
                if (args != null && args.length >= 2) {
                    ChunkEvent ce = toChunkEvent(args[0], args[1], false);
                    if (ce != null) {
                        for (Consumer<ChunkEvent> l : chunkLoadListeners) l.accept(ce);
                    }
                }
                return null;
            });
            registerEventCallback(chunkEvents, "CHUNK_UNLOAD", (proxy, method, args) -> {
                if (args != null && args.length >= 2) {
                    ChunkEvent ce = toChunkEvent(args[0], args[1], false);
                    if (ce != null) {
                        for (Consumer<ChunkEvent> l : chunkUnloadListeners) l.accept(ce);
                    }
                }
                return null;
            });
        } catch (Throwable ignored) {
        }
    }

    private static void registerEventCallback(
            Class<?> holderClass,
            String fieldName,
            java.lang.reflect.InvocationHandler handler) throws ReflectiveOperationException {
        Field f = holderClass.getField(fieldName);
        Object event = f.get(null);
        Method register = event.getClass().getMethod("register", Object.class);
        Method invoker = event.getClass().getMethod("invoker");
        Class<?> listenerType = invoker.getReturnType();
        Object proxy = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, handler);
        register.invoke(event, proxy);
    }

    // --- StressHost / ServerAccessor implementation --------------------------

    @Override
    public Logger getLogger() {
        return LOG;
    }

    @Override
    public Path dataFolder() {
        return dataDir;
    }

    @Override
    public void saveDefaultConfig() {
        try {
            Files.createDirectories(dataDir);
            Path cfgFile = dataDir.resolve("config.yml");
            if (!Files.exists(cfgFile)) {
                try (InputStream in = StressTestFabricMod.class.getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, cfgFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[StressTestRTP] failed to save default config.yml", e);
        }
    }

    @Override
    public void reloadStressConfig() {
        Path cfgFile = dataDir.resolve("config.yml");
        try {
            if (Files.isRegularFile(cfgFile)) {
                this.config = StressConfig.load(cfgFile);
                return;
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[StressTestRTP] failed to load config.yml", e);
        }
        this.config = StressConfig.fromResource(StressTestFabricMod.class, "/config.yml");
    }

    @Override
    public StressConfig getStressConfig() {
        StressConfig c = config;
        if (c == null) {
            reloadStressConfig();
            c = config;
        }
        return c != null ? c : new StressConfig();
    }

    @Override
    public String getServerVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String getPlatform() {
        return "Fabric";
    }

    @Override
    public Integer getServerIntVersion() {
        String v = getServerVersion();
        Matcher m = MC_MINOR.matcher(v);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 21;
    }

    @Override
    public PlatformFamily getPlatformFamily() {
        return PlatformFamily.FABRIC;
    }

    @Override
    public String getServerName() {
        return "Fabric";
    }

    @Override
    public void dispatchConsoleCommand(String commandLine) {
        Object srv = minecraftServer;
        if (srv == null || commandLine == null) return;
        String clean = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        try {
            Object commands = invokeAny(srv, "getCommands", "getCommandManager", "method_3739");
            Object source = invokeAny(srv, "createCommandSourceStack", "getCommandSource", "method_2995");
            if (commands != null && source != null) {
                Method pref = findMethod(commands.getClass(), "performPrefixedCommand", 2);
                if (pref != null) {
                    pref.invoke(commands, source, clean);
                    return;
                }
                Object disp = invokeAny(commands, "getDispatcher", "method_30852");
                if (disp instanceof CommandDispatcher<?> cd) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    CommandDispatcher<Object> d = (CommandDispatcher<Object>) (CommandDispatcher) cd;
                    d.execute(clean, source);
                }
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[StressTestRTP] Fabric console dispatch failed: " + clean, t);
        }
    }

    @Override
    public boolean isCommandRegistered(String name) {
        Object srv = minecraftServer;
        if (srv == null || name == null) return false;
        try {
            Object commands = invokeAny(srv, "getCommands", "getCommandManager", "method_3739");
            Object disp = commands != null ? invokeAny(commands, "getDispatcher", "method_30852") : null;
            if (disp instanceof CommandDispatcher<?> cd) {
                return cd.getRoot().getChild(name) != null;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public Collection<StressPlayer> onlinePlayers() {
        Object srv = minecraftServer;
        if (srv == null) return List.of();
        try {
            Object playerList = invokeAny(srv, "getPlayerList", "getPlayerManager", "method_3760");
            Object players = playerList != null ? invokeAny(playerList, "getPlayers", "getPlayerList", "method_14571") : null;
            if (players instanceof Collection<?> col) {
                List<StressPlayer> out = new ArrayList<>(col.size());
                for (Object p : col) {
                    if (p != null) out.add(wrapPlayer(p));
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    @Override
    public StressPlayer player(UUID uuid) {
        if (uuid == null) return null;
        for (StressPlayer p : onlinePlayers()) {
            if (uuid.equals(p.uuid())) return p;
        }
        return null;
    }

    @Override
    public StressPlayer playerExact(String name) {
        if (name == null) return null;
        for (StressPlayer p : onlinePlayers()) {
            if (name.equalsIgnoreCase(p.name())) return p;
        }
        return null;
    }

    @Override
    public List<StressWorld> worlds() {
        Object srv = minecraftServer;
        if (srv == null) return List.of();
        try {
            Object levels = invokeAny(srv, "getAllLevels", "getWorlds", "method_3738");
            if (levels instanceof Iterable<?> it) {
                List<StressWorld> out = new ArrayList<>();
                for (Object lvl : it) {
                    if (lvl != null) out.add(wrapWorld(lvl));
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }

    @Override
    public StressWorld world(String name) {
        if (name == null) return null;
        for (StressWorld w : worlds()) {
            if (name.equalsIgnoreCase(w.name())) return w;
        }
        return null;
    }

    @Override
    public boolean canTeleportDirect() {
        return minecraftServer != null;
    }

    @Override
    public void teleportDirect(StressPlayer player, int x, int z, Consumer<String> onFail) {
        if (player == null || !canTeleportDirect()) {
            if (onFail != null) onFail.accept("MinecraftServer not ready");
            return;
        }
        runOnPlayer(player.uuid(), () -> {
            String cmd = String.format(java.util.Locale.ROOT, "tp %s %d 100 %d", player.name(), x, z);
            try {
                dispatchConsoleCommand(cmd);
            } catch (Throwable t) {
                if (onFail != null) onFail.accept(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    @Override
    public void runGlobal(Runnable task) {
        if (task == null) return;
        Object srv = minecraftServer;
        if (srv != null) {
            try {
                Method exec = findMethod(srv.getClass(), "execute", 1);
                if (exec != null) {
                    exec.invoke(srv, task);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        if (isTickThread()) {
            task.run();
        } else {
            runGlobalLater(task, 1L);
        }
    }

    @Override
    public Object runGlobalLater(Runnable task, long delayTicks) {
        long target = tickCounter.get() + Math.max(1L, delayTicks);
        ScheduledTickTask st = new ScheduledTickTask(task, target, 0L);
        tickTasks.add(st);
        return st;
    }

    @Override
    public Object runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        long target = tickCounter.get() + Math.max(1L, delayTicks);
        ScheduledTickTask st = new ScheduledTickTask(task, target, Math.max(1L, periodTicks));
        tickTasks.add(st);
        return st;
    }

    @Override
    public void runAsync(Runnable task) {
        if (task != null) asyncScheduler.execute(task);
    }

    @Override
    public Object runAsyncLater(Runnable task, long delayMs) {
        return asyncScheduler.schedule(task, Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public Object runAsyncTimer(Runnable task, long delayMs, long periodMs) {
        return asyncScheduler.scheduleAtFixedRate(
                task, Math.max(1L, delayMs), Math.max(1L, periodMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean cancelTask(Object handle) {
        if (handle instanceof ScheduledTickTask st) {
            st.cancel();
            tickTasks.remove(st);
            return true;
        }
        if (handle instanceof ScheduledFuture<?> sf) {
            sf.cancel(false);
            return true;
        }
        return false;
    }

    @Override
    public boolean isTickThread() {
        Thread st = serverThread;
        if (st != null) return Thread.currentThread() == st;
        return "Server thread".equals(Thread.currentThread().getName());
    }

    @Override
    public double nativeMspt() {
        Object srv = minecraftServer;
        if (srv == null) return -1.0;
        try {
            Object v = invokeAny(srv, "getAverageTickTime", "getAverageTickTimeNanos", "method_3759");
            if (v instanceof Long nanos) return nanos / 1_000_000.0;
            if (v instanceof Number n) return n.doubleValue();
        } catch (Throwable ignored) {
        }
        return -1.0;
    }

    @Override
    public double nativeTps() {
        double mspt = nativeMspt();
        if (mspt <= 0.0) return -1.0;
        return Math.min(20.0, 1000.0 / mspt);
    }

    // --- FabricLoader mod origins & censuses (ADR-122 Decision 2 & 5) --------

    @Override
    public List<LoadedPlugin> loadedPlugins() {
        try {
            return discoverFabricMods(FabricLoader.getInstance());
        } catch (Throwable t) {
            return List.of();
        }
    }

    static List<LoadedPlugin> discoverFabricMods(FabricLoader loader) {
        if (loader == null) return List.of();
        Collection<ModContainer> mods = loader.getAllMods();
        List<LoadedPlugin> out = new ArrayList<>(mods.size());
        Path configRoot;
        try {
            configRoot = loader.getConfigDir();
        } catch (Throwable t) {
            configRoot = Path.of("config");
        }
        ClassLoader cl = StressTestFabricMod.class.getClassLoader();
        for (ModContainer mod : mods) {
            if (mod == null) continue;
            ModMetadata meta = mod.getMetadata();
            String id = meta != null ? meta.getId() : "unknown";
            String ver = meta != null && meta.getVersion() != null
                    ? meta.getVersion().getFriendlyString() : "unknown";
            List<Path> origins = extractOriginPaths(mod);
            Path primaryJar = origins.stream().filter(Files::isRegularFile).findFirst().orElse(null);
            Path modDataDir = configRoot != null ? configRoot.resolve(id) : null;
            out.add(new LoadedPlugin(id, ver, cl, primaryJar, origins, modDataDir, true, ""));
        }
        return Collections.unmodifiableList(out);
    }

    static List<Path> extractOriginPaths(ModContainer mod) {
        if (mod == null) return List.of();
        List<Path> paths = new ArrayList<>();
        try {
            ModOrigin origin = mod.getOrigin();
            if (origin != null) {
                ModOrigin.Kind kind = origin.getKind();
                if (kind == ModOrigin.Kind.PATH) {
                    List<Path> raw = origin.getPaths();
                    if (raw != null) paths.addAll(raw);
                }
            }
        } catch (Throwable ignored) {
        }
        if (paths.isEmpty()) {
            try {
                List<Path> roots = mod.getRootPaths();
                if (roots != null) paths.addAll(roots);
            } catch (Throwable ignored) {
            }
        }
        return paths;
    }

    @Override
    public Census listenerCensus() {
        // ADR-122 Decision 2: Fabric has no global listener registry; report "not measured", never 0.
        return Census.notMeasured();
    }

    @Override
    public Census pendingTaskCensus() {
        // ADR-122 Decision 2: Fabric has no central plugin task registry; report "not measured", never 0.
        return Census.notMeasured();
    }

    @Override
    public Census chunkTicketCensus() {
        // ADR-122 Decision 2: Fabric has no plugin-attributed chunk ticket table; report "not measured", never 0.
        return Census.notMeasured();
    }

    @Override
    public boolean supportsChunkTickets() {
        return false;
    }

    // --- Event subscriptions -------------------------------------------------

    @Override
    public Runnable onPlayerJoin(Consumer<StressPlayer> listener) {
        joinListeners.add(listener);
        return () -> joinListeners.remove(listener);
    }

    @Override
    public Runnable onPlayerQuit(Consumer<StressPlayer> listener) {
        quitListeners.add(listener);
        return () -> quitListeners.remove(listener);
    }

    @Override
    public Runnable onPlayerTeleport(Consumer<TeleportEvent> listener) {
        teleportListeners.add(listener);
        return () -> teleportListeners.remove(listener);
    }

    @Override
    public Runnable onChunkLoad(Consumer<ChunkEvent> listener) {
        chunkLoadListeners.add(listener);
        return () -> chunkLoadListeners.remove(listener);
    }

    @Override
    public Runnable onChunkUnload(Consumer<ChunkEvent> listener) {
        chunkUnloadListeners.add(listener);
        return () -> chunkUnloadListeners.remove(listener);
    }

    // --- Active harness run state delegates ----------------------------------

    @Override public Runner runner() { return runtime != null ? runtime.runner() : null; }
    @Override public LeakProbe leakProbe() { return runtime != null ? runtime.leakProbe() : null; }
    @Override public CoverageProbe coverageProbe() { return runtime != null ? runtime.coverageProbe() : null; }
    @Override public HarnessTeleporter harnessTeleporter() { return runtime != null ? runtime.harnessTeleporter() : null; }
    @Override public TpsMsptHeapSampler sampler() { return runtime != null ? runtime.sampler() : null; }
    @Override public TicketFootprintProbe ticketFootprintProbe() { return runtime != null ? runtime.ticketFootprintProbe() : null; }
    @Override public MetricsRecorder recorder() { return runtime != null ? runtime.recorder() : null; }

    @Override
    public void beginRun() throws IOException {
        if (runtime != null) runtime.beginRun();
    }

    // --- Reflective wrappers for MinecraftServer / ServerPlayer / ServerLevel -

    private StressSender wrapCommandSource(Object source) {
        return new StressSender() {
            @Override
            public String name() {
                try {
                    Object n = invokeAny(source, "getTextName", "getName", "method_9214");
                    if (n != null) return n.toString();
                } catch (Throwable ignored) {
                }
                return "console";
            }

            @Override
            public UUID playerUuid() {
                try {
                    Object p = invokeAny(source, "getPlayer", "method_44023");
                    if (p != null) {
                        Object id = invokeAny(p, "getUUID", "getUuid", "method_5667");
                        if (id instanceof UUID u) return u;
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }

            @Override
            public boolean hasPermission(String node) {
                try {
                    Method m = findMethod(source.getClass(), "hasPermission", 1);
                    if (m == null) m = findMethod(source.getClass(), "hasPermissionLevel", 1);
                    if (m == null) m = findMethod(source.getClass(), "method_9259", 1);
                    if (m != null) {
                        Object r = m.invoke(source, 2);
                        if (r instanceof Boolean b) return b;
                    }
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public void sendMessage(String message) {
                LOG.info("[StressTestRTP] " + message);
            }
        };
    }

    private StressPlayer extractPlayerFromHandler(Object handler) {
        if (handler == null) return null;
        for (String field : new String[]{"player", "field_14140"}) {
            try {
                Field f = handler.getClass().getField(field);
                Object p = f.get(handler);
                if (p != null) return wrapPlayer(p);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private StressPlayer wrapPlayer(Object sp) {
        return new StressPlayer() {
            @Override
            public UUID uuid() {
                try {
                    Object u = invokeAny(sp, "getUUID", "getUuid", "method_5667");
                    if (u instanceof UUID id) return id;
                } catch (Throwable ignored) {
                }
                return new UUID(0L, System.identityHashCode(sp));
            }

            @Override
            public String name() {
                try {
                    Object gp = invokeAny(sp, "getGameProfile", "method_7334");
                    if (gp != null) {
                        Object n = invokeAny(gp, "getName", "name");
                        if (n != null) return n.toString();
                    }
                    Object score = invokeAny(sp, "getScoreboardName", "getEntityName", "method_5820");
                    if (score != null) return score.toString();
                } catch (Throwable ignored) {
                }
                return "player";
            }

            @Override
            public boolean isOnline() {
                try {
                    Object disc = invokeAny(sp, "hasDisconnected", "isDisconnected", "method_14239");
                    if (disc instanceof Boolean b) return !b;
                } catch (Throwable ignored) {
                }
                return true;
            }

            @Override
            public StressLocation location() {
                try {
                    double x = toDouble(invokeAny(sp, "getX", "method_23317"));
                    double y = toDouble(invokeAny(sp, "getY", "method_23318"));
                    double z = toDouble(invokeAny(sp, "getZ", "method_23321"));
                    Object lvl = invokeAny(sp, "serverLevel", "getServerWorld", "level", "getWorld", "method_51469");
                    String w = levelName(lvl);
                    return new StressLocation(w, x, y, z);
                } catch (Throwable ignored) {
                    return new StressLocation("minecraft:overworld", 0, 64, 0);
                }
            }

            @Override
            public boolean hasPermission(String node) {
                return true;
            }

            @Override
            public boolean performCommand(String command) {
                String clean = command != null && command.startsWith("/") ? command.substring(1) : command;
                if (clean == null || clean.isEmpty()) return false;
                try {
                    Object srv = minecraftServer;
                    Object source = invokeAny(sp, "createCommandSourceStack", "getCommandSource", "method_2995");
                    Object commands = srv != null ? invokeAny(srv, "getCommands", "getCommandManager", "method_3739") : null;
                    if (commands != null && source != null) {
                        Method pref = findMethod(commands.getClass(), "performPrefixedCommand", 2);
                        if (pref != null) {
                            pref.invoke(commands, source, clean);
                            return true;
                        }
                    }
                } catch (Throwable ignored) {
                }
                dispatchConsoleCommand(clean);
                return true;
            }
        };
    }

    private StressWorld wrapWorld(Object level) {
        String wName = levelName(level);
        return new StressWorld() {
            @Override
            public String name() {
                return wName;
            }

            @Override
            public Path worldFolder() {
                return Path.of("world");
            }

            @Override
            public int loadedChunkCount() {
                try {
                    Object cs = invokeAny(level, "getChunkSource", "getChunkManager", "method_8398");
                    Object count = cs != null ? invokeAny(cs, "getLoadedChunksCount", "getLoadedChunkCount", "method_14151") : null;
                    if (count instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {
                }
                return 0;
            }

            @Override
            public boolean isChunkLoaded(int cx, int cz) {
                try {
                    Method m = findMethod(level.getClass(), "hasChunk", 2);
                    if (m == null) m = findMethod(level.getClass(), "isChunkLoaded", 2);
                    if (m == null) m = findMethod(level.getClass(), "method_8393", 2);
                    if (m != null) {
                        Object r = m.invoke(level, cx, cz);
                        if (r instanceof Boolean b) return b;
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }

            @Override
            public boolean isChunkGenerated(int cx, int cz) {
                return true;
            }
        };
    }

    private ChunkEvent toChunkEvent(Object level, Object chunk, boolean newChunk) {
        try {
            String wName = levelName(level);
            Object pos = invokeAny(chunk, "getPos", "method_12004");
            if (pos != null) {
                int cx = 0;
                int cz = 0;
                try {
                    cx = ((Number) pos.getClass().getField("x").get(pos)).intValue();
                    cz = ((Number) pos.getClass().getField("z").get(pos)).intValue();
                } catch (Throwable t) {
                    Object xObj = invokeAny(pos, "x", "getX");
                    Object zObj = invokeAny(pos, "z", "getZ");
                    if (xObj instanceof Number nx) cx = nx.intValue();
                    if (zObj instanceof Number nz) cz = nz.intValue();
                }
                return new ChunkEvent(wName, cx, cz, newChunk, List.of());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String levelName(Object level) {
        if (level == null) return "minecraft:overworld";
        try {
            Object dim = invokeAny(level, "dimension", "getRegistryKey", "method_27983");
            if (dim != null) {
                Object loc = invokeAny(dim, "location", "getValue", "method_29177");
                if (loc != null) return loc.toString();
            }
        } catch (Throwable ignored) {
        }
        return "minecraft:overworld";
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Object invokeAny(Object target, String... names) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        for (String name : names) {
            try {
                Method m = c.getMethod(name);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int paramCount) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
