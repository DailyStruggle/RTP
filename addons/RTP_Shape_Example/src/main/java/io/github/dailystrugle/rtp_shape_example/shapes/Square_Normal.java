package io.github.dailystrugle.rtp_shape_example.shapes;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystrugle.rtp_shape_example.shapes.enums.NormalDistributionParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Square_Normal extends MemoryShape<NormalDistributionParams> {
    protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
    protected static final List<String> keys = Arrays.stream(NormalDistributionParams.values()).map(Enum::name).collect(Collectors.toList());
    protected static final EnumMap<NormalDistributionParams,Object> defaults = new EnumMap<>(NormalDistributionParams.class);
    static {
        defaults.put(NormalDistributionParams.mode,"REROLL");
        defaults.put(NormalDistributionParams.radius,256);
        defaults.put(NormalDistributionParams.centerRadius,64);
        defaults.put(NormalDistributionParams.centerX,0);
        defaults.put(NormalDistributionParams.centerZ,0);
        defaults.put(NormalDistributionParams.mean,0.5);
        defaults.put(NormalDistributionParams.deviation,1.0);
        defaults.put(NormalDistributionParams.expand,false);
        defaults.put(NormalDistributionParams.uniquePlacements,false);

        subParameters.put("mode",new EnumParameter<>("rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
        subParameters.put("radius",new IntegerParameter("rtp.params", "outer radius of region", (sender, s) -> true, 64,128,256,512,1024));
        subParameters.put("centerRadius",new IntegerParameter("rtp.params", "inner radius of region", (sender, s) -> true,16,32,64,128,256));
        subParameters.put("centerX",new CoordinateParameter("rtp.params", "center point x", (sender, s) -> true));
        subParameters.put("centerZ",new CoordinateParameter("rtp.params", "center point Z", (sender, s) -> true));
        subParameters.put("mean",new FloatParameter("rtp.params", "relative selection range between inner and outer radius, 0-1", (sender, s) -> true,0.0, 0.5, 1.0));
        subParameters.put("deviation",new FloatParameter("rtp.params", "standard deviation", (sender, s) -> true,0.1,1.0,10.0));
        subParameters.put("expand",new BooleanParameter("rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
        subParameters.put("uniquePlacements",new BooleanParameter("rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
    }

    public Square_Normal() {
        super(NormalDistributionParams.class,"SQUARE_NORMAL",defaults);
    }

    public Square_Normal(String newName) {
        super(NormalDistributionParams.class,newName,defaults);
    }

    @Override
    public double getRange() {
        long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
        long cr = getNumber(NormalDistributionParams.centerRadius,64L).longValue();
        return (radius-cr)*(radius+cr)*4;
    }

    @Override
    public double xzToLocation(long x, long z) {
        long cr = getNumber(NormalDistributionParams.centerRadius,64L).longValue();
        long cx = getNumber(NormalDistributionParams.centerX,0L).longValue();
        long cz = getNumber(NormalDistributionParams.centerZ,0L).longValue();

        x = x - cx;
        z = z - cz;

        double theta = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

        if ((z < 0) && (x < 0)) {
            theta += 0.5;
        } else if (z < 0) {
            theta += 0.75;
        } else if (x < 0) {
            theta += 0.25;
        }

        double radius;
        long ax = Math.abs(x);
        long az = Math.abs(z);
        radius = Math.max(ax, az);

        int perimeterStep = 0;
        if (theta < 0.5) {
            if (theta < 0.25) {
                if (theta < 0.125) {   //octant 1, from 0 to pi/4
                    perimeterStep += az;
                } else {              //octant 2, from pi/4 to pi/2
                    perimeterStep += radius;
                    perimeterStep += (radius - ax);
                }
            } else {
                if (theta < 0.375) {   //octant 3
                    perimeterStep += radius * 2;
                    perimeterStep += ax; //x is negative in this quadrant, so fix
                } else {              //octant 4
                    perimeterStep += radius * 3;
                    perimeterStep += (radius - az);
                }
            }
        } else {
            if (theta < 0.75) {
                if (theta < 0.625) {   //octant 5
                    perimeterStep += radius * 4;
                    perimeterStep += az;
                } else {              //octant 6
                    perimeterStep += radius * 5;
                    perimeterStep += (radius - ax);
                }
            } else {
                if (theta < 0.875) {   //octant 7
                    perimeterStep += radius * 6;
                    perimeterStep += ax;
                } else {              //octant 8
                    perimeterStep += radius * 7;
                    perimeterStep += (radius - (az));
                }
            }
        }

        return ((radius * radius - cr * cr) * 4) + perimeterStep;
    }

    @Override
    public int[] locationToXZ(long location) {
        long cr = getNumber(NormalDistributionParams.centerRadius,64L).longValue();
        long cx = getNumber(NormalDistributionParams.centerX,0L).longValue();
        long cz = getNumber(NormalDistributionParams.centerZ,0L).longValue();

        int[] res;
        //getFromString a distance from the center
        double radius = Math.sqrt(location + cr * cr * 4) / 2;

        //getFromString how far to step around the square
        double theta = radius - (int) radius;
        Double perimeterStep = 8 * (radius * (theta));

        radius = (int) radius;

        res = squareOct2Coords(radius, perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    private static int[] squareOct2Coords(Double radius, Double perimeterStep) {
        long[] res = new long[2];
        //getFromString how far to go from a corner
        radius = radius + 0.5;
        Double shortStep = (perimeterStep % radius) + 0.5;

        if (perimeterStep < radius * 4) {
            if (perimeterStep < radius * 2) {
                if (perimeterStep < radius) {      //octant 1, from 0 to pi/4
                    res[0] = radius.intValue();
                    res[1] = shortStep.intValue();
                } else {                          //octant 2, from pi/4 to pi/2
                    res[0] = (int) (radius - shortStep);
                    res[1] = radius.intValue();
                }
            } else {
                if (perimeterStep < radius * 3) {    //octant 3
                    res[0] = -shortStep.intValue();
                    res[1] = radius.intValue();
                } else {                          //octant 4
                    res[0] = -radius.intValue();
                    res[1] = (int) (radius - shortStep);
                }
            }
        } else {
            if (perimeterStep < radius * 6) {
                if (perimeterStep < radius * 5) { //octant 5
                    res[0] = -radius.intValue();
                    res[1] = -shortStep.intValue();
                } else {                          //octant 6
                    res[0] = -(int) (radius - shortStep);
                    res[1] = -radius.intValue();
                }
            } else {
                if (perimeterStep < radius * 7) { //octant 7
                    res[0] = shortStep.intValue();
                    res[1] = -radius.intValue();
                } else {                          //octant 8
                    res[0] = radius.intValue();
                    res[1] = -(int) ((radius - shortStep));
                }
            }
        }
        return new int[]{(int)res[0], (int)res[1]};
    }

    @Override
    public int[] select() {
        long location = rand();

        return locationToXZ(location);
    }

    @Override
    public long rand() {
        long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
        long cr = getNumber(NormalDistributionParams.centerRadius,64L).longValue();
        double mean = getNumber(NormalDistributionParams.mean,0.5).doubleValue();
        double deviation = getNumber(NormalDistributionParams.deviation,1.0).doubleValue();
        double range = (radius-cr)*(radius+cr)*4;

        boolean expand = Boolean.parseBoolean(data.getOrDefault(NormalDistributionParams.expand,false).toString());
        if(!expand) range -= badLocationSum.get();

        mean = Math.abs(mean)%1.0; //ensure mean 0.0-1.0
        deviation = Math.abs(deviation); //ensure deviation>0

//        double center = (radius-cr)*mean+cr; //relative point between these two radii
//        center = (center-cr)*(center+cr)*4; //convert to range at this point

//        double upper = range-center; //distance from center to max
//        double lower = center; //distance from center to min

        //get a valid number between 0 and 1
        // apply corrective deviation to get , apply requested deviation, shift over to mean
        //todo: find a way to approximate this without rejection sampling
        double gaussian;
        do {
            //approximately -4 to 4
            gaussian = ThreadLocalRandom.current().nextGaussian();

            //correct to -0.5 to 0.5
            gaussian = gaussian/8;

            //apply requested deviation
            gaussian = gaussian*deviation;

            //shift over to requested mean
            gaussian = gaussian+mean;

            if(mean<0.05 && gaussian<0) gaussian = -gaussian;
            else if(mean>0.95 && gaussian>1) gaussian = 1-gaussian;
        }
        while (gaussian<0 || gaussian>1); //reject values outside distribution

        //an approximation of the necessary exponent for 1d to 2d mapping
        // 0.5-1.0 depending on cr, so it shouldn't escape bounds
        double exponent = (1+((double) cr)/((double) radius))*0.5;
        gaussian = Math.pow(gaussian,1.0/exponent);

        //expand to fit
        double res = (range) * (gaussian);

        long location = (long) res;

        String mode = data.getOrDefault(NormalDistributionParams.mode,"ACCUMULATE").toString().toUpperCase();
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

        Object unique = data.getOrDefault(NormalDistributionParams.uniquePlacements,false);
        boolean u;
        if(unique instanceof Boolean) u = (Boolean) unique;
        else {
            u = Boolean.parseBoolean(String.valueOf(unique));
            data.put(NormalDistributionParams.uniquePlacements,u);
        }
        if(u) addBadLocation(location);

        return location;
    }

    @Override
    public Map<String, CommandParameter> getParameters() {
        return subParameters;
    }

    @Override
    public Collection<String> keys() {
        return keys;
    }
}
