package io.github.dailystruggle.helpers.stresstestrtp;

import io.github.dailystruggle.server.api.PlatformFamily;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin Bukkit, Paper, and Folia carrier for StressTestRTP (ADR-122 Decision 1, 2, 3, 5).
 *
 * <p>Implements {@link StressHost} (which extends {@code ServerAccessor} from
 * {@code api/server-api}) and delegates all harness logic, probes, samplers, and
 * CSV/JSON writers to {@link StressCoreRuntime} in {@code stresstest-core}.
 */
public final class StressTestRTPPlugin extends JavaPlugin implements StressHost, CommandExecutor, TabCompleter {

    static final String ASYNC_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.AsyncScheduler";
    static final String GLOBAL_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";
    static final String REGION_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.RegionScheduler";
    static final String ENTITY_SCHEDULER = "io.papermc.paper.threadedregions.scheduler.EntityScheduler";
    private static final String BUKKIT_ENTITY = "org.bukkit.entity.Entity";
    private static final Class<?>[] NO_TYPES = {};
    private static final Class<?>[] CONSUMER = {Plugin.class, Consumer.class};
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Method>> METHODS =
            new ConcurrentHashMap<>();
    private static final Pattern MC_VER = Pattern.compile("1\\.(\\d+)");

    private StressCoreRuntime runtime;
    private volatile StressConfig cachedConfig;

    private Method tpsMethod;
    private Method msptMethod;
    private Method regionTpsMethod;
    private Method ownsChunkMethod;
    private Method getChunkAtAsync;
    private Method teleportAsync;
    private Method setViewDistance;
    private Method setSimDistance;
    private Method setSendViewDistance;
    private boolean chunkTicketsAvailable;

