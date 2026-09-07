package io.github.dailystruggle.rtp.common.benchmark;

/**
 * Controller that selects between {@link KeyRunTable} (flat continuous table, minimal RAM) and
 * {@link SegmentedKeyRunTable} (segmented two-layer table, maximal CPU/cache speed) based on
 * available heap headroom and access characteristics, using <b>hysteresis</b> to strictly prevent
 * mode thrashing.
 *
 * <p><b>The Thrashing Hazard:</b> If a system switches modes purely on a single threshold
 * (e.g. {@code freeMemory < 64 MB -> switch to flat}), an operator hovering near 64 MB will cause
 * the controller to continually re-segment and flatten the table on every pulse, generating
 * garbage and wasting CPU.
 *
 * <p><b>Hysteretic Dual-Threshold Rule:</b>
 * <ul>
 *   <li><b>Degrade to FLAT (RAM Saver):</b> Only when free heap drops below {@code LOW_WATERMARK}
 *       (e.g. 15% of max heap or 64 MB).
 *   <li><b>Upgrade to SEGMENTED (Speed Booster):</b> Only when free heap rises above {@code HIGH_WATERMARK}
 *       (e.g. 35% of max heap or 160 MB) <i>AND</i> has remained stable for at least {@code STABILITY_EPOCHS}
 *       (e.g. 5 consecutive check intervals).
 * </ul>
 *
 * <p>The wide gap between the low watermark and high watermark guarantees that transient spikes
 * (such as chunk loading bursts or GC pauses) cannot trigger a transition.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class AdaptiveTableController {

  public enum Mode {
    /** Global flat run table: maximum compression, lowest possible RAM footprint. */
    FLAT_COMPACT,
    /** Two-layer segmented table: L1 cache-resident lookups, fastest reconciliation. */
    SEGMENTED_ACCELERATED
  }

  private final long lowWatermarkBytes;
  private final long highWatermarkBytes;
  private final int stabilityEpochsRequired;

  private Mode currentMode;
  private int highMemoryEpochCounter = 0;
  private int totalTransitions = 0;

  /**
   * @param lowWatermarkBytes threshold below which system immediately flattens to save RAM
   * @param highWatermarkBytes threshold above which system may upgrade to segmented
   * @param stabilityEpochsRequired consecutive high-memory checks required before upgrading
   */
  public AdaptiveTableController(
      long lowWatermarkBytes, long highWatermarkBytes, int stabilityEpochsRequired) {
    if (lowWatermarkBytes >= highWatermarkBytes) {
      throw new IllegalArgumentException("lowWatermark must be < highWatermark for hysteresis");
    }
    this.lowWatermarkBytes = lowWatermarkBytes;
    this.highWatermarkBytes = highWatermarkBytes;
    this.stabilityEpochsRequired = stabilityEpochsRequired;
    // Default to the compact mode on startup for safety
    this.currentMode = Mode.FLAT_COMPACT;
  }

  /**
   * Evaluates memory state and updates the active mode under hysteresis.
   *
   * @param currentFreeHeapBytes observed free heap bytes
   * @return active mode after evaluation
   */
  public Mode evaluate(long currentFreeHeapBytes) {
    if (currentMode == Mode.SEGMENTED_ACCELERATED) {
      if (currentFreeHeapBytes < lowWatermarkBytes) {
        // Immediate drop to save memory
        currentMode = Mode.FLAT_COMPACT;
        highMemoryEpochCounter = 0;
        totalTransitions++;
      }
    } else {
      // In FLAT_COMPACT mode: only upgrade if comfortably above high watermark for several epochs
      if (currentFreeHeapBytes >= highWatermarkBytes) {
        highMemoryEpochCounter++;
        if (highMemoryEpochCounter >= stabilityEpochsRequired) {
          currentMode = Mode.SEGMENTED_ACCELERATED;
          highMemoryEpochCounter = 0;
          totalTransitions++;
        }
      } else {
        highMemoryEpochCounter = 0;
      }
    }
    return currentMode;
  }

  public Mode currentMode() {
    return currentMode;
  }

  public int totalTransitions() {
    return totalTransitions;
  }

  public long lowWatermarkBytes() {
    return lowWatermarkBytes;
  }

  public long highWatermarkBytes() {
    return highWatermarkBytes;
  }
}
