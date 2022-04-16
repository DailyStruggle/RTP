package leafcraft.rtp.api;

import leafcraft.rtp.api.substitutions.RTPWorld;

import java.util.UUID;

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
    RTPWorld getRTPWorld(String name);

    /**
     * getFromString relevant methods for getting stuff in a specific world
     * @param id id of world
     * @return world
     */
    RTPWorld getRTPWorld(UUID id);

    RTPWorld getDefaultRTPWorld();

    /**
     * @return predicted next tick time minus current time, in millis
     *          if >0, RTP should cut short any pipeline processing
     */
    long overTime();
}