    @Override
    public void onEnable() {
        resolveReflectiveHandles();
        this.runtime = new StressCoreRuntime(this);
        PluginCommand cmd = getCommand("rtpstress");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().warning("Command 'rtpstress' not registered in plugin.yml — /rtpstress will not work.");
        }
        getLogger().info("StressTestRTP enabled. /rtpstress (permission stresstestrtp.admin) is live."
                + (isFolia() ? " Folia detected — using region/entity schedulers." : ""));
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }

    private void resolveReflectiveHandles() {
        try {
            tpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
        } catch (Throwable ignored) {
            tpsMethod = null;
        }
        try {
            msptMethod = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
        } catch (Throwable ignored) {
            msptMethod = null;
        }
        try {
            regionTpsMethod = Bukkit.class.getMethod("getRegionTPS", World.class, int.class, int.class);
        } catch (Throwable ignored) {
            regionTpsMethod = null;
        }
        try {
            ownsChunkMethod = Bukkit.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
        } catch (Throwable ignored) {
            ownsChunkMethod = null;
        }
        try {
            getChunkAtAsync = World.class.getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (Throwable ignored) {
            getChunkAtAsync = null;
        }
        try {
            teleportAsync = Player.class.getMethod("teleportAsync", Location.class);
        } catch (Throwable ignored) {
            teleportAsync = null;
        }
        try {
            setViewDistance = Player.class.getMethod("setViewDistance", int.class);
        } catch (Throwable ignored) {
            setViewDistance = null;
        }
        try {
            setSimDistance = Player.class.getMethod("setSimulationDistance", int.class);
        } catch (Throwable ignored) {
            setSimDistance = null;
        }
        try {
            setSendViewDistance = Player.class.getMethod("setSendViewDistance", int.class);
        } catch (Throwable ignored) {
            setSendViewDistance = null;
        }
        try {
            Chunk.class.getMethod("getPluginChunkTickets");
            World.class.getMethod("getPluginChunkTickets");
            World.class.getMethod("addPluginChunkTicket", int.class, int.class, Plugin.class);
            World.class.getMethod("removePluginChunkTicket", int.class, int.class, Plugin.class);
            chunkTicketsAvailable = true;
        } catch (Throwable ignored) {
            chunkTicketsAvailable = false;
        }
    }

    // --- CommandExecutor / TabCompleter --------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (runtime == null || runtime.command() == null) return false;
        return runtime.command().execute(wrapSender(sender), args);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (runtime == null || runtime.command() == null) return List.of();
        return runtime.command().tabComplete(wrapSender(sender), args);
    }

    private StressSender wrapSender(CommandSender sender) {
        return new StressSender() {
            @Override public String name() { return sender.getName(); }
            @Override public UUID playerUuid() { return sender instanceof Player p ? p.getUniqueId() : null; }
            @Override public boolean hasPermission(String node) { return sender.hasPermission(node); }
            @Override public void sendMessage(String message) { sender.sendMessage(message); }
        };
    }

    // --- StressHost / ServerAccessor implementation --------------------------

    @Override
    public Path dataFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public void reloadStressConfig() {
        super.reloadConfig();
        cachedConfig = null;
    }

    @Override
    public StressConfig getStressConfig() {
        StressConfig c = cachedConfig;
        if (c != null) return c;
        c = convertBukkitConfig(getConfig());
        cachedConfig = c;
        return c;
    }

    static StressConfig convertBukkitConfig(FileConfiguration fc) {
        StressConfig out = new StressConfig();
        if (fc == null) return out;
        copySection("", fc, out);
        return out;
    }

    private static void copySection(String prefix, ConfigurationSection sec, StressConfig out) {
        for (String key : sec.getKeys(false)) {
            String full = prefix.isEmpty() ? key : prefix + "." + key;
            Object val = sec.get(key);
            if (val instanceof ConfigurationSection child) {
                copySection(full, child, out);
            } else {
                out.set(full, val);
            }
        }
    }

    @Override
    public String getServerVersion() {
        try {
            return Bukkit.getVersion();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String getPlatform() {
        if (isFolia()) return "Folia";
        if (isPaper()) return "Paper";
        return "Spigot";
    }

    @Override
    public Integer getServerIntVersion() {
        try {
            Matcher m = MC_VER.matcher(Bukkit.getBukkitVersion());
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
        }
        return 21;
    }

    @Override
    public PlatformFamily getPlatformFamily() {
        return PlatformFamily.BUKKIT;
    }

    @Override
    public String getServerName() {
        try {
            return Bukkit.getName();
        } catch (Throwable t) {
            return getPlatform();
        }
    }

    @Override
    public boolean isFolia() {
        return Runner.isFoliaFamily();
    }

    @Override
    public boolean isPaper() {
        return Runner.isPaperFamily();
    }

    @Override
    public void dispatchConsoleCommand(String commandLine) {
        Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), commandLine);
    }

    @Override
    public boolean isCommandRegistered(String name) {
        return Bukkit.getPluginCommand(name) != null;
    }

    @Override
    public Collection<StressPlayer> onlinePlayers() {
        Collection<? extends Player> raw = Bukkit.getOnlinePlayers();
        List<StressPlayer> out = new ArrayList<>(raw.size());
        for (Player p : raw) {
            if (p != null) out.add(wrapPlayer(p));
        }
        return out;
    }

    @Override
    public StressPlayer player(UUID uuid) {
        if (uuid == null) return null;
        Player p = Bukkit.getPlayer(uuid);
        return p != null ? wrapPlayer(p) : null;
    }

    @Override
    public StressPlayer playerExact(String name) {
        if (name == null) return null;
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? wrapPlayer(p) : null;
    }

    private StressPlayer wrapPlayer(Player p) {
        return new BukkitStressPlayer(p);
    }

    private final class BukkitStressPlayer implements StressPlayer {
        final Player delegate;
        BukkitStressPlayer(Player delegate) { this.delegate = delegate; }
        @Override public UUID uuid() { return delegate.getUniqueId(); }
        @Override public String name() { return delegate.getName(); }
        @Override public boolean isOnline() { return delegate.isOnline(); }
        @Override
        public StressLocation location() {
            Location loc = delegate.getLocation();
            return toStressLocation(loc);
        }
        @Override public boolean hasPermission(String node) { return delegate.hasPermission(node); }
        @Override public boolean isOp() { return delegate.isOp(); }
        @Override public void setOp(boolean value) { delegate.setOp(value); }
        @Override public void kick(String reason) { delegate.kickPlayer(reason); }
        @Override public boolean performCommand(String command) { return delegate.performCommand(command); }
        @Override
        public void capDistances(int d) {
            try { if (setViewDistance != null) setViewDistance.invoke(delegate, d); } catch (Throwable ignored) {}
            try { if (setSimDistance != null) setSimDistance.invoke(delegate, d); } catch (Throwable ignored) {}
            try { if (setSendViewDistance != null) setSendViewDistance.invoke(delegate, d); } catch (Throwable ignored) {}
        }
        @Override
        public boolean equals(Object o) {
            return o instanceof BukkitStressPlayer other && delegate.equals(other.delegate);
        }
        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }

    private static StressLocation toStressLocation(Location loc) {
        if (loc == null) return null;
        World w = loc.getWorld();
        return new StressLocation(w != null ? w.getName() : "", loc.getX(), loc.getY(), loc.getZ());
    }

    @Override
    public List<StressWorld> worlds() {
        List<World> raw = Bukkit.getWorlds();
        List<StressWorld> out = new ArrayList<>(raw.size());
        for (World w : raw) {
            if (w != null) out.add(wrapWorld(w));
        }
        return out;
    }

    @Override
    public StressWorld world(String name) {
        if (name == null || name.isEmpty()) return null;
        World w = Bukkit.getWorld(name);
        return w != null ? wrapWorld(w) : null;
    }

    private StressWorld wrapWorld(World w) {
        return new StressWorld() {
            @Override public String name() { return w.getName(); }
            @Override public Path worldFolder() { return w.getWorldFolder().toPath(); }
            @Override public int loadedChunkCount() { return w.getLoadedChunks().length; }
            @Override public boolean isChunkLoaded(int cx, int cz) { return w.isChunkLoaded(cx, cz); }
            @Override public boolean isChunkGenerated(int cx, int cz) { return w.isChunkGenerated(cx, cz); }
            @Override public int minHeight() { return w.getMinHeight(); }
            @Override public int highestBlockYAt(int x, int z) { return w.getHighestBlockYAt(x, z); }
            @Override
            public BlockSample blockAt(int x, int y, int z) {
                Block b = w.getBlockAt(x, y, z);
                return new BlockSample(b.getType().name(), b.getType().isSolid(), b.isPassable());
            }
            @Override public void save() { w.save(); }
            @Override public boolean loadChunkSync(int cx, int cz) { return w.loadChunk(cx, cz, false); }
            @Override
            @SuppressWarnings("unchecked")
            public CompletableFuture<Boolean> loadChunkAsync(int cx, int cz, boolean generate) {
                if (getChunkAtAsync == null) return null;
                try {
                    CompletableFuture<Chunk> fut = (CompletableFuture<Chunk>) getChunkAtAsync.invoke(w, cx, cz, generate);
                    return fut != null ? fut.thenApply(c -> c != null) : null;
                } catch (Throwable t) {
                    return CompletableFuture.failedFuture(t);
                }
            }
            @Override public void unloadChunkRequest(int cx, int cz) { w.unloadChunkRequest(cx, cz); }
        };
    }

    @Override
    public boolean canTeleportDirect() {
        return getChunkAtAsync != null && teleportAsync != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void teleportDirect(StressPlayer player, int x, int z, Consumer<String> onFail) {
        if (!canTeleportDirect()) {
            if (onFail != null) onFail.accept("teleportAsync unavailable");
            return;
        }
        Player bp = player instanceof BukkitStressPlayer bsp ? bsp.delegate : Bukkit.getPlayer(player.uuid());
        if (bp == null || !bp.isOnline()) {
            if (onFail != null) onFail.accept("player offline");
            return;
        }
        World world = bp.getWorld();
        int cx = x >> 4;
        int cz = z >> 4;
        CompletableFuture<?> chunkFut;
        try {
            chunkFut = (CompletableFuture<?>) getChunkAtAsync.invoke(world, cx, cz, true);
        } catch (Throwable t) {
            if (onFail != null) onFail.accept("getChunkAtAsync: " + t.getClass().getSimpleName());
            return;
        }
        chunkFut.whenComplete((chunk, err) -> {
            if (err != null || chunk == null) {
                if (onFail != null) onFail.accept("chunk load failed: " + err);
                return;
            }
            runOnPlayer(bp.getUniqueId(), () -> {
                if (!bp.isOnline()) {
                    if (onFail != null) onFail.accept("player quit before teleport");
                    return;
                }
                Location dest;
                try {
                    int y = world.getHighestBlockYAt(x, z) + 1;
                    dest = new Location(world, x + 0.5, y, z + 0.5);
                } catch (Throwable t) {
                    dest = new Location(world, x + 0.5, 100.0, z + 0.5);
                }
                try {
                    CompletableFuture<Boolean> tp = (CompletableFuture<Boolean>) teleportAsync.invoke(bp, dest);
                    tp.whenComplete((ok, tpErr) -> {
                        if (tpErr != null || !Boolean.TRUE.equals(ok)) {
                            if (onFail != null) onFail.accept("teleportAsync returned " + ok + " err=" + tpErr);
                        }
                    });
                } catch (Throwable t) {
                    if (onFail != null) onFail.accept("teleportAsync: " + t.getClass().getSimpleName());
                }
            });
        });
    }

    // --- Scheduling ----------------------------------------------------------

    static Method resolve(Object target, String iface, String name, Class<?>... types)
            throws ReflectiveOperationException {
        Class<?> c = target.getClass();
        ConcurrentHashMap<String, Method> perClass = METHODS.computeIfAbsent(c, k -> new ConcurrentHashMap<>());
        String key = name + '/' + types.length;
        Method cached = perClass.get(key);
        if (cached != null) return cached;
        Method m = null;
        try {
            Class<?> i = Class.forName(iface);
            if (i.isInstance(target)) m = i.getMethod(name, types);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }
        if (m == null) m = c.getMethod(name, types);
        if (!Modifier.isPublic(m.getDeclaringClass().getModifiers())) m.setAccessible(true);
        perClass.put(key, m);
        return m;
    }

    private static Object call(Object target, String iface, String name, Class<?>[] types, Object... args)
            throws ReflectiveOperationException {
        return resolve(target, iface, name, types).invoke(target, args);
    }

    private static Object server(String getter) throws ReflectiveOperationException {
        return call(Bukkit.getServer(), "org.bukkit.Server", getter, NO_TYPES);
    }

    @Override
    public void runGlobal(Runnable task) {
        if (isFolia()) {
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                call(globalSched, GLOBAL_SCHEDULER, "run", CONSUMER,
                        this, (Consumer<Object>) t -> task.run());
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    @Override
    public Object runGlobalLater(Runnable task, long delayTicks) {
        long delay = Math.max(1L, delayTicks);
        if (isFolia()) {
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                return call(globalSched, GLOBAL_SCHEDULER, "runDelayed",
                        new Class<?>[]{Plugin.class, Consumer.class, long.class},
                        this, (Consumer<Object>) t -> task.run(), delay);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return Bukkit.getScheduler().runTaskLater(this, task, delay);
    }

    @Override
    public Object runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            try {
                Object globalSched = server("getGlobalRegionScheduler");
                return call(globalSched, GLOBAL_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class},
                        this, (Consumer<Object>) t -> task.run(), delay, period);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return Bukkit.getScheduler().runTaskTimer(this, task, delay, period);
    }

    @Override
    public void runOnRegion(String worldName, int cx, int cz, Runnable task) {
        World w = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (isFolia() && w != null) {
            try {
                Object regionSched = server("getRegionScheduler");
                call(regionSched, REGION_SCHEDULER, "execute",
                        new Class<?>[]{Plugin.class, World.class, int.class, int.class, Runnable.class},
                        this, w, cx, cz, task);
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    @Override
    public void runOnPlayer(UUID playerId, Runnable task) {
        Player p = playerId != null ? Bukkit.getPlayer(playerId) : null;
        if (p == null) return;
        if (isFolia()) {
            try {
                Object entSched = call(p, BUKKIT_ENTITY, "getScheduler", NO_TYPES);
                call(entSched, ENTITY_SCHEDULER, "run",
                        new Class<?>[]{Plugin.class, Consumer.class, Runnable.class},
                        this,
                        (Consumer<Object>) t -> task.run(),
                        (Runnable) () -> {});
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Bukkit.getScheduler().runTask(this, task);
    }

    @Override
    public Object runOnPlayerTimer(UUID playerId, Runnable task, long delayTicks, long periodTicks) {
        Player p = playerId != null ? Bukkit.getPlayer(playerId) : null;
        if (p == null) return null;
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (isFolia()) {
            try {
                Object entSched = call(p, BUKKIT_ENTITY, "getScheduler", NO_TYPES);
                return call(entSched, ENTITY_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, Consumer.class, Runnable.class, long.class, long.class},
                        this,
                        (Consumer<Object>) t -> task.run(),
                        (Runnable) () -> {},
                        delay, period);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return Bukkit.getScheduler().runTaskTimer(this, task, delay, period);
    }

    @Override
    public void runAsync(Runnable task) {
        if (isFolia()) {
            try {
                Object asyncSched = server("getAsyncScheduler");
                call(asyncSched, ASYNC_SCHEDULER, "runNow", CONSUMER,
                        this, (Consumer<Object>) t -> task.run());
                return;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    @Override
    public Object runAsyncLater(Runnable task, long delayMs) {
        long delay = Math.max(1L, delayMs);
        if (isFolia()) {
            try {
                Object asyncSched = server("getAsyncScheduler");
                return call(asyncSched, ASYNC_SCHEDULER, "runDelayed",
                        new Class<?>[]{Plugin.class, Consumer.class, long.class, TimeUnit.class},
                        this, (Consumer<Object>) t -> task.run(), delay, TimeUnit.MILLISECONDS);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        long ticks = Math.max(1L, delay / 50L);
        return Bukkit.getScheduler().runTaskLaterAsynchronously(this, task, ticks);
    }

    @Override
    public Object runAsyncTimer(Runnable task, long delayMs, long periodMs) {
        long delay = Math.max(1L, delayMs);
        long period = Math.max(1L, periodMs);
        if (isFolia()) {
            try {
                Object asyncSched = server("getAsyncScheduler");
                return call(asyncSched, ASYNC_SCHEDULER, "runAtFixedRate",
                        new Class<?>[]{Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class},
                        this, (Consumer<Object>) t -> task.run(), delay, period, TimeUnit.MILLISECONDS);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        long ticks = Math.max(1L, period / 50L);
        return Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, ticks, ticks);
    }

    @Override
    public boolean cancelTask(Object handle) {
        if (handle instanceof BukkitTask bt) {
            bt.cancel();
            return true;
        }
        return false;
    }

    // --- Tick thread, TPS & MSPT --------------------------------------------

    @Override
    public boolean isTickThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean ownsChunk(String worldName, int cx, int cz) {
        if (ownsChunkMethod != null && worldName != null && !worldName.isEmpty()) {
            World w = Bukkit.getWorld(worldName);
            if (w != null) {
                try {
                    Object res = ownsChunkMethod.invoke(null, w, cx, cz);
                    if (res instanceof Boolean b) return b;
                } catch (Throwable ignored) {
                }
            }
        }
        return isTickThread();
    }

    @Override
    public boolean regionOwnershipAvailable() {
        return ownsChunkMethod != null;
    }

    @Override
    public double nativeTps() {
        if (tpsMethod == null) return -1.0;
        try {
            double[] arr = (double[]) tpsMethod.invoke(Bukkit.getServer());
            return (arr != null && arr.length > 0) ? arr[0] : -1.0;
        } catch (Throwable t) {
            return -1.0;
        }
    }

    @Override
    public double nativeMspt() {
        if (msptMethod == null) return -1.0;
        try {
            Object v = msptMethod.invoke(Bukkit.getServer());
            return (v instanceof Number n) ? n.doubleValue() : -1.0;
        } catch (Throwable t) {
            return -1.0;
        }
    }

    @Override
    public double[] regionTps(String worldName, int cx, int cz) {
        if (regionTpsMethod == null || worldName == null) return null;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return null;
        try {
            return (double[]) regionTpsMethod.invoke(null, w, cx, cz);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public int viewDistance() {
        try {
            return Bukkit.getServer().getViewDistance();
        } catch (Throwable t) {
            return 10;
        }
    }

    // --- Loaded plugins, censuses & chunk tickets ----------------------------

    @Override
    public List<LoadedPlugin> loadedPlugins() {
        Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
        List<LoadedPlugin> out = new ArrayList<>(plugins.length);
        for (Plugin p : plugins) {
            if (p == null) continue;
            ClassLoader cl = p.getClass().getClassLoader();
            Path jar = jarPathOf(p);
            Path dataDir = p.getDataFolder() != null ? p.getDataFolder().toPath() : null;
            String main = p.getDescription() != null ? p.getDescription().getMain() : "";
            int dot = main != null ? main.lastIndexOf('.') : -1;
            String fallbackPkg = dot > 0 ? main.substring(0, dot) : "";
            out.add(new LoadedPlugin(
                    p.getName(),
                    p.getDescription() != null ? p.getDescription().getVersion() : "unknown",
                    cl,
                    jar,
                    jar != null ? List.of(jar) : List.of(),
                    dataDir,
                    p.isEnabled(),
                    fallbackPkg));
        }
        return out;
    }

    private static Path jarPathOf(Plugin p) {
        try {
            CodeSource cs = p.getClass().getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            URI u = cs.getLocation().toURI();
            if (!"file".equalsIgnoreCase(u.getScheme())) return null;
            Path path = Path.of(u);
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Census listenerCensus() {
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p == null) continue;
                ArrayList<RegisteredListener> list = HandlerList.getRegisteredListeners(p);
                counts.put(p.getName(), list.size());
            }
            return Census.measured(counts);
        } catch (Throwable t) {
            return Census.notMeasured();
        }
    }

    @Override
    public Census pendingTaskCensus() {
        if (isFolia()) {
            return Census.notMeasured();
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (BukkitTask bt : Bukkit.getScheduler().getPendingTasks()) {
                if (bt != null && bt.getOwner() != null) {
                    counts.merge(bt.getOwner().getName(), 1, Integer::sum);
                }
            }
            return Census.measured(counts);
        } catch (Throwable t) {
            return Census.notMeasured();
        }
    }

    @Override
    public Census chunkTicketCensus() {
        if (!chunkTicketsAvailable || isFolia()) {
            return Census.notMeasured();
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (World w : Bukkit.getWorlds()) {
                Map<Plugin, Collection<Chunk>> byPlugin = w.getPluginChunkTickets();
                for (Map.Entry<Plugin, Collection<Chunk>> e : byPlugin.entrySet()) {
                    Collection<Chunk> chunks = e.getValue();
                    if (e.getKey() == null || chunks == null) continue;
                    counts.merge(e.getKey().getName(), chunks.size(), Integer::sum);
                }
            }
            return Census.measured(counts);
        } catch (Throwable t) {
            return Census.notMeasured();
        }
    }

    @Override
    public boolean supportsChunkTickets() {
        return chunkTicketsAvailable;
    }

    @Override
    public boolean addChunkTicket(String worldName, int cx, int cz) {
        if (!chunkTicketsAvailable || worldName == null) return false;
        World w = Bukkit.getWorld(worldName);
        return w != null && w.addPluginChunkTicket(cx, cz, this);
    }

    @Override
    public boolean removeChunkTicket(String worldName, int cx, int cz) {
        if (!chunkTicketsAvailable || worldName == null) return false;
        World w = Bukkit.getWorld(worldName);
        return w != null && w.removePluginChunkTicket(cx, cz, this);
    }

    // --- Event subscriptions -------------------------------------------------

    @Override
    public Runnable onPlayerJoin(Consumer<StressPlayer> listener) {
        Listener l = new Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
            public void on(PlayerJoinEvent e) {
                listener.accept(wrapPlayer(e.getPlayer()));
            }
        };
        Bukkit.getPluginManager().registerEvents(l, this);
        return () -> HandlerList.unregisterAll(l);
    }

    @Override
    public Runnable onPlayerQuit(Consumer<StressPlayer> listener) {
        Listener l = new Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
            public void on(PlayerQuitEvent e) {
                listener.accept(wrapPlayer(e.getPlayer()));
            }
        };
        Bukkit.getPluginManager().registerEvents(l, this);
        return () -> HandlerList.unregisterAll(l);
    }

    @Override
    public Runnable onPlayerTeleport(Consumer<TeleportEvent> listener) {
        Listener l = new Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void on(PlayerTeleportEvent e) {
                listener.accept(new TeleportEvent(
                        wrapPlayer(e.getPlayer()),
                        toStressLocation(e.getFrom()),
                        toStressLocation(e.getTo()),
                        e.getCause().name()));
            }
        };
        Bukkit.getPluginManager().registerEvents(l, this);
        return () -> HandlerList.unregisterAll(l);
    }

    @Override
    public Runnable onChunkLoad(Consumer<ChunkEvent> listener) {
        Listener l = new Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void on(ChunkLoadEvent e) {
                Chunk c = e.getChunk();
                Collection<String> holders = List.of();
                if (chunkTicketsAvailable) {
                    try {
                        Collection<Plugin> plugins = c.getPluginChunkTickets();
                        if (plugins != null && !plugins.isEmpty()) {
                            List<String> names = new ArrayList<>(plugins.size());
                            for (Plugin p : plugins) {
                                if (p != null) names.add(p.getName());
                            }
                            holders = names;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                listener.accept(new ChunkEvent(
                        e.getWorld().getName(),
                        c.getX(),
                        c.getZ(),
                        e.isNewChunk(),
                        holders));
            }
        };
        Bukkit.getPluginManager().registerEvents(l, this);
        return () -> HandlerList.unregisterAll(l);
    }

    @Override
    public Runnable onChunkUnload(Consumer<ChunkEvent> listener) {
        Listener l = new Listener() {
            @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
            public void on(ChunkUnloadEvent e) {
                Chunk c = e.getChunk();
                listener.accept(new ChunkEvent(
                        e.getWorld().getName(),
                        c.getX(),
                        c.getZ(),
                        false,
                        List.of()));
            }
        };
        Bukkit.getPluginManager().registerEvents(l, this);
        return () -> HandlerList.unregisterAll(l);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Runnable registerDirectTeleportEvents(
            Class<?> preClass,
            Consumer<Object> onPre,
            Class<?> postClass,
            Consumer<Object> onPost) {
        if (!Event.class.isAssignableFrom(preClass) || !Event.class.isAssignableFrom(postClass)) {
            return null;
        }
        Listener holder = new Listener() {};
        Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) preClass,
                holder,
                EventPriority.MONITOR,
                (l, event) -> {
                    if (preClass.isInstance(event)) onPre.accept(event);
                },
                this,
                true);
        Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) postClass,
                holder,
                EventPriority.MONITOR,
                (l, event) -> {
                    if (postClass.isInstance(event)) onPost.accept(event);
                },
                this,
                true);
        return () -> HandlerList.unregisterAll(holder);
    }

    // --- Active harness run state delegates ----------------------------------

    public static String chunkLoadCostKey(boolean folia, boolean paper) {
        return StressCoreRuntime.chunkLoadCostKey(folia, paper);
    }

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
}
