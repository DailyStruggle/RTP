package leafcraft.rtp.tools;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.tools.selection.RandomSelect;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

public class Cache {
    Config config;
    public Cache(Config config) {this.config = config;}

    //keyed table of which chunks to keep alive, for quick checking
    // key: chunk coordinate
    // value: number of usages. if it gets to 0, the pair should be removed
    public ConcurrentHashMap<HashableChunk,Long> keepChunks = new ConcurrentHashMap<>();

    //if we needed to force load a chunk to prevent unload, undo that on teleport.
    public ConcurrentHashMap<HashableChunk,Long> forceLoadedChunks = new ConcurrentHashMap<>();


    //table of which players are teleporting to what location
    // key: player name
    // value: location they're going to, to be re-added to the queue on cancellation
    public ConcurrentHashMap<String,Location> todoTP = new ConcurrentHashMap<>();

    //Bukkit task list in case of cancellation
    public ConcurrentHashMap<String, BukkitTask> doTeleports = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, BukkitTask> loadChunks = new ConcurrentHashMap<>();

    //pre-teleport location info for checking distance from command location
    public ConcurrentHashMap<String, Location> playerFromLocations = new ConcurrentHashMap<>();

    //post-teleport chunks set up so far
    public ConcurrentHashMap<Location,List<CompletableFuture<Chunk>>> locAssChunks = new ConcurrentHashMap<>();

    //info on number of attempts on last rtp command
    public ConcurrentHashMap<Location, Integer> numTeleportAttempts = new ConcurrentHashMap<>();

    //store teleport command cooldown
    public ConcurrentHashMap<String,Long> lastTeleportTime = new ConcurrentHashMap<>();

    //store locations
    public ConcurrentHashMap<String,ConcurrentLinkedQueue<Location>> locationQueue = new ConcurrentHashMap<>();

    //Collection of bad sector detections, populated by rerolls to prevent future rerolls at those locations
    // key: the starting point along the curve
    // value: length of bad segment
    public ConcurrentSkipListMap<Long,Long> badChunks = new ConcurrentSkipListMap<>();

    public void shutdown() {
        for(ConcurrentHashMap.Entry<String,BukkitTask> entry : loadChunks.entrySet()) {
            entry.getValue().cancel();
        }
        loadChunks.clear();

        for(ConcurrentHashMap.Entry<String,BukkitTask> entry : doTeleports.entrySet()) {
            entry.getValue().cancel();
        }
        doTeleports.clear();

        for(Map.Entry<Location,List<CompletableFuture<Chunk>>> entry : this.locAssChunks.entrySet()) {
            for(CompletableFuture<Chunk> cfChunk : entry.getValue()) {
                cfChunk.cancel(true);
            }
        }
        locAssChunks.clear();

        keepChunks.clear();

        for(Map.Entry<HashableChunk,Long> entry : forceLoadedChunks.entrySet()) {
            entry.getKey().getChunk().setForceLoaded(false);
        }
        forceLoadedChunks.clear();
    }

    //add location and adjacent chunks to cache
    public void addLocation(Location location) {
        if(!locationQueue.containsKey(location.getWorld().getName())) {
            locationQueue.put(location.getWorld().getName(),new ConcurrentLinkedQueue<>());
        }
        locationQueue.get(location.getWorld().getName()).offer(location);

        if(locAssChunks.containsKey(location)) return;

        addChunks(location);
    }

    public void addChunks(Location location) {
        List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
        int vd = Bukkit.getViewDistance();
        int cx = location.getBlockX()/16;
        int cz = location.getBlockZ()/16;
        int area = (int)(vd*vd*4+0.5d);
        for(int i = 0; i < area; i++) {
            int[] xz = RandomSelect.squareLocationToXZ(0,cx,cz,i);
            chunks.add(PaperLib.getChunkAtAsync(location.getWorld(),xz[0],xz[1]));
            HashableChunk hc = new HashableChunk(location.getWorld(),xz[0],xz[1]);
            keepChunks.putIfAbsent(hc,Long.valueOf(0));
            keepChunks.compute(hc, (k,v) -> v + 1);
        }
        locAssChunks.put(location,chunks);
    }


    public Location getRandomLocation(RandomSelectParams rsParams, boolean urgent) {
        if(rsParams.hasCustomValues) return config.getRandomLocation(rsParams,urgent);

        Location res;


        try{
            res = locationQueue.get(rsParams.world.getName()).remove();
        }
        catch (NoSuchElementException exception) {
            res = config.getRandomLocation(rsParams, urgent);
        }
        catch (NullPointerException exception) {
            res = config.getRandomLocation(rsParams, urgent);
        }
        return res;
    }

    public void resetQueues() {
        for(Map.Entry<String,ConcurrentLinkedQueue<Location>> entry : locationQueue.entrySet()) {
            entry.getValue().clear();
        }
        for(Map.Entry<Location,List<CompletableFuture<Chunk>>> entry : locAssChunks.entrySet()) {
            for(CompletableFuture<Chunk> chunk : entry.getValue()) {
                chunk.cancel(true);
            }
            locAssChunks.remove(entry);
        }
    }

    public void addBadChunk(int x, int z) {

    }
}
