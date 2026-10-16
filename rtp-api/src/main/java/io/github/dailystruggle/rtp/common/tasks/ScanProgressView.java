package io.github.dailystruggle.rtp.common.tasks;

/**
 * Tier 1 read-only interface (ADR-115 section 1) exposing scan and pregen task progress metrics
 * to tools, diagnostics, and placeholders without coupling to {@code tasks.ScanTask}.
 */
public interface ScanProgressView {
  /**
   * Current completed chunk position within the current pass.
   *
   * @return completed chunk count
   */
  long absolutePos();

  /**
   * Total chunks in the current pass or region.
   *
   * @return total target chunk count
   */
  long absoluteTotal();

  /**
   * Chunks processed per second (throughput estimate).
   *
   * @return rate in chunks/second
   */
  long cps();

  /**
   * Land percentage estimate (good candidate chunks / checked chunks).
   *
   * @return percentage as a double [0.0, 100.0]
   */
  double landPercentage();

  /**
   * Estimated seconds remaining until completion.
   *
   * @return remaining seconds
   */
  long etaSeconds();
}
