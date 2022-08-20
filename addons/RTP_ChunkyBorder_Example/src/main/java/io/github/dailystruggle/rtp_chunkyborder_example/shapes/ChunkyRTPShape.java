package io.github.dailystruggle.rtp_chunkyborder_example.shapes;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.bukkit.Bukkit;

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
                new IllegalStateException("10000 worldborder checks failed. region is likely outside the worldborder").printStackTrace();
                return badLocations.firstEntry().getValue();
            }
        }
        return res;
    }
}
