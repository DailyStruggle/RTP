package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import java.util.UUID;
import java.util.Set;

public class RTPAPI {
    public static UUID serverId = new UUID( 0, 0 );
    public static void addShape( Object shape ) {
        // Implementation will be handled by rtp-core but interface is here
    }

    public static void addVerticalAdjustor( Object verticalAdjustor ) {
        // Implementation will be handled by rtp-core but interface is here
    }

    public static Set<String> getBiomes( RTPWorld world ) {
        return null; // Will be implemented in core
    }
}
