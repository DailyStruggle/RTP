package io.github.dailystruggle.helpers.stresstestrtp;

/** Observation channel that completed an attempt. Written literally. */
public enum AttributionSource {
    /** LeafRTP PostTeleportEvent (plugin's own completion instant). */
    PLUGIN_EVENT,
    /** Bukkit PlayerTeleportEvent. */
    TELEPORT_EVENT,
    /** Position comparison (Folia fallback). */
    POSITION_POLL,
    /** Console line matched a short-circuit failure. */
    CONSOLE,
    /** Per-attempt deadline elapsed. */
    TIMEOUT,
    /** Not yet completed. */
    NONE
}
