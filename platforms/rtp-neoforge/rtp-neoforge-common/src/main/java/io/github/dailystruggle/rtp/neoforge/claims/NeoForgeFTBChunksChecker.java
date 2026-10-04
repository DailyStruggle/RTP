package io.github.dailystruggle.rtp.neoforge.claims;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Verifier for FTB Chunks on NeoForge.
 */
public class NeoForgeFTBChunksChecker {
  private NeoForgeFTBChunksChecker() {}

  private static boolean exists = true;

  public static boolean isInClaim(RTPCoords coords) {
    if (!exists || coords == null) return false;
    try {
      int chunkX = coords.x() >> 4;
      int chunkZ = coords.z() >> 4;

      Class<?> apiClass;
      try {
        apiClass = Class.forName("dev.ftb.mods.ftbchunks.data.FTBChunksAPI");
      } catch (ClassNotFoundException e) {
        try {
          apiClass = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
        } catch (ClassNotFoundException e2) {
          exists = false;
          return false;
        }
      }

      for (Method m : apiClass.getMethods()) {
        if (m.getName().equals("isChunkClaimed")) {
          if (m.getParameterCount() == 3) {
            Object res = m.invoke(null, coords.worldName(), chunkX, chunkZ);
            if (res instanceof Boolean b) return b;
            if (res != null) return true;
          } else if (m.getParameterCount() == 2) {
            Object res = m.invoke(null, chunkX, chunkZ);
            if (res instanceof Boolean b) return b;
            if (res != null) return true;
          }
        }
      }

      return false;
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.WARNING,
          "[RTP] Disabling FTB Chunks integration for NeoForge: " + t.getMessage(),
          t);
      return false;
    }
  }
}
