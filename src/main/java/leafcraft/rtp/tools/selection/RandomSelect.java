package leafcraft.rtp.tools.selection;

import java.math.BigDecimal;

public class RandomSelect {
    public static int[] select(RandomSelectParams params) {
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

    public static int[] circleLocationToXZ(long cr, long cx, long cz, long location) {
        int[] res = new int[2];

        //get a distance from the center
        double radius = Math.sqrt(location/Math.PI + cr*cr);

        //get a % around the curve, convert to radians
        double rotation = (radius - (int)radius)*2*Math.PI;

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (int)((radius * cosRes)+cx+0.5);
        res[1] = (int)((radius * sinRes)+cz+0.5);

        return res;
    }

    public static int[] circleLocationToXZ(long cr, long cx, long cz, BigDecimal location) {
        int[] res = new int[2];

        BigDecimal crSqr = BigDecimal.valueOf(cr).pow(2);

        //get a distance from the center

        double radius = location.divide(RandomSelectParams.bigPi,RandomSelectParams.mc).add(crSqr,RandomSelectParams.mc).sqrt(RandomSelectParams.mc).doubleValue();

        //get a % around the curve, convert to radians
        double rotation = (radius - (int)radius)*2*Math.PI;

        double cosRes = Math.cos(rotation);
        double sinRes = Math.sin(rotation);

        //polar to cartesian
        res[0] = (int)((radius * cosRes)+cx);
        res[1] = (int)((radius * sinRes)+cz);

        return res;
    }

    public static long xzToCircleLocation(long x, long z, int cx, int cz) {
        long res = 0;

        x = x-cx;
        z = z-cz;

        double rotation = (Math.atan(((double)z)/x)/(2*Math.PI)) % 0.25;

        if((z<0) && (x<0)) {
            rotation += 0.5;
        }
        else if(z<0) {
            rotation += 0.75;
        }
        else if(x<0) {
            rotation += 0.25;
        }

        double radius = ((long)(Math.sqrt(x*x+z*z)+0.5))+rotation;

        return (long)(radius*radius*Math.PI+0.5);
    }

    public static int[] squareLocationToXZ(long cr, long cx, long cz, long location) {
        int[] res;
        //get a distance from the center
        Double radius = Math.sqrt(location + cr*cr)/2;

        //get how far to step around the square
        Double perimeterStep = 8*(radius*(radius-radius.intValue()));
        System.out.println("location = " + location);
        System.out.println("radius = " + radius);
        System.out.println("perimeterStep = " + perimeterStep);

        res = squareOct2Coords(radius,perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    public static int[] squareLocationToXZ(long cr, long cx, long cz, BigDecimal location) {
        int[] res;

        BigDecimal four = BigDecimal.valueOf(4);
        BigDecimal crSqr = BigDecimal.valueOf(cr).pow(2).multiply(four);

        //get a distance from the center
        Double radius = location.add(crSqr,RandomSelectParams.mc).divide(four).sqrt(RandomSelectParams.mc).doubleValue();

        //get how far to step around the square
        Double perimeterStep = 8*(radius*(radius-radius.intValue()));

        res = squareOct2Coords(radius,perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    private static int[] squareOct2Coords(Double radius, Double perimeterStep) {
        int[] res = new int[2];
        //get how far to go from a corner
        Double shortStep = (perimeterStep%radius) + 0.5;

        if(perimeterStep<radius*4) {
            if(perimeterStep<radius*2) {
                if(perimeterStep<radius) {      //octant 1, from 0 to pi/4
                    res[0] = radius.intValue();
                    res[1] = shortStep.intValue();
                }
                else {                          //octant 2, from pi/4 to pi/2
                    res[0] = (int)(radius - shortStep);
                    res[1] = radius.intValue();
                }
            }
            else {
                if(perimeterStep<radius*3) {    //octant 3
                    res[0] = -shortStep.intValue();
                    res[1] = radius.intValue();
                }
                else {                          //octant 4
                    res[0] = -radius.intValue();
                    res[1] = (int)(radius - shortStep);
                }
            }
        }
        else {
            if(perimeterStep<radius*6) {
                if(perimeterStep<radius*5) { //octant 5
                    res[0] = -radius.intValue();
                    res[1] = -shortStep.intValue();
                }
                else {                          //octant 6
                    res[0] = -(int)(radius - shortStep);
                    res[1] = -radius.intValue();
                }
            }
            else {
                if(perimeterStep<radius*7) { //octant 7
                    res[0] = shortStep.intValue();
                    res[1] = -radius.intValue();
                }
                else {                          //octant 8
                    res[0] = radius.intValue();
                    res[1] = -(int)(radius-shortStep);
                }
            }
        }
        return res;
    }

    public static long xzToSquareLocation(long x, long z, int cx, int cz) {
        long res = 0;

        x = x-cx;
        z = z-cz;

        double theta = (Math.atan(((double)z)/x)/(2*Math.PI)) % 0.25;

        if((z<0) && (x<0)) {
            theta += 0.5;
        }
        else if(z<0) {
            theta += 0.75;
        }
        else if(x<0) {
            theta += 0.25;
        }

        double radius;
        long ax = Math.abs(x);
        long az = Math.abs(z);
        if(ax>az) radius = ax;
        else radius = az;

        System.out.println("radius = " + radius);
        System.out.println("ax = " + ax);
        System.out.println("az = " + az);

        int perimeterStep = 0;
        if(theta<0.5) {
            if(theta<0.25) {
                if(theta<0.125) {   //octant 1, from 0 to pi/4
                    perimeterStep+=az;
                }
                else {              //octant 2, from pi/4 to pi/2
                    System.out.println("2");
                    perimeterStep+=radius;
                    perimeterStep+=(radius-ax);
                    System.out.println("perimeterStep = " + perimeterStep);
                }
            }
            else {
                if(theta<0.375) {   //octant 3
                    perimeterStep+=radius*2;
                    perimeterStep+=ax; //x is negative in this quadrant, so fix
                }
                else {              //octant 4
                    perimeterStep+=radius*3;
                    perimeterStep+=(radius-az);
                }
            }
        }
        else {
            if(theta<0.75) {
                if(theta<0.625) {   //octant 5
                    perimeterStep+=radius*4;
                    perimeterStep+=az;
                }
                else {              //octant 6
                    perimeterStep+=radius*5;
                    perimeterStep+=(radius-ax);
                }
            }
            else {
                if(theta<0.875) {   //octant 7
                    perimeterStep+=radius*6;
                    perimeterStep+=x;
                }
                else {              //octant 8
                    perimeterStep+=radius*7;
                    perimeterStep+=(radius-(az));
                }
            }
        }

        return (long)(radius*radius*4)+perimeterStep;
    }
}
