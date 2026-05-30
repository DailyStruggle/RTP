package io.github.dailystruggle.rtp.example;

/**
 * Configuration keys for the example addon.
 *
 * <p>Enum constants are matched against YAML keys in {@code example.yml} by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}. Add a new constant here to
 * expose a new option; no reflection glue is needed.
 */
public enum ExampleKeys {
  /** When true, the addon registers a {@code GlobalRegionVerifier} that rejects claim land. */
  rejectInClaim,
  /** When true, the addon listens for {@code PostTeleportEvent} and broadcasts a message. */
  announceTeleport,
  /**
   * When true, the addon shows a per-second "teleporting in N..." countdown while the configured
   * {@code teleportDelay} is waited out. This only fires for immediate (unqueued) teleports, where
   * the delay is actually observed in {@code TeleportPipelineTask.runLoad}.
   */
  delayCountdown,
  /**
   * When true, the addon shows a recurring "you are #N in the queue" countdown while a player waits
   * in the public wait queue, and a "you're up!" message the moment the player is popped.
   */
  queueCountdown,
}
