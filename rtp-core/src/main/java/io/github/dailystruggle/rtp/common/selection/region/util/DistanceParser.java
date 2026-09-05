package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust parser for spatial distance values with optional unit suffixes and auto-interpretation.
 */
public final class DistanceParser {

  private static final Pattern DISTANCE_PATTERN =
      Pattern.compile("^\\s*([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z'\"]+)?\\s*$");

  private DistanceParser() {}

  /**
   * Represents the parsed outcome of a distance token.
   */
  public record ParsedDistance(double magnitude, SpatialUnit unit, boolean explicitUnit) {

    public double toBlocks() {
      return unit.toBlocks(magnitude);
    }

    public double toChunks() {
      return unit.toChunks(magnitude);
    }

    public double toRegions() {
      return unit.toRegions(magnitude);
    }
  }

  /**
   * Parse a raw input string into a {@link ParsedDistance}.
   *
   * @param input the raw string (e.g. "256c", "4096b", "4r", "2km", "16")
   * @param defaultUnit fallback unit when no suffix is specified
   * @return parsed distance, or null if input cannot be parsed
   */
  public static ParsedDistance parse(String input, SpatialUnit defaultUnit) {
    if (input == null) return null;
    String trimmed = input.trim().replace(',', '.');
    Matcher matcher = DISTANCE_PATTERN.matcher(trimmed);
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
      SpatialUnit parsedUnit = SpatialUnit.fromString(suffix);
      if (parsedUnit != null) {
        return new ParsedDistance(magnitude, parsedUnit, true);
      } else {
        // Unknown suffix
        return null;
      }
    }

    SpatialUnit effectiveUnit = (defaultUnit != null) ? defaultUnit : SpatialUnit.BLOCK;
    return new ParsedDistance(magnitude, effectiveUnit, false);
  }

  /**
   * Auto-interprets a dimensionless distance magnitude when auto-interpretation is enabled.
   *
   * <p>Heuristic:
   * <ul>
   *   <li>If an explicit unit was already provided, returns it unchanged.</li>
   *   <li>If magnitude is unusually small (e.g. <= 32) and world border radius is substantial (e.g. >= 500 blocks),
   *       a dimensionless number like 4, 8, 16 was almost certainly intended in chunks or regions, not single blocks!
   *       If magnitude * 512 <= worldBorderRadius: regions.
   *       Else if magnitude * 16 <= worldBorderRadius: chunks.</li>
   *   <li>If magnitude is unusually large (e.g. > worldBorderRadius or > 50000) while default was chunks/regions,
   *       it was almost certainly intended in blocks.</li>
   * </ul>
   *
   * @param parsed the parsed distance
   * @param worldBorderRadius radius of the world border in blocks (or <= 0 if unknown/infinite)
   * @param contextName context identifier for logging (e.g. "region 'default' radius")
   * @return auto-interpreted distance (or the original if no re-interpretation occurred)
   */
  public static ParsedDistance autoInterpret(
      ParsedDistance parsed, double worldBorderRadius, String contextName) {
    if (parsed == null || parsed.explicitUnit()) {
      return parsed;
    }

    double val = parsed.magnitude();
    if (val <= 0) return parsed;

    // Default interpretation was BLOCK
    if (parsed.unit() == SpatialUnit.BLOCK) {
      // Unusually small: an outer radius <= 32 blocks is ~1-2 chunks, virtually useless for random teleport.
      if (val <= 32.0 && worldBorderRadius >= 256.0) {
        // Check if regions or chunks fits nicely inside the world border
        if (val <= 8.0 && val * 512.0 <= worldBorderRadius) {
          logAutoInterpret(contextName, val, "blocks", val * 32.0, "regions", val * 512.0);
          return new ParsedDistance(val, SpatialUnit.REGION, false);
        } else if (val * 16.0 <= worldBorderRadius) {
          logAutoInterpret(contextName, val, "blocks", val, "chunks", val * 16.0);
          return new ParsedDistance(val, SpatialUnit.CHUNK, false);
        }
      }
    } else if (parsed.unit() == SpatialUnit.CHUNK) {
      // If default was CHUNK, but value is e.g. 5000 (which would be 80,000 blocks) and exceeds world border
      if (worldBorderRadius > 0 && val > 512.0 && val * 16.0 > worldBorderRadius && val <= worldBorderRadius) {
        logAutoInterpret(contextName, val, "chunks", val / 16.0, "blocks", val);
        return new ParsedDistance(val, SpatialUnit.BLOCK, false);
      }
    }

    return parsed;
  }

  private static void logAutoInterpret(
      String context, double origVal, String origUnit, double newVal, String newUnit, double blockEquiv) {
    String msg = String.format(
        Locale.ROOT,
        "[RTP] Auto-interpreted dimensionless %s '%s' from %s to %s %s (%.0f blocks). To specify explicitly, use suffix '%s'.",
        (context != null ? context : "distance"),
        (origVal == (long) origVal ? String.valueOf((long) origVal) : String.valueOf(origVal)),
        origUnit,
        (newVal == (long) newVal ? String.valueOf((long) newVal) : String.valueOf(newVal)),
        newUnit,
        blockEquiv,
        SpatialUnit.fromString(newUnit) != null ? SpatialUnit.fromString(newUnit).getPrimarySuffix() : newUnit
    );
    RTP.log(Level.INFO, msg);
  }
}
