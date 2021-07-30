package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class DoTeleport extends BukkitRunnable {
    private Config config;
    private Player player;
    private Location location;
    private Cache cache;

    public DoTeleport(Config config, Player player, Location location, Cache cache) {
        this.config = config;
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        this.player.teleport(location);
        this.player.sendMessage(this.config.getLog("teleportMessage", this.cache.getNumTeleportAttempts(location).toString()));
        this.cache.removePlayer(this.player);
    }
}
