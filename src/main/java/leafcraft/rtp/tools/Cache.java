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
import java.util.logging.Level;

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

    //store for teleport command cooldown
    public ConcurrentHashMap<String,Long> lastTeleportTime = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String,ConcurrentLinkedQueue<Location>> locationQueue = new ConcurrentHashMap<>();

    public void shutdown() {
        Bukkit.getLogger().log(Level.INFO, "[rtp] cancelling teleportations");
        for(ConcurrentHashMap.Entry<String,BukkitTask> entry : loadChunks.entrySet()) {
            entry.getValue().cancel();
        }
        loadChunks.clear();

        for(ConcurrentHashMap.Entry<String,BukkitTask> entry : doTeleports.entrySet()) {
            entry.getValue().cancel();
        }
        doTeleports.clear();

        Bukkit.getLogger().log(Level.INFO, "[rtp] cancelling chunk loads");
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

        List<CompletableFuture<Chunk>> chunks = new ArrayList<>();
        int vd = Bukkit.getViewDistance();
        int cx = location.getBlockX()/16;
        int cz = location.getBlockZ()/16;
        int area = (int)(vd*vd*Math.PI+0.5d);
        for(int i = 0; i < area; i++) {
            long[] xz = RandomSelect.circleLocationToXZ(0,cx,cz,BigDecimal.valueOf(i));
            chunks.add(PaperLib.getChunkAtAsync(location.getWorld(),(int)xz[0],(int)xz[1]));
            HashableChunk hc = new HashableChunk(location.getWorld().getName(),(int)xz[0],(int)xz[1]);
            keepChunks.putIfAbsent(hc,Long.valueOf(0));
            keepChunks.compute(hc, (k,v) -> v + 1);
        }
        locAssChunks.put(location,chunks);
    }


    public Location getRandomLocation(World world, boolean urgent) {
        Location res;
        try{
            res = locationQueue.get(world.getName()).remove();
        }
        catch (NoSuchElementException exception) {
            res = config.getRandomLocation(world, urgent);
        }
        catch (NullPointerException exception) {
            res = config.getRandomLocation(world, urgent);
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
}
