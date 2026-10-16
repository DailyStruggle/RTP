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

  private static final Pattern OP_PATTERN =
      Pattern.compile("^(<=|>=|<|>|==|=)\\s*(.+)$");
  private static final Pattern RANGE_PATTERN =
      Pattern.compile("^(.+?)\\s*\\.\\.\\s*(.+)$");

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

    // Range pattern: "min..max"
    Matcher rangeMatcher = RANGE_PATTERN.matcher(trimmed);
    if (rangeMatcher.matches()) {
      double min = parseValue(rangeMatcher.group(1));
      double max = parseValue(rangeMatcher.group(2));
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

  /** {@code text} is a regex group bounded by non-whitespace, so never null or blank. */
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
