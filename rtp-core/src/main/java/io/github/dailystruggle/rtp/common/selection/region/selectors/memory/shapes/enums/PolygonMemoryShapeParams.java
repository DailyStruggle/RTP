package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Configurable parameters for the {@code Polygon} memory shape (ADR-034).
 *
 * <p>{@code Polygon.keys()} reports these names to the web editor's schema. The bounding-square
 * settings (radius, centre, centerRadius, expand) are not configurable: they are derived from the
 * vertices.</p>
 */
public enum PolygonMemoryShapeParams {
  /**
   * The mode for handling biome selection and bad-location avoidance.
   *
   * @see io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode
   */
  mode,

  /**
   * Admin-authored vertex list as {@code [x, z]} coordinate pairs in traversal order.
   */
  vertices,

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
