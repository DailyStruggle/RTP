package leafcraft.rtp.API.selection.region.selectors.shapes;

import leafcraft.rtp.API.selection.region.selectors.Shape;

public record Circle(long cx, long cz, long cr) implements Shape {

    @Override
    public String name() {
        return "Circle";
    }

    @Override
    public double xzToLocation(long x, long z) {
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
        return (radius*radius - cr*cr)*Math.PI + rotation*(2*radius*Math.PI);
    }

    @Override
    public long[] locationToXZ(long location) {
        long[] res = new long[2];

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
}
