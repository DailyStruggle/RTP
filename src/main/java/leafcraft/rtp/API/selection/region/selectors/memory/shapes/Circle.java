package leafcraft.rtp.api.selection.region.selectors.memory.shapes;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import leafcraft.rtp.api.substitutions.RTPLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public final class Circle extends MemoryShape<GenericMemoryShapeParams> {
    private static final List<String> keys = Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
    private static final EnumMap<GenericMemoryShapeParams,Object> defaults = new EnumMap<>(GenericMemoryShapeParams.class);
    static {
        defaults.put(GenericMemoryShapeParams.WORLD, RTPAPI.getInstance().serverAccessor.getDefaultRTPWorld().name());
        defaults.put(GenericMemoryShapeParams.CR,64);
        defaults.put(GenericMemoryShapeParams.CX,0);
        defaults.put(GenericMemoryShapeParams.CZ,0);
        defaults.put(GenericMemoryShapeParams.EXPAND,false);
        defaults.put(GenericMemoryShapeParams.RADIUS,256);
        defaults.put(GenericMemoryShapeParams.WORLDBORDEROVERRIDE,false);
        defaults.put(GenericMemoryShapeParams.UNIQUE,false);
    }

    public Circle() {
        super(GenericMemoryShapeParams.class,"CIRCLE", defaults);
    }

    @Override
    public double getRange(long radius) {
        long cr = getNumber(GenericMemoryShapeParams.CR,64L).longValue();
        return (radius - cr) * (radius + cr) * Math.PI;
    }

    @Override
    public double xzToLocation(long x, long z) {
        long cr = getNumber(GenericMemoryShapeParams.CR,64L).longValue();
        long cx = getNumber(GenericMemoryShapeParams.CX,0L).longValue();
        long cz = getNumber(GenericMemoryShapeParams.CZ,0L).longValue();

        x = x - cx;
        z = z - cz;

        double rotation = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

        if ((z < 0) && (x < 0)) {
            rotation += 0.5;
        } else if (z < 0) {
            rotation += 0.75;
        } else if (x < 0) {
            rotation += 0.25;
        }

        double radius = ((long) (Math.sqrt(x * x + z * z)));
        return (radius * radius - cr * cr) * Math.PI + rotation * (2 * radius * Math.PI);
    }

    @Override
    public long[] locationToXZ(long location) {
        long cr = getNumber(GenericMemoryShapeParams.CR,64L).longValue();
        long cx = getNumber(GenericMemoryShapeParams.CX,0L).longValue();
        long cz = getNumber(GenericMemoryShapeParams.CZ,0L).longValue();

        long[] res = new long[2];

        //getFromString a distance from the center
        double radius = Math.sqrt(location / Math.PI + cr * cr);

        //getFromString a % around the curve, convert to radians
        double rotation = (radius - (int) radius + 0.000069) * 2 * Math.PI;
        //rotation = ((0.875)*2*Math.PI);

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (int) ((radius * cosRes) + cx + 0.5);
        res[1] = (int) ((radius * sinRes) + cz + 0.5);

        return res;
    }

    @Override
    public RTPLocation select(@Nullable Set<String> biomes) {
        return null;
    }

    @Override
    public Collection<String> keys() {
        return keys;
    }
}
