package io.github.dailystruggle.rtp.common.serverSide;

import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public interface RTPServerAccessor {
    /**
     * @return whole version string
     */
    @NotNull
    String getServerVersion();

    /**
     * @return second integer in version, e.g. the 13 in 1.13.2
     */
    @NotNull
    Integer getServerIntVersion();

    /**
     * @param name name of world
     * @return world
     */
    @Nullable
    RTPWorld getRTPWorld(String name);

    /**
     * @param id id of world
     * @return world
     */
    @Nullable
    RTPWorld getRTPWorld(UUID id);

    @NotNull
    List<RTPWorld> getRTPWorlds();

    @Nullable
    RTPPlayer getPlayer(UUID uuid);

    /**
     * @param name player identification
     * @return player, or null if they don't exist
     */
    @Nullable
    RTPPlayer getPlayer(String name);

    /**
     * @param uuid player identification
     * @return sender, or null if they don't exist
     */
    @Nullable
    RTPCommandSender getSender(UUID uuid);

    /**
     * @return predicted next tick time minus current time, in millis
     *          if >0, RTP should cut short any pipeline processing
     */
    long overTime();

    /**
     * @return base directory for plugin configuration files
     */
    File getPluginDirectory();

    /**
     * send a message to this person
     * @param target
     * @param msgType
     */
    void sendMessage(UUID target, MessagesKeys msgType);

    /**
     * send a message to these people, avoiding duplicates
     * @param sender
     * @param target
     * @param msgType
     */
    void sendMessage(UUID sender, UUID target, MessagesKeys msgType);

    /**
     * send a message to this person
     * @param target
     * @param message
     */
    void sendMessage(UUID target, String message);

    /**
     * send a message with a hover and click event for suggesting a subsequent command
     * @param target
     * @param message
     * @param suggestion
     */
    void sendMessageAndSuggest(UUID target, String message, String suggestion);

    /**
     * send a message to these people, avoiding duplicates
     * @param sender
     * @param target
     * @param message
     */
    void sendMessage(UUID sender, UUID target, String message);

    /**
     * output a message to console
     * @param level
     * @param msg
     */
    void log(Level level, String msg);

    /**
     * output a message to console
     * @param level
     * @param msg
     * @param exception
     */
    void log(Level level, String msg, Exception exception);

    /**
     * send a message to all players with this permission
     * @param msg message to send
     * @param permission permission required
     */
    void announce(String msg, String permission);

    /**
     * @return list of all possible biomes
     */
    Set<String> getBiomes();

    /**
     * @return thread state, for determining whether to apply a change now or schedule it
     */
    boolean isPrimaryThread();

    /**
     * getShape method for overriding region shape
     * @param worldName name of world
     * @return desired shape of world
     */
    @Nullable
    Shape<?> getShape(String worldName);
    boolean setShapeFunction(Function<String,Shape<?>> shapeFunction);

    /**
     * @param worldName name of world
     * @return a worldborder as defined in this library
     */
    @Nullable
    WorldBorder getWorldBorder(String worldName);
    boolean setWorldBorderFunction(Function<String,WorldBorder> function);

    /**
     * @return set of all possible block types
     */
    Set<String> materials();

    /**
     * using server scheduling methods,
     *  cancel command/teleport tasks and clear all chunk loads
     */
    void stop();

    /**
     * using server scheduling methods,
     *  schedule repeating tasks for command and teleport methods
     */
    void start();
}
