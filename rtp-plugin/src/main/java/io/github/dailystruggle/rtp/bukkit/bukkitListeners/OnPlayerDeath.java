package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/**
 * Listens for player death events and routes them to active action sessions (ADR-093).
 */
public class OnPlayerDeath implements Listener {

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlayerDeath(PlayerDeathEvent event) {
    if (RTP.actionManager == null) return;
    Player victim = event.getEntity();
    UUID victimId = victim.getUniqueId();
    Player killer = victim.getKiller();
    UUID killerId = (killer != null) ? killer.getUniqueId() : null;

    RTP.actionManager.handlePlayerDeath(victimId, killerId);
  }
}
