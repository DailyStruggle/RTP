package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.AbstractVerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Production {@link CandidateValidator} backed by a parent {@link Region}.
 *
 * <p>This is the single shared implementation that turns a world column into a verified location by
 * chaining the exact validation layers already used by the standard {@code /rtp} queue path (see
 * {@code QueueTask}):
 *
 * <ol>
 *   <li>{@link VerticalAdjustor#adjustColumn(RTPChunk, int, int)} resolves a real standable
 *       {@code Y} for the requested column - no anchor-Y copying (S-001). {@code LinearAdjustor} and
 *       {@code JumpAdjustor} both implement per-column resolution; any adjustor that cannot resolve a
 *       specific column falls back to the {@link VerticalAdjustor} default ({@code null}), which
 *       causes a fail-closed rejection rather than an unsafe guess (S-004).</li>
 *   <li>{@link SafetyScan#isColumnSafe} runs the shared block-clearance verdict over the resident
 *       neighbour grid - the same code the queue path uses (S-001, no drift).</li>
 * </ol>
 *
 * <p><b>Non-blocking (S-005 + async architecture rule).</b> This validator never loads chunks or
 * blocks on a future: it reads only chunks already resident via
 * {@link RTPWorld#getCachedChunk(long)} and fails closed (returns {@code null}) if a required chunk
 * is not resident. Warming the anchor's bounded {@code NxN} neighbour footprint is the caller's
 * (dispatcher's) responsibility before allocation.
 *
 * <p><b>Claim / global verifiers (S-003, ADR-026).</b> The claim / global check
 * ({@code GlobalRegionVerifiers.checkGlobalRegionVerifiers}) is inherently asynchronous and cannot
 * be awaited here without a blocking {@code join()} (forbidden in core). It is therefore applied by
 * the caller as a separate non-blocking stage on each selected slot - exactly as {@code QueueTask}
 * runs it after its safety verdict - not inside {@link #validate(int, int)}.
 */
final class RegionCandidateValidator implements CandidateValidator {

  private final Region region;

  RegionCandidateValidator(Region region) {
    this.region = Objects.requireNonNull(region, "region cannot be null");
  }

  @Override
  @Nullable
  public RTPLocation validate(int worldX, int worldZ) {
    try {
      RTPWorld<?> world = region.getWorld();
      VerticalAdjustor<?> vert = region.getVert();
      if (world == null || vert == null) return null;

      int cx = worldX >> 4;
      int cz = worldZ >> 4;
      int nominalLx = worldX & 15;
      int nominalLz = worldZ & 15;

      // Non-blocking: only read chunks already resident. The caller warms the bounded footprint.
      RTPChunk<?> center = world.getCachedChunk(packChunkKey(cx, cz));
      if (center == null) return null;

      int safe = Math.max(0, readSafetyRadius());
      Set<String> unsafeBlocks = readUnsafeBlocks();
      int L = safe * 2 + 1;
      int centerChunkX = center.x();
      int centerChunkZ = center.z();

      // Candidate columns to probe: nominal column first, followed by alternate quadrant columns
      // from AbstractVerticalAdjustor.TEST_COORDS ((7,7), (2,2), (12,12), (2,12), (12,2)) in the same chunk.
      List<int[]> probeColumns = new ArrayList<>(6);
      probeColumns.add(new int[] {nominalLx, nominalLz});
      for (List<Integer> coord : AbstractVerticalAdjustor.TEST_COORDS) {
        int qx = coord.get(0);
        int qz = coord.get(1);
        if (qx == nominalLx && qz == nominalLz) continue;
        probeColumns.add(new int[] {qx, qz});
      }

      for (int[] col : probeColumns) {
        int lx = col[0];
        int lz = col[1];

        // Stage: resolve a real standable Y on the candidate column.
        RTPCoords resolved = vert.adjustColumn(center, lx, lz);
        if (resolved == null) continue;

        // Assemble the neighbour grid the shared SafetyScan expects from resident chunks only.
        RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
        boolean neighbourMissing = false;
        for (int bx = resolved.x() - safe; bx <= resolved.x() + safe; bx++) {
          int chunkX = bx >> 4;
          int dcX = chunkX - centerChunkX;
          for (int bz = resolved.z() - safe; bz <= resolved.z() + safe; bz++) {
            int chunkZ = bz >> 4;
            int dcZ = chunkZ - centerChunkZ;
            int index = (dcX + safe) * L + (dcZ + safe);
            if (index < 0 || index >= localChunks.length || localChunks[index] != null) continue;
            if (chunkX == centerChunkX && chunkZ == centerChunkZ) {
              localChunks[index] = center;
            } else {
              RTPChunk<?> neighbour = world.getCachedChunk(packChunkKey(chunkX, chunkZ));
              if (neighbour == null) {
                neighbourMissing = true;
                break;
              }
              localChunks[index] = neighbour;
            }
          }
          if (neighbourMissing) break;
        }

        if (neighbourMissing) continue;

        boolean pass = SafetyScan.isColumnSafe(
            resolved, world, localChunks, L, centerChunkX, centerChunkZ, safe, unsafeBlocks);
        if (!pass) continue;

        // Claim / global-verifier stage (S-003, ADR-026) is applied asynchronously by the caller;
        // see class Javadoc. This method returns the safety-verified candidate only.
        return new RTPLocation(resolved, 1L, null);
      }

      return null;
    } catch (Throwable t) {
      // Fail-closed on any error (S-004): a validation error is a rejection, never a silent pass.
      RTP.log(Level.WARNING, "[RTP] RegionCandidateValidator failed: " + t, t);
      return null;
    }
  }

  @Override
  public java.util.concurrent.CompletableFuture<RTPLocation> validateAsync(int worldX, int worldZ) {
    try {
      RTPWorld<?> world = region.getWorld();
      VerticalAdjustor<?> vert = region.getVert();
      if (world == null || vert == null) {
        return java.util.concurrent.CompletableFuture.completedFuture(null);
      }

      int cx = worldX >> 4;
      int cz = worldZ >> 4;
      int baseWorldX = cx << 4;
      int baseWorldZ = cz << 4;

      int safe = Math.max(0, readSafetyRadius());
      int minChunkX = (baseWorldX - safe) >> 4;
      int maxChunkX = (baseWorldX + 15 + safe) >> 4;
      int minChunkZ = (baseWorldZ - safe) >> 4;
      int maxChunkZ = (baseWorldZ + 15 + safe) >> 4;

      // Collect futures for on-demand chunk loading without blocking or failing on cache misses (S-005)
      java.util.List<java.util.concurrent.CompletableFuture<?>> chunkFutures = new ArrayList<>();
      for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
          if (world.getCachedChunk(packChunkKey(chunkX, chunkZ)) == null) {
            chunkFutures.add(world.getChunkAt(chunkX, chunkZ));
          }
        }
      }

      if (chunkFutures.isEmpty()) {
        // Fast path: all needed chunks already resident in cache
        return java.util.concurrent.CompletableFuture.completedFuture(validate(worldX, worldZ));
      }

      return java.util.concurrent.CompletableFuture.allOf(chunkFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .thenApply(v -> validate(worldX, worldZ))
          .exceptionally(ex -> {
            RTP.log(Level.WARNING, "[RTP] Async candidate validation failed: " + ex, ex);
            return null;
          });
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP] RegionCandidateValidator validateAsync setup failed: " + t, t);
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
  }

  /** Packs chunk coords into the key format expected by {@link RTPWorld#getCachedChunk(long)}. */
  private static long packChunkKey(int cx, int cz) {
    return ((long) cx & 0xffffffffL) | ((long) cz << 32);
  }

  private int readSafetyRadius() {
    try {
      if (RTP.configs == null) return 0;
      @SuppressWarnings("unchecked")
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return 0;
      Number n = safety.getNumber(SafetyKeys.safetyRadius, 0);
      return n != null ? n.intValue() : 0;
    } catch (Throwable ignored) {
      return 0;
    }
  }

  private Set<String> readUnsafeBlocks() {
    Set<String> out = new ConcurrentSkipListSet<>();
    try {
      Object value = RTP.configs.getConfigValue(BlocksKeys.unsafeBlocks, new ArrayList<>());
      if (value instanceof Collection<?> collection) {
        for (Object o : collection) {
          if (o != null) out.add(o.toString());
        }
      }
    } catch (Throwable ignored) {
      // best-effort; empty set means "nothing explicitly unsafe"
    }
    return out;
  }
}
