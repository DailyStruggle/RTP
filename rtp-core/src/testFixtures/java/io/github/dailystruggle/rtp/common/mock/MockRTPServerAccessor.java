package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * In-memory implementation of {@link RTPServerAccessor} for unit tests.
 * All futures complete immediately. Wire into singletons via {@code RTPTestSetup.install(File)}.
 */
public class MockRTPServerAccessor implements RTPServerAccessor {

    private final Map<String, MockRTPWorld> worldsByName = new ConcurrentHashMap<>();
    private final Map<UUID, MockRTPWorld> worldsById = new ConcurrentHashMap<>();
    private final Map<UUID, MockRTPPlayer> playersById = new ConcurrentHashMap<>();
    private final Map<String, MockRTPPlayer> playersByName = new ConcurrentHashMap<>();
    private final Map<String, Object> registeredCommands = new ConcurrentHashMap<>();

    private final MockRTPPlayer consolePlayer = new MockRTPPlayer(RTP.serverId, "CONSOLE", null);
    private final MockRTPScheduler scheduler = new MockRTPScheduler();
    /** Replaceable via {@link #setLocationGenerator} - defaults to {@link MockLocationGenerator}. */
    private ILocationGenerator locationGenerator;
    private final File pluginDirectory;
    /** World border that always reports every location as inside - no real border in tests. */
    private static final WorldBorder ALWAYS_INSIDE_BORDER =
            new WorldBorder(() -> null, loc -> true);

    private String platform = "mock";

    public MockRTPServerAccessor(File pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        MockRTPWorld defaultWorld = new MockRTPWorld("world");
        addWorld(defaultWorld);
        this.locationGenerator = new MockLocationGenerator(defaultWorld);
    }

    public void addSender(RTPCommandSender sender) {
        if (sender instanceof MockRTPPlayer) {
            addPlayer((MockRTPPlayer) sender);
        } else {
            playersById.put(sender.uuid(), new MockRTPPlayer(sender.uuid(), sender.name(), null) {
                @Override public void sendMessage(String message) { sender.sendMessage(message); }
                @Override public boolean hasPermission(String permission) { return sender.hasPermission(permission); }
            });
        }
    }

    // -------------------------------------------------------------------------
    // World / player registration helpers
    // -------------------------------------------------------------------------

    public void addWorld(MockRTPWorld world) {
        worldsByName.put(world.name(), world);
        worldsById.put(world.id(), world);
    }

    public void addPlayer(MockRTPPlayer player) {
        playersById.put(player.uuid(), player);
        playersByName.put(player.name(), player);
    }

    /**
     * Replaces the location generator used by this accessor.
     *
     * <p>Pass a real {@link io.github.dailystruggle.rtp.common.selection.region.LocationGenerator}
     * to exercise the full location-selection pipeline, or keep the default
     * {@link MockLocationGenerator} for tests that only need a stub result.
     */
    public void setLocationGenerator(ILocationGenerator locationGenerator) {
        this.locationGenerator = locationGenerator;
    }

