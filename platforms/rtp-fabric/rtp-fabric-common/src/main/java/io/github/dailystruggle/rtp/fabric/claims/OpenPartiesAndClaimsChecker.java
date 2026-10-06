package io.github.dailystruggle.rtp.fabric.claims;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.lang.reflect.Method;

/**
 * Verifier for Open Parties and Claims (OPAC) on Fabric/NeoForge.
 *
 * <p>OPAC (mod id {@code openpartiesandclaims}) manages chunk claims through its
 * API surfaces. We resolve the claim lookup reflectively so RTP carries no
 * compile-time dependency on OPAC.
 *
 * <p>A missing or incompatible OPAC API disables the verifier ("not claimed"); an error raised
 * inside OPAC rejects the location and keeps it active ({@link ModClaimCheckFailure}, S-003).
 */
public class OpenPartiesAndClaimsChecker {
  private OpenPartiesAndClaimsChecker() {}

  private static volatile boolean exists = true;

  public static boolean isInClaim(RTPCoords coords) {
    if (!exists || coords == null) return false;
    try {
      int chunkX = coords.x() >> 4;
      int chunkZ = coords.z() >> 4;

      // Probe OPAC API classes
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
          // Attempt reflective invocation if signature matches chunk coords or world
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
      return ModClaimCheckFailure.handle("OpenPartiesAndClaims", t, () -> exists = false);
    }
  }
}
