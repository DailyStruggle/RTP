package io.github.dailystruggle.rtp.api.entity;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * Interface representing a command sender (player or console)
 */
public interface RTPCommandSender extends Cloneable {
    /**
     * Get the UUID of the command sender
     * @return the UUID
     */
    UUID uuid();

    /**
     * Check if the command sender has the specified permission
     * @param permission the permission to check
     * @return true if has permission, false otherwise
     */
    boolean hasPermission( String permission );

    /**
     * Send a message to the command sender
     * @param message the message to send
     */
    void sendMessage( String message );

    /**
     * Get the cooldown for the command sender in seconds
     * @return the cooldown
     */
    long cooldown();

    /**
     * Get the delay for the command sender in seconds
     * @return the delay
     */
    long delay();

    /**
     * Get the name of the command sender
     * @return the name
     */
    String name();

    /**
     * Get all effective permissions for the command sender
     * @return the set of permissions
     */
    Set<String> getEffectivePermissions();

    /**
     * Perform a command as the command sender
     * @param player the player involved, or null if not applicable
     * @param command the command to perform
     */
    void performCommand( @Nullable RTPPlayer player, String command );

    /**
     * Clone the command sender
     * @return the cloned command sender
     */
    RTPCommandSender clone();
}

