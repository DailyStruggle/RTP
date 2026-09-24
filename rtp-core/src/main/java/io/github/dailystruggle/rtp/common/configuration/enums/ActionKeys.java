package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Field keys for declarative scripted action YAML definitions under
 * {@code definitions/actions/<actionId>.yml} (ADR-093).
 */
public enum ActionKeys {
  version,
  alias,
  permission,
  description,
  placement,
  confinement,
  lifecycle,
  command
}
