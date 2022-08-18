package io.github.dailystruggle.rtp.common.serverSide;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
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
    String getServerVersion();

    /**
     * @return second integer in version, e.g. the 13 in 1.13.2
     */
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
    RTPWorld getRTPWorld(UUID id);

    /**
     * getShape method for overriding region shape
     * @param name name of world
     * @return desired shape of world
     */
    @Nullable
    Shape<?> getShape(String name);

    boolean setShapeFunction(Function<String,Shape<?>> shapeFunction);

    List<RTPWorld> getRTPWorlds();

    RTPPlayer getPlayer(UUID uuid);
    RTPPlayer getPlayer(String name);

    RTPCommandSender getSender(UUID uuid);

    /**
     * @return predicted next tick time minus current time, in millis
     *          if >0, RTP should cut short any pipeline processing
     */
    long overTime();

    File getPluginDirectory();

    void sendMessage(UUID target, LangKeys msgType);
    void sendMessage(UUID sender, UUID target, LangKeys msgType);

    void sendMessage(UUID target, String message);

    void sendMessage(UUID sender, UUID target, String message);

    void log(Level level, String msg);

    void log(Level level, String msg, Exception exception);

    Set<String> getBiomes();

    boolean isPrimaryThread();

    void reset();

    WorldBorder getWorldBorder(String worldName);

    void setWorldBorderFunction(Function<String,WorldBorder> function);
}
