package io.github.dailystruggle.rtp.folia_v1_21_R1.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.help.SendMessage;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.folia_v1_21_R1.entity.FoliaRTPCommandSender;
import io.github.dailystruggle.rtp.folia_v1_21_R1.entity.FoliaRTPPlayer;
import io.github.dailystruggle.rtp.folia_v1_21_R1.world.FoliaRTPChunkManager;
import io.github.dailystruggle.rtp.folia_v1_21_R1.world.FoliaRTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ServerAccessorImpl implements RTPServerAccessor {
    private final Map<UUID, FoliaRTPWorld> worldMap = new ConcurrentHashMap<>();
    private Function<String, ?> shapeFunction = (s) -> null;
    private Function<String, ?> worldBorderFunction = (s) -> null;
    private Function<RTPWorld<?>, Set<String>> biomes = (rtpWorld) -> new HashSet<>();

    public ServerAccessorImpl() {
        for (World world : Bukkit.getWorlds()) {
            worldMap.put(world.getUID(), new FoliaRTPWorld(world));
        }
    }

    @Override
    public String getServerVersion() {
        return Bukkit.getVersion();
    }

    @Override
    public Integer getServerIntVersion() {
        String version = Bukkit.getBukkitVersion().split("-")[0];
        String[] parts = version.split("\\.");
        if (parts.length < 2) return 0;
        return Integer.parseInt(parts[1]);
    }

    @Override
    public @Nullable RTPWorld<?> getRTPWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world == null) return null;
        return worldMap.computeIfAbsent(world.getUID(), uuid -> new FoliaRTPWorld(world));
    }

    @Override
    public @Nullable RTPWorld<?> getRTPWorld(UUID id) {
        World world = Bukkit.getWorld(id);
        if (world == null) return null;
        return worldMap.computeIfAbsent(id, uuid -> new FoliaRTPWorld(world));
    }

    @Override
    public io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager() {
        return new FoliaRTPChunkManager();
    }

    @Override
    public @Nullable Object getShape(String name) {
        return shapeFunction.apply(name);
    }

    @Override
    public boolean isPrimaryThread() {
        // Folia doesn't have a single primary thread
        return false;
    }

    @Override
    public @Nullable Object getWorldBorder(String worldName) {
        return worldBorderFunction.apply(worldName);
    }

    @Override
    public @NotNull List<RTPWorld<?>> getRTPWorlds() {
        return Bukkit.getWorlds().stream().map(world -> getRTPWorld(world.getUID())).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public @Nullable RTPPlayer getPlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return null;
        return new FoliaRTPPlayer(player);
    }

    @Override
    public @Nullable RTPPlayer getPlayer(String name) {
        Player player = Bukkit.getPlayer(name);
        if (player == null) return null;
        return new FoliaRTPPlayer(player);
    }

    @Override
    public @Nullable RTPCommandSender getSender(UUID uuid) {
        CommandSender commandSender = (uuid.equals(RTPAPI.serverId)) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(uuid);
        if (commandSender == null) return null;
        if (commandSender instanceof Player) return new FoliaRTPPlayer((Player) commandSender);
        return new FoliaRTPCommandSender(commandSender);
    }

    @Override
    public long overTime() {
        return 0;
    }

    @Override
    public File getPluginDirectory() {
        return Bukkit.getPluginManager().getPlugin("RTP").getDataFolder();
    }

    @Override
    public void sendMessage(UUID target, MessagesKeys msgType) {
        ConfigParser<MessagesKeys> parser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (parser == null) return;
        String msg = String.valueOf(parser.getConfigValue(msgType, ""));
        if (msg == null || msg.isEmpty()) return;
        sendMessage(target, msg);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType) {
        ConfigParser<MessagesKeys> parser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = String.valueOf(parser.getConfigValue(msgType, ""));
        if (msg == null || msg.isEmpty()) return;
        sendMessage(target1, target2, msg);
    }

    @Override
    public void sendMessage(UUID target, String message) {
        SendMessage.sendMessage(getSender(target), message);
    }

    @Override
    public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
        SendMessage.sendMessage(getSender(target), message, suggestion, suggestion);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, String message) {
        SendMessage.sendMessage(getSender(target1), getSender(target2), message);
    }

    @Override
    public void log(Level level, String msg) {
        Bukkit.getLogger().log(level, msg);
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        Bukkit.getLogger().log(level, msg, throwable);
    }

    @Override
    public void announce(String msg, String permission) {
        SendMessage.sendMessage(Bukkit.getConsoleSender(), msg);
        for (Player p : Bukkit.getOnlinePlayers().stream().filter(player -> player.hasPermission(permission)).collect(Collectors.toSet())) {
            SendMessage.sendMessage(p, msg);
        }
    }

    @Override
    public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
        return biomes.apply(rtpWorld);
    }

    @Override
    public Set<String> materials() {
        return Arrays.stream(Material.values()).map(Material::name).collect(Collectors.toSet());
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean setShapeFunction(Function<String, ?> shapeFunction) {
        this.shapeFunction = shapeFunction;
        return true;
    }

    @Override
    public boolean setWorldBorderFunction(Function<String, ?> function) {
        this.worldBorderFunction = function;
        return true;
    }

    @Override
    public void start() {
    }

    @Override
    public void setBiomeGetter(Function<RTPLocation, String> getter) {
    }

    @Override
    public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
        this.biomes = getter;
    }
}
