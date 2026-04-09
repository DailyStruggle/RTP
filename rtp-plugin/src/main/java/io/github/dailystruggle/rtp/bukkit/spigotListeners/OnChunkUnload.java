package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

public class OnChunkUnload implements Listener {
  @EventHandler(priority = EventPriority.NORMAL)
  public void onChunkUnload(ChunkUnloadEvent event) {
    if (!(event instanceof Cancellable)) return;

    Chunk chunk = event.getChunk();
    World world = chunk.getWorld();
    int x = chunk.getX();
    int z = chunk.getZ();

    RTPWorld<?> rtpWorld = RTP.serverAccessor.getRTPWorld(world.getUID());
    if (rtpWorld == null) return;

    if (rtpWorld.isForceLoaded(x, z)) {
      ((Cancellable) event).setCancelled(true);
    }
  }
}
