package io.github.dailystruggle.rtp.api.action;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Locale;

/**
 * Declarative command parameter (ADR-098 declarative command parameters).
 *
 * <p>A parameter binds an optional permission and a fallback default to a named command argument:
 * <ul>
 *   <li>{@code permission} gates whether the caller may <em>supply/override</em> the argument. If a
 *       caller supplies the argument without holding the permission, the invocation is denied (a
 *       configurable message is shown; the argument is not silently dropped).</li>
 *   <li>{@code defaultValue} is applied whenever the argument is absent (or dropped for lack of
 *       permission). A {@code null}/blank default with {@code required=true} yields a usage error.</li>
 * </ul>
 *
 * <p>The default may be a type-specific <em>symbolic</em> keyword (e.g. {@code self}, {@code any},
 * {@code spawn}) or a plain literal. Symbolic keywords illegal for the declared {@link ParameterType}
 * are rejected at config load time (fail-fast).
 */
@PublicApi
public record ParameterSpec(
    String name,
    ParameterType type,
    boolean required,
    String permission,
    String defaultValue) {

  public ParameterSpec {
    name = (name == null) ? "" : name.trim().toLowerCase(Locale.ROOT);
    type = (type == null) ? ParameterType.STRING : type;
    permission = (permission == null) ? "" : permission.trim();
    defaultValue = (defaultValue == null) ? "" : defaultValue.trim();
  }

  /**
   * Returns whether this parameter requires a permission to be supplied/overridden.
   */
  public boolean hasPermission() {
    return !permission.isBlank();
  }

  /**
   * Returns whether this parameter declares a default value.
   */
  public boolean hasDefault() {
    return !defaultValue.isBlank();
  }

  /**
   * Returns whether the declared default is a symbolic keyword (as opposed to a literal value).
   */
  public boolean isSymbolicDefault() {
    return hasDefault() && ParameterType.isKnownSymbolicKeyword(defaultValue);
  }

  /**
   * Validates the parameter, throwing {@link IllegalArgumentException} on any config error so that
   * misconfiguration fails fast at load (never degrades silently).
   *
   * @throws IllegalArgumentException if the name is blank, or the default is a symbolic keyword that
   *     is illegal for the declared type
   */
  public void validate() {
    if (name.isBlank()) {
      throw new IllegalArgumentException("Command parameter must declare a non-blank 'name'");
    }
    if (hasDefault()
        && ParameterType.isKnownSymbolicKeyword(defaultValue)
        && !type.isSymbolicDefault(defaultValue)) {
      throw new IllegalArgumentException(
          "Parameter '" + name + "' declares symbolic default '" + defaultValue
              + "' which is not valid for type " + type + " (allowed: " + type.symbolicDefaults()
              + "). Use a literal value or a valid symbolic keyword.");
    }
  }
}
