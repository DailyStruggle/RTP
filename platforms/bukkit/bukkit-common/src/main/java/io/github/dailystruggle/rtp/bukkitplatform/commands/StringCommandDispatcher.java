package io.github.dailystruggle.rtp.bukkitplatform.commands;

import java.util.UUID;

/**
 * Seam for the legacy Bukkit {@code String[]}-args command path.
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
