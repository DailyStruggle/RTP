package io.github.dailystruggle.commandsapi.bukkit;

import java.util.UUID;

/**
 * Platform-neutral seam for the legacy Bukkit {@code String[]}-args command path.
 *
 * <p>{@link BukkitCommandRegistrar} owns the platform registration concern
 * (binding {@code CommandExecutor} / {@code TabCompleter} onto a
 * {@code PluginCommand}) and resolves the caller's {@link UUID}. The actual
 * command dispatch is delegated here so a command tree author can inject any
 * pre-dispatch behaviour (e.g. RTP's cooldown / waitlist / sender checks)
 * without subclassing a Bukkit command type.</p>
 *
 * <p>When no dispatcher is supplied the registrar falls back to the generic
 * {@code TreeCommand} args dispatch, matching the former {@code BukkitTreeCommand}
 * behaviour.</p>
 */
@FunctionalInterface
public interface StringCommandDispatcher {

  /**
   * Dispatches a resolved legacy command invocation.
   *
   * @param callerId the resolved caller UUID ({@code CommandsAPI.serverId} for console)
   * @param label    the command label / alias used
   * @param args     the raw arguments
   * @return {@code true} if the invocation was handled
   */
  boolean dispatch(UUID callerId, String label, String[] args);
}
