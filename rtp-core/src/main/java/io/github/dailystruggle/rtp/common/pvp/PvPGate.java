package io.github.dailystruggle.rtp.common.pvp;

import io.github.dailystruggle.rtp.api.hooks.PvPCombatAction;
import io.github.dailystruggle.rtp.api.hooks.PvPCombatStateRegistry;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Platform-agnostic decision point for the PvP / combat-tag pre-flight gate.
 */
public final class PvPGate {

  /** Source selection for combat state. */
  public enum Source { AUTO, NATIVE, EXTERNAL;
    static Source fromConfig(String raw) {
      if (raw == null) return AUTO;
      try {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return AUTO;
      }
    }
  }

  private static final NativePvPCombatTracker NATIVE = new NativePvPCombatTracker();

  private PvPGate() {}

  /** @return the process-wide native combat tracker that platform listeners feed. */
  public static NativePvPCombatTracker nativeTracker() {
    return NATIVE;
  }

  /** @return {@code true} when the PvP gate is enabled in {@code safety.yml}. */
  public static boolean isEnabled() {
    ConfigParser<SafetyKeys> safety = safety();
    if (safety == null) return false;
    return truthy(safety.getConfigValue(SafetyKeys.pvpCheckEnabled, false));
  }

  /**
   * @param player the requesting player's UUID
   * @return {@code true} if the gate is enabled and the player is currently in combat
   */
  public static boolean isInCombat(UUID player) {
    if (player == null || !isEnabled()) return false;
    Source source = Source.fromConfig(stringValue(safety(), SafetyKeys.pvpSource, "AUTO"));
    PvPCombatStateRegistry.Provider provider = boundProvider();

    boolean useExternal = (source == Source.EXTERNAL)
        || (source == Source.AUTO && provider != null);
    if (useExternal) {
      if (provider == null) {
        // EXTERNAL requested but nothing bound: treat as not-in-combat (no native fallback).
        return false;
      }
      try {
        return provider.isInCombat(player);
      } catch (Throwable t) {
        // Never let a buggy integration block teleports; audit and treat as clear (REQ-RTP-S-004).
        RTP.log(Level.WARNING,
            "[RTP] PvP combat-state provider threw; treating player as not-in-combat", t);
        return false;
      }
    }

    long tagMillis = combatTagMillis();
    return NATIVE.isInCombat(player, System.currentTimeMillis(), tagMillis);
  }

  /**
   * Evaluate the configured response for {@code player} at a gate checkpoint.
   *
   * @param player the requesting player's UUID
   * @return {@link PvPCombatAction#ALLOW} when the gate is disabled or the player is
   *     not in combat; otherwise the configured {@code safety.yml#pvpOnCombat} action.
   */
  public static PvPCombatAction evaluate(UUID player) {
    if (!isEnabled() || !isInCombat(player)) {
      return PvPCombatAction.ALLOW;
    }
    return PvPCombatAction.fromConfig(stringValue(safety(), SafetyKeys.pvpOnCombat, "DENY"));
  }

  /** @return whether the native tracker should stamp players who <i>take</i> PvP damage. */
  public static boolean tagVictim() {
    ConfigParser<SafetyKeys> safety = safety();
    return safety == null || truthy(safety.getConfigValue(SafetyKeys.pvpTagVictim, true));
  }

  /** @return whether the native tracker should stamp players who <i>deal</i> PvP damage. */
  public static boolean tagAggressor() {
    ConfigParser<SafetyKeys> safety = safety();
    return safety == null || truthy(safety.getConfigValue(SafetyKeys.pvpTagAggressor, true));
  }

  /** @return the configured native combat-tag window, in millis (clamped to {@code >= 0}). */
  public static long combatTagMillis() {
    ConfigParser<SafetyKeys> safety = safety();
    long seconds = (safety == null)
        ? 15L
        : safety.getNumber(SafetyKeys.pvpCombatTagSeconds, 15L).longValue();
    return Math.max(0L, seconds) * 1000L;
  }

  // --- helpers -------------------------------------------------------------

  private static PvPCombatStateRegistry.Provider boundProvider() {
    try {
      return RTPAPI.hooks().pvpCombatState().current();
    } catch (IllegalStateException notLoaded) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static ConfigParser<SafetyKeys> safety() {
    try {
      return (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String stringValue(ConfigParser<SafetyKeys> safety, SafetyKeys key, String def) {
    if (safety == null) return def;
    Object o = safety.getConfigValue(key, def);
    return (o == null) ? def : o.toString();
  }

  private static boolean truthy(Object o) {
    if (o instanceof Boolean) return (Boolean) o;
    return o != null && Boolean.parseBoolean(o.toString());
  }
}
