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
   * @param expr        the expression string (e.g. "< 2", "1..5", ">= 30s", ">= 1m30s")
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
        case "==", "=" -> Math.abs(actualValue - val) < 1e-6;
        default -> false;
      };
    }

    // Exact numeric match fallback
    try {
      double val = Double.parseDouble(trimmed);
      return Math.abs(actualValue - val) < 1e-6;
    } catch (NumberFormatException ignored) {
      DurationParser.ParsedDuration parsed = DurationParser.parse(trimmed, TemporalUnit.SECOND);
      if (parsed != null) {
        return Math.abs(actualValue - parsed.toSeconds()) < 1e-6;
      }
      return false;
    }
  }

  private static double parseValue(String text) {
    if (text == null || text.isBlank()) return 0.0;
    String trimmed = text.trim();
    DurationParser.ParsedDuration parsed = DurationParser.parse(trimmed, TemporalUnit.SECOND);
    if (parsed != null) {
      return parsed.toSeconds();
    }
    try {
      return Double.parseDouble(trimmed);
    } catch (NumberFormatException ignored) {
      return 0.0;
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

  private static double parseMultiplier(String unit) {
    if (unit == null || unit.isBlank()) return 1.0;
    TemporalUnit tu = TemporalUnit.fromString(unit);
    if (tu != null) {
      return tu.getSecondsPerUnit();
    }
    return switch (unit.toLowerCase()) {
      case "s", "sec", "seconds" -> 1.0;
      case "m", "min", "minutes" -> 60.0;
      case "h", "hr", "hours" -> 3600.0;
      case "d", "days" -> 86400.0;
      default -> 1.0;
    };
  }
}
