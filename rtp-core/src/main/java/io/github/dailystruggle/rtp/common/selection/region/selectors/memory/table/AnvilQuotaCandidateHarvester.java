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

  private static final java.security.SecureRandom SEED_SOURCE = new java.security.SecureRandom();

  private final int targetQuota;
  private final int maxTrials;
  private final int[] probeOffsets;
  private final int stride;
  private final long salt = SEED_SOURCE.nextLong();

  /**
   * Samples a stride quota from a normal (Gaussian) distribution centered on 33%
   * (mean 0.33, stddev 0.05, bounded to [0.15, 0.50]) across a stride subset of bins.
   *
   * @param strideCapacity total bin capacity or candidate quota baseline for the active stride
   * @return sampled quota of candidates to harvest before rotating to the next stride
   */
  public static int sampleGaussianStrideQuota(int strideCapacity) {
    if (strideCapacity <= 1) return 1;
    double gaussian = SEED_SOURCE.nextGaussian() * 0.05 + 0.33;
    if (gaussian < 0.15) gaussian = 0.15;
    if (gaussian > 0.50) gaussian = 0.50;
    int quota = (int) Math.floor(gaussian * strideCapacity);
    return Math.max(1, Math.min(strideCapacity, quota));
  }

  public AnvilQuotaCandidateHarvester() {
    this(DEFAULT_TARGET_QUOTA, DEFAULT_MAX_TRIALS);
  }

  /**
   * Constructs an AnvilQuotaCandidateHarvester sized to a stride subset of bins,
   * sampling its target quota dynamically from a Gaussian distribution centered on 33%.
   */
  public static AnvilQuotaCandidateHarvester forStride(int strideCapacity) {
    int quota = sampleGaussianStrideQuota(strideCapacity);
    int trials = Math.max(quota * 2, DEFAULT_MAX_TRIALS);
    return new AnvilQuotaCandidateHarvester(quota, trials);
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
    int s = CHUNKS_PER_BIN / (1 << bits);
    if (s <= 0) s = 1;
    this.stride = s;

    for (int i = 0; i < maxTrials; i++) {
      int rev = Integer.reverse(i) >>> (32 - bits);
      this.probeOffsets[i] = (rev * this.stride) % CHUNKS_PER_BIN;
    }
  }

  /**
   * Computes a jittered chunk offset within the [0, 1023] bin space for trial {@code trialIndex}.
   * Adds a per-bin phase shift delta derived from the bin coordinates and instance salt,
   * varying the sampling lattice across different region files while strictly preserving
   * dyadic bisection spacing.
   */
  public int jitteredOffset(int trialIndex, int regionX, int regionZ) {
    int baseOffset = probeOffsets[trialIndex % maxTrials];
    if (stride <= 1) return baseOffset;
    long hash = (regionX * 0x517CC1B727220A95L) ^ (regionZ * 0x9E3779B97F4A7C15L) ^ salt;
    int delta = (int) Math.floorMod(hash, stride);
    return (baseOffset + delta) % CHUNKS_PER_BIN;
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
      int localOffset = jitteredOffset(i, regionX, regionZ);
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

  public int stride() {
    return stride;
  }

  /**
   * Selects {@code n} candidate chunk points from {@code m} total possibilities across an active bin set,
   * applying per-bin dyadic bisection and phase jitter, and assuming any chunk coordinates outside
   * {@code validBins} are invalid to yield an optimal within-bin-set traversal route.
   *
   * @param validBins list of [regionX, regionZ] bin coordinates in the active bin set
   * @param n target number of candidate points to select (N &le; M)
   * @return list of [chunkX, chunkZ] candidate chunk coordinates within the active bin set
   */
  public List<int[]> selectNFromMBinSet(List<int[]> validBins, int n) {
    if (validBins == null || validBins.isEmpty() || n <= 0) {
      return Collections.emptyList();
    }
    int m = validBins.size() * CHUNKS_PER_BIN;
    int targetCount = Math.min(n, m);
    List<int[]> selectedChunks = new ArrayList<>(targetCount);

    int numBins = validBins.size();
    int trialsPerBin = (int) Math.ceil((double) targetCount / numBins);
    if (trialsPerBin < 1) trialsPerBin = 1;

    for (int t = 0; t < trialsPerBin && selectedChunks.size() < targetCount; t++) {
      for (int b = 0; b < numBins && selectedChunks.size() < targetCount; b++) {
        int[] bin = validBins.get(b);
        int rx = bin[0];
        int rz = bin[1];
        int localOffset = jitteredOffset(t, rx, rz);
        int localRx = localOffset & 31;
        int localRz = (localOffset >>> 5) & 31;
        int cx = (rx << 5) + localRx;
        int cz = (rz << 5) + localRz;
        selectedChunks.add(new int[]{cx, cz});
      }
    }

    return selectedChunks;
  }
}
