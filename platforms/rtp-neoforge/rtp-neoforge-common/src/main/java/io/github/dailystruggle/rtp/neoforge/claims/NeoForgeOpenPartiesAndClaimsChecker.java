package io.github.dailystruggle.rtp.neoforge.claims;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Verifier for Open Parties and Claims (OPAC) on NeoForge.
 */
public class NeoForgeOpenPartiesAndClaimsChecker {
  private static boolean exists = true;

  public static boolean isInClaim(RTPCoords coords) {
    if (!exists || coords == null) return false;
    try {
      int chunkX = coords.x() >> 4;
      int chunkZ = coords.z() >> 4;

      Class<?> apiClass;
      try {
        apiClass = Class.forName("xaero.pac.common.claims.player.api.PlayerClaimsApi");
      } catch (ClassNotFoundException e) {
        try {
          apiClass = Class.forName("xaero.pac.common.server.player.config.PlayerConfig");
        } catch (ClassNotFoundException e2) {
          exists = false;
          return false;
        }
      }

      for (Method m : apiClass.getMethods()) {
        if (m.getName().equals("isClaimed") || m.getName().equals("getClaim")) {
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
          "[RTP] Disabling OpenPartiesAndClaims integration for NeoForge: " + t.getMessage(),
          t);
      return false;
    }
  }
}
