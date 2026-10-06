package io.github.dailystruggle.rtp.fabric.claims;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.lang.reflect.Method;

/**
 * Verifier for FTB Chunks on Fabric/NeoForge.
 *
 * <p>FTB Chunks (mod id {@code ftbchunks}) manages chunk claiming and chunk loading.
 * We resolve the claim lookup reflectively so RTP carries no compile-time dependency
 * on FTB Chunks.
 *
 * <p>A missing or incompatible FTB Chunks API disables the verifier ("not claimed"); an error
 * raised inside FTB Chunks rejects the location and keeps it active ({@link ModClaimCheckFailure},
 * S-003).
 */
public class FTBChunksChecker {
  private FTBChunksChecker() {}

  private static volatile boolean exists = true;

  public static boolean isInClaim(RTPCoords coords) {
    if (!exists || coords == null) return false;
    try {
      int chunkX = coords.x() >> 4;
      int chunkZ = coords.z() >> 4;

      // Probe FTB Chunks API
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
      return ModClaimCheckFailure.handle("FTB Chunks", t, () -> exists = false);
    }
  }
}
