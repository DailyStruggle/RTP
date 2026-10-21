package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

public class OnPlayerDamage implements Listener {
  @EventHandler(priority = EventPriority.LOWEST)
  public void onPlayerDamage(EntityDamageEvent event) {
    Entity entity = event.getEntity();
    if (!(entity instanceof Player)) return;
    if (!TeleportSessions.current().invulnerablePlayers.containsKey(entity.getUniqueId())) return;
    long lastTeleport = TeleportSessions.current().invulnerablePlayers.get(entity.getUniqueId());
    long current = System.currentTimeMillis();

    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) ActiveConfigs.get().getParser(SafetyKeys.class);
    long max = safety.getNumber(SafetyKeys.invulnerabilityTime, 0).longValue();

    if (max <= 0) {
      TeleportSessions.current().invulnerablePlayers.remove(entity.getUniqueId());
      return;
    }

    long dt = current - lastTeleport;
    if (dt < 0) dt = dt - Long.MIN_VALUE;

    if (dt < TimeUnit.SECONDS.toMillis(max)) event.setCancelled(true);
    else TeleportSessions.current().invulnerablePlayers.remove(entity.getUniqueId());
  }
}
