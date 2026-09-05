package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative profile descriptor for group subspace placement, instantiated dynamically from configuration.
 *
 * <p><b>Thin by design.</b> Everything spatial lives in the region-style {@code shape} block and
 * reuses the shape's own parameters - {@code radius}/{@code centerRadius} for the subspace footprint
 * (in blocks), {@code spatialResolution} for the selection stride (participant spacing),
 * {@code uniquePlacements} for non-repeating selection. The only group-specific knob is
 * {@code maxGroupSize}. Shape resolution lives in {@link GroupShapes}.
 *
 * @param name profile name (filename without .yml)
 * @param shape shape block ({@code name} + shape parameters); may be empty, never {@code null}
 * @param maxGroupSize maximum participant count supported by this profile
 */
public record GroupProfile(String name, Map<String, Object> shape, int maxGroupSize) {

  public GroupProfile {
    Objects.requireNonNull(name, "name cannot be null");
    shape =
        (shape == null)
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(shape));
    if (maxGroupSize < 1) maxGroupSize = 1;
  }

  /**
   * @return the configured shape name (from the shape block's {@code name}), or {@code "square"}
   *     when unspecified - the full-lattice default.
   */
  public String shapeName() {
    return String.valueOf(shapeParam("name", "square"));
  }

  /**
   * @return the subspace footprint half-width in blocks, from the shape block's {@code radius}
   *     (default {@code 16}).
   */
  public int radiusBlocks() {
    return (int) longParam("radius", 16L);
  }

  /**
   * @return the selection stride (participant spacing), from the shape block's
   *     {@code spatialResolution} (default {@code 1}); clamped to {@code >= 1}.
   */
  public int spacing() {
    return (int) Math.max(1L, longParam("spatialResolution", 1L));
  }

  private Object shapeParam(String key, Object def) {
    for (Map.Entry<String, Object> e : shape.entrySet()) {
      if (key.equalsIgnoreCase(e.getKey()) && e.getValue() != null) return e.getValue();
    }
    return def;
  }

  private long longParam(String key, long def) {
    Object v = shapeParam(key, null);
    if (v instanceof Number n) return n.longValue();
    if (v != null) {
      try {
        return Long.parseLong(v.toString().trim());
      } catch (NumberFormatException ignored) {
        // fall through to default
      }
    }
    return def;
  }

  /**
   * Instantiates a {@link GroupProfile} dynamically from a {@link ConfigParser<GroupKeys>}.
   *
   * @param name profile name
   * @param parser loaded configuration parser
   * @return dynamic GroupProfile
   */
  public static GroupProfile fromConfig(String name, ConfigParser<GroupKeys> parser) {
    Objects.requireNonNull(parser, "parser cannot be null");

    // getMap resolves a nested block (RtpYamlSection) into a plain Map, mirroring region shape reads.
    Map<String, Object> shapeBlock = Collections.emptyMap();
    Map<String, Object> raw = parser.getMap(GroupKeys.shape);
    if (raw != null && !raw.isEmpty()) {
      Map<String, Object> collected = new LinkedHashMap<>();
      for (Map.Entry<String, Object> e : raw.entrySet()) {
        if (e.getKey() != null) collected.put(e.getKey(), e.getValue());
      }
      shapeBlock = collected;
    }

    int maxGroup = ((Number) parser.getNumber(GroupKeys.maxGroupSize, 8)).intValue();
    return new GroupProfile(name, shapeBlock, maxGroup);
  }
}
