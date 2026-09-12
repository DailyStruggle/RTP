package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Command parameter supporting distance inputs with unit suffixes
 * (e.g. b, c, r, km, mi, ft) and auto-interpretation backed by {@link DistanceParser}.
 */
public class DistanceParameter extends CommandParameter {

  private static final List<String> DEFAULT_PRIMARY_UNIT_SUGGESTIONS = Arrays.asList(
      "64c", "128c", "1024b", "2048b", "2r", "4r"
  );

  private final Set<String> allValues;

  public DistanceParameter(
      String permission,
      String description,
      BiFunction<UUID, String, Boolean> isRelevant,
      Object... options) {
    super(
        permission,
        description,
        (uuid, s) -> {
          if (s == null || s.trim().isEmpty()) return false;
          if (s.indexOf('~') >= 0) {
            try {
              RTPCmd.resolveRelativeCoordinate(s, 0L);
              return isRelevant.apply(uuid, s);
            } catch (NumberFormatException e) {
              return false;
            }
          }
          DistanceParser.ParsedDistance parsed = DistanceParser.parse(s, SpatialUnit.CHUNK);
          if (parsed == null) {
            return false;
          }
          return isRelevant.apply(uuid, s);
        });

    Set<String> values = new LinkedHashSet<>();
    if (options != null && options.length > 0) {
      for (Object opt : options) {
        if (opt != null) {
          values.add(String.valueOf(opt));
        }
      }
    }
    // Always include primary unit suggestions (e.g., 64c, 128c, 1024b, 2048b, 2r, 4r)
    values.addAll(DEFAULT_PRIMARY_UNIT_SUGGESTIONS);
    this.allValues = Collections.unmodifiableSet(values);
  }

  public DistanceParameter(
      String permission,
      String description,
      BiFunction<UUID, String, Boolean> isRelevant) {
    this(permission, description, isRelevant, new Object[0]);
  }

  @Override
  public Set<String> values() {
    return allValues;
  }

  /**
   * Parse a distance string into a {@link DistanceParser.ParsedDistance}.
   *
   * @param input raw input string
   * @return parsed distance, or null if unparseable
   */
  public static DistanceParser.ParsedDistance parse(String input) {
    return DistanceParser.parse(input, SpatialUnit.CHUNK);
  }

  /**
   * Parse a distance string and convert it into chunk units.
   *
   * @param input raw input string
   * @param def fallback chunk value
   * @return distance in chunks
   */
  public static double parseToChunks(String input, double def) {
    if (input == null) return def;
    DistanceParser.ParsedDistance parsed = DistanceParser.parse(input, SpatialUnit.CHUNK);
    if (parsed == null) return def;
    return parsed.toChunks();
  }

  /**
   * Parse a distance string and convert it into block units.
   *
   * @param input raw input string
   * @param def fallback block value
   * @return distance in blocks
   */
  public static double parseToBlocks(String input, double def) {
    if (input == null) return def;
    DistanceParser.ParsedDistance parsed = DistanceParser.parse(input, SpatialUnit.BLOCK);
    if (parsed == null) return def;
    return parsed.toBlocks();
  }
}
