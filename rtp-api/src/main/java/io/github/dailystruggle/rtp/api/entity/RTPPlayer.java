package io.github.dailystruggle.rtp.api.entity;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-agnostic representation of a player who can be teleported.
 *
 * <p>This interface abstracts over the platform's native player object (e.g.
 * {@code org.bukkit.entity.Player}) to decouple the core teleport logic from
 * server-specific APIs. Implementations are provided by the platform adapter
 * (e.g. {@code rtp-bukkit}) and are obtained via
 * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getPlayer(java.util.UUID)}.
 *
 * <p><b>Thread safety:</b> With the exception of {@link #isOnline()}, all methods
 * on this interface must be called from the main server thread.
 */
public interface RTPPlayer extends RTPCommandSender {
  /**
   * Asynchronously teleports the player to the specified {@link RTPLocation}.
   *
   * <p>The teleport is not guaranteed to be instantaneous; it may be delayed by
   * the platform to ensure chunks are loaded. The returned {@link CompletableFuture}
   * completes on the main server thread after the teleport has been dispatched.
   *
   * @param to the target location; must not be {@code null}
   * @return a {@link CompletableFuture} that completes with {@code true} if the
   *         teleport was successfully initiated, or {@code false} otherwise (e.g.
   *         if the teleport was cancelled by another plugin or the player logged off)
   */
  CompletableFuture<Boolean> setLocation(RTPLocation to);

  /**
   * Returns the player's current location.
   *
   * <p>This method must be called from the main server thread.
   *
   * @return the player's current {@link RTPLocation}
   */
  RTPLocation getLocation();

  /**
   * Checks if the player is currently online.
   *
   * <p>This method is safe to call from any thread.
   *
   * @return {@code true} if the player is online, {@code false} otherwise
   */
  boolean isOnline();

  /**
   * Sets the given location as the player's persistent spawn anchor (the point
   * they respawn at after death), backing the {@code setRespawnOnTeleport} config
   * knob (BetterRTP {@code SetAsRespawn} parity).
   *
   * <p>The default implementation is a no-op so existing {@link RTPPlayer}
   * implementations (and addon test doubles) remain source- and binary-compatible;
   * each platform adapter overrides it with the native respawn-point call.
   *
   * <p>Must be called from the thread that owns the destination (the main server
   * thread, or the owning region thread on Folia).
   *
   * @param location the location to anchor the player's respawn to; must not be
   *                 {@code null}
   */
  default void setRespawnLocation(RTPLocation location) {
    // no-op by default; platform adapters override with the native call
  }
}
