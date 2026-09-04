package io.github.dailystruggle.rtp.common.selection.region.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for data size and memory strings with optional unit suffixes and whitespace tolerance.
 * Handles strings such as "256MB", "1GiB", "64KB", "1024 B", case-insensitively.
 */
public final class DataSizeParser {

  private static final Pattern SINGLE_DATA_SIZE_PATTERN =
      Pattern.compile("^\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z]+)?\\s*$");

  private DataSizeParser() {}

  /**
   * Represents the parsed outcome of a data size token.
   */
  public record ParsedDataSize(double magnitude, DataSizeUnit unit, boolean explicitUnit) {

    public double toBytes() {
      return unit.toBytes(magnitude);
    }

    public double toKilobytes() {
      return unit.toKilobytes(magnitude);
    }

    public double toMegabytes() {
      return unit.toMegabytes(magnitude);
    }

    public double toGigabytes() {
      return unit.toGigabytes(magnitude);
    }

    public double toKibibytes() {
      return unit.toKibibytes(magnitude);
    }

    public double toMebibytes() {
      return unit.toMebibytes(magnitude);
    }

    public double toGibibytes() {
      return unit.toGibibytes(magnitude);
    }
  }

  /**
   * Parse a raw input string into a {@link ParsedDataSize}.
   * Supports inputs such as "256MB", "1 GiB", "64kb", "1024", "10.5 MiB".
   *
   * @param input the raw string
   * @param defaultUnit fallback unit when no suffix is specified (defaults to {@link DataSizeUnit#BYTES} if null)
   * @return parsed data size, or null if input cannot be parsed or is malformed
   */
  public static ParsedDataSize parse(String input, DataSizeUnit defaultUnit) {
    if (input == null) return null;
    String trimmed = input.trim().replace(',', '.');
    if (trimmed.isEmpty()) return null;

    Matcher matcher = SINGLE_DATA_SIZE_PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      return null;
    }

    double magnitude;
    try {
      magnitude = Double.parseDouble(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }

    String suffix = matcher.group(2);
    if (suffix != null && !suffix.isEmpty()) {
      DataSizeUnit parsedUnit = DataSizeUnit.fromString(suffix);
      if (parsedUnit != null) {
        return new ParsedDataSize(magnitude, parsedUnit, true);
      } else {
        return null;
      }
    }

    DataSizeUnit effectiveUnit = (defaultUnit != null) ? defaultUnit : DataSizeUnit.BYTES;
    return new ParsedDataSize(magnitude, effectiveUnit, false);
  }

  /**
   * Parse a raw input into a {@link ParsedDataSize} assuming {@link DataSizeUnit#BYTES} by default.
   *
   * @param input the raw string
   * @return parsed data size, or null if input cannot be parsed
   */
  public static ParsedDataSize parse(String input) {
    return parse(input, DataSizeUnit.BYTES);
  }

  /**
   * Parses an input object or string into total bytes with fallback support.
   * Supports numbers, parsed strings (e.g. "256MB", "1GiB"), and special sentinels ("-1", "infinite", "unlimited").
   *
   * @param input string or object representation of data size
   * @param defBytes default fallback value in bytes if input is missing or unparseable
   * @return size in bytes, or {@code defBytes} if unparseable
   */
  public static long parseBytes(Object input, long defBytes) {
    if (input == null) return defBytes;
    if (input instanceof Number num) {
      long val = num.longValue();
      return val < 0 ? -1L : val;
    }
    String s = input.toString().trim().toLowerCase(Locale.ROOT);
    if (s.isEmpty()) return defBytes;
    if (s.equals("-1") || s.equals("infinite") || s.equals("unlimited") || s.equals("none")) {
      return -1L;
    }
    try {
      ParsedDataSize parsed = parse(s, DataSizeUnit.BYTES);
      if (parsed == null) {
        return defBytes;
      }
      double bytes = parsed.toBytes();
      if (bytes < 0) return -1L;
      return (long) Math.round(bytes);
    } catch (Exception e) {
      return defBytes;
    }
  }

  /**
   * Parses an input object or string into total bytes with -1L fallback.
   *
   * @param input string or object representation of data size
   * @return size in bytes, or -1L if invalid or unlimited
   */
  public static long parseBytes(Object input) {
    return parseBytes(input, -1L);
  }
}
