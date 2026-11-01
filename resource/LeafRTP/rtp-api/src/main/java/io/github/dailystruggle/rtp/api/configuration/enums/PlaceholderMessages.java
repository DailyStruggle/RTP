package io.github.dailystruggle.rtp.api.configuration.enums;

/**
 * Message keys for {@code messages/placeholders.yml} (ADR-071).
 *
 * <p>Holds the placeholder/template support block shared by every other message
 * file. One enum per {@code messages/<concern>.yml} file (ADR-071).
 */
public enum PlaceholderMessages {
  /** Token set defining available placeholder variables for message templates. */
  placeholders
}
