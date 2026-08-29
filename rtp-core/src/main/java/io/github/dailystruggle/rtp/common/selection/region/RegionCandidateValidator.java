package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.ArrayList;
import java.util.Collection;
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
 *       {@code Y} for the requested column - no anchor-Y copying (S-001). Adjustors that cannot
 *       resolve a specific column (e.g. {@code LinearAdjustor}, whose {@code adjustColumn} default
 *       returns {@code null}) cause a fail-closed rejection rather than an unsafe guess (S-004).</li>
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
      int lx = worldX & 15;
      int lz = worldZ & 15;

      // Non-blocking: only read chunks already resident. The caller warms the bounded footprint.
      RTPChunk<?> center = world.getCachedChunk(packChunkKey(cx, cz));
      if (center == null) return null;

      // Stage: resolve a real standable Y on the requested column. Fail-closed if the adjustor
      // cannot resolve this specific column (S-004) - never fabricate a Y.
      RTPCoords resolved = vert.adjustColumn(center, lx, lz);
      if (resolved == null) return null;

      int safe = Math.max(0, readSafetyRadius());
      Set<String> unsafeBlocks = readUnsafeBlocks();
      int L = safe * 2 + 1;

      // Assemble the neighbour grid the shared SafetyScan expects from resident chunks only.
      RTPChunk<?>[] localChunks = new RTPChunk<?>[L * L];
      int centerChunkX = center.x();
      int centerChunkZ = center.z();
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
            if (neighbour == null) return null; // fail-closed: required chunk not resident
            localChunks[index] = neighbour;
          }
        }
      }

      boolean pass = SafetyScan.isColumnSafe(
          resolved, world, localChunks, L, centerChunkX, centerChunkZ, safe, unsafeBlocks);
      if (!pass) return null;

      // Claim / global-verifier stage (S-003, ADR-026) is applied asynchronously by the caller;
      // see class Javadoc. This method returns the safety-verified candidate only.
      return new RTPLocation(resolved, 1L, null);
    } catch (Throwable t) {
      // Fail-closed on any error (S-004): a validation error is a rejection, never a silent pass.
      RTP.log(Level.WARNING, "[RTP] RegionCandidateValidator failed: " + t, t);
      return null;
    }
  }

  /** Packs chunk coords into the key format expected by {@link RTPWorld#getCachedChunk(long)}. */
  private static long packChunkKey(int cx, int cz) {
    return ((long) cx & 0xffffffffL) | ((long) cz << 32);
  }

  private int readSafetyRadius() {
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      return safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
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
