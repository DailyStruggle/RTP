package io.github.dailystruggle.rtp_glide.Listeners;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent;
import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Listener for post-teleport events to trigger glide */
public final class OnRandomTeleport implements Listener {
  private final RTP_Glide plugin;
  private final Configs Configs;

  /**
   * Constructor for OnRandomTeleport listener
   *
   * @param plugin the plugin instance
   * @param Configs the configurations instance
   */
  public OnRandomTeleport(RTP_Glide plugin, Configs Configs) {
    this.plugin = plugin;
    this.Configs = Configs;
  }

  /**
   * EventHandler for PostTeleportEvent
   *
   * @param event the event
   */
  @EventHandler(priority = EventPriority.NORMAL)
  public void onRandomTeleport(PostTeleportEvent event) {
    RTPWorld rtpWorld = event.getDoTeleport().region().getWorld();
    World world = Bukkit.getWorld(rtpWorld.id());
    Player player = Bukkit.getPlayer(event.getDoTeleport().player().uuid());
    if (world == null || world.getEnvironment().equals(World.Environment.NETHER)) return;
    Bukkit.getScheduler().runTask(plugin, new SetupGlide(player, Configs));
  }
}
