package io.github.dailystruggle.rtp.claimaddon;

import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * Per-call plugin gate for the reflective claim checkers.
 *
 * <p>Separates "claim plugin not running" (no claims to respect: fail open) from "claim plugin
 * running but its API cannot be reached" (fail closed through {@link ClaimCheckFailure}). Plugin
 * names are the {@code plugin.yml} {@code name:} values and are matched case-sensitively.
 */
final class ClaimPlugins {
  private ClaimPlugins() {}

  /**
   * Unannotated view of {@code Bukkit.getServer()}, which is {@code @NotNull} but returns null
   * until a server is installed (tests, early class loading). Read through a supplier so static
   * analysis does not treat the null check in {@link #server()} as dead code.
   */
  private static final Supplier<Server> BUKKIT_SERVER = Bukkit::getServer;

  /** The Bukkit server, or empty before one is installed. */
  static Optional<Server> server() {
    return Optional.ofNullable(BUKKIT_SERVER.get());
  }

  /** True when a Bukkit server is present and {@code pluginName} is enabled on it. */
  static boolean isEnabled(String pluginName) {
    return server()
        .map(Server::getPluginManager)
        .map(pluginManager -> pluginManager.isPluginEnabled(pluginName))
        .orElse(false);
  }
}
