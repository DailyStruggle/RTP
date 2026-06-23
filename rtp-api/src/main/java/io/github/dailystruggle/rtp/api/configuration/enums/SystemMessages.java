package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Message keys for {@code messages/system.yml} (ADR-071): configuration/reload
 * lifecycle log lines, location save/load confirmations, and miscellaneous
 * display flags. This file also carries the {@code version} stamp for the
 * {@code messages/} tree.
 *
 * <p>One enum per {@code messages/<concern>.yml} file (ADR-071).
 */
public enum SystemMessages {
  // --- Configuration / reload events ---
  /** Logged when a legacy configuration file is detected and migrated. */
  oldFile,
  /** Logged when a new world is discovered and a default region config is created for it. */
  newWorld,
  /** Sent to the command sender when a configuration reload starts. */
  reloading,
  /** Sent to the command sender when a configuration reload completes successfully. */
  reloaded,
  /** Sent when the plugin begins applying a configuration or data update. */
  updating,
  /** Sent when the configuration or data update completes successfully. */
  updated,
  /** Sent to confirm that a named location has been saved successfully. */
  locationSaved,
  /** Sent to confirm that a named location has been loaded successfully. */
  locationLoaded,
  /** Whether to append the developer tag to relevant messages; controls a boolean display flag. */
  showDevTag,
  /** Version string shown in the plugin startup banner and {@code /rtp version}. */
  version
}
