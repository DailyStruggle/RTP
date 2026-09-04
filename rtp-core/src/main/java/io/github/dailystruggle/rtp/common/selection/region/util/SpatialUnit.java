package io.github.dailystruggle.rtp.common.selection.region.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enumeration of spatial units supported by RTP for distance parameters.
 *
 * <p>In Minecraft, 1 block = 1 meter.
 * 1 chunk = 16 blocks.
 * 1 region (Anvil / Linear) = 32 chunks = 512 blocks.
 */
public enum SpatialUnit {
  // Minecraft native units
  BLOCK(1.0, "b", "block", "blocks"),
  NETHER_BLOCK(8.0, "nb", "netherblock", "netherblocks"),
  CHUNK(16.0, "c", "chunk", "chunks"),
  REGION(512.0, "r", "region", "regions"),

  // Metric units (1 block = 1 meter)
  MILLIMETER(0.001, "mm", "millimeter", "millimeters", "millimetre", "millimetres"),
  CENTIMETER(0.01, "cm", "centimeter", "centimeters", "centimetre", "centimetres"),
  METER(1.0, "m", "meter", "meters", "metre", "metres"),
  KILOMETER(1000.0, "km", "k", "kilo", "kilos", "kilometer", "kilometers", "kilometre", "kilometres"),

  // Imperial / US customary units
  INCH(0.0254, "in", "inch", "inches", "\""),
  FOOT(0.3048, "ft", "foot", "feet", "'"),
  YARD(0.9144, "yd", "yard", "yards"),
  MILE(1609.344, "mi", "mile", "miles"),
  NAUTICAL_MILE(1852.0, "nmi", "nm", "nauticalmile", "nauticalmiles"),

  // Historical and Easter egg units
  SMOOT(1.7018, "smoot", "smoots"),
  FATHOM(1.8288, "fathom", "fathoms"),
  ROD(5.0292, "rod", "rods", "perch", "pole"),
  CHAIN(20.1168, "chain", "chains"),
  FURLONG(201.168, "furlong", "furlongs"),
  LEAGUE(4828.032, "league", "leagues"),
  CUBIT(0.4572, "cubit", "cubits"),
  ASTRONOMICAL_UNIT(149597870700.0, "au", "aus", "astronomicalunit", "astronomicalunits"),
  LIGHT_YEAR(9460730472580800.0, "ly", "lightyear", "lightyears"),
  PARSEC(3.085677581491367e16, "pc", "parsec", "parsecs");

  private final double blocksPerUnit;
  private final String[] aliases;

  private static final Map<String, SpatialUnit> ALIAS_MAP;

  static {
    Map<String, SpatialUnit> map = new HashMap<>();
    for (SpatialUnit unit : values()) {
      registerAlias(map, unit.name().toLowerCase(Locale.ROOT), unit);
      for (String alias : unit.aliases) {
        registerAlias(map, alias.toLowerCase(Locale.ROOT), unit);
      }
    }
    ALIAS_MAP = Collections.unmodifiableMap(map);
  }

  private static void registerAlias(Map<String, SpatialUnit> map, String key, SpatialUnit unit) {
    SpatialUnit existing = map.put(key, unit);
    if (existing != null && existing != unit) {
      throw realCollision(key, existing, unit);
    }
  }

  private static IllegalStateException realCollision(String key, SpatialUnit first, SpatialUnit second) {
    return new IllegalStateException("SpatialUnit alias collision on '" + key + "' between " + first + " and " + second);
  }

  SpatialUnit(double blocksPerUnit, String... aliases) {
    this.blocksPerUnit = blocksPerUnit;
    this.aliases = aliases;
  }

  /**
   * Number of Minecraft blocks represented by 1.0 of this unit.
   */
  public double getBlocksPerUnit() {
    return blocksPerUnit;
  }

  /**
   * Primary short suffix, e.g. "b", "c", "r", "km".
   */
  public String getPrimarySuffix() {
    return (aliases.length > 0) ? aliases[0] : name().toLowerCase(Locale.ROOT);
  }

  /**
   * Convert a magnitude in this unit to blocks.
   */
  public double toBlocks(double value) {
    return value * blocksPerUnit;
  }

  /**
   * Convert a magnitude in this unit to chunks (1 chunk = 16 blocks).
   */
  public double toChunks(double value) {
    return toBlocks(value) / CHUNK.blocksPerUnit;
  }

  /**
   * Convert a magnitude in this unit to regions (1 region = 512 blocks).
   */
  public double toRegions(double value) {
    return toBlocks(value) / REGION.blocksPerUnit;
  }

  /**
   * Find a SpatialUnit by alias or name (case-insensitive).
   *
   * @param token unit string (e.g. "c", "km", "blocks")
   * @return matching SpatialUnit or null if unrecognized
   */
  public static SpatialUnit fromString(String token) {
    if (token == null || token.isEmpty()) return null;
    return ALIAS_MAP.get(token.trim().toLowerCase(Locale.ROOT));
  }
}
