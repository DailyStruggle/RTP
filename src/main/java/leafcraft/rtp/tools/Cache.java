package leafcraft.rtp.tools;

import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Cache {
    private static final Integer precision = 100;
    private static final Integer modulus = 360*precision;
    private static final Double[] sin = new Double[modulus];
    static {
        for(int i = 0; i<sin.length; i++) {
            sin[i] = Math.sin(i*Math.PI)/(precision*100);
        }
    }
    // Private function for table lookup
    private static Double sinLookup(int a) {
        return a>=0 ? sin[a%(modulus)] : -sin[-a%(modulus)];
    }

    // These are your working functions:
    public static Double sin(Double a) {
        return sinLookup((int)(a * precision + 0.5f));
    }
    public static Double cos(Double a) {
        return sinLookup((int)((a+90f) * precision + 0.5f));
    }

    //Bukkit task list in case of cancellation
    private Map<String, BukkitTask> doTeleports = new HashMap<>();
    private Map<String, BukkitTask> loadChunks = new HashMap<>();

    //pre-teleport location info for checking distance from command location
    private Map<String, Location> playerFromLocations = new HashMap<>();

    //post-teleport chunks set up so far
    public Map<String,List<CompletableFuture<Chunk>>> playerAssChunks = new HashMap<>();

    //info on number of attempts on last rtp command
    private Map<Location, Integer> numTeleportAttempts = new HashMap<>();

    //teleport command time
    private Map<String,Long> lastTeleportTime = new HashMap<>();

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
