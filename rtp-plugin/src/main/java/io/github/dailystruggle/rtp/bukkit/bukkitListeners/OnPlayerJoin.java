package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class OnPlayerJoin implements Listener {
  @EventHandler(priority = EventPriority.HIGH)
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();

    if (RTP.getInstance().fileBinStore != null && !RTP.getInstance().latestTeleportData.containsKey(uuid)) {
      TeleportData restored = RTP.getInstance().fileBinStore.load(uuid);
      if (restored != null) {
        RTP.getInstance().latestTeleportData.put(uuid, restored);
        if (restored.targetRegionName != null) {
          RTP.setLastRegionTeleportTime(uuid, restored.targetRegionName, restored.time);
        }
      }
    }

    if (player.hasPermission("rtp.personalqueue")) {
      Region region =
          RTP.selectionAPI.getRegion(RTP.serverAccessor.getPlayer(player.getUniqueId()));
      if (region == null) return;
      // ADR-043: bucket-only opt-in. Open the personal coordinate bucket
      // and schedule the push-on-open pregen fill. Does NOT enroll the
      // player on the teleport waitlist - they will only ever be enrolled
      // when they actually invoke /rtp via QueueTask.fallback.
      region.openPersonalQueue(player.getUniqueId());
    }
  }
}
