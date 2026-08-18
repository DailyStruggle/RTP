package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;

/** Shape implementation that uses Chunky's shapes */
public class ChunkyRTPShape extends Rectangle {
  /** The name of the shape in Chunky */
  public final String chunkyShapeName;

  /**
   * Constructor for ChunkyRTPShape
   *
   * @param chunkyShapeName the name of the Chunky shape
   */
  public ChunkyRTPShape(String chunkyShapeName) {
    super(chunkyShapeName.toUpperCase());
    this.chunkyShapeName = "chunky_" + chunkyShapeName;

    RTP.addShape(this);
  }

  /**
   * Get a random location value within the shape
   *
   * @return the random location value
   */
  @Override
  public long rand() {
    Selection.Builder builder = Selection.builder(ChunkyProvider.get(), null);
    builder.centerX(getNumber(RectangleParams.centerX, 0).doubleValue());
    builder.centerZ(getNumber(RectangleParams.centerZ, 0).doubleValue());

    builder.radius(getNumber(RectangleParams.width, 256).doubleValue());
    builder.radiusX(getNumber(RectangleParams.width, 256).doubleValue());
    builder.radiusZ(getNumber(RectangleParams.height, 256).doubleValue());

    builder.shape(chunkyShapeName.replace("chunky_", ""));
    Shape shape = ShapeFactory.getShape(builder.build());

    long res = super.rand();
    int[] xz = locationToXZ(res);

    int i = 0;
    while (!shape.isBounding(xz[0], xz[1])) {
      // addBadChunk: chunk-uniform - Chunky's isBounding predicate is evaluated at the
      // chunk-unit coords from locationToXZ; membership in the Chunky-defined boundary is
      // a per-chunk property, so the twin spiral index decoding to the same chunk is
      // guaranteed to also be out-of-bounds.
      addBadChunk(res);
      res = super.rand();
      xz = locationToXZ(res);
      i++;
      if (i > 10000) {
        if (badPrefixSumsCache.length > 0) {
          return badPrefixSumsCache[0];
        }
        return -1;
      }
    }
    return res;
  }
}
