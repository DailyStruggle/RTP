import org.junit.jupiter.api.Test;

import java.util.Random;

public class sinTableTest {
    static final int precision = 10000; // gradations per degree, adjust to suit
    static final int modulus = 360*precision;
    static final double[] sin = new double[modulus]; // lookup table
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

    @Test
    public void timeTest(){
        int len = 1000000;
        double[] mathTestTable = new double[len];
        double[] mathTestSinRes = new double[len];
        double[] mathTestCosRes = new double[len];
        double[] tableTestTable = new double[len];
        double[] tableTestSinRes = new double[len];
        double[] tableTestCosRes = new double[len];

        for(Integer i = 0; i < len; i++)
            mathTestTable[i] = (i.doubleValue()/mathTestTable.length)*2*Math.PI;
        for(Integer i = 0; i < len; i++)
            tableTestTable[i] = ((i.doubleValue()/mathTestTable.length)*360);

        Long startTimeMath = System.currentTimeMillis();
        for(Integer i = 0; i < len; i++) mathTestSinRes[i] = Math.sin(mathTestTable[i]);
        for(Integer i = 0; i < len; i++) mathTestCosRes[i] = Math.cos(mathTestTable[i]);
        Double totalTimeMath = Double.valueOf(System.currentTimeMillis()- startTimeMath);
        System.out.println("avg time Math: " + 1000000*totalTimeMath/len + "ns");

        Long startTimeTable = System.currentTimeMillis();
        for(Integer i = 0; i < len; i++) tableTestSinRes[i] = sin(tableTestTable[i]);
        for(Integer i = 0; i < len; i++) tableTestCosRes[i] = cos(tableTestTable[i]);
        Double totalTimeTable = Double.valueOf(System.currentTimeMillis()-startTimeTable);
        System.out.println("avg time table: " + 1000000*totalTimeTable/len + "ns");

        Double err = 0.0;

        for(Integer i = 0; i < tableTestSinRes.length; i++) err+= Math.abs(tableTestSinRes[i] - mathTestSinRes[i]);
        System.out.println("avg sin error: " + err/tableTestTable.length);
    }
}