    /** Removes all registered worlds. Useful when a test requires an empty world list. */
    public void clearWorlds() {
        worldsByName.clear();
        worldsById.clear();
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - server metadata
    // -------------------------------------------------------------------------

    @Override
    public String getServerVersion() {
        return "1.0.0-MOCK";
    }

    @Override
    public String getPluginVersion() {
        return "TEST";
    }

    @Override
    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    @Override
    public Integer getServerIntVersion() {
        return 0;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - worlds
    // -------------------------------------------------------------------------

    @Override
    public RTPWorld<?> getRTPWorld(String name) {
        return worldsByName.get(name);
    }

    @Override
    public RTPWorld<?> getRTPWorld(UUID id) {
        return worldsById.get(id);
    }

    @Override
    public List<RTPWorld<?>> getRTPWorlds() {
        return Collections.unmodifiableList(new ArrayList<>(worldsByName.values()));
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - players / senders
    // -------------------------------------------------------------------------

    @Override
    public RTPPlayer getPlayer(UUID uuid) {
        return playersById.get(uuid);
    }

    @Override
    public RTPPlayer getPlayer(String name) {
        return playersByName.get(name);
    }

    @Override
    @Nullable
    public RTPPlayer getConsolePlayer() {
        return consolePlayer;
    }

    @Override
    public RTPCommandSender getSender(UUID uuid) {
        if (uuid.equals(consolePlayer.uuid())) return consolePlayer;
        RTPPlayer player = playersById.get(uuid);
        if (player != null) return player;

        // Return existing mock player if available (registered via addSender/addPlayer)
        // or create and register a new one so its messages can be tracked
        MockRTPPlayer mockSender = new MockRTPPlayer(uuid, "MockSender", new RTPLocation(new MockRTPWorld("default"), 0, 0, 0));
        addPlayer(mockSender);
        return mockSender;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - timing / filesystem
    // -------------------------------------------------------------------------

    @Override
    public long overTime() {
        return 0L;
    }

    @Override
    public File getPluginDirectory() {
        return pluginDirectory;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - messaging (all no-ops; capture via MockRTPPlayer)
    // -------------------------------------------------------------------------

    @Override
    public void sendMessage(UUID target, Enum<?> msgType, String tag) {
        RTPCommandSender sender = getSender(target);
        if (sender != null) {
            Object configValue = RTP.configs.getConfigValue(msgType, null);
            String msg = (configValue != null) ? String.valueOf(configValue) : msgType.name();
            sender.sendMessage(msg);
        }
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, Enum<?> msgType, String tag) {
        sendMessage(target1, msgType, tag);
    }

    @Override
    public void sendMessage(UUID target, String message, String tag) {
        RTPCommandSender sender = getSender(target);
        if (sender != null && message != null) {
            sender.sendMessage(message);
        }
    }

    @Override
    public void sendMessageAndSuggest(UUID target, String message, String suggestion) { }

    @Override
    public void sendMessage(UUID sender, UUID target, String message, String tag) {
        sendMessage(target, message, tag);
    }

    @Override
    public void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag) { }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - text formatting
    // -------------------------------------------------------------------------

    @Override
    public String format(@Nullable UUID player, String text) {
        return text;
    }

    @Override
    public String formatNoColor(@Nullable UUID player, String text) {
        return text;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - logging
    // -------------------------------------------------------------------------

    /**
     * Test hook: every message passed to {@link #log(Level, String)} or
     * {@link #log(Level, String, Throwable)} is appended here so tests can assert on
     * diagnostic output (e.g. the REQ-RTP-S-004 nullChunk attribution summary line).
     * Format: {@code "<LEVEL>: <msg>"}.
     */
    public final List<String> logMessages = new CopyOnWriteArrayList<>();

    @Override
    public void log(Level level, String msg) {
        logMessages.add(level + ": " + msg);
        Logger.getLogger("RTP-Mock").log(level, msg);
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        logMessages.add(level + ": " + msg);
        Logger.getLogger("RTP-Mock").log(level, msg, throwable);
    }

    /** Messages passed to {@link #announce} - inspectable in tests. */
    public final List<String> announcedMessages = new CopyOnWriteArrayList<>();

    @Override
    public void announce(String msg, String permission, String tag) {
        announcedMessages.add(msg);
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - biomes / materials
    // -------------------------------------------------------------------------

    @Override
    public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
        return Collections.singleton("PLAINS");
    }

    @Override
    public Set<String> getBiomes() {
        return Collections.singleton("PLAINS");
    }

    @Override
    public boolean isPrimaryThread() {
        // In the synchronous model everything runs on the calling ("primary")
        // thread. In the threaded server-topology model this reflects the mock
        // main lane, so teleport sync-enforcement can be asserted.
        return !scheduler.isThreaded() || scheduler.isOnMainLane();
    }

    @Override
    public Set<String> materials() {
        Set<String> set = new HashSet<>();
        set.add("AIR");
        set.add("STONE");
        return set;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void stop() { }

    @Override
    public void start() { }

    @Override
    public void start(Object plugin) { }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - biome / shape getters and setters
    // -------------------------------------------------------------------------

    @Override
    public void setBiomeGetter(Function<RTPLocation, String> getter) { }

    @Override
    public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) { }

    private Function<String, ?> worldBorderFunction = null;

    /**
     * Returns configured world border or always-inside world border so
     * {@link io.github.dailystruggle.rtp.common.selection.region.LocationGenerator}
     * never rejects locations in tests.
     */
    @Override
    public Object getWorldBorder(String worldName) {
        if (worldBorderFunction != null) {
            Object res = worldBorderFunction.apply(worldName);
            if (res != null) return res;
        }
        return ALWAYS_INSIDE_BORDER;
    }

    @Override
    public Object getShape(String name) {
        return null;
    }

    @Override
    public boolean setWorldBorderFunction(Function<String, ?> function) {
        this.worldBorderFunction = function;
        return true;
    }

    @Override
    public boolean setShapeFunction(Function<String, ?> shapeFunction) {
        return false;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - task pipes / plugin handle
    // -------------------------------------------------------------------------

    @Override
    public Object createTaskPipe() {
        return new TimeBoundTaskPipe();
    }

    @Override
    public Object createCachePipe() {
        return new TimeBoundTaskPipe();
    }

    @Override
    public Object getPlugin() {
        return null;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - scheduler / location generator
    // -------------------------------------------------------------------------

    @Override
    public RTPScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public ILocationGenerator getLocationGenerator() {
        return locationGenerator;
    }

    // -------------------------------------------------------------------------
    // RTPServerAccessor - TPS
    // -------------------------------------------------------------------------

    @Override
    public double getTPS(int ticks) {
        return 20.0;
    }

    // -------------------------------------------------------------------------
    // Command registration & execution SPI
    // -------------------------------------------------------------------------

    @Override
    public void registerCommands(Object rootCommand, String... aliases) {
        if (rootCommand == null) return;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isEmpty()) {
                    registeredCommands.put(alias.toLowerCase(java.util.Locale.ROOT), rootCommand);
                }
            }
        }
        if (rootCommand instanceof CommandsAPICommand cmd) {
            String name = cmd.name();
            if (name != null && !name.isEmpty()) {
                registeredCommands.put(name.toLowerCase(java.util.Locale.ROOT), rootCommand);
            }
        }
    }

    private final List<String> executedCommands = new CopyOnWriteArrayList<>();

    public List<String> getExecutedCommands() {
        return Collections.unmodifiableList(executedCommands);
    }

    @Override
    public boolean executeCommand(UUID senderId, String commandLine) {
        if (senderId == null || commandLine == null || commandLine.isBlank()) {
            return false;
        }
        executedCommands.add(commandLine);
        String[] tokens = commandLine.trim().split("\\s+");
        if (tokens.length == 0) {
            return false;
        }
        String label = tokens[0].toLowerCase(java.util.Locale.ROOT);
        Object cmdObj = registeredCommands.get(label);
        if (cmdObj == null) {
            return false;
        }
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        RTPCommandSender sender = getSender(senderId);
        if (sender == null) {
            return false;
        }

        if (cmdObj instanceof TreeCommand tree) {
            tree.onCommand(senderId, sender::hasPermission, sender::sendMessage, args);
            CommandsAPI.execute();
            return true;
        } else if (cmdObj instanceof CommandsAPICommand cmd) {
            cmd.onCommand(senderId, sender::hasPermission, sender::sendMessage, args, 0, null);
            CommandsAPI.execute();
            return true;
        }
        return false;
    }

    /**
     * Exposes an unmodifiable view of registered commands for test assertions.
     *
     * @return map of command labels/aliases to registered command objects
     */
    public Map<String, Object> getRegisteredCommands() {
        return Collections.unmodifiableMap(registeredCommands);
    }

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    /** Returns the underlying {@link MockRTPScheduler} for tick-advancement in tests. */
    public MockRTPScheduler getMockScheduler() {
        return scheduler;
    }
}
