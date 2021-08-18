package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tasks.DoTeleport;
import leafcraft.rtp.tasks.LoadChunks;
import leafcraft.rtp.tasks.SetupTeleport;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class OnPlayerJoin implements Listener {
    private RTP plugin;
    private Configs configs;
    private Cache cache;

    public OnPlayerJoin(RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;
    }

    @EventHandler
    public void OnPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        //if has this perm, go again
        if (player.hasPermission("rtp.onEvent.join")) {
            //skip if already going
            SetupTeleport setupTeleport = (SetupTeleport) this.cache.setupTeleports.get(player.getUniqueId());
            LoadChunks loadChunks = (LoadChunks) this.cache.loadChunks.get(player.getUniqueId());
            DoTeleport doTeleport = (DoTeleport) this.cache.doTeleports.get(player.getUniqueId());
            if(setupTeleport!=null && setupTeleport.isNoDelay()) return;
            if(loadChunks!=null && loadChunks.isNoDelay()) return;
            if(doTeleport!=null && doTeleport.isNoDelay()) return;

            //run command as console
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "rtp player:"+player.getName()+" world:"+player.getWorld().getName());
        }
    }
}
