package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.common.selection.region.util.DurationParser;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expression parser for gate range/comparison operators (ADR-093 Section 3).
 *
 * <p>Supports syntax:
 * <ul>
 *   <li>{@code "< 2"}, {@code "<= 64"}, {@code "> 3"}, {@code ">= 30"} (single comparison)</li>
 *   <li>{@code "1..5"} (inclusive range)</li>
 *   <li>{@code "30s"}, {@code "5m"} (temporal duration units)</li>
 *   <li>{@code "true"}, {@code "false"} (boolean)</li>
 * </ul>
 */
public final class GateExpressionParser {

  /** Possessive blank run: operand starts at the first non-blank, no backtracking (input is trimmed). */
  private static final Pattern OP_PATTERN =
      Pattern.compile("(<=|>=|<|>|==|=)\\s*+(.+)");
  private static final String RANGE_SEPARATOR = "..";

  private GateExpressionParser() {}

  /**
   * Tests whether {@code actualValue} satisfies the gate expression {@code expr}.
   *
   * @param expr        the expression string (e.g. {@code "< 2"}, {@code "1..5"}, {@code ">= 30s"}, {@code ">= 1m30s"})
   * @param actualValue the actual numeric value (in base units, e.g. seconds for time, blocks for distance)
   * @return true if satisfied, false otherwise
   */
  public static boolean matches(String expr, double actualValue) {
    if (expr == null || expr.isBlank()) return false;
    String trimmed = expr.trim();

    // Range "min..max": split at the first ".." after a non-empty min (linear scan, no regex backtracking).
    int sep = trimmed.indexOf(RANGE_SEPARATOR, 1);
    if (sep > 0 && sep + RANGE_SEPARATOR.length() < trimmed.length()) {
      double min = parseValue(trimmed.substring(0, sep));
      double max = parseValue(trimmed.substring(sep + RANGE_SEPARATOR.length()));
      return actualValue >= min && actualValue <= max;
    }

    // Comparison pattern: "<= N"
    Matcher opMatcher = OP_PATTERN.matcher(trimmed);
    if (opMatcher.matches()) {
      String op = opMatcher.group(1);
      double val = parseValue(opMatcher.group(2));
      return switch (op) {
        case "<" -> actualValue < val;
        case "<=" -> actualValue <= val;
        case ">" -> actualValue > val;
        case ">=" -> actualValue >= val;
        default -> Math.abs(actualValue - val) < 1e-6; // "==" or "=" (OP_PATTERN admits no other operator)
      };
    }

    // Exact match fallback; same duration-first order as operator operands, so "1d" == "== 1d".
    Double val = parseNumber(trimmed);
    return val != null && Math.abs(actualValue - val) < 1e-6;
  }

  /** Returns whether {@code expr} has a complete expression accepted by the gate parser. */
  public static boolean isValid(String expr) {
    if (expr == null || expr.isBlank()) return false;
    String trimmed = expr.trim();

    int sep = trimmed.indexOf(RANGE_SEPARATOR, 1);
    if (sep > 0 && sep + RANGE_SEPARATOR.length() < trimmed.length()) {
      return isNumber(trimmed.substring(0, sep))
          && isNumber(trimmed.substring(sep + RANGE_SEPARATOR.length()));
    }

    Matcher opMatcher = OP_PATTERN.matcher(trimmed);
    if (opMatcher.matches()) return isNumber(opMatcher.group(2));
    return isNumber(trimmed);
  }

  private static boolean isNumber(String text) {
    Double value = parseNumber(text.trim());
    return value != null && Double.isFinite(value);
  }

  /** {@code text} is a slice of the trimmed expression ending or starting at its non-blank edge, so never blank. */
  private static double parseValue(String text) {
    Double val = parseNumber(text.trim());
    return val != null ? val : 0.0;
  }

  /**
   * Duration first: {@link Double#parseDouble} accepts Java literal suffixes ({@code 1d}, {@code 5f}),
   * which would otherwise shadow the day unit.
   */
  private static Double parseNumber(String trimmed) {
    DurationParser.ParsedDuration parsed = DurationParser.parse(trimmed, TemporalUnit.SECOND);
    if (parsed != null) {
      return parsed.toSeconds();
    }
    try {
      return Double.parseDouble(trimmed);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  /**
   * Parses time duration in string form (e.g. "5m", "30s", "1h", "1m30s", "500ms", "1w") to seconds.
   */
  public static long parseDurationSeconds(String text, long defaultVal) {
    if (text == null || text.isBlank()) return defaultVal;
    DurationParser.ParsedDuration parsed = DurationParser.parse(text, TemporalUnit.SECOND);
    if (parsed != null) {
      return Math.round(parsed.toSeconds());
    }
    return defaultVal;
  }
}
