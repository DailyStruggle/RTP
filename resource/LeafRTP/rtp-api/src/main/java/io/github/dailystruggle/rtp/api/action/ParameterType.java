package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

/**
 * Declared type of a command {@link ParameterSpec} (ADR-098 declarative command parameters).
 *
 * <p>The type governs which <em>symbolic</em> default keywords are legal for a parameter. A default
 * value that is not one of the recognized symbolic keywords is treated as a literal value of the
 * declared type. Symbolic keywords that are illegal for the declared type are rejected at config load
 * time (fail-fast), so operators discover misconfiguration immediately rather than through degraded
 * runtime behavior.
 */
@PublicApi
public enum ParameterType {

  /** Another player (target of the action). Symbolic defaults: {@code self}, {@code any}. */
  PLAYER("self", "any"),

  /** A single world/block coordinate component. Symbolic defaults: {@code self}, {@code spawn}. */
  COORDINATE("self", "spawn"),

  /** A named region. Symbolic defaults: {@code self}. */
  REGION("self"),

  /** A named world. Symbolic defaults: {@code self}. */
  WORLD("self"),

  /** A numeric literal. No symbolic defaults. */
  NUMBER(),

  /** A free-form string literal. No symbolic defaults. */
  STRING();

  private final Set<String> symbolicDefaults;

  ParameterType(String... symbolicDefaults) {
    this.symbolicDefaults = Set.of(symbolicDefaults);
  }

  /**
   * Returns the set of symbolic default keywords legal for this type (lowercase).
   */
  public Set<String> symbolicDefaults() {
    return Collections.unmodifiableSet(symbolicDefaults);
  }

  /**
   * Returns whether {@code keyword} is a recognized symbolic default for this type.
   */
  public boolean isSymbolicDefault(String keyword) {
    if (keyword == null) return false;
    return symbolicDefaults.contains(keyword.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Returns whether {@code keyword} is a symbolic default keyword recognized by <em>any</em> type.
   * Used to distinguish an intended-but-illegal symbolic default (config error) from a plain literal.
   */
  public static boolean isKnownSymbolicKeyword(String keyword) {
    if (keyword == null) return false;
    String k = keyword.trim().toLowerCase(Locale.ROOT);
    for (ParameterType t : values()) {
      if (t.symbolicDefaults.contains(k)) return true;
    }
    return false;
  }

  /**
   * Parses a type name (case-insensitive), defaulting to {@link #STRING} when null/blank.
   *
   * @throws IllegalArgumentException if {@code name} is non-blank but not a recognized type
   */
  public static ParameterType parse(String name) {
    if (name == null || name.isBlank()) return STRING;
    try {
      return valueOf(name.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unknown parameter type: '" + name + "'");
    }
  }
}
