package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Circle extends MemoryShape<GenericMemoryShapeParams> {
    protected static final EnumMap<GenericMemoryShapeParams,Object> defaults = new EnumMap<>(GenericMemoryShapeParams.class);
    protected static final Map<String,CommandParameter> subParameters = new ConcurrentHashMap<>();
    protected static final List<String> keys = Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
    static {
        try {
            defaults.put(GenericMemoryShapeParams.mode, Mode.ACCUMULATE);
            defaults.put(GenericMemoryShapeParams.radius,256);
            defaults.put(GenericMemoryShapeParams.centerRadius,64);
            defaults.put(GenericMemoryShapeParams.centerX,0);
            defaults.put(GenericMemoryShapeParams.centerZ,0);
            defaults.put(GenericMemoryShapeParams.weight,1.0);
            defaults.put(GenericMemoryShapeParams.uniquePlacements,false);
            defaults.put(GenericMemoryShapeParams.expand,false);

            subParameters.put("mode",new EnumParameter<>("rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
            subParameters.put("radius",new IntegerParameter("rtp.params", "outer radius of region", (sender, s) -> true, 64,128,256,512,1024));
            subParameters.put("centerradius",new IntegerParameter("rtp.params", "inner radius of region", (sender, s) -> true,16,32,64,128,256));
            subParameters.put("centerx",new CoordinateParameter("rtp.params", "center point x", (sender, s) -> true));
            subParameters.put("centerz",new CoordinateParameter("rtp.params", "center point Z", (sender, s) -> true));
            subParameters.put("weight",new FloatParameter("rtp.params", "weigh towards or away from center", (sender, s) -> true,0.1,1.0,10.0));
            subParameters.put("expand",new BooleanParameter("rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
            subParameters.put("uniquePlacements",new BooleanParameter("rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Circle() throws IllegalArgumentException {
        super(GenericMemoryShapeParams.class,"CIRCLE", defaults);
    }

    public Circle(String newName) throws IllegalArgumentException {
        super(GenericMemoryShapeParams.class,newName, defaults);
    }

    @Override
    public double getRange() {
        long radius = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
        long cr = getNumber(GenericMemoryShapeParams.centerRadius,64L).longValue();
        return (radius - cr) * (radius + cr) * Math.PI;
    }

    @Override
    public double xzToLocation(long x, long z) {
        long cr = getNumber(GenericMemoryShapeParams.centerRadius,64L).longValue();
        long cx = getNumber(GenericMemoryShapeParams.centerX,0L).longValue();
        long cz = getNumber(GenericMemoryShapeParams.centerZ,0L).longValue();

        x = x-cx;
        z = z-cz;

        double rotation = ((Math.atan(((double)z)/x)/(2*Math.PI))+1) % 0.25;

        if((z<0) && (x<0)) {
            rotation += 0.5;
        }
        else if(z<0) {
            rotation += 0.75;
        }
        else if(x<0) {
            rotation += 0.25;
        }

        double radius = ((long)(Math.sqrt(x*x+z*z)));

        return (radius * radius - cr * cr) * Math.PI + rotation * (2 * radius * Math.PI);
    }

    @Override
    public int[] locationToXZ(long location) {
        long cr = getNumber(GenericMemoryShapeParams.centerRadius,64L).longValue();
        long cx = getNumber(GenericMemoryShapeParams.centerX,0L).longValue();
        long cz = getNumber(GenericMemoryShapeParams.centerZ,0L).longValue();

        int[] res = new int[2];

        //get a distance from the center
        double radius = Math.sqrt(location/Math.PI + cr*cr);

        //get a % around the curve, convert to radians
        double rotation = (radius - (int)radius + 0.000069)*2*Math.PI;
        //rotation = ((0.875)*2*Math.PI);

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (int)((radius * cosRes)+cx+0.5);
        res[1] = (int)((radius * sinRes)+cz+0.5);

        return res;
    }

    @Override
    public Map<String, CommandParameter> getParameters() {
        return subParameters;
    }

    @Override
    public Collection<String> keys() {
        return keys;
    }

    @Override
    public int[] select() {
        return locationToXZ(rand());
    }

    @Override
    public long rand() {
        double range = getRange();
        boolean expand = (boolean) data.getOrDefault(GenericMemoryShapeParams.expand,false);
        String mode = data.getOrDefault(GenericMemoryShapeParams.mode,"ACCUMULATE").toString().toUpperCase();

        if((!expand) && mode.equalsIgnoreCase("ACCUMULATE")) range -= badLocationSum.get();
        else if(expand && !mode.equals("ACCUMULATE")) range += badLocationSum.get();

        double res = (range) * (ThreadLocalRandom.current().nextDouble());

        long location = (long) res;
        switch (mode) {
            case "ACCUMULATE": {
                Map.Entry<Long, Long> idx = badLocations.firstEntry();
                while ((idx != null) && (location >= idx.getKey() || isKnownBad(location))) {
                    location += idx.getValue();
                    idx = badLocations.ceilingEntry(idx.getKey() + idx.getValue());
                }
            }
            case "NEAREST": {
                ConcurrentSkipListMap<Long,Long> map = badLocations;
                Map.Entry<Long, Long> check = map.floorEntry(location);

                if(     (check!=null)
                        && (location >= check.getKey())
                        && (location < (check.getKey()+check.getValue()))) {
                    Map.Entry<Long, Long> lower = map.floorEntry(check.getKey()-1);
                    Map.Entry<Long, Long> upper = map.ceilingEntry(check.getKey()+check.getValue());

                    if(upper == null) {
                        if(lower == null) {
                            long cutout = check.getValue();
                            location = ThreadLocalRandom.current().nextLong((long) (range - cutout));
                            if (location >= check.getKey()) location += check.getValue();
                        }
                        else {
                            long len = check.getKey() - (lower.getKey()+lower.getValue());
                            location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                            location += lower.getKey() + lower.getValue();
                        }
                    }
                    else if(lower == null) {
                        long len = upper.getKey() - (check.getKey()+check.getValue());
                        location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                        location += check.getKey() + check.getValue();
                    }
                    else {
                        long d1 = (upper.getKey()-location);
                        long d2 = location - (lower.getKey()+lower.getValue());
                        if(d2>d1) {
                            long len = check.getKey() - (lower.getKey()+lower.getValue());
                            location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                            location += lower.getKey() + lower.getValue();
                        }
                        else {
                            long len = upper.getKey() - (check.getKey()+check.getValue());
                            location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                            location += check.getKey() + check.getValue();
                        }
                    }
                }
            }
            case "REROLL": {
                Map.Entry<Long, Long> check = badLocations.floorEntry(location);
                if(     (check!=null)
                        && (location > check.getKey())
                        && (location < check.getKey()+check.getValue())) {
                    return -1;
                }
            }
            default: {

            }
        }

        Object unique = data.getOrDefault(GenericMemoryShapeParams.uniquePlacements,false);
        boolean u;
        if(unique instanceof Boolean) u = (Boolean) unique;
        else {
            u = Boolean.parseBoolean(String.valueOf(unique));
            data.put(GenericMemoryShapeParams.uniquePlacements,u);
        }
        if(u) addBadLocation(location);

        return location;
    }
}
