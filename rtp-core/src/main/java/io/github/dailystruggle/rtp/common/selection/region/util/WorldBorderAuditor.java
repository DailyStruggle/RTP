package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.util.logging.Level;

/**
 * Utility for auditing region bounds against the world border and calculating chunk inscriptions.
 */
public final class WorldBorderAuditor {

  private WorldBorderAuditor() {}

  /**
   * Scale a block-unit radius down to a whole chunk radius such that
   * every block in every selectable chunk is strictly within the radius.
   *
   * <p>A chunk at offset C spans blocks [16*C, 16*C + 15].
   * For the positive boundary, 16*C + 15 <= R => C <= (R - 15) / 16.
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

    Object wbObj = RTP.serverAccessor != null ? RTP.serverAccessor.getWorldBorder(world.name()) : null;
    if (!(wbObj instanceof WorldBorder wb)) {
      return;
    }

    Shape<?> borderShape = wb.getShape() != null ? wb.getShape().get() : null;
    if (!(borderShape instanceof Square borderSquare)) {
      return;
    }

    long borderRadChunks = borderSquare.getNumber(GenericMemoryShapeParams.radius, 0L).longValue();
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
        DistanceParser.autoInterpret(parsed, borderRadBlocks, "region '" + regionName + "' " + paramName);

    if (interpreted != null && interpreted.unit() != SpatialUnit.CHUNK) {
      double chunks = interpreted.toChunks();
      Object coerced = (chunks == (long) chunks) ? (Long) (long) chunks : (Double) chunks;
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

    Object wbObj = RTP.serverAccessor != null ? RTP.serverAccessor.getWorldBorder(world.name()) : null;
    if (!(wbObj instanceof WorldBorder wb)) {
      return true;
    }

    Shape<?> borderShape = wb.getShape() != null ? wb.getShape().get() : null;
    if (!(borderShape instanceof Square borderSquare)) {
      return true;
    }

    long borderRadChunks = borderSquare.getNumber(GenericMemoryShapeParams.radius, 0L).longValue();
    long borderCxChunks = borderSquare.getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long borderCzChunks = borderSquare.getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    if (borderRadChunks <= 0) {
      return true;
    }

    long borderMinX = borderCxChunks - borderRadChunks;
    long borderMaxX = borderCxChunks + borderRadChunks;
    long borderMinZ = borderCzChunks - borderRadChunks;
    long borderMaxZ = borderCzChunks + borderRadChunks;

    long shapeMinX;
    long shapeMaxX;
    long shapeMinZ;
    long shapeMaxZ;
    long shapeRad;

    if (shape instanceof Square sq) {
      shapeRad = sq.getNumber(GenericMemoryShapeParams.radius, 0L).longValue();
      long cx = sq.getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
      long cz = sq.getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
      shapeMinX = cx - shapeRad;
      shapeMaxX = cx + shapeRad;
      shapeMinZ = cz - shapeRad;
      shapeMaxZ = cz + shapeRad;
    } else if (shape instanceof Circle cir) {
      shapeRad = cir.getNumber(GenericMemoryShapeParams.radius, 0L).longValue();
      long cx = cir.getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
      long cz = cir.getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
      shapeMinX = cx - shapeRad;
      shapeMaxX = cx + shapeRad;
      shapeMinZ = cz - shapeRad;
      shapeMaxZ = cz + shapeRad;
    } else {
      return true;
    }

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
      RTP.log(Level.WARNING, msg);
      return false;
    }

    return true;
  }
}
