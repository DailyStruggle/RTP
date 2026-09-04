package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for temporal duration values with optional unit suffixes and auto-interpretation.
 */
public final class DurationParser {

  private static final Pattern SINGLE_DURATION_PATTERN =
      Pattern.compile("^\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)?\\s*$");

  private static final Pattern COMPOSITE_SEGMENT_PATTERN =
      Pattern.compile("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)");

  private DurationParser() {}

  /**
   * Represents the parsed outcome of a duration token.
   */
  public record ParsedDuration(double magnitude, TemporalUnit unit, boolean explicitUnit) {

    public double toSeconds() {
      return unit.toSeconds(magnitude);
    }

    public double toMillis() {
      return unit.toMillis(magnitude);
    }

    public double toTicks() {
      return unit.toTicks(magnitude);
    }
  }

  /**
   * Parse a raw input string into a {@link ParsedDuration}.
   * Supports single tokens (e.g. "20t", "5s", "100ms", "10") and composite tokens (e.g. "1d12h", "2h30m").
   * For composite tokens, total duration is normalized into seconds with unit {@link TemporalUnit#SECOND}.
   *
   * @param input the raw string (e.g. "20t", "5s", "100ms", "10", "1d12h")
   * @param defaultUnit fallback unit when no suffix is specified
   * @return parsed duration, or null if input cannot be parsed
   */
  public static ParsedDuration parse(String input, TemporalUnit defaultUnit) {
    if (input == null) return null;
    String trimmed = input.trim().replace(',', '.');
    if (trimmed.isEmpty()) return null;

    // Check single token match first
    Matcher singleMatcher = SINGLE_DURATION_PATTERN.matcher(trimmed);
    if (singleMatcher.matches()) {
      double magnitude;
      try {
        magnitude = Double.parseDouble(singleMatcher.group(1));
      } catch (NumberFormatException e) {
        return null;
      }

      String suffix = singleMatcher.group(2);
      if (suffix != null && !suffix.isEmpty()) {
        TemporalUnit parsedUnit = TemporalUnit.fromString(suffix);
        if (parsedUnit != null) {
          return new ParsedDuration(magnitude, parsedUnit, true);
        } else {
          return null;
        }
      }

      TemporalUnit effectiveUnit = (defaultUnit != null) ? defaultUnit : TemporalUnit.SECOND;
      return new ParsedDuration(magnitude, effectiveUnit, false);
    }

    // Attempt composite parsing (e.g. "1d12h", "2h 30m 10s")
    Matcher compositeMatcher = COMPOSITE_SEGMENT_PATTERN.matcher(trimmed);
    double totalSeconds = 0.0;
    int matchedChars = 0;
    boolean foundSegment = false;

    while (compositeMatcher.find()) {
      foundSegment = true;
      double segmentValue;
      try {
        segmentValue = Double.parseDouble(compositeMatcher.group(1));
      } catch (NumberFormatException e) {
        return null;
      }

      String suffix = compositeMatcher.group(2);
      TemporalUnit unit = TemporalUnit.fromString(suffix);
      if (unit == null) {
        return null;
      }

      totalSeconds += unit.toSeconds(segmentValue);
      matchedChars += compositeMatcher.end() - compositeMatcher.start();
    }

    if (!foundSegment) {
      return null;
    }

    // Verify there are no leftover non-whitespace characters
    String residual = trimmed.replaceAll("[+-]?[0-9]+(?:\\.[0-9]+)?\\s*[a-zA-Z]+", "").trim();
    if (!residual.isEmpty()) {
      return null;
    }

    return new ParsedDuration(totalSeconds, TemporalUnit.SECOND, true);
  }

  /**
   * Auto-interprets a dimensionless duration magnitude when auto-interpretation is enabled.
   *
   * <p>Heuristic:
   * <ul>
   *   <li>If an explicit unit was provided, returns it unchanged.</li>
   *   <li>Context-based interpretation:
   *     <ul>
   *       <li>If context is tick-centric (e.g. queue periods, pulse intervals, sync timers) and value is >= 1000,
   *           it was likely specified in milliseconds instead of game ticks.</li>
   *       <li>If context is second-centric (e.g. cooldowns, delays, TTLs) and value is unusually large (>= 1000),
   *           it was likely specified in milliseconds. If value is small and context expects seconds, remains seconds.</li>
   *       <li>If default was ticks and value is unusually large (e.g. >= 100000), or default was milliseconds and value is small.</li>
   *     </ul>
   *   </li>
   * </ul>
   *
   * @param parsed the parsed duration
   * @param expectedUnit expected/conventional unit in this context (e.g. TICK or SECOND)
   * @param contextName context identifier for logging (e.g. "teleport cooldown")
   * @return auto-interpreted duration (or the original if no re-interpretation occurred)
   */
  public static ParsedDuration autoInterpret(
      ParsedDuration parsed, TemporalUnit expectedUnit, String contextName) {
    if (parsed == null || parsed.explicitUnit()) {
      return parsed;
    }

    double val = parsed.magnitude();
    if (val <= 0) return parsed;

    // If expected is TICK, but user configured e.g. 5000 (likely 5000 ms = 100 ticks)
    if (expectedUnit == TemporalUnit.TICK && parsed.unit() == TemporalUnit.TICK) {
      if (val >= 1000.0 && val % 50 == 0) {
        // High likelihood of millisecond value mistakenly entered without unit
        logAutoInterpret(contextName, val, "ticks", val / 50.0, "ticks (from millis)", val / 50.0 * 0.05);
        return new ParsedDuration(val, TemporalUnit.MILLISECOND, false);
      }
    } else if (expectedUnit == TemporalUnit.SECOND && parsed.unit() == TemporalUnit.SECOND) {
      // If expected is SECOND, but user configured e.g. 1000 or 5000 (likely milliseconds)
      if (val >= 1000.0 && val % 500 == 0) {
        logAutoInterpret(contextName, val, "seconds", val / 1000.0, "seconds (from millis)", val / 1000.0);
        return new ParsedDuration(val, TemporalUnit.MILLISECOND, false);
      }
    }

    return parsed;
  }

  private static void logAutoInterpret(
      String context, double origVal, String origUnit, double newVal, String newUnit, double secEquiv) {
    String msg = String.format(
        Locale.ROOT,
        "[RTP] Auto-interpreted dimensionless %s '%s' from %s to %s %s (%.2f seconds). To specify explicitly, use suffix '%s'.",
        (context != null ? context : "duration"),
        (origVal == (long) origVal ? String.valueOf((long) origVal) : String.valueOf(origVal)),
        origUnit,
        (newVal == (long) newVal ? String.valueOf((long) newVal) : String.valueOf(newVal)),
        newUnit,
        secEquiv,
        TemporalUnit.fromString(newUnit) != null ? TemporalUnit.fromString(newUnit).getPrimarySuffix() : newUnit
    );
    RTP.log(Level.INFO, msg);
  }
}
