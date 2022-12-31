package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;

public class ChunkyRTPShape extends Circle {
    private final org.popcraft.chunky.shape.Shape chunkyShape;
    public ChunkyRTPShape(org.popcraft.chunky.shape.Shape chunkyShape) {
        super(chunkyShape.name().toUpperCase());
        this.chunkyShape = chunkyShape;

        RTPAPI.addShape(this);
    }

    @Override
    public long rand() {
        long res = super.rand();
        int[] xz = locationToXZ(res);

        int i = 0;
        while(!chunkyShape.isBounding(xz[0],xz[1])) {
            addBadLocation(res);
            res = super.rand();
            xz = locationToXZ(res);
            i++;
            if(i>10000) {
                return badLocations.firstEntry().getValue();
            }
        }
        return res;
    }
}
