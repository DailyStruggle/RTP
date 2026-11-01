package io.github.dailystruggle.rtp.api.schematic;

/**
 * Outcome of a schematic paste attempt. Every value other than {@link #PASTED} is a
 * "did not place blocks" outcome that the caller audits (S-004) and that never aborts a teleport
 * (leafrtp-ADR-041).
 *
 * @governedBy ADR-115
 * @governedBy leafrtp-ADR-041
 * @trace REQ-RTP-S-003
 * @trace REQ-RTP-S-004
 */
public enum PasteResult {
  /** Blocks were written successfully. */
  PASTED,
  /** The destination footprint intersected a claim; paste suppressed (S-003). */
  SKIPPED_CLAIM,
  /** The running platform / active paster cannot service the source. */
  SKIPPED_UNSUPPORTED,
  /** The configured source file was missing or unreadable. */
  MISSING_SOURCE,
  /** The source was found but could not be decoded into a schematic. */
  DECODE_ERROR,
  /** Decoding succeeded but the block-write phase failed. */
  PASTE_ERROR;

  /**
   * @return {@code true} only for {@link #PASTED}; any other outcome is a skip/failure
   *     that must be audited and must not fail the teleport.
   */
  public boolean placed() {
    return this == PASTED;
  }
}
