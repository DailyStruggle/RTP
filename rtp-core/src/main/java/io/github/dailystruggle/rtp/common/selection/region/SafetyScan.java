package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Set;
import java.util.logging.Level;

/**
 * Shared, reservation-agnostic block-clearance safety verdict.
 *
 * <p>This is the single definition of "is this column safe to stand in" (S-001): a
 * {@code safetyRadius} cube scan against configured {@code unsafeBlocks} plus a head-clearance and
 * ground-depth re-validation on the centre column. It was extracted verbatim from
 * {@code QueueTask.runSafetyScan} so the standard {@code /rtp} queue path and every future consumer
 * (the {@code SubspaceShape} group path, addon candidate validators) evaluate identical safety
 * logic rather than growing a second, drifting definition.
 *
 * <p>This helper never loads chunks (S-005): callers supply an already-resolved {@code localChunks}
 * neighbour grid and are responsible for any Folia region-thread ownership required by live
 * {@link RTPChunk#isSafe(int, int, int, Set)} reads.
 */
final class SafetyScan {

  private SafetyScan() {}

  /**
   * Pure per-column block-safety verdict. Mirrors {@code QueueTask.runSafetyScan}'s inner check.
   *
   * @param left the candidate coordinates (feet Y)
   * @param world the world (for min/max height clamps)
   * @param localChunks neighbour chunk grid, row-major {@code L x L} indexed by
   *     {@code (dcX + safe) * L + (dcZ + safe)}
   * @param L neighbour grid edge length ({@code 2 * safe + 1})
   * @param centerChunkX candidate chunk X
   * @param centerChunkZ candidate chunk Z
   * @param safe safety radius in blocks
   * @param unsafeBlocks configured unsafe material names
   * @return {@code true} if the candidate column passes all clearance checks
   */
  static boolean isColumnSafe(
      RTPCoords left,
      RTPWorld<?> world,
      RTPChunk<?>[] localChunks,
      int L,
      int centerChunkX,
      int centerChunkZ,
      int safe,
      Set<String> unsafeBlocks) {

    boolean pass = true;
    try {
      safetyCheck:
      for (int x = left.x() - safe; x <= left.x() + safe; x++) {
        int chunkX = x >> 4;
        int xx = x & 15;
        int dcX = chunkX - centerChunkX;
        for (int z = left.z() - safe; z <= left.z() + safe; z++) {
          int chunkZ = z >> 4;
          int zz = z & 15;
          int dcZ = chunkZ - centerChunkZ;
          int index = (dcX + safe) * L + (dcZ + safe);
          RTPChunk<?> chunk1 = (index >= 0 && index < localChunks.length) ? localChunks[index] : null;
          if (chunk1 == null) {
            pass = false;
            break safetyCheck;
          }
          for (int y = left.y() - safe; y <= left.y() + safe; y++) {
            if (y > world.getMaxHeight() || y < world.getMinHeight()) continue;
            if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
              pass = false;
              break safetyCheck;
            }
          }
        }
      }

      // Ground and head re-validation on the live center chunk, independent of safetyRadius.
      if (pass) {
        RTPChunk<?> centerLive = (L > 0)
                ? localChunks[safe * L + safe]
                : null;
        if (centerLive == null) {
          pass = false;
        } else {
          int xx = left.x() & 15;
          int zz = left.z() & 15;
          int feetY = left.y();
          int headY = feetY + 1;
          int minH = world.getMinHeight();
          int maxH = world.getMaxHeight();
          if (headY <= maxH && headY >= minH
                  && !centerLive.isSafe(xx, headY, zz, unsafeBlocks)) {
            pass = false;
          }
          if (pass) {
            int depth = Math.max(
                    1, LocationGenerator.platformDepthCache.get());
            for (int d = 1; d <= depth; d++) {
              int gy = feetY - d;
              if (gy < minH || gy > maxH) continue;
              if (!centerLive.isSafe(xx, gy, zz, unsafeBlocks)) {
                pass = false;
                break;
              }
            }
          }
        }
      }
    } catch (Exception e) {
      pass = false;
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
    return pass;
  }
}
