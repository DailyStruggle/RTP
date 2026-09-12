package io.github.dailystruggle.rtp.common.selection.region.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enumeration of data size and memory units supporting standard binary and decimal byte units.
 *
 * <p>Standard units:
 * <ul>
 *   <li>Bytes: {@code b}, {@code byte}, {@code bytes} (1 byte)</li>
 *   <li>Kilobytes (decimal): {@code kb} (1,000 bytes)</li>
 *   <li>Kibibytes (binary): {@code kib} (1,024 bytes)</li>
 *   <li>Megabytes (decimal): {@code mb} (1,000,000 bytes)</li>
 *   <li>Mebibytes (binary): {@code mib} (1,048,576 bytes)</li>
 *   <li>Gigabytes (decimal): {@code gb} (1,000,000,000 bytes)</li>
 *   <li>Gibibytes (binary): {@code gib} (1,073,741,824 bytes)</li>
 * </ul>
 */
public enum DataSizeUnit {
  // Byte units
  BYTES(1.0, "b", "byte", "bytes"),

  // Kilobyte units
  KILOBYTES(1000.0, "kb", "kilobyte", "kilobytes"),
  KIBIBYTES(1024.0, "kib", "kibibyte", "kibibytes"),

  // Megabyte units
  MEGABYTES(1000.0 * 1000.0, "mb", "megabyte", "megabytes"),
  MEBIBYTES(1024.0 * 1024.0, "mib", "mebibyte", "mebibytes"),

  // Gigabyte units
  GIGABYTES(1000.0 * 1000.0 * 1000.0, "gb", "gigabyte", "gigabytes"),
  GIBIBYTES(1024.0 * 1024.0 * 1024.0, "gib", "gibibyte", "gibibytes");

  private final double bytesPerUnit;
  private final String[] aliases;

  private static final Map<String, DataSizeUnit> ALIAS_MAP;

  static {
    Map<String, DataSizeUnit> map = new HashMap<>();
    for (DataSizeUnit unit : values()) {
      registerAlias(map, unit.name().toLowerCase(Locale.ROOT), unit);
      for (String alias : unit.aliases) {
        registerAlias(map, alias.toLowerCase(Locale.ROOT), unit);
      }
    }
    ALIAS_MAP = Collections.unmodifiableMap(map);
  }

  private static void registerAlias(Map<String, DataSizeUnit> map, String key, DataSizeUnit unit) {
    DataSizeUnit existing = map.put(key, unit);
    if (existing != null && existing != unit) {
      throw new IllegalStateException("DataSizeUnit alias collision on '" + key + "' between " + existing + " and " + unit);
    }
  }

  DataSizeUnit(double bytesPerUnit, String... aliases) {
    this.bytesPerUnit = bytesPerUnit;
    this.aliases = aliases;
  }

  /**
   * Number of bytes represented by 1.0 of this unit.
   */
  public double getBytesPerUnit() {
    return bytesPerUnit;
  }

  /**
   * Primary short suffix, e.g. "b", "kb", "kib", "mb", "mib", "gb", "gib".
   */
  public String getPrimarySuffix() {
    return (aliases.length > 0) ? aliases[0] : name().toLowerCase(Locale.ROOT);
  }

  /**
   * Convert 1.0 of this unit to bytes.
   */
  public double toBytes() {
    return bytesPerUnit;
  }

  /**
   * Convert a magnitude in this unit to bytes.
   */
  public double toBytes(double value) {
    return value * bytesPerUnit;
  }

  /**
   * Convert 1.0 of this unit to decimal kilobytes (1 KB = 1000 bytes).
   */
  public double toKilobytes() {
    return bytesPerUnit / 1000.0;
  }

  /**
   * Convert a magnitude in this unit to decimal kilobytes (1 KB = 1000 bytes).
   */
  public double toKilobytes(double value) {
    return toBytes(value) / 1000.0;
  }

  /**
   * Convert 1.0 of this unit to decimal megabytes (1 MB = 1,000,000 bytes).
   */
  public double toMegabytes() {
    return bytesPerUnit / 1_000_000.0;
  }

  /**
   * Convert a magnitude in this unit to decimal megabytes (1 MB = 1,000,000 bytes).
   */
  public double toMegabytes(double value) {
    return toBytes(value) / 1_000_000.0;
  }

  /**
   * Convert 1.0 of this unit to decimal gigabytes (1 GB = 1,000,000,000 bytes).
   */
  public double toGigabytes() {
    return bytesPerUnit / 1_000_000_000.0;
  }

  /**
   * Convert a magnitude in this unit to decimal gigabytes (1 GB = 1,000,000,000 bytes).
   */
  public double toGigabytes(double value) {
    return toBytes(value) / 1_000_000_000.0;
  }

  /**
   * Convert 1.0 of this unit to binary kibibytes (1 KiB = 1024 bytes).
   */
  public double toKibibytes() {
    return bytesPerUnit / 1024.0;
  }

  /**
   * Convert a magnitude in this unit to binary kibibytes (1 KiB = 1024 bytes).
   */
  public double toKibibytes(double value) {
    return toBytes(value) / 1024.0;
  }

  /**
   * Convert 1.0 of this unit to binary mebibytes (1 MiB = 1,048,576 bytes).
   */
  public double toMebibytes() {
    return bytesPerUnit / (1024.0 * 1024.0);
  }

  /**
   * Convert a magnitude in this unit to binary mebibytes (1 MiB = 1,048,576 bytes).
   */
  public double toMebibytes(double value) {
    return toBytes(value) / (1024.0 * 1024.0);
  }

  /**
   * Convert 1.0 of this unit to binary gibibytes (1 GiB = 1,073,741,824 bytes).
   */
  public double toGibibytes() {
    return bytesPerUnit / (1024.0 * 1024.0 * 1024.0);
  }

  /**
   * Convert a magnitude in this unit to binary gibibytes (1 GiB = 1,073,741,824 bytes).
   */
  public double toGibibytes(double value) {
    return toBytes(value) / (1024.0 * 1024.0 * 1024.0);
  }

  /**
   * Find a DataSizeUnit by alias or name (case-insensitive).
   *
   * @param token unit string (e.g. "b", "byte", "kb", "kib", "mb", "mib", "gb", "gib")
   * @return matching DataSizeUnit or null if unrecognized
   */
  public static DataSizeUnit fromString(String token) {
    if (token == null || token.isEmpty()) return null;
    return ALIAS_MAP.get(token.trim().toLowerCase(Locale.ROOT));
  }
}
