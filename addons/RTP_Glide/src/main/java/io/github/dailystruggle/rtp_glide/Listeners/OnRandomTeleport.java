package io.github.dailystruggle.rtp_glide.Listeners;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import io.github.dailystruggle.rtp_glide.customEvents.PlayerGlideEvent;
import leafcraft.rtp.API.customEvents.RandomTeleportEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

public final class OnRandomTeleport implements Listener {
    private final RTP_Glide plugin;
    private final Configs Configs;

    public OnRandomTeleport(RTP_Glide plugin, Configs Configs) {
        this.plugin = plugin;
        this.Configs = Configs;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRandomTeleport(RandomTeleportEvent event) {
        if(Objects.requireNonNull(event.getTo().getWorld())
                .getEnvironment().equals(World.Environment.NETHER))
            return;
        new SetupGlide(event.getPlayer(), Configs).runTask(plugin);
    }
}
