package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Message keys for {@code messages/network.yml} (ADR-071): the cross-server /
 * proxy network-mode player-facing messages (rtp-proxy-ADR-014).
 *
 * <p>One enum per {@code messages/<concern>.yml} file (ADR-071).
 */
public enum NetworkMessages {
  /**
   * Sent to the player when {@code /rtp} (optionally with {@code region=<name>})
   * is enrolled on the cross-server wait queue. Placeholder {@code [position]}
   * carries the FIFO position (0 == head). Configurable per REQ-RTP-F-013.
   */
  networkQueued,
  /**
   * Sent when a player runs {@code /rtp*} while already holding a non-terminal
   * cross-server enrolment (ADR-015 / REQ-RTP-NET-015 command-lock).
   * Placeholder {@code [position]} carries the FIFO position when known;
   * an empty value indicates the proxy has not yet assigned one.
   * Configurable per REQ-RTP-F-013.
   */
  alreadyQueued,
  /**
   * Sent when a cross-server waitlist entry was reaped after exceeding its
   * configured TTL (ADR-015). No placeholders. Configurable per
   * REQ-RTP-F-013.
   */
  networkTimedOut,
  /**
   * Sent when a cross-server waitlist enrolment is rejected because the
   * shared waitlist has reached its configured maximum size (ADR-015
   * {@code REJECTED_FULL}). No placeholders. Configurable per REQ-RTP-F-013.
   */
  waitlistFull,
  /**
   * Sent when the proxy has picked a backend for this request and the
   * reservation is being claimed. No placeholders. Configurable per REQ-RTP-F-013.
   */
  networkRouting,
  /**
   * Sent when a coordinate has been reserved on the destination backend
   * and the player is about to be transferred. Placeholder {@code [server]}
   * carries the destination server id. Configurable per REQ-RTP-F-013.
   */
  networkReserved,
  /**
   * Sent immediately before the cross-server hop fires. Placeholder
   * {@code [server]} carries the destination server id. Configurable per
   * REQ-RTP-F-013.
   */
  networkTransferring,
  /**
   * Sent when the network router declines a cross-server hop and falls back
   * to the local pipeline (kill-switch, queue full, rate limit, no live peer,
   * etc.). Placeholder {@code [reason]} carries a short reason code.
   * Configurable per REQ-RTP-F-013.
   */
  networkFallback,
  /**
   * Sent when an enrolled cross-server teleport ultimately failed at a
   * terminal stage. Placeholder {@code [reason]} carries the failure reason
   * code. Configurable per REQ-RTP-F-013.
   */
  networkFailed,
  /**
   * Sent when the player explicitly asked for {@code region=<name>} but no
   * live backend in the network snapshot advertises that region. Placeholder
   * {@code [region]} carries the requested region name. Configurable per
   * REQ-RTP-F-013.
   */
  networkRegionUnavailable,
  /**
   * Sent when {@code region=<name>} is advertised by multiple backends and
   * the operator's collision policy requires a pinned server. Placeholder
   * {@code [region]} carries the requested region name. Configurable per
   * REQ-RTP-F-013.
   */
  networkRegionAmbiguous
}
