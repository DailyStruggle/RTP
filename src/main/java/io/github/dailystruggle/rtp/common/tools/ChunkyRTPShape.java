package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Rectangle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import org.popcraft.chunky.ChunkyProvider;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;

public class ChunkyRTPShape extends Rectangle {
    public final String chunkyShapeName;

    public ChunkyRTPShape(String chunkyShapeName) {
        super(chunkyShapeName.toUpperCase());
        this.chunkyShapeName = "chunky_" + chunkyShapeName;

        RTPAPI.addShape(this);
    }

    @Override
    public long rand() {
        Selection.Builder builder = Selection.builder(ChunkyProvider.get(), null);
        builder.centerX(getNumber(RectangleParams.centerX, 0).doubleValue());
        builder.centerZ(getNumber(RectangleParams.centerZ, 0).doubleValue());

        builder.radius(getNumber(RectangleParams.width, 256).doubleValue());
        builder.radiusX(getNumber(RectangleParams.width, 256).doubleValue());
        builder.radiusZ(getNumber(RectangleParams.height, 256).doubleValue());

        builder.shape(chunkyShapeName.replace("chunky_",""));
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
                return badLocations.firstEntry().getValue();
            }
        }
        return res;
    }
}
