package leafcraft.rtp.tools.selection;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class RandomSelectParams {
    private static final long maxRadBeforeBig = (long)Math.sqrt(Long.MAX_VALUE/4);
    public boolean hasCustomValues;
    public boolean isBig;
    private BigDecimal totalSpaceBig;
    private long totalSpaceNative;

    public static MathContext mc = new MathContext(10);
    public static BigDecimal bigPi = BigDecimal.valueOf(Math.PI);

    public enum Shapes{SQUARE,CIRCLE};

    public World world;

    public Shapes shape;
    public long r,cr,cx,cz, minY, maxY; //radius, centerRadius, center x,z

    public double weight;

    public boolean requireSkyLight, worldBorderOverride;

    public BigDecimal positionBig; //how much space is occupied
    public long positionNative;

    public RandomSelectParams(World world, Map<String,String> params, Config config) {
        String worldName = params.getOrDefault("world",world.getName());
        if(!config.checkWorldExists(worldName)) worldName = world.getName();
        this.world = Bukkit.getWorld(worldName);

        hasCustomValues = params.size()>0;

        //ugh string parsing, but at least it's short and clean
        String shapeStr =   params.getOrDefault("shape",(String)config.getWorldSetting(worldName,"shape","CIRCLE"));
        String rStr =       params.getOrDefault("radius", (config.getWorldSetting(worldName,"radius",4096)).toString());
        String crStr =      params.getOrDefault("centerRadius", (config.getWorldSetting(worldName,"centerRadius",1024)).toString());
        String cxStr =      params.getOrDefault("centerX", (config.getWorldSetting(worldName,"centerX",0)).toString());
        String czStr =      params.getOrDefault("centerZ", (config.getWorldSetting(worldName,"centerZ",0)).toString());
        String weightStr =  params.getOrDefault("centerX", (config.getWorldSetting(worldName,"weight",1.0)).toString());
        String minYStr =    params.getOrDefault("minY", (config.getWorldSetting(worldName,"minY",0)).toString());
        String maxYStr =    params.getOrDefault("maxY", (config.getWorldSetting(worldName,"maxY",128)).toString());
        String rslStr =     params.getOrDefault("requireSkyLight", (config.getWorldSetting(worldName,"requireSkyLight",true)).toString());

        r = Long.valueOf(rStr)/16; //switch to chunks for better good/bad detection
        cr = Long.valueOf(crStr)/16;
        cx = Long.valueOf(cxStr);
        cz = Long.valueOf(czStr);

        cx = (cx>0) ? cx/16 : cx/16-1;
        cz = (cz>0) ? cz/16 : cz/16-1;

        weight = Double.valueOf(weightStr);
        minY = Long.valueOf(minYStr);
        maxY = Long.valueOf(maxYStr);
        requireSkyLight = Boolean.valueOf(rslStr);

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
