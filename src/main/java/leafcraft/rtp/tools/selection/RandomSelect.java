package leafcraft.rtp.tools.selection;

import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

public class RandomSelect {
    public static long[] select(RandomSelectParams params) {
        params.randPos();
        if(params.isBig) {
            switch (params.shape) {
                case SQUARE:
                    return squareLocationToXZ(params.cr, params.cx, params.cz, params.positionBig);
                default:
                    return circleLocationToXZ(params.cr, params.cx, params.cz, params.positionBig);
            }
        }
        else {
            switch (params.shape) {
                case SQUARE:
                    return squareLocationToXZ(params.cr, params.cx, params.cz, params.positionNative);
                default:
                    return circleLocationToXZ(params.cr, params.cx, params.cz, params.positionNative);
            }
        }
    }

    public static long[] circleLocationToXZ(long cr, long cx, long cz, long location) {
        long[] res = new long[2];

        //get a distance from the center
        double radius = Math.sqrt(location/Math.PI + cr*cr);

        //get a % around the curve, convert to radians
        double rotation = (radius - (long)radius)*2*Math.PI;

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (long)(radius * cosRes)+cx;
        res[1] = (long)(radius * sinRes)+cz;

        return res;
    }

    public static long[] circleLocationToXZ(long cr, long cx, long cz, BigDecimal location) {
        long[] res = new long[2];

        BigDecimal crSqr = BigDecimal.valueOf(cr).pow(2);

        //get a distance from the center

        double radius = location.divide(RandomSelectParams.bigPi,RandomSelectParams.mc).add(crSqr,RandomSelectParams.mc).sqrt(RandomSelectParams.mc).doubleValue();

        //get a % around the curve, convert to radians
        double rotation = (radius - (long)radius)*2*Math.PI;

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (long)(radius * cosRes)+cx;
        res[1] = (long)(radius * sinRes)+cz;

        return res;
    }

    public static long[] squareLocationToXZ(long cr, long cx, long cz, long location) {
        long[] res;
        //get a distance from the center
        Double radius = Math.sqrt((location + cr*cr)/4);

        //get how far to step around the square
        Double perimeterStep = 8*(radius*(radius-radius.longValue()));

        res = squareOct2Coords(radius,perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    public static long[] squareLocationToXZ(long cr, long cx, long cz, BigDecimal location) {
        long[] res;

        BigDecimal four = BigDecimal.valueOf(4);
        BigDecimal crSqr = BigDecimal.valueOf(cr).pow(2).multiply(four);

        //get a distance from the center
        Double radius = location.add(crSqr,RandomSelectParams.mc).divide(four).sqrt(RandomSelectParams.mc).doubleValue();

        //get how far to step around the square
        Double perimeterStep = 8*(radius*(radius-radius));

        res = squareOct2Coords(radius,perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    private static long[] squareOct2Coords(Double radius, Double perimeterStep) {
        long[] res = new long[2];
        //get how far to go from a corner
        Double shortStep = perimeterStep%radius;

        if(perimeterStep<radius*4) {
            if(perimeterStep<radius*2) {
                if(perimeterStep<radius) {      //octant 1, from 0 to pi/4
                    res[0] = radius.longValue();
                    res[1] = shortStep.longValue();
                }
                else {                          //octant 2, from pi/4 to pi/2
                    res[0] = (long)(radius - shortStep);
                    res[1] = radius.longValue();
                }
            }
            else {
                if(perimeterStep<radius*3) {    //octant 3
                    res[0] = -shortStep.longValue();
                    res[1] = radius.longValue();
                }
                else {                          //octant 4
                    res[0] = -radius.longValue();
                    res[1] = (long)(radius - shortStep);
                }
            }
        }
        else {
            if(perimeterStep<radius*6) {
                if(perimeterStep<radius*5) { //octant 5
                    res[0] = -radius.longValue();
                    res[1] = -shortStep.longValue();
                }
                else {                          //octant 6
                    res[0] = -(long)(radius - shortStep);
                    res[1] = -radius.longValue();
                }
            }
            else {
                if(perimeterStep<radius*7) { //octant 7
                    res[0] = shortStep.longValue();
                    res[1] = -radius.longValue();
                }
                else {                          //octant 8
                    res[0] = radius.longValue();
                    res[1] = -(long)(radius-shortStep);
                }
            }
        }
        return res;
    }
}
