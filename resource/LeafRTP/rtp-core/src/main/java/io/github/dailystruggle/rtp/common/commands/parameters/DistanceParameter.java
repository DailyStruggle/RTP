package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.rtp.common.selection.region.util.BaseDistanceParameter;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;

import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Command parameter supporting distance inputs with unit suffixes
 * (e.g. b, c, r, km, mi, ft) and auto-interpretation backed by {@link DistanceParser}.
 */
public class DistanceParameter extends BaseDistanceParameter {

  public DistanceParameter(
      String permission,
      String description,
      BiFunction<UUID, String, Boolean> isRelevant,
      Object... options) {
    super(permission, description, isRelevant, options);
  }

  public DistanceParameter(
      String permission,
      String description,
      BiFunction<UUID, String, Boolean> isRelevant) {
    super(permission, description, isRelevant);
  }

  /**
   * Parse a distance string into a {@link DistanceParser.ParsedDistance}.
   *
   * @param input raw input string
   * @return parsed distance, or null if unparseable
   */
  public static DistanceParser.ParsedDistance parse(String input) {
    return DistanceParser.parse(input);
  }

  /**
   * Parse a distance string and convert it into chunk units.
   *
   * @param input raw input string
   * @param def fallback chunk value
   * @return distance in chunks
   */
  public static double parseToChunks(String input, double def) {
    return DistanceParser.parseToChunks(input, def);
  }

  /**
   * Parse a distance string and convert it into block units.
   *
   * @param input raw input string
   * @param def fallback block value
   * @return distance in blocks
   */
  public static double parseToBlocks(String input, double def) {
    return DistanceParser.parseToBlocks(input, def);
  }
}
