package io.github.dailystruggle.rtp.common.configuration.enums;

/**
 * Configuration keys for the {@code advanced/ttl.yml} surface (ADR-079).
 * Defines retention duration for rejected spatial memory segments by failure cause
 * and optional per-verifier overrides.
 */
public enum TtlKeys {
  causes,
  verifiers,
  version
}
