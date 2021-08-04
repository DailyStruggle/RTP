package leafcraft.rtp.tools.selection;

public class SinTable {
    static final int precision = 10000; // gradations per degree, adjust to suit
    static final int modulus = 360*precision;
    public static final double[] sin = new double[modulus]; // lookup table
    static {
        // a static initializer fills the table
        // in this implementation, units are in degrees
        for (int i = 0; i<sin.length; i++) {
            sin[i]=Math.sin((i*Math.PI)/(precision*180));
        }
    }
    // Private function for table lookup
    private static double sinLookup(int a) {
        return a>=0 ? sin[a%(modulus)] : -sin[-a%(modulus)];
    }

    // These are your working functions:
    public static double sin(double a) {
        int idx = (int)(a * precision + 0.5d);
        return sinLookup(idx);
    }
    public static double cos(double a) {
        int idx = (int)((a+90f) * precision + 0.5d);
        return sinLookup(idx);
    }
}
