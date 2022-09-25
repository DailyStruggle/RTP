package io.github.dailystruggle.rtp_glide.Listeners;

import io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import io.github.dailystruggle.rtp_glide.customEvents.PlayerGlideEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
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
    public void onRandomTeleport(PostTeleportEvent event) {
        RTPWorld rtpWorld = event.getDoTeleport().location().world();
        World world = ((BukkitRTPWorld)rtpWorld).world();
        Player player = ((BukkitRTPPlayer)event.getDoTeleport().player()).player();
        if(world.getEnvironment().equals(World.Environment.NETHER))
            return;
        Bukkit.getScheduler().runTask(plugin,new SetupGlide(player, Configs));
    }
}
