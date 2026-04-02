package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class RTPAPI {
  public static RTPServerAccessor serverAccessor;
  public static UUID serverId = new UUID(0, 0);

  public static final String DOWNLOADER_ID = "%%__USER__%%";
  public static final String DOWNLOAD_NONCE = "%%__NONCE__%%";

  // Functional delegates mapped by the Core module
  public static Consumer<Object> shapeAdder = null;
  public static Consumer<Object> vertAdder = null;
  public static Function<RTPWorld, Set<String>> biomeProvider = null;

  public static void addShape(Object shape) {
    if (shapeAdder != null) {
      shapeAdder.accept(shape);
    } else {
      throw new IllegalStateException("[RTP API] Cannot add shape: Core implementation is not loaded.");
    }
  }

  public static void addVerticalAdjustor(Object verticalAdjustor) {
    if (vertAdder != null) {
      vertAdder.accept(verticalAdjustor);
    } else {
      throw new IllegalStateException("[RTP API] Cannot add vertical adjustor: Core implementation is not loaded.");
    }
  }

  public static Set<String> getBiomes(RTPWorld world) {
    if (biomeProvider != null) {
      return biomeProvider.apply(world);
    }
    return null;
  }
}
