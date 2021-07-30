package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class OnPlayerMove implements Listener {
    private Config config;
    private Cache cache;

    public OnPlayerMove(Config config, Cache cache) {
        this.config = config;
        this.cache = cache;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location location = this.cache.getPlayerFromLocation(event.getPlayer());
        if(location == null) {
            return;
        }
        if(location.distance(event.getTo()) < (Integer)this.config.getConfigValue("cancelDistance",2)) return;

        this.cache.removePlayer(event.getPlayer());
        event.getPlayer().sendMessage(this.config.getLog("teleportCancel"));
    }

}
