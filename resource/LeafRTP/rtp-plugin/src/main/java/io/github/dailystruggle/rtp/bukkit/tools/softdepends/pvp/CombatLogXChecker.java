package io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp;

import com.github.sirblobman.combatlogx.api.ICombatLogX;
import com.github.sirblobman.combatlogx.api.manager.ICombatManager;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Combat-state adapter for <a href="https://www.spigotmc.org/resources/combatlogx.31689">CombatLogX</a>
 * (SirBlobman), bound to RTP's {@code PvPCombatStateRegistry} by {@link PvPIntegrations}.
 *
 * <p>The {@code CombatLogX} plugin instance implements {@link ICombatLogX}; its
 * {@code getCombatManager().isInCombat(Player)} answers the gate's question. This adapter compiles
 * against the published CombatLogX API (a {@code compileOnly} dependency provided by the plugin at
 * runtime), so the {@link ICombatManager} method resolves against the public interface -- avoiding
 * the reflective {@code IllegalAccessException} that the concrete, package-private
 * {@code ICombatManager} implementation triggers when a {@code Method} is resolved on its class.
 *
 * <p>The CombatLogX API classes are only touched once {@link #isAvailable()} has confirmed the
 * plugin is enabled (so they are guaranteed to be on the classpath); {@link #isInCombat(UUID)}
 * returns before linking them for an offline / unknown player, keeping this class loadable on a
 * server without CombatLogX.
 *
 * <p>Per the {@code PvPCombatStateRegistry} contract and REQ-RTP-S-004, any failure disables this
 * adapter for the session (logged once) and reports "not in combat" so a buggy or version-mismatched
 * integration never blocks teleports.
 */
public final class CombatLogXChecker {
  private CombatLogXChecker() {}

  private static volatile boolean exists = true;
  private static volatile boolean loggedLinkFailure = false;

  /** @return {@code true} when CombatLogX is installed and enabled on this server. */
  public static boolean isAvailable() {
    return Bukkit.getPluginManager().isPluginEnabled("CombatLogX");
  }

  /**
   * @param uuid the requesting player's UUID
   * @return {@code true} if CombatLogX currently tags {@code uuid} as in combat
   */
  public static boolean isInCombat(UUID uuid) {
    if (!exists || uuid == null) return false;
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return false; // offline players are never combat-tagged
    Plugin plugin = Bukkit.getPluginManager().getPlugin("CombatLogX");
    if (!(plugin instanceof ICombatLogX)) {
      // Previously a silent "return false" -- the most likely real-world failure mode, and one
      // that does NOT trip disable(): the CombatLogX plugin is present, but RTP resolved a
      // different copy of ICombatLogX (a class-loader identity mismatch), so the instanceof is
      // false even though the gate should be active. Log it once so the cause is visible instead
      // of silently treating every player as not-in-combat.
      if (plugin != null && !loggedLinkFailure) {
        loggedLinkFailure = true;
        RTP.log(
            Level.SEVERE,
            "[RTP] CombatLogX is enabled, but its plugin instance is not assignable to the"
                + " ICombatLogX API class RTP compiled against (class-loader identity mismatch:"
                + " RTP-loaded=" + ICombatLogX.class.getClassLoader()
                + ", plugin-loaded=" + plugin.getClass().getClassLoader()
                + "). The PvP gate cannot read combat state and will treat players as"
                + " not-in-combat. Report this with your CombatLogX version.");
      }
      return false;
    }
    try {
      ICombatManager combatManager = ((ICombatLogX) plugin).getCombatManager();
      return combatManager != null && combatManager.isInCombat(player);
    } catch (Throwable t) {
      disable(t);
      return false;
    }
  }

  private static void disable(Throwable t) {
    exists = false;
    RTP.log(
        Level.SEVERE,
        "[RTP] CombatLogX combat-state integration failed (likely an API/version mismatch);"
            + " disabling it for this session. Players will be treated as not-in-combat.",
        t);
  }
}
