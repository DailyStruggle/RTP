package leafcraft.rtp.tools.selection;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.opentest4j.TestAbortedException;

import java.util.*;
import java.util.concurrent.*;

public class TeleportRegion {
    private static Set<Material> acceptableAir = new HashSet<>();;
    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
    }

    public String name;

    private Configs configs;
    private Cache cache;

    private World world;
    private double totalSpace;

    public enum Shapes{SQUARE,CIRCLE}
    public Shapes shape;

    private double weight;

    public boolean requireSkyLight;

    //location queue for this region and associated chunks
    private ConcurrentLinkedQueue<Location> locationQueue = new ConcurrentLinkedQueue<>();
    private ConcurrentHashMap<Location, List<CompletableFuture<Chunk>>> locAssChunks = new ConcurrentHashMap<>();

    //list of bad chunks in this region to avoid retries
    private ConcurrentSkipListMap<Long,Long> badChunks = new ConcurrentSkipListMap<>();
    private long badChunkSum = 0;

    public int r, cr, cx, cz, minY, maxY;

    public TeleportRegion(Map<String,String> params, Configs configs, Cache cache) {
        this.configs = configs;
        this.cache = cache;
        String worldName = params.getOrDefault("world","world");
        if(!configs.worlds.checkWorldExists(worldName)) worldName = "world";
        this.world = Bukkit.getWorld(worldName);

        String shapeStr =   params.get("shape");
        String rStr =       params.get("radius");
        String crStr =      params.get("centerRadius");
        String cxStr =      params.get("centerX");
        String czStr =      params.get("centerZ");
        String weightStr =  params.get("weight");
        String minYStr =    params.get("minY");
        String maxYStr =    params.get("maxY");
        String rslStr =     params.get("requireSkyLight");

        r = Integer.valueOf(rStr);
        cr = Integer.valueOf(crStr);
        cx = Integer.valueOf(cxStr);
        cz = Integer.valueOf(czStr);

        weight = Double.valueOf(weightStr);
        minY = Integer.valueOf(minYStr);
        maxY = Integer.valueOf(maxYStr);
        requireSkyLight = Boolean.valueOf(rslStr);

        try{
            this.shape = TeleportRegion.Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.shape = TeleportRegion.Shapes.CIRCLE;
        }

        this.totalSpace = (r-cr)*(r+cr);
        switch (this.shape) {
            case SQUARE: this.totalSpace = totalSpace*4; break;
            default: this.totalSpace = totalSpace*Math.PI;
        }
    }

    public void shutdown() {
        for(List<CompletableFuture<Chunk>> chunks : locAssChunks.values()) {
            for(CompletableFuture<Chunk> chunk : chunks) {
                chunk.cancel(true);
            }
        }
        locAssChunks.clear();
        locationQueue.clear();
    }

    public Location getLocation(boolean urgent) {
        Location res;

        try{
            res = locationQueue.remove();
        }
        catch (NoSuchElementException exception) {
            res = getRandomLocation(urgent);
        }
        catch (NullPointerException exception) {
            res = getRandomLocation(urgent);
        }
        return res;
    }

    private void addChunks(Location location) {
        List<CompletableFuture<Chunk>> chunks = new ArrayList<>();

        int vd = Bukkit.getViewDistance();
        int cx = (location.getBlockX()>0) ? location.getBlockX()/16 : location.getBlockX()/16-1;
        int cz = (location.getBlockZ()>0) ? location.getBlockZ()/16 : location.getBlockZ()/16-1;
        for(int i = -vd; i < vd; i++) {
            for(int j = -vd; j < vd; j++) {
                chunks.add(PaperLib.getChunkAtAsync(location.getWorld(), cx+i, cz+j));
                HashableChunk hc = new HashableChunk(location.getWorld(), cx+i, cz+j);
                cache.keepChunks.putIfAbsent(hc, Long.valueOf(0));
                cache.keepChunks.compute(hc, (k, v) -> v + 1);
            }
        }
        locAssChunks.put(location,chunks);
    }

    public List<CompletableFuture<Chunk>> getChunks(Location location) {
        return locAssChunks.getOrDefault(location,new ArrayList<>());
    }

    public void removeChunks(Location location) {
        locAssChunks.remove(location);
    }

    public void queueLocation(Location location) {
        locationQueue.offer(location);
    }

    public boolean queueRandomLocation() {
        if(locationQueue == null) {
            locationQueue = new ConcurrentLinkedQueue<>();
        }
        Integer queueLen = (Integer)configs.regions.getRegionSetting(name,"queueLen",0);
        if(locationQueue.size() >= queueLen) return false;

        Location location = getRandomLocation(false);
        if(location == null) {
            return false;
        }
        queueLocation(location);
        return true;
    }

    public void addBadChunk(int x, int z) {
        double location;
        int chunkCr = (cr/16)-1;
        int chunkCx = (cx>0) ? cx/16 : (cx/16)-1;
        int chunkCz = (cz>0) ? cz/16 : (cz/16)-1;
        switch (shape) {
            case SQUARE: {
                location = Translate.xzToSquareLocation(chunkCr,x,z,chunkCx,chunkCz);
                break;
            }
            default: {
                location = Translate.xzToCircleLocation(chunkCr,x,z,chunkCx,chunkCz);
            }
        }
        if(location<0) return;
        addBadChunk((long)location);
    }

    private void addBadChunk(long location) {
        if(badChunks == null) badChunks = new ConcurrentSkipListMap<>();

        Map.Entry<Long,Long> lower = badChunks.floorEntry(location);
        Map.Entry<Long,Long> upper = badChunks.ceilingEntry(location);

        //goal: merge adjacent values
        // if within bounds of lower entry, do nothing
        // if lower start+length meets position, add 1 to its length and use that
        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
            return;
        }
        else if((lower!=null) && (location == lower.getKey()+lower.getValue())) {
            badChunks.put(lower.getKey(),lower.getValue()+1);
        }
        else {
            badChunks.put(location,Long.valueOf(1));
        }

        lower = badChunks.floorEntry(location);

        // if upper start meets position + length, merge its length and delete upper entry
        if((upper!=null)&&(lower.getKey()+lower.getValue() >= upper.getKey())) {
            badChunks.put(lower.getKey(),lower.getValue()+upper.getValue());
            badChunks.remove(upper.getKey());
        }

        int[] xz;
        switch (shape) {
            case SQUARE: {
                xz = Translate.squareLocationToXZ(cr,cx,cz,location);
                break;
            }
            default: {
                xz = Translate.circleLocationToXZ(cr,cx,cz,location);
            }
        }
        cache.addBadChunk(world,xz[0],xz[1]);
    }

    private double select() {
        double res = (totalSpace-badChunkSum) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);
        return res;
    }

    private Location getRandomLocation(boolean urgent) {
        Location res;

        Boolean rerollLiquid = (Boolean) configs.config.getConfigValue("rerollLiquid",true);

        double location = select();
        int[] xz;
        switch (shape) {
            case SQUARE: {
                xz = Translate.squareLocationToXZ(cr,0,0,location);
                break;
            }
            default: {
                xz = Translate.circleLocationToXZ(cr,0,0,location);
            }
        }
        int[] xzChunk = new int[2];

        xzChunk[0] = (xz[0] >= 0) ? (xz[0] / 16) : (xz[0] / 16) - 1;
        xzChunk[1] = (xz[1] >= 0) ? (xz[1] / 16) : (xz[1] / 16) - 1;

        Long chunkLocation = 0l;

        int[] centerChunk = new int[2];
        centerChunk[0] = (cx >= 0) ? ((cx) / 16) : ((cx) / 16) - 1;
        centerChunk[1] = (cz >= 0) ? ((cz) / 16) : ((cz) / 16) - 1;

        chunkLocation = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                (long)(Translate.xzToSquareLocation( (cr/16)-1, xzChunk[0], xzChunk[1], centerChunk[0], centerChunk[1])) :
                (long)(Translate.xzToCircleLocation((cr/16)-1, xzChunk[0], xzChunk[1], centerChunk[0], centerChunk[1]));

        int[] posInChunk = new int[2];
        posInChunk[0] = Math.abs(xz[0]%16);
        posInChunk[1] = Math.abs(xz[1]%16);

        //shift location
        Map.Entry<Long,Long> idx = badChunks.firstEntry();
        while((idx!=null) && (chunkLocation >= (idx.getKey()))) {
            chunkLocation += idx.getValue();
            idx = badChunks.ceilingEntry(idx.getKey()+idx.getValue());
        }

        xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                (Translate.squareLocationToXZ((cr/16)-1, centerChunk[0], centerChunk[1], chunkLocation.doubleValue())) :
                (Translate.circleLocationToXZ((cr/16)-1, centerChunk[0], centerChunk[1], chunkLocation.doubleValue()));
        xz[0] = 16*(xzChunk[0]+centerChunk[0]) + posInChunk[0] + cx;
        xz[1] = 16*(xzChunk[1]+centerChunk[1]) + posInChunk[1] + cz;

        CompletableFuture<Chunk> cfChunk = (urgent) ?
                PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);

        Chunk chunk;
        try {
            chunk = cfChunk.get();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        }

        res = new Location(world,xz[0],minY,xz[1]);

        res = this.getFirstNonAir(res);
        res = this.getLastNonAir(res);

        Integer numAttempts = 1;
        Integer maxAttempts = (Integer) configs.config.getConfigValue("maxAttempts",100);
        while(numAttempts < maxAttempts &&
                ( acceptableAir.contains(res.getBlock().getType())
                        || (res.getBlockY() >= maxY)
                        || (rerollLiquid && res.getBlock().isLiquid()))) {
            addBadChunk(chunkLocation);

            location = select();
            switch (shape) {
                case SQUARE: {
                    xz = Translate.squareLocationToXZ(cr,0,0,location);
                    break;
                }
                default: {
                    xz = Translate.circleLocationToXZ(cr,0,0,location);
                }
            }

            xzChunk[0] = (xz[0] >= 0) ? (xz[0] / 16) : (xz[0] / 16) - 1;
            xzChunk[1] = (xz[1] >= 0) ? (xz[1] / 16) : (xz[1] / 16) - 1;

            centerChunk[0] = (cx >= 0) ? (cx / 16) : (cx / 16) - 1;
            centerChunk[1] = (cz >= 0) ? (cz / 16) : (cz / 16) - 1;

            chunkLocation = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                    (long)(Translate.xzToSquareLocation( (cr/16)-1, xzChunk[0], xzChunk[1], centerChunk[0], centerChunk[1])) :
                    (long)(Translate.xzToCircleLocation((cr/16)-1, xzChunk[0], xzChunk[1], centerChunk[0], centerChunk[1]));

            posInChunk[0] = Math.abs(xz[0]%16);
            posInChunk[1] = Math.abs(xz[1]%16);

            //shift location
            idx =  badChunks.firstEntry();
            while((idx!=null) && (chunkLocation >= (idx.getKey()))) {
                chunkLocation += idx.getValue();
                idx = badChunks.ceilingEntry(idx.getKey()+idx.getValue());
            }

            xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                    (Translate.squareLocationToXZ(cr/16-1, centerChunk[0], centerChunk[1], chunkLocation.doubleValue())) :
                    (Translate.circleLocationToXZ(cr/16-1, centerChunk[0], centerChunk[1], chunkLocation.doubleValue()));
            xz[0] = 16*(xzChunk[0]+centerChunk[0]) + posInChunk[0] + cx;
            xz[1] = 16*(xzChunk[1]+centerChunk[1]) + posInChunk[1] + cz;

            cfChunk = (urgent) ?
                    PaperLib.getChunkAtAsyncUrgently(world,xz[0],xz[1],true) :
                    PaperLib.getChunkAtAsync(world,xz[0],xz[1],true);

            try {
                chunk = cfChunk.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            } catch (ExecutionException e) {
                e.printStackTrace();
                return null;
            }

            res = new Location(world,xz[0],minY,xz[1]);

            //int y = world.getHighestBlockYAt(res.getBlockX(),res.getBlockZ(),HeightMap.WORLD_SURFACE);
            //res.setY(y);
            res = this.getFirstNonAir(res);
            res = this.getLastNonAir(res);
            numAttempts++;
        }

        res.setY(res.getBlockY()+1);
        res.setX(res.getBlockX()+0.5);
        res.setZ(res.getBlockZ()+0.5);

        if(numAttempts >= maxAttempts) {
            return null;
        }

        this.cache.numTeleportAttempts.put(res, numAttempts);
        addChunks(res);
        return res;
    }

    public Location getFirstNonAir(Location start) {
        //iterate over a good distance to reduce thin floors
        int i = start.getBlockY();
        for(; i <= maxY; i+=8) {
            start.setY(i);
            if(!acceptableAir.contains(start.getBlock().getType())) {
                break;
            }
        }
        return start;
    }

    public Location getLastNonAir(Location start) {
        Integer oldY;
        Integer minY = start.getBlockY();
        Integer maxY = this.maxY;

        //iterate over a larger distance first, then fine-tune
        for(Integer it_length = (maxY-minY)/2; it_length > 0; it_length = it_length/2) {
            int i = minY;
            for(; i <= maxY; i+=it_length) {
                oldY = start.getBlockY();
                start.setY(i);
                byte skyLight;
                try{
                    skyLight = start.getBlock().getLightFromSky();
                }
                catch (TestAbortedException ex) {
                    if(acceptableAir.contains(start.getBlock().getType())) skyLight = 15;
                    else skyLight = 0;
                }
                if(acceptableAir.contains(start.getBlock().getType())
                        && acceptableAir.contains(start.getBlock().getRelative(BlockFace.UP).getType())
                        && !(requireSkyLight && skyLight==0)) {
                    start.setY(oldY);
                    minY = oldY;
                    maxY = i;
                    break;
                }
            }
            if(i>= this.maxY) {
                start.setY(this.maxY);
                break;
            }
        }
        return start;
    }
}
