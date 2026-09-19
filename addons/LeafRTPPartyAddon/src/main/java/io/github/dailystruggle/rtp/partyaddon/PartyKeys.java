package io.github.dailystruggle.rtp.partyaddon;

/**
 * Configuration keys for the party addon.
 *
 * <p>Enum constants are matched against YAML keys in {@code party.yml} by RTP's
 * {@link io.github.dailystruggle.rtp.common.configuration.ConfigParser}. Add a new constant here to
 * expose a new option; no reflection glue is needed.
 */
public enum PartyKeys {
  /** When true, the party addon is active; when false it registers nothing (hard off switch). */
  enabled,
  /**
   * How party members are placed relative to the drawn destination.
   *
   * <p>{@code SAME} teleports every member to the single prepared coordinate; {@code CLUSTER}
   * reserves a small adjacent group of prepared coordinates (one per member) so members land near
   * each other without stacking. Read as a string; unknown values fall back to {@code SAME}.
   */
  placement,
  /**
   * Maximum members teleported in a single party operation. Bounds the number of prepared
   * coordinates a party may consume at once so one large party cannot drain the supply pipeline.
   */
  maxPartySize,
}
