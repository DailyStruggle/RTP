package leafcraft.rtp.api.selection.region.selectors.shapes;

import leafcraft.rtp.api.selection.region.selectors.shapes.enums.GenericShapeParams;
import leafcraft.rtp.api.selection.region.selectors.shapes.enums.RectangleParams;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class Rectangle extends Shape<RectangleParams> {
    public Rectangle(EnumMap<RectangleParams,Object> data) {
        super(RectangleParams.class,"Rectangle", data);
    }

//    @Override
//    public RTPChunk select() {
//        long w = (long) data.get(RectangleParams.WIDTH);
//        long h = (long) data.get(RectangleParams.HEIGHT);
//        long cx = (long) data.get(RectangleParams.CX);
//        long cz = (long) data.get(RectangleParams.CZ);
//
//        long sel = ThreadLocalRandom.current().nextLong(w*h);
//        long x = sel%h + cx, z = sel/h + cz;
//
//        return new RTPLocation(null,x,0,z);
//    }

    @Override
    public RTPChunk select(@Nullable Set<String> biomes) {
        return null;
    }
}
