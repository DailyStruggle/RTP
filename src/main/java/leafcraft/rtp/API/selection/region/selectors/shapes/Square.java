package leafcraft.rtp.API.selection.region.selectors.shapes;

import leafcraft.rtp.API.selection.region.selectors.Shape;

public record Square(long cx, long cz, long cr) implements Shape {
    @Override
    public String name() {
        return "Square";
    }

    @Override
    public double xzToLocation(long x, long z) {
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
    public long[] locationToXZ(long location) {
        long[] res;
        //get a distance from the center
        double radius = Math.sqrt(location + cr * cr * 4) / 2;

        //get how far to step around the square
        double theta = radius - (int) radius;
        Double perimeterStep = 8 * (radius * (theta));

        radius = (int) radius;

        res = squareOct2Coords(radius, perimeterStep);
        res[0] += cx;
        res[1] += cz;

        return res;
    }

    private static long[] squareOct2Coords(Double radius, Double perimeterStep) {
        long[] res = new long[2];
        //get how far to go from a corner
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
        return res;
    }
}
