package io.github.dailystruggle.rtp.claimaddon;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;

/**
 * Per-call plugin gate for the reflective claim checkers.
 *
 * <p>Separates "claim plugin not running" (no claims to respect: fail open) from "claim plugin
 * running but its API cannot be reached" (fail closed through {@link ClaimCheckFailure}). Plugin
 * names are the {@code plugin.yml} {@code name:} values and are matched case-sensitively.
 */
final class ClaimPlugins {
  private ClaimPlugins() {}

  /** True when a Bukkit server is present and {@code pluginName} is enabled on it. */
  static boolean isEnabled(String pluginName) {
    Server server = Bukkit.getServer();
    if (server == null) return false;
    PluginManager pluginManager = server.getPluginManager();
    return pluginManager != null && pluginManager.isPluginEnabled(pluginName);
  }
}
