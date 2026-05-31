package io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.hooks.PvPCombatStateRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Bundled combat-tag plugin integrations for the optional PvP / combat-tag pre-flight gate
 * (ADR-055). Each supported combat plugin contributes a combat-state adapter (CombatLogX and
 * PvPManager compile against their published APIs; Simple Combat Log stays reflective); the
 * first installed-and-enabled plugin (in priority order) is bound to RTP's single-binding
 * {@code PvPCombatStateRegistry}, <i>replacing</i> RTP's {@code NativePvPCombatTracker} exactly the
 * way an external biome/anvil pre-filter replaces the built-in one.
 *
 * <p>When no supported plugin is present (or {@code safety.yml#pvpSource: NATIVE}), nothing is
 * bound and {@code PvPGate} falls back to the native damage tracker. The registry is single-binding,
 * so only one combat plugin can own the gate at a time; ties are resolved by the documented priority
 * (PvPManager, then CombatLogX, then Simple Combat Log -- the two with stable developer APIs first).
 *
 * <p>Binding is unconditional with respect to the {@code safety.yml#pvpCheckEnabled} master toggle:
 * {@code PvPGate} never queries the provider while the gate is disabled, and {@code pvpSource}
 * selection happens at query time, so installing the provider early is harmless and survives a later
 * enable without a re-bind.
 */
public final class PvPIntegrations {
  private PvPIntegrations() {}

  /**
   * Bind the first available bundled combat-tag adapter to {@code RTPHooks#pvpCombatState()}.
   *
   * @param plugin bukkit plugin instance (unused today; accepted for symmetry with the other
   *     {@code softdepends} {@code setup(...)} entry points and forward-compatibility)
   */
  public static void setup(org.bukkit.plugin.java.JavaPlugin plugin) {
    if (bind("PvPManager", PvPManagerChecker.isAvailable(), PvPManagerChecker::isInCombat)) return;
    if (bind("CombatLogX", CombatLogXChecker.isAvailable(), CombatLogXChecker::isInCombat)) return;
    if (bind("SimpleCombatLog", SimpleCombatLogChecker.isAvailable(), SimpleCombatLogChecker::isInCombat)) {
      return;
    }
    RTP.log(
        Level.FINER,
        "[RTP] No supported combat-tag plugin (PvPManager / CombatLogX / Simple Combat Log) detected;"
            + " the PvP gate (if enabled) will use RTP's native combat tracker.");
  }

  private static boolean bind(String pluginName, boolean available, Function<UUID, Boolean> query) {
    if (!available) return false;
    try {
      PvPCombatStateRegistry registry = RTPAPI.hooks().pvpCombatState();
      registry.bind(query::apply);
      RTP.log(
          Level.INFO,
          "[RTP] Bound combat-tag integration '" + pluginName
              + "' as the PvP gate's combat-state authority.");
      return true;
    } catch (IllegalStateException coreNotLoaded) {
      // S-006: RTPAPI throws until core is loaded. Should not happen from setupIntegrations,
      // but never let a buggy ordering crash startup.
      RTP.log(
          Level.WARNING,
          "[RTP] Could not bind combat-tag integration '" + pluginName
              + "' (RTP core not loaded yet); the native combat tracker will be used.",
          coreNotLoaded);
      return false;
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP] Failed to bind combat-tag integration '" + pluginName
              + "'; continuing with the native combat tracker.",
          t);
      return false;
    }
  }
}
