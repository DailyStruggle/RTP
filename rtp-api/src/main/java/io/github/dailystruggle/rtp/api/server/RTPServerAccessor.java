package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Interface for accessing server-specific functionality
 */
public interface RTPServerAccessor {
    String getServerVersion();

    Integer getServerIntVersion();

    RTPWorld<?> getRTPWorld(String name);

    RTPWorld<?> getRTPWorld(UUID id);

    io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager();

    List<RTPWorld<?>> getRTPWorlds();

    RTPPlayer getPlayer(UUID uuid);

    RTPPlayer getPlayer(String name);

    RTPCommandSender getSender(UUID uuid);

    long overTime();

    File getPluginDirectory();

    void sendMessage(UUID target, MessagesKeys msgType);

    void sendMessage(UUID target1, UUID target2, MessagesKeys msgType);

    void sendMessage(UUID target, String message);

    void sendMessageAndSuggest(UUID target, String message, String suggestion);

    void sendMessage(UUID sender, UUID target, String message);

    void log(Level level, String msg);

    void log(Level level, String msg, Throwable throwable);

    void announce(String msg, String permission);

    Set<String> getBiomes(RTPWorld<?> rtpWorld);

    boolean isPrimaryThread();

    Set<String> materials();

    void stop();

    void start();

    void setBiomeGetter(java.util.function.Function<io.github.dailystruggle.rtp.api.world.RTPLocation, String> getter);

    void setBiomesGetter(java.util.function.Function<io.github.dailystruggle.rtp.api.world.RTPWorld<?>, java.util.Set<String>> getter);

    Object getWorldBorder(String worldName);

    Object getShape(String name);

    boolean setWorldBorderFunction(Function<String, ?> function);

    boolean setShapeFunction(Function<String, ?> shapeFunction);
}

