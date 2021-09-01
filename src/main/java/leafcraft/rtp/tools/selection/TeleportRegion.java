package leafcraft.rtp.tools.selection;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.softdepends.GriefPreventionChecker;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import leafcraft.rtp.tools.softdepends.WorldGuardChecker;
import leafcraft.rtp.tools.HashableChunk;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class TeleportRegion {
    private static final Set<String> regionParams = new HashSet<>();
    static {
        regionParams.add("world");
        regionParams.add("shape");
        regionParams.add("radius");
        regionParams.add("centerRadius");
        regionParams.add("centerX");
        regionParams.add("centerZ");
        regionParams.add("weight");
        regionParams.add("minY");
        regionParams.add("maxY");
        regionParams.add("requireSkyLight");
        regionParams.add("requirePermission");
        regionParams.add("worldBorderOverride");
        regionParams.add("uniquePlacements");
    }

    private static final Set<Material> acceptableAir = new HashSet<>();
    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
        acceptableAir.add(Material.SNOW);
        acceptableAir.add(Material.GRASS);
        acceptableAir.add(Material.SUNFLOWER);
        acceptableAir.add(Material.DANDELION);
        acceptableAir.add(Material.DANDELION_YELLOW);
        acceptableAir.add(Material.POPPY);
        acceptableAir.add(Material.BLUE_ORCHID);
        acceptableAir.add(Material.ALLIUM);
        acceptableAir.add(Material.AZURE_BLUET);
        acceptableAir.add(Material.RED_TULIP);
        acceptableAir.add(Material.ORANGE_TULIP);
        acceptableAir.add(Material.WHITE_TULIP);
        acceptableAir.add(Material.PINK_TULIP);
        acceptableAir.add(Material.OXEYE_DAISY);
        acceptableAir.add(Material.LILAC);
        acceptableAir.add(Material.ROSE_RED);
        acceptableAir.add(Material.PEONY);
        acceptableAir.add(Material.SUGAR_CANE);
        acceptableAir.add(Material.VINE);
        acceptableAir.add(Material.WHEAT);
        acceptableAir.add(Material.CARROT);
        acceptableAir.add(Material.CARROTS);
        acceptableAir.add(Material.POTATO);
        acceptableAir.add(Material.POTATOES);
        acceptableAir.add(Material.BEETROOT);
        acceptableAir.add(Material.BEETROOTS);
        acceptableAir.add(Material.MELON_STEM);
        acceptableAir.add(Material.PUMPKIN_STEM);
        acceptableAir.add(Material.DEAD_BUSH);
        acceptableAir.add(Material.LARGE_FERN);
        acceptableAir.add(Material.FERN);
    }

    private class FillTask extends BukkitRunnable {
        private boolean cancelled = false;
        private RTP plugin;
        private ArrayList<CompletableFuture<Chunk>> chunks;

        public FillTask(RTP plugin) {
            this.plugin = plugin;
            this.chunks = new ArrayList<>(100);
        }

        @Override
        public void run() {
            if(Bukkit.getOnlinePlayers().size()>0 || TPS.getTPS()<configs.config.minTPS) {
                fillTask = new FillTask(plugin);
                fillTask.runTaskLaterAsynchronously(plugin,20);
                return;
            }
            long it = fillIterator.get();
            AtomicInteger completion = new AtomicInteger(1000);
//            AtomicLong max = new AtomicLong((long) ((expand) ? totalSpace : totalSpace - badLocationSum.get()));
            AtomicLong max = new AtomicLong((long) totalSpace);
            long itStop = it + completion.get();
            AtomicBoolean completed = new AtomicBoolean(false);
            for(; it < itStop; it++) {
                if(cancelled) return;
                long shiftedIt = it;
//                Map.Entry<Long,Long> idx = badLocations.firstEntry();
//                while((idx !=null) && (it >= idx.getKey())) {
//                    shiftedIt += idx.getValue();
//                    idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
//                }

                if (it > max.get()) {
                    Bukkit.getLogger().log(Level.INFO, "[rtp] completed " + (it-1) + "/" + max.get() + " chunks");
                    fillTask = null;
                    return;
                }

                int xz[] = (shape.equals(Shapes.SQUARE)) ?
                        Translate.squareLocationToXZ(cr,cx,cz,shiftedIt) :
                        Translate.circleLocationToXZ(cr,cx,cz,shiftedIt);
                CompletableFuture<Chunk> cfChunk = PaperLib.getChunkAtAsync(world,xz[0],xz[1],true);
                this.chunks.add(cfChunk);
                final long finalShiftedIt = shiftedIt;
                max.set((long)totalSpace);
//                max.set((long) ((expand) ? totalSpace : totalSpace - badLocationSum.get()));
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot();
                    int y = getFirstNonAir(chunkSnapshot);
                    y = getLastNonAir(chunkSnapshot,y);

                    Location res = chunk.getBlock(7,y,7).getLocation();
                    if(!checkLocation(res)) {
                        addBadLocation(finalShiftedIt);
                    }
                    fillIterator.incrementAndGet();
                    if(completion.decrementAndGet() == 0 && !cancelled && !completed.get()) {
                        completed.set(true);
                        Bukkit.getLogger().log(Level.INFO, "[rtp] completed " + fillIterator.get() + "/" + max.get() + " chunks");
                        fillTask = new FillTask(plugin);
                        fillTask.runTaskLaterAsynchronously(plugin,5);
                    }
                });
                cfChunk.whenComplete((chunk, throwable) -> {
                    if(completion.get() == 0 || cancelled || fillIterator.get()==max.get()) {
                        world.save();
                    }
                });
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            for(CompletableFuture<Chunk> cfChunk : chunks) {
                if(!cfChunk.isDone()) cfChunk.cancel(true);
            }
            fillTask = null;
            super.cancel();
        }
    }

    public class ChunkSet {
        public AtomicInteger completed;
        public int expectedSize;
        public ArrayList<CompletableFuture<Chunk>> chunks;

        public ChunkSet() {
            completed = new AtomicInteger(0);
            int vd = configs.config.vd;
            expectedSize = (vd*2+1)*(vd*2+1);
            chunks = new ArrayList<>(expectedSize);
        }

        public void shutDown() {
            if(fillTask!=null) fillTask.cancel();
            for(CompletableFuture<Chunk> chunk : chunks) {
                if(!chunk.isDone()) chunk.cancel(true);
            }
        }
    }

    public String name;

    private final Configs configs;
    private final Cache cache;

    private final World world;
    private double totalSpace;

    //location queue for this region and associated chunks
    private ConcurrentLinkedQueue<Location> locationQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Location, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HashableChunk, CompletableFuture<Chunk>> currChunks = new ConcurrentHashMap<>();

    //player reservation queue for reserving a spot on death
    private final ConcurrentHashMap<UUID,ConcurrentLinkedQueue<Location>> perPlayerQueue = new ConcurrentHashMap<>();

    //list of bad chunks in this region to avoid retries
    private ConcurrentSkipListMap<Long,Long> badLocations = new ConcurrentSkipListMap<>();
    private AtomicLong badLocationSum = new AtomicLong(0l);

    public enum Shapes{SQUARE,CIRCLE}
    public Shapes shape;

    private final double weight;

    public boolean requireSkyLight, uniquePlacements, expand;

    public boolean rerollLiquid, rerollWorldGuard, rerollGriefPrevention;

    public int r, cr, cx, cz, minY, maxY;

    private AtomicLong fillIterator;
    private FillTask fillTask = null;

    public TeleportRegion(String name, Map<String,String> params, Configs configs, Cache cache) {
        this.name = name;
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

        rerollLiquid = configs.config.rerollLiquid;
        rerollWorldGuard = configs.config.rerollWorldGuard;
        rerollGriefPrevention = configs.config.rerollGriefPrevention;
    }

    public boolean isFilling() {
        return fillTask != null && !fillTask.cancelled;
    }

    public void startFill(RTP plugin) {
        fillIterator = new AtomicLong(0l);
        fillTask = new FillTask(plugin);
        fillTask.runTaskAsynchronously(plugin);
    }

    public void stopFill() {
        fillTask.cancel();
        fillTask = null;
    }

    public boolean hasQueuedLocation(Player player) {
        boolean hasPlayerLocation = perPlayerQueue.containsKey(player.getUniqueId());
        boolean hasPublicLocation = locationQueue.size()>0;
        return ( hasPlayerLocation || hasPublicLocation);
    }

    public int getTotalQueueLength(Player player) {
        return getPublicQueueLength() + getPlayerQueueLength(player);
    }

    public int getPublicQueueLength() {
        return (locationQueue==null) ? 0 : locationQueue.size();
    }

    public int getPlayerQueueLength(Player player) {
        return (perPlayerQueue==null) ? 0 :
                (!perPlayerQueue.contains(player.getUniqueId())) ? 0 :
                        perPlayerQueue.get(player).size();
    }

    public void shutdown() {
        for(ChunkSet chunkSet : locAssChunks.values()) {
            chunkSet.shutDown();
        }
        for(CompletableFuture<Chunk> chunk : currChunks.values()) {
            if(!chunk.isDone()) {
                chunk.cancel(true);
            }
        }
        locAssChunks.clear();
        locationQueue.clear();
    }

    public Location getLocation(boolean urgent, CommandSender sender, Player player) {
        Location res = null;

        if(perPlayerQueue.containsKey(player.getUniqueId()) && perPlayerQueue.get(player.getUniqueId()).size()>0) {
            Location location = perPlayerQueue.get(player.getUniqueId()).remove();
            if(perPlayerQueue.get(player.getUniqueId()).size() <= 0) perPlayerQueue.remove(player.getUniqueId());
            return location;
        }

        try{
            res = locationQueue.remove();
        }
        catch (NoSuchElementException | NullPointerException exception) {
            if(sender.hasPermission("rtp.unqueued")) {
                res = getRandomLocation(urgent);
                if(res == null) {
                    Integer maxAttempts = configs.config.maxAttempts;
                    player.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("unsafe",maxAttempts.toString())));
                    if(!sender.getName().equals(player.getName()))
                        sender.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("unsafe",maxAttempts.toString())));
                }
            }
            else {
                player.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("noLocationsQueued")));
                if(!sender.getName().equals(player.getName()))
                    sender.sendMessage(PAPIChecker.fillPlaceholders(player,configs.lang.getLog("noLocationsQueued")));
            }
        }
        return res;
    }

    private void addChunks(Location location, boolean urgent) {
        ChunkSet chunkSet = new ChunkSet();
        locAssChunks.put(location,chunkSet);

        int vd = configs.config.vd;
        int cx = (location.getBlockX() > 0) ? location.getBlockX() / 16 : location.getBlockX() / 16 - 1;
        int cz = (location.getBlockZ() > 0) ? location.getBlockZ() / 16 : location.getBlockZ() / 16 - 1;
        int idx = 0;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        for (int i = -vd; i <= vd; i++) {
            for (int j = -vd; j <= vd; j++) {
                if(PaperLib.isPaper() || !urgent) {
                    CompletableFuture<Chunk> cfChunk;
                    cfChunk = urgent ? PaperLib.getChunkAtAsyncUrgently(Objects.requireNonNull(location.getWorld()), cx + i, cz + j, true) :
                            PaperLib.getChunkAtAsync(Objects.requireNonNull(location.getWorld()), cx + i, cz + j, true);
                    chunkSet.chunks.add(idx, cfChunk);
                    cfChunk.whenCompleteAsync((chunk, throwable) -> {
                        chunkSet.completed.getAndAdd(1);
                        if(!chunk.isForceLoaded()) {
                            Bukkit.getScheduler().runTask(plugin, () -> chunk.setForceLoaded(true));
                            cache.forceLoadedChunks.put(new HashableChunk(chunk),0l);
                        }
                    });
                }
                if (uniquePlacements) {
                    addBadLocation(cx + i, cz + j);
                }
            }
        }
    }

    public ChunkSet getChunks(Location location) {
        return locAssChunks.getOrDefault(location,new ChunkSet());
    }

    public void removeChunks(Location location) {
        locAssChunks.remove(location);
    }

    public void queueLocation(Location location) {
        if(location == null) return;
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
        ChunkSet chunkSet = getChunks(location);
        if(chunkSet.completed.get()>=chunkSet.expectedSize-1) {
            queueLocation(location);
        }
        else {
            AtomicBoolean added = new AtomicBoolean(false);
            for (CompletableFuture<Chunk> cfChunk : chunkSet.chunks) {
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    if (chunkSet.completed.get() >= chunkSet.expectedSize-1 && !added.getAndSet(true)) {
//                        Bukkit.getLogger().warning("adding public location");
                        queueLocation(location);
                    }
                });
            }
        }
    }

    public void queueRandomLocation(Player player) {
        if(locationQueue.size() > 1 && locationQueue.size() >= (Integer)configs.regions.getRegionSetting(name,"queueLen",0)) {
            perPlayerQueue.putIfAbsent(player.getUniqueId(),new ConcurrentLinkedQueue<>());
            perPlayerQueue.get(player.getUniqueId()).add(locationQueue.remove());
            return;
        }

        Location location = getRandomLocation(false);
        if(location == null) {
            return;
        }
        ChunkSet chunkSet = getChunks(location);
        if(chunkSet.completed.get()>=chunkSet.expectedSize-1) {
            perPlayerQueue.putIfAbsent(player.getUniqueId(),new ConcurrentLinkedQueue<>());
            perPlayerQueue.get(player.getUniqueId()).add(location);
        }
        else {
            AtomicBoolean added = new AtomicBoolean(false);
            for (CompletableFuture<Chunk> cfChunk : chunkSet.chunks) {
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    if (chunkSet.completed.get() >= chunkSet.expectedSize-1 && !added.getAndSet(true)) {
                        if(player.isOnline()) {
                            perPlayerQueue.putIfAbsent(player.getUniqueId(),new ConcurrentLinkedQueue<>());
                            perPlayerQueue.get(player.getUniqueId()).offer(location);
                        }
                        else {
                            locationQueue.add(location);
                        }
                    }
                });
            }
        }
    }

    public void recyclePlayerLocations(Player player) {
        if(!perPlayerQueue.containsKey(player.getUniqueId())) return;
        while(perPlayerQueue.get(player.getUniqueId()).size()>0) {
            queueLocation(perPlayerQueue.get(player.getUniqueId()).remove());
        }
        if(perPlayerQueue.containsKey(player.getUniqueId()))
            perPlayerQueue.remove(player.getUniqueId());
    }

    public void addBadLocation(int chunkX, int chunkZ) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,chunkX,chunkZ,cx,cz) :
                Translate.xzToCircleLocation(cr,chunkX,chunkZ,cx,cz);
        addBadLocation((long)location);
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

        badLocationSum.incrementAndGet();
    }

    private long select() {
        double space = totalSpace;
        if(!expand) space -= badLocationSum.get();
        double res = (space) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);
        return (long)res;
    }

    private Location getRandomLocation(boolean urgent) {
        Location res;

        double selectTime = 0D;
        double chunkTime = 0D;
        double yTime = 0D;

        long start = System.currentTimeMillis();
        long location = select();

        Map.Entry<Long,Long> idx = badLocations.firstEntry();
        while((idx!=null) && (location>=idx.getKey() || isKnownBad(location))) {
            location += idx.getValue();
            idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
        }

        int[] xz = new int[2];
        int[] xzChunk;

        xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                (Translate.circleLocationToXZ(cr, cx, cz, location));

        xz[0] = (xzChunk[0]*16)+7;
        xz[1] = (xzChunk[1]*16)+7;

        long stop = System.currentTimeMillis();
        selectTime += (stop-start);

        start = System.currentTimeMillis();
        CompletableFuture<Chunk> cfChunk = (urgent) ?
                PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);
        HashableChunk hashableChunk = new HashableChunk(world,xzChunk[0],xzChunk[1]);
        currChunks.put(hashableChunk,cfChunk);

        ChunkSnapshot chunkSnapshot;
        try {
            chunkSnapshot = cfChunk.get().getChunkSnapshot(); //wait on chunk load/gen
        } catch (ExecutionException e) {
            e.printStackTrace();
            return null;
        } catch (InterruptedException | CancellationException | StackOverflowError e) {
            return null;
        }
        currChunks.remove(hashableChunk);

        stop = System.currentTimeMillis();
        chunkTime += (stop-start);

        int y;
        start = System.currentTimeMillis();
        y = this.getFirstNonAir(chunkSnapshot);
        y = this.getLastNonAir(chunkSnapshot,y);
        res = new Location(world,xz[0],y,xz[1]);
        stop = System.currentTimeMillis();
        yTime += (stop - start);

        Integer numAttempts = 1;
        Integer maxAttempts = configs.config.maxAttempts;
        while(numAttempts < maxAttempts && !checkLocation(res)) {
            addBadLocation(location);

            start = System.currentTimeMillis();
            location = select();

            idx = badLocations.firstEntry();
            while((idx!=null) && (location>=idx.getKey() || isKnownBad(location))) {
                location += idx.getValue();
                idx = badLocations.ceilingEntry(idx.getKey()+idx.getValue());
            }

            xzChunk = shape.equals(TeleportRegion.Shapes.SQUARE) ?
                    (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                    (Translate.circleLocationToXZ(cr, cx, cz, location));

            xz[0] = (xzChunk[0]*16)+7;
            xz[1] = (xzChunk[1]*16)+7;

            stop = System.currentTimeMillis();
            selectTime += (stop-start);

            start = System.currentTimeMillis();
            cfChunk = (urgent) ?
                    PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                    PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);
            hashableChunk = new HashableChunk(world,xzChunk[0],xzChunk[1]);
            currChunks.put(hashableChunk,cfChunk);

            try {
                chunkSnapshot = cfChunk.get().getChunkSnapshot(); //wait on chunk load/gen
            } catch (ExecutionException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException | CancellationException | StackOverflowError e) {
                return null;
            }
            currChunks.remove(hashableChunk);

            stop = System.currentTimeMillis();
            chunkTime += (stop-start);

            start = System.currentTimeMillis();
            y = this.getFirstNonAir(chunkSnapshot);
            y = this.getLastNonAir(chunkSnapshot,y);
            res = new Location(world,xz[0],y,xz[1]);
            stop = System.currentTimeMillis();
            yTime += (stop - start);

            numAttempts++;
        }

        res.setY(res.getBlockY()+1);
        res.setX(res.getBlockX()+0.5);
        res.setZ(res.getBlockZ()+0.5);

        if(numAttempts >= maxAttempts) {
            return null;
        }

        this.cache.numTeleportAttempts.put(res, numAttempts);
        addChunks(res, urgent);

