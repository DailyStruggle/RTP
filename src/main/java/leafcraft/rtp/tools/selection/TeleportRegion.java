package leafcraft.rtp.tools.selection;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.Configuration.WorldGuardChecker;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.opentest4j.TestAbortedException;

import java.util.*;
import java.util.concurrent.*;

public class TeleportRegion {
    private static final Set<Material> acceptableAir = new HashSet<>();

    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
    }

    public String name;

    private final Configs configs;
    private final Cache cache;

    private final World world;
    private double totalSpace;

    public enum Shapes{SQUARE,CIRCLE}
    public Shapes shape;

    private final double weight;

    public boolean requireSkyLight, uniquePlacements, expand;

    //location queue for this region and associated chunks
    private ConcurrentLinkedQueue<Location> locationQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Location, List<CompletableFuture<Chunk>>> locAssChunks = new ConcurrentHashMap<>();

    //player reservation queue for reserving a spot on death
    private final ConcurrentHashMap<UUID,Location> playerNextLocation = new ConcurrentHashMap<>();

    //list of bad chunks in this region to avoid retries
    private ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    private long badLocationSum = 0;

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
        String upStr =      params.get("uniquePlacements");
        String expandStr =  params.get("expand");

        r = Integer.parseInt(rStr);
        cr = Integer.parseInt(crStr);
        cx = Integer.parseInt(cxStr);
        cz = Integer.parseInt(czStr);

        weight = Double.parseDouble(weightStr);
        minY = Integer.parseInt(minYStr);
        maxY = Integer.parseInt(maxYStr);
        requireSkyLight = Boolean.parseBoolean(rslStr);
        uniquePlacements = Boolean.parseBoolean(upStr);
        expand = Boolean.parseBoolean(expandStr);

        try{
            this.shape = TeleportRegion.Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.shape = TeleportRegion.Shapes.CIRCLE;
        }

        this.totalSpace = (r-cr)*(r+cr);
        if (this.shape == Shapes.SQUARE) {
            this.totalSpace = totalSpace * 4;
        } else {
            this.totalSpace = totalSpace * Math.PI;
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

    public Location getLocation(boolean urgent, CommandSender sender, Player player) {
        Location res = null;

        if(playerNextLocation.containsKey(player.getUniqueId())) {
            Location location = playerNextLocation.get(player.getUniqueId());
            playerNextLocation.remove(player.getUniqueId());
            return location;
        }

        try{
            res = locationQueue.remove();
        }
        catch (NoSuchElementException | NullPointerException exception) {
            if(sender.hasPermission("rtp.unqueued")) {
                res = getRandomLocation(urgent);
                if(res == null) {
                    Integer maxAttempts = (Integer) configs.config.getConfigValue("maxAttempts", 100);
                    player.sendMessage(configs.lang.getLog("unsafe",maxAttempts.toString()));
                    if(!sender.getName().equals(player.getName()))
                        sender.sendMessage(configs.lang.getLog("unsafe",maxAttempts.toString()));
                }
            }
            else {
                player.sendMessage(configs.lang.getLog("noLocationsQueued"));
                if(!sender.getName().equals(player.getName()))
                    sender.sendMessage(configs.lang.getLog("noLocationsQueued"));
            }
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
                chunks.add(PaperLib.getChunkAtAsync(Objects.requireNonNull(location.getWorld()), cx+i, cz+j));
                HashableChunk hc = new HashableChunk(location.getWorld(), cx+i, cz+j);
                cache.keepChunks.putIfAbsent(hc, 0L);
                cache.keepChunks.compute(hc, (k, v) -> v + 1);
                if(uniquePlacements) {
                    addBadChunk(cx+i,cz+j);
                }
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

    public void queueRandomLocation() {
        if(locationQueue == null) {
            locationQueue = new ConcurrentLinkedQueue<>();
        }
        Integer queueLen = (Integer)configs.regions.getRegionSetting(name,"queueLen",0);
        if(locationQueue.size() >= queueLen) return;

        Location location = getRandomLocation(false);
        if(location == null) {
            return;
        }
        queueLocation(location);
    }

    public void queueRandomLocation(Player player) {
        Location location = getRandomLocation(true);
        if(location == null) {
            return;
        }
        playerNextLocation.put(player.getUniqueId(),location);
    }

    public void addBadChunk(int chunkX, int chunkZ) {
        for(int i = 0; i < 16; i++) {
            for(int j = 0; j < 16; j++) {
                int localX = chunkX*16+i;
                int localZ = chunkZ*16+j;
                double location = (shape.equals(Shapes.SQUARE)) ?
                        Translate.xzToSquareLocation(cr,localX,localZ,cx,cz) :
                        Translate.xzToCircleLocation(cr,localX,localZ,cx,cz);
                addBadLocation((long)location);
            }
        }
    }

    private void addBadLocation(Long location) {
        if(location < 0) return;
        if(location > totalSpace) return;

        if(badLocations == null) badLocations = new ConcurrentSkipListMap<>();

        Map.Entry<Long,Long> lower = badLocations.floorEntry(location);
        Map.Entry<Long,Long> upper = badLocations.ceilingEntry(location);

        //goal: merge adjacent values
        // if within bounds of lower entry, do nothing
        // if lower start+length meets position, add 1 to its length and use that
        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
            return;
        }
        else if((lower!=null) && (location == lower.getKey()+lower.getValue())) {
            badLocations.put(lower.getKey(),lower.getValue()+1);
        }
        else {
            badLocations.put(location, 1L);
        }

        lower = badLocations.floorEntry(location);

        // if upper start meets position + length, merge its length and delete upper entry
        if((upper!=null)&&(lower.getKey()+lower.getValue() >= upper.getKey())) {
            badLocations.put(lower.getKey(),lower.getValue()+upper.getValue());
            badLocations.remove(upper.getKey());
        }

        badLocationSum++;
    }

    private long select() {
        double space = totalSpace;
        if(!expand) space -= badLocationSum;
        double res = (space) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);
        return (long)res;
    }

    private Location getRandomLocation(boolean urgent) {
        Location res;

        Boolean rerollLiquid = (Boolean) configs.config.getConfigValue("rerollLiquid",true);
        Boolean rerollWorldGuard = (Boolean) configs.config.getConfigValue("rerollWorldGuard",true);


        long selectTime = 0L;
        long chunkTime = 0L;

        long start = System.currentTimeMillis();
        long location = select();

        Map.Entry<Long,Long> idx = badLocations.firstEntry();
        while((idx!=null) && (location >= (idx.getKey()))) {
            location += idx.getValue();
            idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
        }

        int[] xz = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                (Translate.circleLocationToXZ(cr, cx, cz, location));

        int[] xzChunk = new int[2];

        xzChunk[0] = (xz[0] >= 0 || (xz[0]%16==0)) ? (xz[0] / 16) : (xz[0] / 16) - 1;
        xzChunk[1] = (xz[1] >= 0 || (xz[1]%16==0)) ? (xz[1] / 16) : (xz[1] / 16) - 1;

        long stop = System.currentTimeMillis();
        selectTime += (stop-start);

        start = System.currentTimeMillis();
        CompletableFuture<Chunk> cfChunk = (urgent) ?
                PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);

        Chunk chunk;
        try {
            chunk = cfChunk.get(); //wait on chunk load/gen
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return null;
        }

        res = new Location(world,xz[0],minY,xz[1]);
        res = this.getFirstNonAir(res);
        res = this.getLastNonAir(res);

        stop = System.currentTimeMillis();
        chunkTime += (stop-start);

        Integer numAttempts = 1;
        Integer maxAttempts = (Integer) configs.config.getConfigValue("maxAttempts",100);
        while(numAttempts < maxAttempts &&
                ( acceptableAir.contains(res.getBlock().getType())
                        || (res.getBlockY() >= maxY)
                        || (rerollLiquid && res.getBlock().isLiquid())
                        || (rerollWorldGuard && WorldGuardChecker.isInRegion(res)))) {
            addBadLocation(location);
            addBadChunk(xzChunk[0], xzChunk[1]);

            start = System.currentTimeMillis();
            location = select();

            idx = badLocations.firstEntry();
            while((idx!=null) && (location >= (idx.getKey()))) {
                location += idx.getValue();
                idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
            }

            xz = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                    (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                    (Translate.circleLocationToXZ(cr, cx, cz, location));

            xzChunk[0] = (xz[0] >= 0) ? (xz[0] / 16) : (xz[0] / 16) - 1;
            xzChunk[1] = (xz[1] >= 0) ? (xz[1] / 16) : (xz[1] / 16) - 1;

            stop = System.currentTimeMillis();
            selectTime += (stop-start);

            start = System.currentTimeMillis();
            cfChunk = (urgent) ?
                    PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                    PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);

            try {
                chunk = cfChunk.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return null;
            }

            res = new Location(world,xz[0],minY,xz[1]);
            res = this.getFirstNonAir(res);
            res = this.getLastNonAir(res);

            stop = System.currentTimeMillis();
            chunkTime += (stop-start);
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
        int oldY;
        int minY = start.getBlockY();
        int maxY = this.maxY;

        //iterate over a larger distance first, then fine-tune
        for(int it_length = (maxY-minY)/2; it_length > 0; it_length = it_length/2) {
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
        }
        return start;
    }
}
