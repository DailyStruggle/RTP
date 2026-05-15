package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Configurable parameters for the {@code Polygon} memory shape.
 *
 * <p>Mirrors {@link GenericMemoryShapeParams} for the knobs that still make sense
 * on a polygon (mode / center / weight / uniquePlacements) and adds {@link #vertices}
 * for the admin-authored vertex list (Chunky-parity {@code [[x,z],[x,z],...]} format).
 *
 * <p>{@code expand} is intentionally omitted: a polygon has a fixed admin-authored
 * boundary, so range expansion to compensate for runtime bad locations would push
 * the sampler past that boundary. The {@code Polygon} shape forces {@code expand=false}
 * internally regardless of any value carried in the underlying data map.
 *
 * <p>{@code radius}, the ellipse-specific {@code radius2} (see {@link EllipseMemoryShapeParams#radius2}),
 * and {@code centerRadius} are also omitted because the polygon's bounding box is derived from
 * {@link #vertices} at load time.
 *
 * <p>See ADR-034 for the full design.
 */
public enum PolygonMemoryShapeParams {
  /**
   * The mode for handling biome selection and bad-location avoidance.
   *
   * @see io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode
   */
  mode,

  /**
   * Admin-authored vertex list as a collection of {@code [x, z]} pairs, in
   * traversal order. Chunky-compatible: admins may paste the same vertex list
   * they used in {@code /chunky shape polygon}.
   *
   * <p>Must contain at least 3 vertices. The polygon is implicitly closed (last
   * vertex back to first). Self-intersecting vertex lists are rejected at load.
   * Concave polygons are accepted.
   */
  vertices,

  /**
   * Optional x-coordinate of the shape's center. Defaults to the centroid of the
   * polygon's axis-aligned bounding box when unset.
   */
  centerX,

  /**
   * Optional z-coordinate of the shape's center. Defaults to the centroid of the
   * polygon's axis-aligned bounding box when unset.
   */
  centerZ,

  /**
   * A weighting factor that influences the distribution of random selections.
   * A value greater than 1.0 biases selections towards the outer edge of the
   * polygon's bounding box, while a value less than 1.0 biases them towards the
   * center.
   */
  weight,

  /**
   * If true, each selected location is marked as "bad" to prevent it from being
   * selected again.
   */
  uniquePlacements
}
