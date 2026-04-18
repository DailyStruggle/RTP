package io.github.dailystruggle.rtp.common.configuration.enums;

public enum SafetyKeys {
  invulnerabilityTime,
  safetyRadius,
  /**
   * Bounded retry budget for the stale-chunk guard (ADR-015 / REQ-RTP-S-005).
   * After {@code getChunkAtAsync} resolves, the follow-up block-evaluation task is enqueued
   * onto a Count-Bound task pipe (Folia Region Thread scheduler). If the pipe is backlogged,
   * the native chunk GC may unload the chunk before the task runs, which would otherwise
   * trigger a synchronous chunk load on the Region Thread and trip the Folia Watchdog.
   * When the guard detects that case, it re-queues an async chunk load up to this many
   * times per location candidate; on exhaustion, the candidate is rejected via the normal
   * "unsafe" path (a WARN log is emitted; no silent discard — REQ-RTP-S-004).
   * Default: 2. Raise only on forks with hyper-aggressive chunk GC.
   */
  staleChunkRetryLimit,
  platformRadius,
  platformAirHeight,
  platformDepth,
  platformMaterial,
  airBlocks,
  unsafeBlocks,
  biomeWhitelist,
  biomes,
  version
}
