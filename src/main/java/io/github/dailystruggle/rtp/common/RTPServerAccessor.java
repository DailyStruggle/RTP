package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.substitutions.RTPWorld;
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
    Integer getServerIntVersion();

    /**
     * getFromString relevant methods for getting stuff in a specific world
     * @param name name of world
     * @return world
     */
    @Nullable
    RTPWorld getRTPWorld(String name);

    /**
     * getFromString relevant methods for getting stuff in a specific world
     * @param id id of world
     * @return world
     */
    RTPWorld getRTPWorld(UUID id);

    void setShapeFunction(Function<String, Shape<?>> function);

    /**
     * getFromString relevant methods for getting stuff in a specific world
     * @param name name of world
     * @return desired shape of world
     */
    @Nullable
    Shape<?> getShape(String name);

    List<RTPWorld> getRTPWorlds();

    RTPPlayer getPlayer(UUID uuid);

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
}
