package io.github.dailystruggle.server.api;

/**
 * Platform-neutral world weather state for {@link SleepWorldAccessor} (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public enum WeatherState {
  /** Clear skies (no precipitation or thunder). */
  CLEAR,
  /** Raining (precipitation active without thunder). */
  RAIN,
  /** Thundering (precipitation and lightning active). */
  THUNDER
}
