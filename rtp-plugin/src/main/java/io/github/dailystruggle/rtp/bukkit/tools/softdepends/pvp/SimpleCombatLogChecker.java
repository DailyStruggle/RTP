package io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Reflection-based combat-state adapter for <a href="https://www.spigotmc.org/resources/simple-combat-log.111737">Simple
 * Combat Log</a> (Gistavs), bound to RTP's {@code PvPCombatStateRegistry} by {@link PvPIntegrations}.
 *
 * <p>Unlike PvPManager and CombatLogX, Simple Combat Log does not publish a stable developer API,
 * so this adapter probes the live plugin instance for a small set of conventional combat-query
 * method shapes ({@code isInCombat} / {@code inCombat} / {@code isTagged} / {@code isCombatTagged}
 * accepting a {@link Player} or {@link UUID}). When none resolves, the adapter is disabled cleanly
 * and {@link PvPIntegrations} simply does not bind it -- RTP's native tracker answers instead. The
 * probe is best-effort by design and will pick up a programmatic API should the plugin add one.
 *
 * <p>Per the {@code PvPCombatStateRegistry} contract and REQ-RTP-S-004, any reflective failure
 * disables this adapter for the session and reports "not in combat" so a buggy or version-mismatched
 * integration never blocks teleports.
 */
public final class SimpleCombatLogChecker {
  private SimpleCombatLogChecker() {}

  private static final String[] METHOD_NAMES = {
    "isInCombat", "inCombat", "isTagged", "isCombatTagged", "isInCombatLog"
  };

  private static volatile boolean exists = true;
  private static volatile boolean probed;
  private static volatile Method query; // accepts a single Player or UUID arg, returns boolean
  private static volatile boolean takesUuid;

  /** @return {@code true} when Simple Combat Log is installed and enabled on this server. */
  public static boolean isAvailable() {
    return Bukkit.getPluginManager().isPluginEnabled("SimpleCombatLog");
  }

  /**
   * @param uuid the requesting player's UUID
   * @return {@code true} if Simple Combat Log currently tags {@code uuid} as in combat; {@code false}
   *     when the plugin exposes no recognisable combat-query method
   */
  public static boolean isInCombat(UUID uuid) {
    if (!exists || uuid == null) return false;
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return false; // offline players are never combat-tagged
    Plugin plugin = Bukkit.getPluginManager().getPlugin("SimpleCombatLog");
    if (plugin == null) return false;
    try {
      resolve(plugin);
      if (query == null) return false; // no usable API; bound provider is a documented no-op
      Object result = takesUuid ? query.invoke(plugin, uuid) : query.invoke(plugin, player);
      return result instanceof Boolean && (Boolean) result;
    } catch (Throwable t) {
      disable(t);
      return false;
    }
  }

  private static synchronized void resolve(Plugin plugin) {
    if (probed) return;
    probed = true;
    Class<?> pluginClass = plugin.getClass();
    for (String name : METHOD_NAMES) {
      Method byPlayer = tryMethod(pluginClass, name, Player.class);
      if (byPlayer != null && isBooleanReturn(byPlayer)) {
        query = byPlayer;
        takesUuid = false;
        return;
      }
      Method byUuid = tryMethod(pluginClass, name, UUID.class);
      if (byUuid != null && isBooleanReturn(byUuid)) {
        query = byUuid;
        takesUuid = true;
        return;
      }
    }
    RTP.log(
        Level.INFO,
        "[RTP] Simple Combat Log is installed but exposes no recognisable combat-query API;"
            + " RTP's native combat tracker will answer the PvP gate instead.");
  }

  private static Method tryMethod(Class<?> owner, String name, Class<?> arg) {
    try {
      return owner.getMethod(name, arg);
    } catch (NoSuchMethodException ignored) {
      return null;
    }
  }

  private static boolean isBooleanReturn(Method m) {
    Class<?> r = m.getReturnType();
    return r == boolean.class || r == Boolean.class;
  }

  private static void disable(Throwable t) {
    exists = false;
    RTP.log(
        Level.SEVERE,
        "[RTP] Simple Combat Log combat-state integration failed (likely an API/version mismatch);"
            + " disabling it for this session. Players will be treated as not-in-combat.",
        t);
  }
}
