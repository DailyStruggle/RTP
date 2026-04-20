package io.github.dailystruggle.rtp.anvil;

/**
 * Outcome of an Anvil region-file pre-filter probe. See ADR-016 Decision §3 for the
 * authoritative contract; a short summary is given here to keep the enum self-documenting.
 *
 * <p>The pre-filter is <strong>advisory only</strong>. A {@link #REJECT} verdict is trusted
 * to short-circuit the candidate off-thread; an {@link #ACCEPT} verdict is <em>not</em>
 * authoritative — it merely indicates that the region file passed the cheap on-disk sample,
 * and the candidate still proceeds through the existing live-load path where
 * {@code chunk.isSafe(...)} remains the source of truth.</p>
 */
public enum Verdict {
  /**
   * Region file is readable, format is supported, and at least one sampled block in the
   * motion-blocking surface column is in the normalized unsafe set. The candidate is
   * rejected off-thread without a live chunk load.
   */
  REJECT,

  /**
   * Region file is readable, format is supported, and no unsafe block was sampled in the
   * motion-blocking surface column. The candidate proceeds through the existing live-load
   * path; the authoritative {@code chunk.isSafe(...)} re-check still runs before teleport.
   */
  ACCEPT,

  /**
   * The pre-filter did not produce a definitive answer — region file missing, custom
   * generator in use, chunk currently loaded, unsupported {@code DataVersion}, unsupported
   * compression mode (e.g. LZ4 in phase 1), or any I/O error. The candidate proceeds
   * through the existing live-load path unchanged.
   */
  UNKNOWN
}
