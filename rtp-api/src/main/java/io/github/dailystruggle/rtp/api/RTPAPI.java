package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;
import java.util.UUID;

public class RTPAPI {
  public static RTPServerAccessor serverAccessor;
  public static UUID serverId = new UUID(0, 0);

  public static void addShape(Object shape) {
    // Implementation will be handled by rtp-core but interface is here
  }

  public static void addVerticalAdjustor(Object verticalAdjustor) {
    // Implementation will be handled by rtp-core but interface is here
  }

  public static Set<String> getBiomes(RTPWorld world) {
    return null; // Will be implemented in core
  }
}
