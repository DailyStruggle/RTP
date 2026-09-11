package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.table;

import io.github.dailystruggle.rtp.anvil.AnvilPrefilter;
import io.github.dailystruggle.rtp.anvil.Verdict;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Quota-gated candidate harvester for backlog warming (ADR-028, ADR-088, ADR-092).
 *
 * <p>Probes region files lazily chunk-by-chunk along dyadic bisection offsets.
 * Stops immediately as soon as {@code targetQuota} safe landing zones are harvested.
 * If all {@code maxTrials} are rejected (e.g. solid deep ocean), marks the bin as discardable
 * in {@link HybridHazardTable} so future pulses skip opening the file completely.
 */
public final class AnvilQuotaCandidateHarvester {

  public static final int DEFAULT_TARGET_QUOTA = 3;
  public static final int DEFAULT_MAX_TRIALS = 16;
  public static final int CHUNKS_PER_BIN = 1024; // 32x32 chunks in one MCA region

  private final int targetQuota;
  private final int maxTrials;
  private final int[] probeOffsets;

  public AnvilQuotaCandidateHarvester() {
    this(DEFAULT_TARGET_QUOTA, DEFAULT_MAX_TRIALS);
  }

  public AnvilQuotaCandidateHarvester(int targetQuota, int maxTrials) {
    if (targetQuota <= 0) throw new IllegalArgumentException("targetQuota must be > 0");
    if (maxTrials < targetQuota) throw new IllegalArgumentException("maxTrials must be >= targetQuota");
    this.targetQuota = targetQuota;
    this.maxTrials = maxTrials;

    // Generate dyadic bisection probe sequence across 1024 chunks
    this.probeOffsets = new int[maxTrials];
    int bits = 32 - Integer.numberOfLeadingZeros(maxTrials - 1);
    if (bits < 4) bits = 4;
    int stride = CHUNKS_PER_BIN / (1 << bits);
    if (stride <= 0) stride = 1;

    for (int i = 0; i < maxTrials; i++) {
      int rev = Integer.reverse(i) >>> (32 - bits);
      this.probeOffsets[i] = (rev * stride) % CHUNKS_PER_BIN;
    }
  }

  public record HarvestResult(
      int regionX,
      int regionZ,
      List<int[]> harvestedChunks, // local [rx, rz] pairs
      int probesExecuted,
      boolean quotaMet,
      boolean isDiscardableBin
  ) {}

  /**
   * Harvests safe candidates for a specific MCA region bin off-thread.
   */
  public HarvestResult harvest(
      Path worldFolder,
      String dimensionSubpath,
      int regionX,
      int regionZ,
      Set<String> unsafeBlocks,
      UnaryOperator<String> reconciler,
      HybridHazardTable hazardTable,
      long binGlobalKeyBase) {

    List<int[]> safeLocations = new ArrayList<>(targetQuota);
    int probesExecuted = 0;
    int consecutiveRejects = 0;

    for (int i = 0; i < maxTrials; i++) {
      int localOffset = probeOffsets[i];
      int rx = localOffset & 31;
      int rz = (localOffset >>> 5) & 31;
      int cx = (regionX << 5) + rx;
      int cz = (regionZ << 5) + rz;
      long globalKey = (binGlobalKeyBase >= 0L) ? (binGlobalKeyBase + localOffset) : -1L;

      // Fast-path: if already known bad in memory, skip probing
      if (hazardTable != null && globalKey >= 0L && hazardTable.isBad(globalKey)) {
        consecutiveRejects++;
        continue;
      }

      probesExecuted++;
      AnvilPrefilter.ProbeResult probe = AnvilPrefilter.probeSyncDetailed(
          worldFolder, dimensionSubpath, cx, cz, unsafeBlocks, reconciler);

      if (probe.verdict() == Verdict.ACCEPT) {
        safeLocations.add(new int[]{rx, rz});
        if (safeLocations.size() >= targetQuota) {
          // EARLY EXIT: Quota satisfied!
          break;
        }
      } else if (probe.verdict() == Verdict.REJECT) {
        consecutiveRejects++;
        if (hazardTable != null && globalKey >= 0L) {
          hazardTable.markBad(globalKey); // free ground-truth learning
        }
      }
    }

    boolean quotaMet = safeLocations.size() >= targetQuota;
    // Discardable if all trials resulted in rejections (e.g. solid deep ocean)
    boolean isDiscardable = (consecutiveRejects == maxTrials && safeLocations.isEmpty());

    return new HarvestResult(
        regionX,
        regionZ,
        Collections.unmodifiableList(safeLocations),
        probesExecuted,
        quotaMet,
        isDiscardable
    );
  }

  public int targetQuota() {
    return targetQuota;
  }

  public int maxTrials() {
    return maxTrials;
  }
}
