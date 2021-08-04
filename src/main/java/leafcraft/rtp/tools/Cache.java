package leafcraft.rtp.tools;

import io.papermc.lib.PaperLib;
import javafx.util.Pair;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class Cache {
    private static final Integer precision = 100000;
    private static final Integer modulus = (int)(Math.PI*precision); //314159.265358979323846
    private static final Double[] sin = new Double[modulus]; //<1MB
    static {
        for(int i = 0; i<sin.length; i++) {
            sin[i] = Math.sin(i*Math.PI/(sin.length)); //sin(i/sz)
        }
    }
    // Private function for table lookup
    private static Double sinLookup(Double a) {
        a = a%sin.length;
        return a>=0 ? sin[(int)(a/(sin.length))] : -sin[(int)(a/(sin.length))];
    }

    // These are your working functions:
    public static Double sin(Double a) {
        return sinLookup(a);
    }
    public static Double cos(Double a) {
        return sinLookup(a+(Math.PI/2));
    }

    //Bukkit task list in case of cancellation
    private Map<String, BukkitTask> doTeleports = new ConcurrentHashMap<>();
    private Map<String, BukkitTask> loadChunks = new ConcurrentHashMap<>();

    //pre-teleport location info for checking distance from command location
    private Map<String, Location> playerFromLocations = new ConcurrentHashMap<>();

    //post-teleport chunks set up so far
    public Map<String,List<CompletableFuture<Chunk>>> playerAssChunks = new ConcurrentHashMap<>();

    //info on number of attempts on last rtp command
    private Map<Location, Integer> numTeleportAttempts = new ConcurrentHashMap<>();

    //store for teleport command cooldown
    private Map<String,Long> lastTeleportTime = new ConcurrentHashMap<>();

    private ConcurrentLinkedQueue<Pair<Location,BukkitTask>> locationQueue = new ConcurrentLinkedQueue<>();

    public void addLoadChunks(Player player, BukkitTask loadChunks) {
        if(this.loadChunks.containsKey(player)) {
            this.loadChunks.get(player).cancel();
            for(CompletableFuture<Chunk> chunk : this.playerAssChunks.get(player.getName())) {
                chunk.cancel(true);
            }
        }
        this.loadChunks.put(player.getName(),loadChunks);
    }

    public void addDoTeleport(Player player, BukkitTask doTeleport) {
        if(this.doTeleports.containsKey(player)) {
            this.doTeleports.get(player).cancel();
        }
        this.doTeleports.put(player.getName(),doTeleport);
    }

    public void setNumTeleportAttempts(Location location, Integer numTeleportAttempts) {
        if(numTeleportAttempts>0)
            this.numTeleportAttempts.put(location,numTeleportAttempts);
        else this.numTeleportAttempts.remove(location);
    }

    public Integer getNumTeleportAttempts(Location location) {
        if(this.numTeleportAttempts.containsKey(location))
            return this.numTeleportAttempts.get(location);
        else return Integer.valueOf(0);
    }

    public void setLastTeleportTime(Player player, Long currTime) {
        if(currTime>0)
            this.lastTeleportTime.put(player.getName(),currTime);
        else this.lastTeleportTime.remove(currTime);
    }

    public Long getLastTeleportTime(Player player) {
        if(this.lastTeleportTime.containsKey(player.getName()))
            return this.lastTeleportTime.get(player.getName());
        else return Long.valueOf(0);
    }

    public void setPlayerFromLocation(Player player, Location location) {
        Location newLocation = new Location(location.getWorld(),location.getX(),location.getY(),location.getZ());
        this.playerFromLocations.put(player.getName(),newLocation);
    }

    public Location getPlayerFromLocation(Player player) {
        if(this.playerFromLocations.containsKey(player.getName()))
            return this.playerFromLocations.get(player.getName());
        return null;
    }

    public void removePlayer(Player player) {
        this.playerFromLocations.remove(player.getName());

        if(this.playerAssChunks.containsKey(player.getName())) {
            for (CompletableFuture<Chunk> chunk : this.playerAssChunks.get(player.getName())) {
                chunk.cancel(true);
            }
        }
        this.playerAssChunks.remove(player.getName());

        if(this.loadChunks.containsKey(player.getName())) this.loadChunks.get(player.getName()).cancel();
        this.loadChunks.remove(player.getName());

        if(this.doTeleports.containsKey(player.getName())) this.doTeleports.get(player.getName()).cancel();
        this.doTeleports.remove(player.getName());
    }

    public void shutdown() {
        for(Map.Entry<String,BukkitTask> entry : doTeleports.entrySet()) {
            entry.getValue().cancel();
        }
    }
}
