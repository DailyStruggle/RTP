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
  /**
   * Toggle for the vanilla-Spigot Anvil read-only pre-filter (ADR-016 / REQ-RTP-S-005).
   * When enabled, {@code BukkitRTPWorld.getChunkAt} will probe the persisted
   * {@code r.X.Z.mca} region file on a background thread and reject clearly-unsafe
   * candidates (lava/magma/fire at the heightmap surface) before requesting the
   * authoritative live chunk load. Default: {@code true}. The flag has no effect on
   * Paper or Folia — those platforms {@code @Override} {@code getChunkAt} with their
   * native async APIs and never enter the pre-filter path.
   */
  anvilPrefilterEnabled,
  biomeWhitelist,
  biomes,
  version
}
