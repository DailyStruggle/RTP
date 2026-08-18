package io.github.dailystruggle.rtp.api.entity;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral representation of a command sender (player or server console).
 *
 * <p>Thread safety: calls must execute on the platform's primary command thread.
 */
public interface RTPCommandSender extends Cloneable {
  /**
   * Returns the unique identifier for this command sender.
   *
   * @return the sender's {@link UUID}
   */
  UUID uuid();

  /**
   * Checks if the sender has the specified permission.
   *
   * @param permission the permission string to check; must not be {@code null}
   * @return {@code true} if the sender has the permission, {@code false} otherwise
   */
  boolean hasPermission(String permission);

  /**
   * Sends a message to this command sender.
   *
   * @param message the message to send; must not be {@code null}
   */
  void sendMessage(String message);

  /**
   * Returns the sender-specific teleport cooldown in seconds.
   *
   * @return the cooldown duration
   */
  long cooldown();

  /**
   * Returns the sender-specific teleport delay in seconds.
   *
   * @return the delay duration
   */
  long delay();

  /**
   * Returns the name of this command sender.
   *
   * @return the sender's name
   */
  String name();

  /**
   * Returns all effective permissions for this command sender.
   *
   * @return a non-null, potentially empty {@link Set} of permission strings
   */
  Set<String> getEffectivePermissions();

  /**
   * Executes a command as this sender.
   *
   * @param player the player context for the command, or {@code null} if none
   * @param command the command string to execute; must not be {@code null}
   */
  void performCommand(@Nullable RTPPlayer player, String command);

  /**
   * Creates a clone of this command sender.
   *
   * @return a new {@link RTPCommandSender} instance
   */
  RTPCommandSender clone();
}
