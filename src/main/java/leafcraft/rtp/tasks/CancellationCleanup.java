package leafcraft.rtp.tasks;

import leafcraft.rtp.tools.Cache;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class CancellationCleanup extends BukkitRunnable {
    Player player;
    Cache cache;

    public CancellationCleanup(Player player, Cache cache) {
        this.player = player;
        this.cache = cache;
    }

    @Override
    public void run() {
        Location location= cache.todoTP.get(player.getName());
        cache.locationQueue.get(location.getWorld()).offer(location);
        cache.todoTP.remove(player.getName());

        //redundant but make sure this happens
        if(cache.loadChunks.containsKey(player.getName())) {
            cache.loadChunks.get(player.getName()).cancel();
            cache.loadChunks.remove(player.getName());
        }
        if(cache.doTeleports.containsKey(player.getName())) {
            cache.doTeleports.get(player.getName()).cancel();
            cache.doTeleports.remove(player.getName());
        }


        cache.playerFromLocations.remove(player.getName());
    }
}
