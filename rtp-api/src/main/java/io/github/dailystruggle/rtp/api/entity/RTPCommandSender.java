package io.github.dailystruggle.rtp.api.entity;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Platform-neutral representation of a command sender (player or server console).
 *
 * <p>Thread safety: calls must execute on the platform's primary command thread.
 *
 * @governedBy ADR-021
 * @governedBy ADR-115
 * @trace REQ-API-F-004
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
   * Returns whether this sender holds the RTP super-permission ({@code rtp.*}) and therefore bypasses
   * per-node and per-parameter permission checks. Centralizes the wildcard-admin test so callers do not
   * hardcode the literal node.
   *
   * @return {@code true} if the sender has {@code rtp.*}
   */
  default boolean isRtpAdmin() {
    return hasPermission("rtp.*");
  }

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
   * Returns whether this sender holds only self-contained pure data and has no live platform handle.
   *
   * @return {@code true} if detached from live platform objects, {@code false} otherwise
   */
  default boolean isSelfContained() {
    return false;
  }

  /**
   * Converts this sender into a pure-data snapshot detached from live platform handles.
   *
   * @return a pure-data snapshot of this sender
   */
  default RTPCommandSender toPureData() {
    return this;
  }

  /**
   * Resolves the underlying live platform sender if online or available, or {@code null}.
   *
   * @return the platform sender object, or {@code null} if offline or unavailable
   */
  default @Nullable Object getLiveSender() {
    return null;
  }

  /**
   * Creates a clone of this command sender.
   *
   * @return a new {@link RTPCommandSender} instance
   */
  RTPCommandSender clone();
}
