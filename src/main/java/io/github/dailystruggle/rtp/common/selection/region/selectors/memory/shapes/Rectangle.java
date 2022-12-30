package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.*;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Rectangle extends MemoryShape<RectangleParams> {
    protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
    protected static final List<String> keys = Arrays.stream(RectangleParams.values()).map(Enum::name).collect(Collectors.toList());
    protected static final EnumMap<RectangleParams,Object> defaults = new EnumMap<>(RectangleParams.class);
    static {
        defaults.put(RectangleParams.mode, Mode.ACCUMULATE);
        defaults.put(RectangleParams.width,256);
        defaults.put(RectangleParams.height,256);
        defaults.put(RectangleParams.centerX,0);
        defaults.put(RectangleParams.centerZ,0);
        defaults.put(RectangleParams.rotation,0);
        defaults.put(RectangleParams.uniquePlacements,false);

        subParameters.put("mode",new EnumParameter<>("rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
        subParameters.put("width",new IntegerParameter("rtp.params", "outer radius of region", (sender, s) -> true, 64,128,256,512,1024));
        subParameters.put("height",new IntegerParameter("rtp.params", "outer radius of region", (sender, s) -> true, 64,128,256,512,1024));
        subParameters.put("rotation",new IntegerParameter("rtp.params", "outer radius of region", (sender, s) -> true, 0,30,45,60,90));
        subParameters.put("centerx",new CoordinateParameter("rtp.params", "center point x", (sender, s) -> true));
        subParameters.put("centerz",new CoordinateParameter("rtp.params", "center point Z", (sender, s) -> true));
        subParameters.put("uniquePlacements",new BooleanParameter("rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
    }

    public Rectangle() {
        super(RectangleParams.class,"RECTANGLE",defaults);
    }

    public Rectangle(String newName) {
        super(RectangleParams.class,newName,defaults);
    }

    @Override
    public double getRange() {
        long w = getNumber(RectangleParams.width, 256L).longValue();
        long h = getNumber(RectangleParams.height,256L).longValue();
        return w*h;
    }

    @Override
    public double xzToLocation(long x, long z) {
        long degrees = getNumber(RectangleParams.rotation,0L).longValue();
        long cx = getNumber(RectangleParams.centerX,0L).longValue();
        long cz = getNumber(RectangleParams.centerZ,0L).longValue();
        long width = getNumber(RectangleParams.width,256L).longValue();

        // shift point back to origin:
        x -= cx;
        z -= cz;

        int[] input = new int[]{(int) x, (int) z};

        input = rotate(input,-degrees);

        //translate to position
        return input[1] * width + input[0];
    }

    @Override
    public int[] locationToXZ(long location) {
        long degrees = getNumber(RectangleParams.rotation,0L).longValue();
        long cx = getNumber(RectangleParams.centerX,0L).longValue();
        long cz = getNumber(RectangleParams.centerZ,0L).longValue();
        long width = getNumber(RectangleParams.width,256L).longValue();

        int[] res = new int[2];

        //compute initial xz
        res[0] = (int) (location % width);
        res[1] = (int) (location / width);

        //rotate around origin
        res = rotate(res, degrees);

        //shift
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    @Override
    public int[] select() {
        long location = rand();
        return locationToXZ(location);
    }

    @Override
    public long rand() {
        String mode = data.getOrDefault(RectangleParams.mode,"ACCUMULATE").toString();

        double range = getRange();

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

        Object unique = data.getOrDefault(RectangleParams.uniquePlacements,false);
        boolean u;
        if(unique instanceof Boolean) u = (Boolean) unique;
        else {
            u = Boolean.parseBoolean(String.valueOf(unique));
            data.put(RectangleParams.uniquePlacements,u);
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
