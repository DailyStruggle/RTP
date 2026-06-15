package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums;

/**
 * Configurable parameters for the {@link
 * io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Ellipse}
 * shape.
 *
 * <p>Mirrors {@link GenericMemoryShapeParams} and adds the second semi-axis
 * {@link #radius2}. Hosted on a dedicated enum so {@code Circle}, {@code Square}
 * and other single-radius shapes do not need to carry a meaningless {@code
 * radius2} default just to satisfy the {@code Shape} constructor's "all enum
 * constants present" invariant.
 */
public enum EllipseMemoryShapeParams {
  /** Selection mode. @see GenericMemoryShapeParams#mode */
  mode,

  /** The first axis radius. */
  radius,

  /**
   * The second axis radius. The wider of {@code radius} and {@code radius2}
   * sets the bounding circle used by the inherited spiral mapping.
   */
  radius2,

  /** Inner exclusion radius. @see GenericMemoryShapeParams#centerRadius */
  centerRadius,

  /**
   * The second axis of the elliptical inner exclusion region. The wider of
   * {@code centerRadius} and {@code centerRadius2} sets the bounding inner
   * circle used by the spiral 1D mapping; {@link
   * io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Ellipse#contains(int, int)}
   * additionally rejects points inside the true rotated inner ellipse.
   */
  centerRadius2,

  /**
   * Rotation of both the inner and outer ellipse in degrees, applied around
   * ({@code centerX}, {@code centerZ}). Mirrors {@code RectangleParams.rotation}.
   */
  rotation,

  /** Center X coordinate. @see GenericMemoryShapeParams#centerX */
  centerX,

  /** Center Z coordinate. @see GenericMemoryShapeParams#centerZ */
  centerZ,

  /** Region selection weight. @see GenericMemoryShapeParams#weight */
  weight,

  /** Unique placements flag. @see GenericMemoryShapeParams#uniquePlacements */
  uniquePlacements,

  /** Expand flag. @see GenericMemoryShapeParams#expand */
  expand
}
