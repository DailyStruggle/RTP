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
}
