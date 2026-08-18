package io.github.dailystruggle.rtp.api.entity;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-agnostic representation of a player who can be teleported.
 *
 * <p>Abstracts over native player handles to decouple core logic from platform APIs.
 *
 * <p><b>Thread safety:</b> Main server thread only, except {@link #isOnline()}.
 */
public interface RTPPlayer extends RTPCommandSender {
  /**
   * Asynchronously teleports the player to the specified {@link RTPLocation}.
   *
   * <p>Completes on the main server thread after teleport dispatch.
   *
   * @param to target location; must not be {@code null}
   * @return future completing with {@code true} if initiated, or {@code false} if cancelled/offline
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
   * Sets the given location as the player's persistent spawn anchor (ADR-023).
   *
   * <p>Must be called from the destination owning thread (main or Folia region thread).
   *
   * @param location anchor location; must not be {@code null}
   */
  default void setRespawnLocation(RTPLocation location) {
    // no-op by default; platform adapters override with the native call
  }

  /**
   * Reads the block-data string currently shown to this player at {@code location}.
   *
   * <p>In-memory read of loaded blocks only; never loads chunks (REQ-RTP-S-005).
   * Returns {@code null} if unloaded or unavailable.
   *
   * @param location block position; must not be {@code null}
   * @return block-data string (e.g. {@code "minecraft:oak_log[axis=y]"}), or {@code null}
   */
  default String getClientBlock(RTPLocation location) {
    return null;
  }

  /**
   * Sends a single client-side visual block change without modifying world state or loading chunks.
   *
   * <p>For bulk updates, prefer {@link #sendClientBlockChanges(Map)} for chunk-section batching.
   *
   * @param location  target block position; must not be {@code null}
   * @param blockData platform-neutral block-data string (e.g. {@code "minecraft:air"})
   */
  default void sendClientBlockChange(RTPLocation location, String blockData) {
    // no-op by default; platform adapters override with the native client-side block change
  }

  /**
   * Sends batched client-side visual block changes to this player in multi-block packets.
   *
   * <p>Unloaded chunks and {@code null} values are skipped without triggering chunk loads (REQ-RTP-S-005).
   *
   * @param changes map of locations to block-data strings; must not be {@code null}
   */
  default void sendClientBlockChanges(Map<RTPLocation, String> changes) {
    if (changes == null) {
      return;
    }
    for (Map.Entry<RTPLocation, String> entry : changes.entrySet()) {
      sendClientBlockChange(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Shows or updates a personal progress bar (e.g. boss-bar) for this player only.
   *
   * <p>Supports legacy color codes in {@code title}. {@code progress} is clamped to {@code [0, 1]}.
   *
   * @param id       stable bar identifier; must not be {@code null}
   * @param title    bar title (null treated as empty)
   * @param progress fill fraction in {@code [0, 1]}
   */
  default void showProgressBar(String id, String title, double progress) {
    // no-op by default; platform adapters override with the native per-player progress bar
  }

  /**
   * Clears a personal progress bar previously shown under {@code id}.
   *
   * @param id bar identifier to clear; must not be {@code null}
   */
  default void clearProgressBar(String id) {
    // no-op by default; platform adapters override with the native per-player progress bar
  }

  /**
   * Returns this player's current server-side chunk delivery radius, or {@code -1} if unsupported.
   *
   * <p>Must be called from the player's owning thread (main or Folia region thread).
   *
   * @return current per-player view distance in chunks, or {@code -1}
   */
  default int getViewDistance() {
    return -1;
  }

  /**
   * Sets this player's server-side chunk delivery radius (ADR-072).
   *
   * <p>Runtime-only; clamps to platform bounds. No-op on unsupported platforms.
   *
   * @param viewDistance desired per-player view distance in chunks
   */
  default void setViewDistance(int viewDistance) {
    // no-op by default; platform adapters override with the native per-player view-distance call
  }

  /**
   * Returns this player's client render distance in chunks, or {@code -1} if unsupported.
   *
   * <p>Distinct from {@link #getViewDistance()}: governs client render radius, not server tracking.
   *
   * @return current send view distance in chunks, or {@code -1}
   */
  default int getSendViewDistance() {
    return -1;
  }

  /**
   * Sets this player's client render distance independently of server tracking distance (ADR-072).
   *
   * <p>Negative values reset to platform default. No-op if unsupported.
   *
   * @param viewDistance desired send view distance in chunks, or negative for default
   */
  default void setSendViewDistance(int viewDistance) {
    // no-op by default; platform adapters override with the native per-player send-view-distance call
  }
}
