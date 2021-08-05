package leafcraft.rtp.tools.selection;

import leafcraft.rtp.tools.Cache;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class RandomSelectParams {
    private static final long maxRadBeforeBig = (long)Math.sqrt(Long.MAX_VALUE/4);
    public boolean isBig;
    private BigDecimal totalSpaceBig;
    private long totalSpaceNative;
    private double weight;
    public static MathContext mc = new MathContext(10);
    public static BigDecimal bigPi = BigDecimal.valueOf(Math.PI);
    public enum Shapes{SQUARE,CIRCLE};
    public Shapes shape;
    public long r,cr,cx,cz; //radius, centerRadius, center x,z

    public BigDecimal positionBig; //how much space is occupied
    public long positionNative;

    public RandomSelectParams(String shapeStr, long r, long cr, long cx, long cz, double weight) {
        if(r>maxRadBeforeBig) isBig = true;
        try{
            this.shape = Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.shape = Shapes.CIRCLE;
        }

        if(isBig) {
            this.totalSpaceBig = BigDecimal.valueOf(r-cr).multiply(BigDecimal.valueOf(r).add(BigDecimal.valueOf(cr)));
            switch (this.shape) {
                case SQUARE: this.totalSpaceBig = totalSpaceBig.multiply(BigDecimal.valueOf(4)); break;
                default: this.totalSpaceBig = totalSpaceBig.multiply(bigPi);
            }
        }
        else {
            this.totalSpaceNative = (r-cr)*(r+cr);
            switch (this.shape) {
                case SQUARE: this.totalSpaceNative = totalSpaceNative*4; break;
                default: this.totalSpaceNative = (int)(totalSpaceNative * Math.PI);
            }
        }

        this.weight = weight;
        this.r = r;
        this.cr = cr;
        this.cx = cx;
        this.cz = cz;
    }

    public void randPos() {
        if(isBig) {
            this.positionBig = totalSpaceBig.multiply(BigDecimal.valueOf(Math.pow(ThreadLocalRandom.current().nextDouble(),weight)));
        }
        else {
            this.positionNative = (long)(totalSpaceNative * Math.pow(ThreadLocalRandom.current().nextDouble(),weight));
        }
    }
}
