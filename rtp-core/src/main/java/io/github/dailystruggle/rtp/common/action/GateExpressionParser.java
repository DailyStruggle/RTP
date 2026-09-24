package io.github.dailystruggle.rtp.common.action;

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
      Pattern.compile("^\\s*(<=|>=|<|>|==|=)\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)\\s*$");
  private static final Pattern RANGE_PATTERN =
      Pattern.compile("^\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*\\.\\.\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)\\s*$");

  private GateExpressionParser() {}

  /**
   * Tests whether {@code actualValue} satisfies the gate expression {@code expr}.
   *
   * @param expr        the expression string (e.g. "< 2", "1..5", ">= 30s")
   * @param actualValue the actual numeric value (in base units, e.g. seconds for time, blocks for distance)
   * @return true if satisfied, false otherwise
   */
  public static boolean matches(String expr, double actualValue) {
    if (expr == null || expr.isBlank()) return false;
    String trimmed = expr.trim();

    // Range pattern: "min..max"
    Matcher rangeMatcher = RANGE_PATTERN.matcher(trimmed);
    if (rangeMatcher.matches()) {
      double min = Double.parseDouble(rangeMatcher.group(1));
      double max = Double.parseDouble(rangeMatcher.group(2));
      String unit = rangeMatcher.group(3);
      double mult = parseMultiplier(unit);
      min *= mult;
      max *= mult;
      return actualValue >= min && actualValue <= max;
    }

    // Comparison pattern: "<= N"
    Matcher opMatcher = OP_PATTERN.matcher(trimmed);
    if (opMatcher.matches()) {
      String op = opMatcher.group(1);
      double val = Double.parseDouble(opMatcher.group(2));
      String unit = opMatcher.group(3);
      val *= parseMultiplier(unit);
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
      return false;
    }
  }

  /**
   * Parses time duration in string form (e.g. "5m", "30s", "1h") to seconds.
   */
  public static long parseDurationSeconds(String text, long defaultVal) {
    if (text == null || text.isBlank()) return defaultVal;
    String trimmed = text.trim();
    Matcher m = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]*)$").matcher(trimmed);
    if (!m.matches()) return defaultVal;
    double val = Double.parseDouble(m.group(1));
    double mult = parseMultiplier(m.group(2));
    return (long) (val * mult);
  }

  private static double parseMultiplier(String unit) {
    if (unit == null || unit.isBlank()) return 1.0;
    return switch (unit.toLowerCase()) {
      case "s", "sec", "seconds" -> 1.0;
      case "m", "min", "minutes" -> 60.0;
      case "h", "hr", "hours" -> 3600.0;
      case "d", "days" -> 86400.0;
      default -> 1.0;
    };
  }
}
