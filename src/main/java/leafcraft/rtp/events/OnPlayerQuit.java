package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class OnPlayerQuit implements Listener {
    private Cache cache;

    public OnPlayerQuit(Cache cache) {
        this.cache = cache;
    }

    @EventHandler
    public void OnPlayerQuit(PlayerQuitEvent event) {
        this.cache.removePlayer(event.getPlayer());
    }
}
