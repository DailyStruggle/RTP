package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
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

    RTPAPI.addShape(this);
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
      addBadLocation(res);
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
