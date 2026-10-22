package io.github.dailystruggle.effectsapi.common;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Stage vocabulary for the {@link EffectsGroupKeys#when} field and the {@code <stage>} segment of
 * effect permission prefixes (effects-api-ADR-005, effects-api-ADR-007). Declared once here so
 * config validation and the platform effect handlers share one list.
 *
 * <p>Tokens are lower-case and never localized. {@link #dispatched()} is false for stages that are
 * documented but not fired by any platform handler: a group bound to one is accepted but never runs.
 */
public enum EffectStage {
  FIRSTJOIN(false),
  JOIN(false),
  PRESETUP(true),
  POSTSETUP(true),
  PRELOAD(true),
  POSTLOAD(true),
  PRETELEPORT(true),
  POSTTELEPORT(true),
  CANCEL(true),
  QUEUEPUSH(true),
  QUEUEPOP(true),
  DEATH(true);

  private static final Set<String> TOKENS;

  static {
    Set<String> tokens = new LinkedHashSet<>();
    for (EffectStage stage : values()) tokens.add(stage.token());
    TOKENS = Collections.unmodifiableSet(tokens);
  }

  private final boolean dispatched;

  EffectStage(boolean dispatched) {
    this.dispatched = dispatched;
  }

  /** Config / permission token, e.g. {@code postteleport}. */
  public String token() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** True when at least one platform handler fires this stage. */
  public boolean dispatched() {
    return dispatched;
  }

  /** Case-insensitive lookup of a {@code when:} token; empty for unknown or blank input. */
  public static Optional<EffectStage> fromToken(String token) {
    if (token == null) return Optional.empty();
    String value = token.trim().toLowerCase(Locale.ROOT);
    for (EffectStage stage : values()) {
      if (stage.token().equals(value)) return Optional.of(stage);
    }
    return Optional.empty();
  }

  /** Every token in declaration order. */
  public static Set<String> tokens() {
    return TOKENS;
  }
}
