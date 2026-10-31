package io.github.dailystruggle.rtp.common.selection.region.util;

/**
 * Resolves relative coordinate tokens for command input (~, ~n, -~, -~n).
 */
public final class RelativeCoordinate {

  private RelativeCoordinate() {}

  /**
   * Resolves relative coordinate tokens (~, ~n, -~, -~n) against a base coordinate.
   * Tokens without ~ are parsed as absolute numbers.
   *
   * @param token raw override token (must be non-null)
   * @param base  the player's current coordinate on this axis
   * @return the resolved absolute coordinate
   * @throws NumberFormatException if the numeric offset is malformed
   */
  public static boolean isRelative(String token) {
    return token != null && token.indexOf('~') >= 0;
  }

  public static long resolve(String token, long base) {
    String t = token.trim();
    int tilde = t.indexOf('~');
    if (tilde < 0) {
      return (long) Double.parseDouble(t);
    }
    boolean negativeBase = tilde > 0 && t.charAt(tilde - 1) == '-';
    long resolved = negativeBase ? -base : base;
    String offsetStr = t.substring(tilde + 1).trim();
    if (!offsetStr.isEmpty()) {
      resolved += (long) Double.parseDouble(offsetStr);
    }
    return resolved;
  }
}
