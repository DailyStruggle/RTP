package leafcraft.rtp.tools.selection;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.API.customEvents.LoadChunksQueueEvent;
import leafcraft.rtp.API.customEvents.RandomSelectPlayerEvent;
import leafcraft.rtp.API.customEvents.RandomSelectQueueEvent;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.softdepends.*;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class TeleportRegion implements leafcraft.rtp.API.selection.TeleportRegion {
    private class FillTask extends BukkitRunnable {
        private boolean cancelled = false;
        private final RTP plugin;
        private final ArrayList<CompletableFuture<Chunk>> chunks;

        public FillTask(RTP plugin) {
            this.plugin = plugin;
            this.chunks = new ArrayList<>(2500);
        }

        @Override
        public void run() {
            Configs configs = RTP.getConfigs();
            if(TPS.getTPS()<configs.config.minTPS) {
                fillTask = new FillTask(plugin);
                fillTask.runTaskLaterAsynchronously(plugin,20);
                return;
            }

            long it = fillIterator.get();
            AtomicInteger completion = new AtomicInteger(2500);
//            AtomicLong max = new AtomicLong((long) ((expand) ? totalSpace : totalSpace - badLocationSum.get()));
            AtomicLong max = new AtomicLong((long) totalSpace);
            long itStop = it + completion.get();
            AtomicBoolean completed = new AtomicBoolean(false);

            String msg = configs.lang.getLog("fillStatus");
            msg = msg.replace("[num]", String.valueOf(it));
            msg = msg.replace("[total]", String.valueOf(max.get()));
            msg = msg.replace("[region]", name);

            SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
            for(Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
            }

            for(; it < itStop; it++) {
                if(cancelled) return;

                if (it > max.get()) {
                    msg = configs.lang.getLog("fillStatus");
                    msg = msg.replace("[num]", String.valueOf(it-1));
                    msg = msg.replace("[total]", String.valueOf(max.get()));
                    msg = msg.replace("[region]", name);

                    SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
                    for(Player player : Bukkit.getOnlinePlayers()) {
                        if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
                    }
                    fillTask = null;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, TeleportRegion.this::storeFile);
                    return;
                }

                int[] xz = (shape.equals(Shapes.SQUARE)) ?
                        Translate.squareLocationToXZ(cr,cx,cz, it) :
                        Translate.circleLocationToXZ(cr,cx,cz, it);
                if(cancelled) return;

                Biome currBiome = Objects.requireNonNull(world).getBiome(xz[0]*16+7,xz[1]*16+7);
                if(configs.config.biomeWhitelist != configs.config.biomes.contains(currBiome)) {
                    addBadLocation(it);
                    removeBiomeLocation(it,currBiome);
                    fillIterator.incrementAndGet();
                    if(completion.decrementAndGet() <3 && !completed.getAndSet(true)) {
                        fillTask = new FillTask(plugin);
                        fillTask.runTaskLaterAsynchronously(plugin,1);
                    }

                    continue;
                }

                CompletableFuture<Chunk> cfChunk = PaperLib.getChunkAtAsync(Objects.requireNonNull(world),xz[0],xz[1],true);
                this.chunks.add(cfChunk);
                final long finalIt = it;
                max.set((long)totalSpace);
//                max.set((long) ((expand) ? totalSpace : totalSpace - badLocationSum.get()));
//                Bukkit.getLogger().warning("added chunk: " + finalShiftedIt);
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    if(cancelled) return;
                    if(!mode.equals(Modes.NONE)) {
                        int y = getFirstNonAir(chunk);
                        y = getLastNonAir(chunk, y);

                        if (checkLocation(chunk, y)) {
                            addBiomeLocation(finalIt, currBiome);
                        } else {
                            addBadLocation(finalIt);
                        }
                    }

                    if(cancelled) return;
                    fillIterator.incrementAndGet();
                    if(completion.decrementAndGet() <3 && !completed.getAndSet(true)) {
                        fillTask = new FillTask(plugin);
                        fillTask.runTaskLaterAsynchronously(plugin,1);
                    }
                });
                cfChunk.whenComplete((chunk, throwable) -> {
                    if(cancelled) return;
                    if(completion.get() == 0 || fillIterator.get()==max.get()) {
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
            storeFile();
            super.cancel();
        }
    }

    public String name;

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
    private final AtomicLong badLocationSum = new AtomicLong(0L);

    private final ConcurrentHashMap<Biome,ConcurrentSkipListMap<Long,Long>> biomeLocations = new ConcurrentHashMap<>();

    public enum Shapes{SQUARE,CIRCLE}
    public Shapes shape;

    public enum Modes{ACCUMULATE,NEAREST,REROLL,NONE}
    public Modes mode;

    private final double weight;

    public boolean requireSkyLight, uniquePlacements, expand;

    public boolean rerollWorldGuard, rerollGriefPrevention;

    public int r, cr, cx, cz, minY, maxY;

    private final AtomicLong fillIterator = new AtomicLong(0L);
    private FillTask fillTask = null;

    public TeleportRegion(String name, Map<String, String> params) {
        this.name = name;
        String worldName = params.getOrDefault("world","world");
        Configs configs = RTP.getConfigs();
        if(!configs.worlds.checkWorldExists(worldName)) worldName = "world";
        this.world = Bukkit.getWorld(worldName);

        String shapeStr =   params.get("shape");
        String rStr =       params.get("radius");
        String modeStr =    params.get("mode");
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
            this.shape = Shapes.valueOf(shapeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.shape = Shapes.CIRCLE;
        }

        try {
            this.mode = Modes.valueOf(modeStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException exception) {
            this.mode = Modes.ACCUMULATE;
        }

        this.totalSpace = (r-cr)*(r+cr);
        if (this.shape == Shapes.SQUARE) {
            this.totalSpace = totalSpace * 4;
        } else {
            this.totalSpace = totalSpace * Math.PI;
        }

        rerollWorldGuard = configs.config.rerollWorldGuard;
        rerollGriefPrevention = configs.config.rerollGriefPrevention;
    }

    public boolean isFilling() {
        return fillTask != null && !fillTask.cancelled;
    }

    public void startFill() {
        RTP plugin = RTP.getPlugin();
        Configs configs = RTP.getConfigs();
        //clear learned data
        biomeLocations.clear();

        badLocations.clear();
        badLocationSum.set(0);

        //start filling
        fillIterator.set(0L);
        fillTask = new FillTask(plugin);
        fillTask.runTaskLaterAsynchronously(plugin,10L);

        String msg = configs.lang.getLog("fillStart", name);
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
        }
    }

    public void stopFill() {
        Configs configs = RTP.getConfigs();
        fillTask.cancel();

        String msg = configs.lang.getLog("fillCancel", name);
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
        }
        fillIterator.set(0L);
    }

    public void pauseFill() {
        Configs configs = RTP.getConfigs();
        fillTask.cancel();

        long iter = fillIterator.get();
        long remainder = iter%2500L;
        fillIterator.set(iter-remainder);

        String msg = configs.lang.getLog("fillPause", name);
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
        }
    }

    public void resumeFill() {
        RTP plugin = RTP.getPlugin();
        Configs configs = RTP.getConfigs();
        fillTask = new FillTask(plugin);
        fillTask.runTaskLaterAsynchronously(plugin,10L);

        String msg = (fillIterator.get()>0) ? configs.lang.getLog("fillResume", name) : configs.lang.getLog("fillStart", name);
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
        }
    }


    public boolean hasQueuedLocation(OfflinePlayer player) {
        return hasQueuedLocation(player.getUniqueId());
    }

    public boolean hasQueuedLocation(Player player) {
        return hasQueuedLocation(player.getUniqueId());
    }

    public boolean hasQueuedLocation(UUID uuid) {
        boolean hasPlayerLocation;
        try {
            hasPlayerLocation = perPlayerQueue.get(uuid).size() > 0;
        } catch (NullPointerException e) {
            return false;
        }
        boolean hasPublicLocation;
        try {
            hasPublicLocation = locationQueue.size()>0;
        } catch (NullPointerException e) {
            return false;
        }
        return ( hasPlayerLocation || hasPublicLocation );
    }

    public boolean hasQueuedLocation() {
        return (locationQueue.size()>0);
    }

    @Override
    public int getTotalQueueLength(OfflinePlayer player) {
        return getPublicQueueLength() + getPlayerQueueLength(player);
    }

    public int getTotalQueueLength(Player player) {
        return getPublicQueueLength() + getPlayerQueueLength(player);
    }

    public int getPublicQueueLength() {
        return (locationQueue==null) ? 0 : locationQueue.size();
    }

    public int getPlayerQueueLength(OfflinePlayer player) {
        return (getPlayerQueueLength(player.getUniqueId()));
    }

    public int getPlayerQueueLength(Player player) {
        return (getPlayerQueueLength(player.getUniqueId()));
    }

    public int getPlayerQueueLength(UUID uuid) {
        return (!perPlayerQueue.containsKey(uuid)) ? 0 : perPlayerQueue.get(uuid).size();
    }

    public void shutdown() {
        if(fillTask!=null && !fillTask.cancelled) pauseFill();
        storeFile();
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

    public Location getQueuedLocation(CommandSender sender, Player player) {
        Configs configs = RTP.getConfigs();

        Location res;
        if(perPlayerQueue.containsKey(player.getUniqueId()) && perPlayerQueue.get(player.getUniqueId()).size()>0) {
            res = perPlayerQueue.get(player.getUniqueId()).remove();
        }
        else {
            try {
                res = locationQueue.remove();
            } catch (NoSuchElementException | NullPointerException exception) {
                String msg = PAPIChecker.fillPlaceholders(player,configs.lang.getLog("noLocationsQueued"));
                SendMessage.sendMessage(sender,player,msg);
                return null;
            }
        }

        return res;
    }

    public Location getLocation(boolean urgent, CommandSender sender, Player player, Biome biome) {
        Configs configs = RTP.getConfigs();

        Location res = getRandomLocation(urgent, biome);
        if (res == null) {
            int maxAttempts = configs.config.maxAttempts;
            if(biome!=null) maxAttempts*=10;
            String msg = PAPIChecker.fillPlaceholders(player, configs.lang.getLog("unsafe", String.valueOf(maxAttempts)));
            SendMessage.sendMessage(sender, player, msg);
        }
        return res;
    }

    public Location getLocation(boolean urgent, CommandSender sender, Player player) {
        RTP plugin = RTP.getPlugin();
        Configs configs = RTP.getConfigs();

        Location res = null;

        if(perPlayerQueue.containsKey(player.getUniqueId()) && perPlayerQueue.get(player.getUniqueId()).size()>0) {
            res = perPlayerQueue.get(player.getUniqueId()).remove();
            return res;
        }

        try{
            res = locationQueue.remove();
        }
        catch (NoSuchElementException | NullPointerException exception) {
            if(sender.hasPermission("rtp.unqueued")) {
                res = getRandomLocation(urgent);
                if(res == null) {
                    int maxAttempts = configs.config.maxAttempts;
                    String msg = PAPIChecker.fillPlaceholders(player,configs.lang.getLog("unsafe",String.valueOf(maxAttempts)));
                    SendMessage.sendMessage(sender,player,msg);
                }
                RandomSelectPlayerEvent randomSelectPlayerEvent = new RandomSelectPlayerEvent(res, player);
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(randomSelectPlayerEvent));
            }
            else {
                String msg = PAPIChecker.fillPlaceholders(player,configs.lang.getLog("noLocationsQueued"));
                SendMessage.sendMessage(sender,player,msg);
            }
        }
        return res;
    }

    private void addChunks(Location location, boolean urgent) {
        Cache cache = RTP.getCache();

        ChunkSet chunkSet = new ChunkSet();
        locAssChunks.put(location,chunkSet);

        int cx = (location.getBlockX() > 0) ? location.getBlockX() / 16 : location.getBlockX() / 16 - 1;
        int cz = (location.getBlockZ() > 0) ? location.getBlockZ() / 16 : location.getBlockZ() / 16 - 1;
        int idx = 0;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        if(plugin == null) return;
        int[] oldXZ;
        int[] xz = Translate.squareLocationToXZ(0,cx,cz,0);
        int inc = 0;
        for (int i = 0; i < chunkSet.expectedSize; i++) {
            if(PaperLib.isPaper() || !urgent) {
                CompletableFuture<Chunk> cfChunk;
                cfChunk = urgent ? PaperLib.getChunkAtAsyncUrgently(Objects.requireNonNull(location.getWorld()), xz[0], xz[1], true) :
                        PaperLib.getChunkAtAsync(Objects.requireNonNull(location.getWorld()), xz[0], xz[1], true);
                chunkSet.chunks.add(idx, cfChunk);
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    chunkSet.completed.getAndAdd(1);
                    if(!chunk.isForceLoaded()) {
                        Bukkit.getScheduler().runTask(plugin, () -> chunk.setForceLoaded(true));
                        cache.forceLoadedChunks.put(new HashableChunk(chunk),0L);
                    }
                    if(isKnownBad(chunk.getX(),chunk.getZ())) return;
                    Location point = chunk.getBlock(7,0,7).getLocation();
                    Biome biome = world.getBiome(point.getBlockX(), point.getBlockZ());
                    long curveLocation = (long) ((shape.equals(Shapes.SQUARE)) ?
                                                    Translate.xzToSquareLocation(cr,chunk.getX(),chunk.getZ(),cx,cz) :
                                                    Translate.xzToCircleLocation(cr,chunk.getX(),chunk.getZ(),cx,cz));
                    Map.Entry<Long,Long> lower = biomeLocations.get(biome).lowerEntry(curveLocation);
                    if(lower!=null && curveLocation < (lower.getKey()+lower.getValue())) return;

                    int y = getFirstNonAir(chunk);
                    y= getLastNonAir(chunk,y);
                    if(checkLocation(chunk,y)) {
                        addBiomeLocation(curveLocation, biome);
                    }
                });
            }
            if (uniquePlacements) {
                addBadLocation(xz[0], xz[1]);
            }

            oldXZ = xz;
            xz = Translate.squareLocationToXZ(0,cx,cz,i+inc);
            while((xz[0] == oldXZ[0]) && (xz[1] == oldXZ[1])) {
                inc++;
                xz = Translate.squareLocationToXZ(0,cx,cz,i+inc);
            }
        }
    }

    @NotNull
    public ChunkSet getChunks(Location location) {
        return locAssChunks.getOrDefault(location,null);
    }

    public void removeChunks(Location location) {
        locAssChunks.remove(location);
    }

    public void queueLocation(Location location) {
        if(location == null) return;
        locationQueue.offer(location);
    }

    public void queueRandomLocation() {
        RTP plugin = RTP.getPlugin();
        if(!plugin.isEnabled()) return;
        Configs configs = RTP.getConfigs();

        if(locationQueue == null) {
            locationQueue = new ConcurrentLinkedQueue<>();
        }
        Integer queueLen = (Integer)configs.regions.getRegionSetting(name,"queueLen",0);
        if(locationQueue.size() >= queueLen) return;

        Location location = getRandomLocation(false);
        RandomSelectQueueEvent randomSelectQueueEvent = new RandomSelectQueueEvent(location);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(randomSelectQueueEvent));
        if(location == null) {
            return;
        }
        ChunkSet chunkSet = getChunks(location);
        LoadChunksQueueEvent loadChunksQueueEvent = new LoadChunksQueueEvent(location, Objects.requireNonNull(chunkSet).chunks);
        Bukkit.getPluginManager().callEvent(loadChunksQueueEvent);
        if(chunkSet.completed.get()>=chunkSet.expectedSize-1) {
            queueLocation(location);
        }
        else {
            AtomicBoolean added = new AtomicBoolean(false);
            for (CompletableFuture<Chunk> cfChunk : chunkSet.chunks) {
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    if (chunkSet.completed.get() >= chunkSet.expectedSize-1 && !added.getAndSet(true)) {
                        queueLocation(location);
                    }
                });
            }
        }
    }

    public void queueRandomLocation(OfflinePlayer player) {
        queueRandomLocation(player.getUniqueId());
    }

    public void queueRandomLocation(Player player) {
        queueRandomLocation(player.getUniqueId());
    }

    public void queueRandomLocation(UUID uuid) {
        Configs configs = RTP.getConfigs();

        if(locationQueue.size() > 1 && locationQueue.size() >= (Integer)configs.regions.getRegionSetting(name,"queueLen",0)) {
            perPlayerQueue.putIfAbsent(uuid,new ConcurrentLinkedQueue<>());
            perPlayerQueue.get(uuid).add(locationQueue.remove());
            return;
        }

        Location location = getRandomLocation(false);
        if(location == null) {
            return;
        }
        ChunkSet chunkSet = getChunks(location);
        if(chunkSet.completed.get()>=chunkSet.expectedSize-1) {
            perPlayerQueue.putIfAbsent(uuid,new ConcurrentLinkedQueue<>());
            perPlayerQueue.get(uuid).add(location);
        }
        else {
            AtomicBoolean added = new AtomicBoolean(false);
            for (CompletableFuture<Chunk> cfChunk : chunkSet.chunks) {
                cfChunk.whenCompleteAsync((chunk, throwable) -> {
                    if (chunkSet.completed.get() >= chunkSet.expectedSize-1 && !added.getAndSet(true)) {
                        if(Bukkit.getOfflinePlayer(uuid).isOnline()) {
                            perPlayerQueue.putIfAbsent(uuid,new ConcurrentLinkedQueue<>());
                            perPlayerQueue.get(uuid).offer(location);
                        }
                        else {
                            locationQueue.add(location);
                        }
                    }
                });
            }
        }
    }

    public void recyclePlayerLocations(OfflinePlayer player) {
        recyclePlayerLocations(player.getUniqueId());
    }

    public void recyclePlayerLocations(Player player) {
        recyclePlayerLocations(player.getUniqueId());
    }

    public void recyclePlayerLocations(UUID uuid) {
        if(!perPlayerQueue.containsKey(uuid)) return;
        while(perPlayerQueue.get(uuid).size()>0) {
            queueLocation(perPlayerQueue.get(uuid).remove());
        }
        perPlayerQueue.remove(uuid);
    }

    public void addBadLocation(int chunkX, int chunkZ) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,chunkX,chunkZ,cx,cz) :
                Translate.xzToCircleLocation(cr,chunkX,chunkZ,cx,cz);
        addBadLocation((long)location);
    }

    public void addBadLocation(Long location) {
        if(location < 0) return;
        if(location > (totalSpace+(expand?badLocationSum.get():0))) return;

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
        if((upper!=null) && (lower.getKey()+lower.getValue() >= upper.getKey())) {
            badLocations.put(lower.getKey(),lower.getValue()+upper.getValue());
            badLocations.remove(upper.getKey());
        }

        badLocationSum.incrementAndGet();
    }

    public void addBiomeLocation(int chunkX, int chunkZ, Biome biome) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,chunkX,chunkZ,cx,cz) :
                Translate.xzToCircleLocation(cr,chunkX,chunkZ,cx,cz);
        addBiomeLocation((long)location, biome);
    }

    public void addBiomeLocation(Long location, Biome biome) {
        if(location < 0) return;
        if(location > (totalSpace+(expand?badLocationSum.get():0))) return;

        biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
        ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

        Map.Entry<Long,Long> lower = map.floorEntry(location);
        Map.Entry<Long,Long> upper = map.ceilingEntry(location);

        //goal: merge adjacent values
        // if within bounds of lower entry, do nothing
        // if lower start+length meets position, add 1 to its length and use that
        if((lower!=null) && (location < lower.getKey()+lower.getValue())) {
            return;
        }
        else if((lower!=null) && (location == lower.getKey()+lower.getValue())) {
            map.put(lower.getKey(),lower.getValue()+1);
        }
        else {
            map.put(location, 1L);
        }

        lower = map.floorEntry(location);

        // if upper start meets position + length, merge its length and delete upper entry
        if((upper!=null)&&(lower.getKey()+lower.getValue() >= upper.getKey())) {
            map.put(lower.getKey(),lower.getValue()+upper.getValue());
            map.remove(upper.getKey());
        }
    }

    public void removeBiomeLocation(int chunkX, int chunkZ, Biome biome) {
        double location = (shape.equals(Shapes.SQUARE)) ?
                Translate.xzToSquareLocation(cr,chunkX,chunkZ,cx,cz) :
                Translate.xzToCircleLocation(cr,chunkX,chunkZ,cx,cz);
        removeBiomeLocation((long)location, biome);
    }

    public void removeBiomeLocation(Long location, Biome biome) {
        biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
        ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

        Map.Entry<Long,Long> lower = map.floorEntry(location);
        if(lower!=null) {
            long key = lower.getKey();
            long val = lower.getValue();
            if (location < key + val){ //if within bounds, slice the bounds to remove the location
                map.remove(lower.getKey());
                if (location > key) {
                    map.put(key, location - key);
                }
                if (location+1 < key + val) {
                    map.put(location+1, (key + val) - (location+1));
                }
            }
        }
    }

    private long select() {
        double space = totalSpace;
        if((!expand) && mode.equals(Modes.ACCUMULATE)) space -= badLocationSum.get();
        double res = (space) * Math.pow(ThreadLocalRandom.current().nextDouble(),weight);
        return (long)res;
    }

    public Location getRandomLocation(boolean urgent,Biome biome) {
        Configs configs = RTP.getConfigs();
        Cache cache = RTP.getCache();

        long totalTimeStart = System.nanoTime();
        Location res = new Location(world,0,this.maxY,0);

        double selectTime = 0D;
        double chunkTime = 0D;
        double yTime = 0D;

        int numAttempts = 0;
        int maxAttempts = configs.config.maxAttempts;
        if(biome!=null) maxAttempts = maxAttempts*10;
        boolean goodLocation = false;
        while(numAttempts < maxAttempts && !goodLocation) {
            numAttempts++;
            long start = System.nanoTime();
            long location = select();

            //if biome is available, try it
            if(biome != null && biomeLocations.containsKey(biome) && biomeLocations.get(biome).size()>0) {
                //get nearest spot according to biome list
                ConcurrentSkipListMap<Long,Long> map = biomeLocations.get(biome);
                Map.Entry<Long, Long> lower = map.floorEntry(location);
                Map.Entry<Long, Long> upper = map.ceilingEntry(location);

                //select top or bottom
                Map.Entry<Long, Long> idx = lower;
                if (upper != null) {
                    if (lower == null) {
                        idx = upper;
                    } else {
                        if (!upper.getKey().equals(lower.getKey())) {
                            if (location >= lower.getKey() + lower.getValue()) {
                                idx = ThreadLocalRandom.current().nextBoolean() ? upper : lower;
                            }
                        }
                    }
                }

                long temp = ThreadLocalRandom.current().nextLong(idx.getValue());
                long key = idx.getKey();
                location = key + temp;

                if (isKnownBad(location)) {
                    removeBiomeLocation(location, biome);
                    numAttempts--;
                    continue;
                }
            }
            else {
                switch (mode) {
                    case ACCUMULATE: {
                        Map.Entry<Long, Long> idx = badLocations.firstEntry();
                        while ((idx != null) && (location >= idx.getKey() || isKnownBad(location))) {
                            location += idx.getValue();
                            idx = badLocations.ceilingEntry(idx.getKey() + idx.getValue());
                        }
                    }
                    case NEAREST: {
                        if(expand) location = location + ThreadLocalRandom.current().nextLong(badLocationSum.get());
                        ConcurrentSkipListMap<Long,Long> map = badLocations;
                        Map.Entry<Long, Long> check = map.floorEntry(location);

                        if(     (check!=null)
                                && (location >= check.getKey())
                                && (location < (check.getKey()+check.getValue()))) {
                            Map.Entry<Long, Long> lower = map.floorEntry(check.getKey()-1);
                            Map.Entry<Long, Long> upper = map.ceilingEntry(check.getKey()+check.getValue());

                            if(upper == null) {
                                if(lower == null) {
                                    long cutout = check.getValue();
                                    location = ThreadLocalRandom.current().nextLong((long) (totalSpace - cutout));
                                    if (location >= check.getKey()) location += check.getValue();
                                }
                                else {
                                    long len = check.getKey() - (lower.getKey()+lower.getValue());
                                    location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                                    location += lower.getKey() + lower.getValue();
                                }
                            }
                            else if(lower == null) {
                                long len = upper.getKey() - (check.getKey()+check.getValue());
                                location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                                location += check.getKey() + check.getValue();
                            }
                            else {
                                long d1 = (upper.getKey()-location);
                                long d2 = location - (lower.getKey()+lower.getValue());
                                if(d2>d1) {
                                    long len = check.getKey() - (lower.getKey()+lower.getValue());
                                    location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                                    location += lower.getKey() + lower.getValue();
                                }
                                else {
                                    long len = upper.getKey() - (check.getKey()+check.getValue());
                                    location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                                    location += check.getKey() + check.getValue();
                                }
                            }
                        }
                    }
                    case REROLL: {
                        if(expand) location = location + ThreadLocalRandom.current().nextLong(badLocationSum.get());
                        Map.Entry<Long, Long> check = badLocations.floorEntry(location);
                        if(     (check!=null)
                                && (location > check.getKey())
                                && (location < check.getKey()+check.getValue())) {
                            continue;
                        }
                    }
                    default: {
                        if(expand) location = (long) (location + ((badLocationSum.get() * location) / totalSpace));
                    }
                }
            }

            int[] xzChunk = shape.equals(Shapes.SQUARE) ?
                    (Translate.squareLocationToXZ(cr, cx, cz, location)) :
                    (Translate.circleLocationToXZ(cr, cx, cz, location));

            Biome currBiome = world.getBiome(xzChunk[0]*16+7, xzChunk[1]*16+7);
            if(biome!=null && !currBiome.equals(biome)) {
                removeBiomeLocation(location,biome);
                continue;
            }

            if(configs.config.biomeWhitelist != configs.config.biomes.contains(currBiome)) {
                addBadLocation(location);
                removeBiomeLocation(location,currBiome);
                continue;
            }

            long stop = System.nanoTime();
            selectTime += (stop-start);

            start = System.nanoTime();
            CompletableFuture<Chunk> cfChunk = (urgent) ?
                    PaperLib.getChunkAtAsyncUrgently(world,xzChunk[0],xzChunk[1],true) :
                    PaperLib.getChunkAtAsync(world,xzChunk[0],xzChunk[1],true);
            HashableChunk hashableChunk = new HashableChunk(world,xzChunk[0],xzChunk[1]);
            currChunks.put(hashableChunk,cfChunk);

            Chunk chunk;
            try {
                chunk = cfChunk.get(5,TimeUnit.SECONDS); //wait on chunk load/gen
            } catch (ExecutionException e) {
                e.printStackTrace();
                return null;
            } catch (InterruptedException | CancellationException | StackOverflowError | TimeoutException e) {
                return null;
            }
            res = chunk.getBlock(7,0,7).getLocation();
            currChunks.remove(hashableChunk);

            stop = System.nanoTime();
            chunkTime += (stop-start);

            start = System.nanoTime();
            int y = this.getFirstNonAir(chunk);
            y = this.getLastNonAir(chunk,y);
            res.setY(y);
            stop = System.nanoTime();
            goodLocation = checkLocation(chunk,y);
            yTime += (stop - start);

            if(goodLocation) addBiomeLocation(location,currBiome);
            else if(!mode.equals(Modes.NONE)) {
                addBadLocation(location);
                removeBiomeLocation(location,currBiome);
            }
        }

        res.setY(res.getBlockY()+1);
        res.setX(res.getBlockX()+0.5);
        res.setZ(res.getBlockZ()+0.5);

        if(numAttempts >= maxAttempts) {
            return null;
        }

        cache.numTeleportAttempts.put(res, numAttempts);
        addChunks(res, urgent);

//        Bukkit.getLogger().warning(ChatColor.AQUA + "TOTAL TIME SPENT ON SELECTION FIXING: " + (selectTime)/1000000 + "ms");
//        Bukkit.getLogger().warning(ChatColor.LIGHT_PURPLE + "TOTAL TIME SPENT WAITING ON CHUNKS: " + (chunkTime)/1000000 + "ms");
//        Bukkit.getLogger().warning(ChatColor.GREEN + "TOTAL TIME SPENT ON PLACEMENT VALIDATION: " + (yTime)/1000000 + "ms");
//        Bukkit.getLogger().warning(ChatColor.WHITE + "TOTAL TIME IN SELECTION FUNCTION: " + (System.nanoTime()-totalTimeStart)/1000000 + "ms");

        return res;
    }

    public Location getRandomLocation(boolean urgent) {
        return getRandomLocation(urgent,null);
    }

    public int getFirstNonAir(Chunk chunk) {
        int i = minY;
        //iterate over a good distance to reduce thin floors
        int increment = (maxY-minY)/12;
        if(increment<=0) increment = 1;
        for(; i <= maxY; i+=increment) {
            Block block = chunk.getBlock(7,i,7);
            if(block.isLiquid() || block.getType().isSolid()) {
                break;
            }
            if(i >= this.maxY-increment) return this.maxY;
        }
        return i;
    }

    public int getLastNonAir(Chunk chunk, int start) {
        int oldY = start;
        int minY = start;
        int maxY = this.maxY;

        //iterate over a larger distance first, then fine-tune
        for(int it_length = (maxY-minY)/16; it_length > 0; it_length = it_length/2) {
            int i = minY;
            for(; i <= maxY; i+=it_length) {
                int skyLight = 15;
                Block block1 = chunk.getBlock(7,i,7);
                Block block2 = chunk.getBlock(7,i+1,7);
                if(requireSkyLight) skyLight = block2.getLightFromSky();


                    if(!(block1.isLiquid() || block1.getType().isSolid())
                        && !(block2.isLiquid() || block2.getType().isSolid())
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
            Block block1 = chunk.getBlock(7,i,7);
            Block block2 = chunk.getBlock(7,i+1,7);
            if(requireSkyLight) skyLight = block2.getLightFromSky();

            if(!(block1.isLiquid() || block1.getType().isSolid())
                    && !(block2.isLiquid() || block2.getType().isSolid())
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
        if(lower!=null) {
            return (location < (lower.getKey() + lower.getValue()));
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
        return !(location > (totalSpace + (expand ? badLocationSum.get() : 0))) && (location >= 0);
    }

    public boolean checkLocation(Chunk chunk, int y) {
        Configs configs = RTP.getConfigs();
        if(y >= maxY) return false;
//        if(!material.isSolid()) return false;
        Material material1 = chunk.getBlock(7,y,7).getType();
        Material material2 = chunk.getBlock(7,y+1,7).getType();
        if(material2.isSolid()) return false;
        if(configs.config.unsafeBlocks.contains(material1)) return false;
        if(configs.config.unsafeBlocks.contains(material2)) return false;
        Location location = new Location(world, chunk.getX()*16+7,y, chunk.getZ()*16+7);
        if(configs.config.rerollWorldGuard && WorldGuardChecker.isInClaim(location)) return false;
        if(configs.config.rerollGriefPrevention && GriefPreventionChecker.isInClaim(location)) return false;
        if(configs.config.rerollTownyAdvanced && TownyAdvancedChecker.isInClaim(location)) return false;
        if(configs.config.rerollHuskTowns && HuskTownsChecker.isInClaim(location)) return false;
        if(configs.config.rerollFactions && FactionsChecker.isInClaim(location)) return false;
        if(configs.config.rerollGriefDefender && GriefDefenderChecker.isInClaim(location)) return false;
        if(configs.config.rerollLands && LandsChecker.isInClaim(location)) return false;
        for(MethodHandle methodHandle : configs.locationChecks) {
            try {
                if((boolean)methodHandle.invokeExact(location)) return false;
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }

        int safetyRadius = configs.config.safetyRadius;
        Set<Material> unsafeBlocks = configs.config.unsafeBlocks;
        for(int i = 7-safetyRadius; i <= 7+safetyRadius; i++) {
            for(int j = 7-safetyRadius; j <= 7+safetyRadius; j++) {
                if(unsafeBlocks.contains(chunk.getBlock(7,y,7).getType())) return false;
                if(unsafeBlocks.contains(chunk.getBlock(7,y+1,7).getType())) return false;
            }
        }
        return true;
    }

    public void loadFile() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("RTP");
        File f = new File(Objects.requireNonNull(plugin).getDataFolder(), "regions"+File.separatorChar+name+".dat");
        if(!f.exists()) return;

        Scanner scanner;
        String line;
        try {
            scanner = new Scanner(
                    new File(f.getAbsolutePath()));
            String name = scanner.nextLine().substring(5);
            Shapes shape = Shapes.valueOf(scanner.nextLine().substring(6));
            String worldName = scanner.nextLine().substring(6);
            int cr = Integer.parseInt(scanner.nextLine().substring(3));
            int cx = Integer.parseInt(scanner.nextLine().substring(3));
            int cz = Integer.parseInt(scanner.nextLine().substring(3));
            int minY = Integer.parseInt(scanner.nextLine().substring(5));
            int maxY = Integer.parseInt(scanner.nextLine().substring(5));
            boolean requireSkyLight = Boolean.parseBoolean(scanner.nextLine().substring(16));
            boolean uniquePlacements = Boolean.parseBoolean(scanner.nextLine().substring(17));

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

            if(!scanner.hasNextLine()) {
                scanner.close();
                return;
            }
            line = scanner.nextLine();
            if(line.startsWith("iter")) {
                fillIterator.set(Long.parseLong(line.substring(5)));
            }

            while (scanner.hasNextLine() && !line.startsWith("badLocations")){
                line = scanner.nextLine();
            }
            if(!scanner.hasNextLine()) {
                scanner.close();
                return;
            }

            while(scanner.hasNextLine()) {
                line = scanner.nextLine();
                if (!line.startsWith("  -")) break;
                String val = line.substring(3);
                int delimiterIdx = val.indexOf(',');
                Long location = Long.parseLong(val.substring(0, delimiterIdx));
                Long length = Long.parseLong(val.substring(delimiterIdx + 1));
                Map.Entry<Long, Long> lower = badLocations.floorEntry(location);
                if (lower != null && location == lower.getKey() + lower.getValue()) {
                    badLocations.put(lower.getKey(), lower.getValue() + length);
                    length += lower.getValue();
                } else badLocations.put(location, length);
                badLocationSum.addAndGet(length);
            }

            while(scanner.hasNextLine()) {
                line = scanner.nextLine();
                if(!line.startsWith("  ")) break;
                if(line.startsWith("    -")) continue;
                Biome biome = Biome.valueOf(line.substring(2,line.length()-1));
                biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
                ConcurrentSkipListMap<Long,Long> map = biomeLocations.get(biome);
                while(scanner.hasNextLine()) {
                    line = scanner.nextLine();
                    if(!line.startsWith("    -")) {
                        if(line.startsWith("  ")) {
                            biome = Biome.valueOf(line.substring(2,line.length()-1));
                            biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
                            map = biomeLocations.get(biome);
                        }
                        else break;
                        continue;
                    }
                    String val = line.substring(5);
                    int delimiterIdx = val.indexOf(',');
                    Long start = Long.parseLong(val.substring(0,delimiterIdx));
                    Long length = Long.parseLong(val.substring(delimiterIdx+1));

                    Map.Entry<Long,Long> lower = map.floorEntry(start);
                    if(lower!=null && start == lower.getKey()+lower.getValue()) {
                        map.put(lower.getKey(),lower.getValue()+length);
                    }
                    else map.put(start,length);
                }
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    public void storeFile() {
        ArrayList<String> linesArray = new ArrayList<>();
        linesArray.add("name:"+name);
        linesArray.add("shape:"+shape.name());
        linesArray.add("world:"+world.getName());
        linesArray.add("cr:"+cr);
        linesArray.add("cx:"+cx);
        linesArray.add("cz:"+cz);
        linesArray.add("minY:"+minY);
        linesArray.add("maxY:"+maxY);
        linesArray.add("requireSkyLight:"+requireSkyLight);
        linesArray.add("uniquePlacements:"+uniquePlacements);
        linesArray.add("iter:"+fillIterator.get());
        linesArray.add("badLocations:");
        for(Map.Entry<Long,Long> entry : badLocations.entrySet()) {
            linesArray.add("  -" + entry.getKey() + "," + entry.getValue());
        }

        linesArray.add("biomes:");
        for(Map.Entry<Biome, ConcurrentSkipListMap<Long,Long>> biomeEntry : biomeLocations.entrySet()) {
            linesArray.add("  " + biomeEntry.getKey().name() +":");
            for(Map.Entry<Long,Long> entry : biomeEntry.getValue().entrySet()) {
                linesArray.add("    -" + entry.getKey() + "," + entry.getValue());
            }
        }

        Plugin plugin = RTP.getPlugin();
        File f = new File(plugin.getDataFolder(), "regions"+File.separatorChar+name+".dat");
        File parentDir = f.getParentFile();
        if(!parentDir.exists()) {
            boolean mkdirs = parentDir.mkdirs();
            if(!mkdirs) throw new DirectoryIteratorException(new IOException("[rtp] failed to create regions directory"));
        }
        if(!f.exists()) {
            try {
                boolean newFile = f.createNewFile();
                if(!newFile) throw new FileAlreadyExistsException("[rtp] failed to create " + f.getName());
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
        }
    }
}
