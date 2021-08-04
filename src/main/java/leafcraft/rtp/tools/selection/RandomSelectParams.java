package leafcraft.rtp.tools.selection;

import leafcraft.rtp.tools.Cache;

import java.util.Locale;

public class RandomSelectParams {
    public enum Shapes{SQUARE,CIRCLE};
    public Shapes shape;
    public int r,cr,cx,cz; //radius, centerRadius, center x,z

    public int totalSpace; //how much space is occupied

    RandomSelectParams(String shapeStr, int r, int cr, int cx, int cz) {
        this.totalSpace = (r-cr) * (r + cr);
        try{
            this.shape = Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.shape = Shapes.CIRCLE;
            totalSpace *= Math.PI;
        }
        this.r = r;
        this.cr = cr;
        this.cx = cx;
        this.cz = cz;
    }
}
