package io.github.dailystruggle.rtp.fabric.server;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.fabric.RTPFabric;
import io.github.dailystruggle.rtp.fabric.entity.FabricPlayer;
import io.github.dailystruggle.rtp.fabric.world.FabricWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class FabricServerAccessor implements RTPServerAccessor {
    private final Map<String, TrackedRTPTask> tasks = new ConcurrentHashMap<>();
    private MinecraftServer server;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public void registerAction(TrackedRTPTask task) {
        tasks.put(task.trackingId(), task);
    }

    @Override
    public void removeAction(String trackingId) {
        tasks.remove(trackingId);
    }

    @Override
    public Map<String, Long> getSnapshot() {
        return tasks.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> 0L));
    }

    @Override
    public String getServerVersion() {
        return server != null ? server.getVersion() : "unknown";
    }

    @Override
    public String getPluginVersion() {
        return FabricLoader.getInstance().getModContainer("rtp").get().getMetadata().getVersion().getFriendlyString();
    }

    @Override
    public String getPlatform() {
        return "Fabric";
    }

    @Override
    public Integer getServerIntVersion() {
        return 21; // Simplified for now
    }

    @Override
    public RTPWorld<?> getRTPWorld(String name) {
        if (server == null) return null;
        for (ServerWorld world : server.getWorlds()) {
            if (world.getRegistryKey().getValue().toString().equals(name)) {
                return new FabricWorld(world);
            }
        }
        return null;
    }

    @Override
    public RTPWorld<?> getRTPWorld(UUID id) {
        return getRTPWorld(id.toString());
    }

    @Override
    public List<RTPWorld<?>> getRTPWorlds() {
        if (server == null) return Collections.emptyList();
        List<RTPWorld<?>> worlds = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            worlds.add(new FabricWorld(world));
        }
        return worlds;
    }

    @Override
    public RTPPlayer getPlayer(UUID uuid) {
        if (server == null) return null;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
        return player != null ? new FabricPlayer(player) : null;
    }

    @Override
    public RTPPlayer getPlayer(String name) {
        if (server == null) return null;
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        return player != null ? new FabricPlayer(player) : null;
    }

    @Override
    public RTPPlayer getConsolePlayer() {
        return null; // Fabric doesn't have a direct equivalent in the same way
    }

    @Override
    public RTPCommandSender getSender(UUID uuid) {
        return getPlayer(uuid);
    }

    @Override
    public long overTime() {
        return 0;
    }

    @Override
    public File getPluginDirectory() {
        return RTPFabric.getInstance().getDataDirectory();
    }

    @Override
    public void sendMessage(UUID target, MessagesKeys msgType, String tag) {
        RTPPlayer player = getPlayer(target);
        if (player != null) {
            player.sendMessage(tag); // Simplified
        }
    }

    @Override
    public void sendMessage(UUID target, String message, String tag) {
        RTPPlayer player = getPlayer(target);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    @Override
    public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
        sendMessage(target, message, "");
    }

    @Override
    public void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag) {
        target.sendMessage(message);
    }

    @Override
    public String format(UUID player, String text) {
        return text;
    }

    @Override
    public String formatNoColor(UUID player, String text) {
        return text;
    }

    @Override
    public void log(Level level, String msg) {
        RTPFabric.LOGGER.info("[" + level.getName() + "] " + msg);
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        RTPFabric.LOGGER.error("[" + level.getName() + "] " + msg, throwable);
    }

    @Override
    public void announce(String msg, String permission, String tag) {
        if (server == null) return;
        server.getPlayerManager().broadcast(Text.literal(msg), false);
    }

    @Override
    public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
        return new HashSet<>(); // Need to implement correctly
    }

    @Override
    public Set<String> getBiomes() {
        return new HashSet<>();
    }

    @Override
    public boolean isPrimaryThread() {
        return server != null && server.isOnThread();
    }

    @Override
    public Set<String> materials() {
        return new HashSet<>();
    }

    @Override
    public void stop() {
    }

    @Override
    public void start() {
    }

    @Override
    public void start(Object plugin) {
    }

    @Override
    public void setBiomeGetter(Function<RTPLocation, String> getter) {
    }

    @Override
    public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
    }

    @Override
    public Object getWorldBorder(String worldName) {
        return null;
    }

    @Override
    public Object getShape(String name) {
        return null;
    }

    @Override
    public boolean setWorldBorderFunction(Function<String, ?> function) {
        return false;
    }

    @Override
    public boolean setShapeFunction(Function<String, ?> shapeFunction) {
        return false;
    }

    @Override
    public Object createTaskPipe() {
        return null;
    }

    @Override
    public Object createCachePipe() {
        return null;
    }

    @Override
    public Object getPlugin() {
        return RTPFabric.getInstance();
    }

    @Override
    public void releaseAllChunkTickets() {
    }

    @Override
    public void shapePlatform(RTPLocation location) {
    }

    @Override
    public RTPScheduler getScheduler() {
        return RTP.scheduler;
    }

    @Override
    public ILocationGenerator getLocationGenerator() {
        return null;
    }

    @Override
    public double getTPS(int ticks) {
        return 20.0;
    }
}