//        Bukkit.getLogger().warning(ChatColor.AQUA + "AVG TIME SPENT ON SELECTION: " + selectTime/numAttempts);
//        Bukkit.getLogger().warning(ChatColor.LIGHT_PURPLE + "AVG TIME SPENT ON CHUNKS: " + chunkTime/numAttempts);
//        Bukkit.getLogger().warning(ChatColor.GREEN + "AVG TIME SPENT ON BLOCKS: " + yTime/numAttempts);

        return res;
    }

    public int getFirstNonAir(ChunkSnapshot chunk) {
        int i = minY;
        //iterate over a good distance to reduce thin floors
        int increment = (maxY-minY)/12;
        if(increment<=0) increment = 1;
        for(; i <= maxY; i+=increment) {
            if(!acceptableAir.contains(chunk.getBlockType(7,i,7))) {
                break;
            }
            if(i >= this.maxY-increment) return this.maxY;
        }
        return i;
    }

    public int getLastNonAir(ChunkSnapshot chunk, int y) {
        int oldY = y;
        int minY = y;
        int maxY = this.maxY;

        //iterate over a larger distance first, then fine-tune
        for(int it_length = (maxY-minY)/16; it_length > 0; it_length = it_length/2) {
            int i = minY;
            for(; i <= maxY; i+=it_length) {
                int skyLight = 15;
                if(requireSkyLight) skyLight = chunk.getBlockSkyLight(7,i,7);

                if(acceptableAir.contains(chunk.getBlockType(7,i,7))
                        && acceptableAir.contains(chunk.getBlockType(7,i+1,7))
                        && skyLight>=8) {
                    minY = oldY;
                    maxY = i;
                    break;
                }
                if(i >= this.maxY-it_length) return this.maxY;
                oldY = i;
            }
        }

        for(int i = minY; i <= maxY; i++) {
            int skyLight = 15;
            if(requireSkyLight) skyLight = chunk.getBlockSkyLight(7,i,7);

            if(acceptableAir.contains(chunk.getBlockType(7,i,7))
                    && acceptableAir.contains(chunk.getBlockType(7,i+1,7))
                    && skyLight>=8) {
                minY = oldY;
                break;
            }
            oldY = i;
        }
        return minY;
    }

    public boolean isKnownBad(int x, int z) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,x,z,cx,cz) :
                Translate.xzToCircleLocation(cr,x,z,cx,cz);
        return isKnownBad((long)location);
    }

    public boolean isKnownBad(long location) {
        Map.Entry<Long,Long> lower = badLocations.floorEntry(location);

        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
            return true;
        }
        return false;
    }

    public boolean isInBounds(int x, int z) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,x,z,cx,cz) :
                Translate.xzToCircleLocation(cr,x,z,cx,cz);
        return isInBounds((long)location);
    }

    public boolean isInBounds(long location) {
        if((location > totalSpace) || (location < 0)) {
            return false;
        }
        return true;
    }

    // true if it's a good placement
    public boolean checkLocation(Location location) {
        return !(acceptableAir.contains(location.getBlock().getType())
                || (!location.getBlock().getType().isSolid())
                || (location.getBlockY() >= maxY)
                || (rerollLiquid && (location.getBlock().isLiquid()))
                || (rerollWorldGuard && WorldGuardChecker.isInRegion(location))
                || (rerollGriefPrevention && GriefPreventionChecker.isInClaim(location)));
    }

    public void loadFile() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name+".dat");
        if(!f.exists()) return;

        ArrayList<String> linesArray = new ArrayList<>();
        try {
            Scanner scanner = new Scanner(
                    new File(f.getAbsolutePath()));
            while (scanner.hasNextLine()) {
                linesArray.add(scanner.nextLine() + "");
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        String name = linesArray.get(0).substring(5);
        Shapes shape = Shapes.valueOf(linesArray.get(1).substring(6));
        String worldName = linesArray.get(2).substring(6);
        int cr = Integer.parseInt(linesArray.get(3).substring(3));
        int cx = Integer.parseInt(linesArray.get(4).substring(3));
        int cz = Integer.parseInt(linesArray.get(5).substring(3));
        int minY = Integer.parseInt(linesArray.get(6).substring(5));
        int maxY = Integer.parseInt(linesArray.get(7).substring(5));
        boolean requireSkyLight = Boolean.parseBoolean(linesArray.get(8).substring(16));
        boolean uniquePlacements = Boolean.parseBoolean(linesArray.get(9).substring(17));

        if(!name.equals(this.name)) return;
        if(!shape.equals(this.shape)) return;
        if(!worldName.equals(this.world.getName())) return;
        if(!(cr==this.cr)) return;
        if(!(cx==this.cx)) return;
        if(!(cz==this.cz)) return;
        if(!(minY==this.minY)) return;
        if(!(maxY==this.maxY)) return;
        if(!(requireSkyLight==this.requireSkyLight)) return;
        if(!(uniquePlacements==this.uniquePlacements)) return;

        for(int i = 11; i < linesArray.size(); i++) {
            String val = linesArray.get(i).substring(3);
            int delimiterIdx = val.indexOf(',');
            Long location = Long.parseLong(val.substring(0,delimiterIdx));
            Long length = Long.parseLong(val.substring(delimiterIdx+1));
            if(location<0) continue;
            Map.Entry<Long,Long> lower = badLocations.floorEntry(location);
            if(lower!=null && location == lower.getKey()+lower.getValue()) {
                badLocations.put(lower.getKey(),lower.getValue()+length);
                length+=lower.getValue();
            }
            else badLocations.put(location,length);
            badLocationSum.addAndGet(length);
        }
    }

    public void storeFile() {
        ArrayList<String> linesArray = new ArrayList<>();
        linesArray.add("name:"+name);
        linesArray.add("shape:"+shape.toString());
        linesArray.add("world:"+world.getName());
        linesArray.add("cr:"+cr);
        linesArray.add("cx:"+cx);
        linesArray.add("cz:"+cz);
        linesArray.add("minY:"+minY);
        linesArray.add("maxY:"+maxY);
        linesArray.add("requireSkyLight:"+requireSkyLight);
        linesArray.add("uniquePlacements:"+uniquePlacements);
        linesArray.add("badLocations:");
        for(Map.Entry<Long,Long> entry : badLocations.entrySet()) {
            linesArray.add("  -" + entry.getKey() + "," + entry.getValue());
        }

        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name+".dat");
        File parentDir = f.getParentFile();
        if(!parentDir.exists()) {
            parentDir.mkdirs();
        }
        if(!f.exists()) {
            try {
                f.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        FileWriter fw;
        try {
            fw = new FileWriter(f.getAbsolutePath());
            for (String s : linesArray) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }
}
