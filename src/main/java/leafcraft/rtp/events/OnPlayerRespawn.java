package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;

//get queued location
public class OnPlayerRespawn implements Listener {
    private RTP plugin;
    private Configs configs;
    private Cache cache;

    public OnPlayerRespawn(RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler
    public void OnPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("rtp.onEvent.respawn")) {
            RandomSelectParams rsParams = new RandomSelectParams(player.getWorld(), new HashMap<>(), configs);
            SetupTeleport setupTeleport = new SetupTeleport(plugin,Bukkit.getConsoleSender(),player,configs,cache,rsParams);
            setupTeleport.runTaskAsynchronously(plugin);
            cache.setupTeleports.put(player.getUniqueId(),setupTeleport);
        }
    }
}
