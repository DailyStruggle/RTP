package io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.UUID;
import java.util.logging.Level;
import me.chancesd.pvpmanager.player.CombatPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Combat-state adapter for <a href="https://www.spigotmc.org/resources/pvpmanager.10610">PvPManager</a>
 * (ChanceSD), bound to RTP's {@code PvPCombatStateRegistry} by {@link PvPIntegrations}.
 *
 * <p>PvPManager exposes a static {@link CombatPlayer#get(Player)} accessor whose
 * {@code isInCombat()} answers the gate's question. This adapter compiles against the published
 * PvPManager API (a {@code compileOnly} dependency provided by the plugin at runtime), replacing the
 * earlier reflective lookup. The API type is only touched once {@link #isAvailable()} has confirmed
 * the plugin is enabled (so it is guaranteed to be on the classpath); {@link #isInCombat(UUID)}
 * returns before linking it for an offline / unknown player, keeping this class loadable on a server
 * without PvPManager.
 *
 * <p>Per the {@code PvPCombatStateRegistry} contract and REQ-RTP-S-004, any failure disables this
 * adapter for the session (logged once) and reports "not in combat" so a buggy or version-mismatched
 * integration never blocks teleports.
 */
public final class PvPManagerChecker {
  private PvPManagerChecker() {}

  private static volatile boolean exists = true;

  /** @return {@code true} when PvPManager is installed and enabled on this server. */
  public static boolean isAvailable() {
    return Bukkit.getPluginManager().isPluginEnabled("PvPManager");
  }

  /**
   * @param uuid the requesting player's UUID
   * @return {@code true} if PvPManager currently tags {@code uuid} as in combat
   */
  public static boolean isInCombat(UUID uuid) {
    if (!exists || uuid == null) return false;
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return false; // offline players are never combat-tagged
    try {
      CombatPlayer combatPlayer = CombatPlayer.get(player);
      return combatPlayer != null && combatPlayer.isInCombat();
    } catch (Throwable t) {
      disable(t);
      return false;
    }
  }

  private static void disable(Throwable t) {
    exists = false;
    RTP.log(
        Level.SEVERE,
        "[RTP] PvPManager combat-state integration failed (likely an API/version mismatch);"
            + " disabling it for this session. Players will be treated as not-in-combat.",
        t);
  }
}
