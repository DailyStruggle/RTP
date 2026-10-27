package io.github.dailystruggle.rtp.anvil;

/**
 * Chunks around a centre chunk that share its region file, decoded the first time they are asked
 * for (ADR-120 terrain square). Handed to a job run by
 * {@link AnvilPrefilter#squareJobAsync}; valid only while that job runs.
 *
 * @governedBy ADR-016
 * @governedBy ADR-115
 */
public interface ChunkSquare {

  /** Largest {@code |dx|} / {@code |dz|} answered. */
  int MAX_RADIUS = 2;

  /**
   * Probe of the chunk at offset {@code (dx, dz)} from the centre over the job's Y window, decoded
   * at most once per drain. Never opens another region file, loads or generates a chunk (S-005).
   *
   * @return the probe, or {@code null} when {@code |dx|} or {@code |dz|} exceeds
   *     {@link #MAX_RADIUS}, the chunk lies in another region file, is not generated, fails to
   *     decode, or the job has returned (unmeasured, never guessed)
   */
  ChunkColumnProbe chunk(int dx, int dz);
}
