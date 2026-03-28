package io.github.dailystruggle.rtp.api.entity;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.concurrent.CompletableFuture;

/**
 * Interface representing a player in the world
 */
public interface RTPPlayer extends RTPCommandSender {
    /**
     * Teleport the player to the specified location
     *
     * @param to the location to teleport to
     * @return a future that completes with true if the teleport was successful, false otherwise
     */
    CompletableFuture<Boolean> setLocation(RTPLocation to);

    /**
     * Get the current location of the player
     *
     * @return the location
     */
    RTPLocation getLocation();

    /**
     * Check if the player is currently online
     *
     * @return true if online, false otherwise
     */
    boolean isOnline();
}

