package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import io.github.dailystruggle.rtp.common.pvp.PvPGate;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Feeds RTP's native PvP combat tracker ({@link PvPGate#nativeTracker()}) from
 * Bukkit/Paper/Folia {@link EntityDamageByEntityEvent}s, so the optional combat
 * gate (ADR-055) has a working out-of-the-box authority when no external
 * combat-tag plugin is bound.
 *
 * <p>Only player-vs-player damage stamps the tracker: the victim is stamped when
 * {@code pvpTagVictim} is enabled and the resolved aggressor (incl. the shooter
 * of a projectile or the source of primed TNT) is stamped when
 * {@code pvpTagAggressor} is enabled. The whole listener short-circuits when the
 * gate is disabled ({@code safety.yml#pvpCheckEnabled=false}, the default), so it
 * is a near-zero-cost no-op on servers that do not use the feature.
 *
 * <p>This listener never cancels the event and never throws into the event bus.
 */
public class OnPlayerCombatTag implements Listener {

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
    // Cheap gate: do nothing unless the operator turned the feature on.
    if (!PvPGate.isEnabled()) return;

    if (!(event.getEntity() instanceof Player victim)) return;
    Player aggressor = resolvePlayerAggressor(event.getDamager());
    if (aggressor == null) return; // not PvP (mob / environment)
    if (aggressor.getUniqueId().equals(victim.getUniqueId())) return; // self-damage

    long now = System.currentTimeMillis();
    if (PvPGate.tagVictim()) PvPGate.nativeTracker().stamp(victim.getUniqueId(), now);
    if (PvPGate.tagAggressor()) PvPGate.nativeTracker().stamp(aggressor.getUniqueId(), now);
  }

  /**
   * Resolve the human responsible for a damage source: the damager itself if it
   * is a player, the shooter of a projectile, or the igniter of primed TNT.
   *
   * @param damager the {@link EntityDamageByEntityEvent#getDamager()} entity
   * @return the responsible {@link Player}, or {@code null} when the damage did
   *     not originate from a player
   */
  private static Player resolvePlayerAggressor(Entity damager) {
    if (damager instanceof Player p) return p;
    if (damager instanceof Projectile projectile) {
      ProjectileSource shooter = projectile.getShooter();
      if (shooter instanceof Player p) return p;
    }
    if (damager instanceof TNTPrimed tnt) {
      if (tnt.getSource() instanceof Player p) return p;
    }
    return null;
  }
}
