package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.util.logging.Level;

/**
 * Utility for auditing region bounds against the world border and calculating chunk inscriptions.
 *
 * @governedBy ADR-115
 */
public final class WorldBorderAuditor {

  private WorldBorderAuditor() {}

  /**
   * Scale a block-unit radius down to a whole chunk radius such that
   * every block in every selectable chunk is strictly within the radius.
   *
   * <p>A chunk at offset C spans blocks [16*C, 16*C + 15].
   * For the positive boundary, {@code 16*C + 15 <= R => C <= (R - 15) / 16}.
   *
   * @param blockRadius radius in blocks (>= 0)
   * @return inscribed chunk radius
   */
  public static long inscribeBlockRadiusToChunks(double blockRadius) {
    if (blockRadius < 16.0) {
      return 0L;
    }
    return (long) Math.floor((blockRadius - 15.0) / 16.0);
  }

  /**
   * Auto-interprets dimensionless distance values in a shape's parameter map against the target world border.
   *
   * @param regionName name of the region
   * @param world target world
   * @param shape region shape
   */
  public static void autoInterpretShape(String regionName, RTPWorld<?> world, Shape<?> shape) {
    if (world == null || shape == null) {
      return;
    }

    Object wbObj = RTPRuntime.serverAccessor() != null ? RTPRuntime.serverAccessor().getWorldBorder(world.name()) : null;
    if (!(wbObj instanceof WorldBorder wb)) {
      return;
    }

    Shape<?> borderShape = wb.getShape() != null ? wb.getShape().get() : null;
    if (!isSquareShape(borderShape)) {
      return;
    }

    long borderRadChunks = getShapeLongParam(borderShape, "radius", 0L);
    if (borderRadChunks <= 0) {
      return;
    }
    double borderRadBlocks = borderRadChunks * 16.0;

    autoInterpretShapeParamsGeneric(regionName, shape, borderRadBlocks);
  }

  @SuppressWarnings("unchecked")
  private static <E extends Enum<E>> void autoInterpretShapeParamsGeneric(
      String regionName, Shape<E> shape, double borderRadBlocks) {
    autoInterpretShapeParam(regionName, shape, "radius", borderRadBlocks);
    autoInterpretShapeParam(regionName, shape, "centerRadius", borderRadBlocks);
    autoInterpretShapeParam(regionName, shape, "radius2", borderRadBlocks);
    autoInterpretShapeParam(regionName, shape, "centerRadius2", borderRadBlocks);
    autoInterpretShapeParam(regionName, shape, "width", borderRadBlocks);
    autoInterpretShapeParam(regionName, shape, "length", borderRadBlocks);
  }

  private static <E extends Enum<E>> void autoInterpretShapeParam(
      String regionName, Shape<E> shape, String paramName, double borderRadBlocks) {
    E key;
    try {
      key = Enum.valueOf(shape.myClass, paramName);
    } catch (IllegalArgumentException ignored) {
      return;
    }

    Object val = shape.getData(key);
    if (val == null) return;

    DistanceParser.ParsedDistance parsed;
    if (val instanceof String s) {
      parsed = DistanceParser.parse(s, SpatialUnit.CHUNK);
    } else if (val instanceof Number n) {
      parsed = new DistanceParser.ParsedDistance(n.doubleValue(), SpatialUnit.CHUNK, false);
    } else {
      return;
    }

    if (parsed == null || parsed.explicitUnit()) {
      return;
    }

    DistanceParser.ParsedDistance interpreted =
        DistanceParser.autoInterpret(parsed, borderRadBlocks, "region '" + regionName + "' " + paramName,
            message -> RTPRuntime.log(Level.INFO, message));

    if (interpreted != null && interpreted.unit() != SpatialUnit.CHUNK) {
      double chunks = interpreted.toChunks();
      Object coerced = (chunks == Math.floor(chunks)) ? (Object) Long.valueOf((long) chunks) : (Object) Double.valueOf(chunks);
      shape.set(key, coerced);
    }
  }

  /**
   * Check if a region's configured shape extends beyond the world border and emit a warning if so.
   *
   * @param regionName name of the region
   * @param world target world
   * @param shape region shape
   * @param worldBorderOverride whether world border override is enabled
   */
  public static boolean checkRegionWorldBorder(
      String regionName, RTPWorld<?> world, Shape<?> shape, boolean worldBorderOverride) {
    if (worldBorderOverride || world == null || shape == null) {
      return true;
    }

    Object wbObj = RTPRuntime.serverAccessor() != null ? RTPRuntime.serverAccessor().getWorldBorder(world.name()) : null;
    if (!(wbObj instanceof WorldBorder wb)) {
      return true;
    }

    Shape<?> borderShape = wb.getShape() != null ? wb.getShape().get() : null;
    if (!isSquareShape(borderShape)) {
      return true;
    }

    long borderRadChunks = getShapeLongParam(borderShape, "radius", 0L);
    long borderCxChunks = getShapeLongParam(borderShape, "centerX", 0L);
    long borderCzChunks = getShapeLongParam(borderShape, "centerZ", 0L);

    if (borderRadChunks <= 0) {
      return true;
    }

    long borderMinX = borderCxChunks - borderRadChunks;
    long borderMaxX = borderCxChunks + borderRadChunks;
    long borderMinZ = borderCzChunks - borderRadChunks;
    long borderMaxZ = borderCzChunks + borderRadChunks;

    if (!isRadialShape(shape)) {
      return true;
    }
    long shapeRad = getShapeLongParam(shape, "radius", 0L);
    long cx = getShapeLongParam(shape, "centerX", 0L);
    long cz = getShapeLongParam(shape, "centerZ", 0L);
    long shapeMinX = cx - shapeRad;
    long shapeMaxX = cx + shapeRad;
    long shapeMinZ = cz - shapeRad;
    long shapeMaxZ = cz + shapeRad;

    boolean overflows = (shapeMinX < borderMinX)
        || (shapeMaxX > borderMaxX)
        || (shapeMinZ < borderMinZ)
        || (shapeMaxZ > borderMaxZ);

    if (overflows) {
      long shapeRadiusBlocks = shapeRad * 16L;
      long borderRadiusBlocks = borderRadChunks * 16L;
      String msg = String.format(
          "[RTP] WARNING: Region '%s' in world '%s' has configured radius %d chunks (%d blocks), which exceeds the world border radius of %d chunks (%d blocks). Teleport selections outside the border will fail. Consider enabling 'worldBorderOverride: true' or reducing the radius.",
          regionName,
          world.name(),
          shapeRad,
          shapeRadiusBlocks,
          borderRadChunks,
          borderRadiusBlocks
      );
      RTPRuntime.log(Level.WARNING, msg);
      return false;
    }

    return true;
  }

  private static boolean isSquareShape(Shape<?> shape) {
    if (shape == null) return false;
    String n = shape.name != null ? shape.name : "";
    return "SQUARE".equalsIgnoreCase(n) || shape.getClass().getSimpleName().startsWith("Square");
  }

  private static boolean isRadialShape(Shape<?> shape) {
    if (shape == null) return false;
    if (isSquareShape(shape)) return true;
    String n = shape.name != null ? shape.name : "";
    return "CIRCLE".equalsIgnoreCase(n) || shape.getClass().getSimpleName().startsWith("Circle");
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static long getShapeLongParam(Shape<?> shape, String paramName, long def) {
    if (shape == null || shape.myClass == null) return def;
    try {
      Enum key = Enum.valueOf((Class) shape.myClass, paramName);
      return ((Shape) shape).getNumber(key, def).longValue();
    } catch (IllegalArgumentException ignored) {
      return def;
    }
  }
}
