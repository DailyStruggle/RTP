package leafcraft.rtp.api.tasks;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.selection.RegionParams;
import leafcraft.rtp.api.substitutions.RTPLocation;

import java.util.UUID;

public interface SetupTeleport extends Runnable {
    default void setupTeleportNow(UUID sender, UUID player, RegionParams rsParams) {
        RTPAPI api = RTPAPI.getInstance();
        RTPLocation location = api.selectionAPI.getRandomLocation(rsParams,sender,player);
        if(location == null) {
            return;
        }
    }
}
