package io.github.dailystruggle.rtp.api.entity;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import java.util.Map;
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

  /**
   * Reads the block currently shown to this player at {@code location} and returns its
   * platform-neutral block-data string (e.g. {@code "minecraft:oak_log[axis=y]"}), in the
   * same string form a block material is otherwise exchanged across the SPI.
   *
   * <p>This is a purely local, in-memory read of an <b>already-loaded</b> block; it must never
   * trigger a chunk load (REQ-RTP-S-005). Implementations return {@code null} when the block's
   * chunk is not currently loaded (or when the platform cannot answer), and callers must treat
   * {@code null} as "skip this position".
   *
   * <p>The default implementation returns {@code null}; platform adapters override it.
   *
   * <p>Must be called from the thread that owns the player / location (the main server thread,
   * or the owning region thread on Folia).
   *
   * @param location the block position to read; must not be {@code null}
   * @return the block-data string shown at {@code location}, or {@code null} if unavailable
   */
  default String getClientBlock(RTPLocation location) {
    return null;
  }

  /**
   * Sends a single client-side ("fake") block change to this player: the block at
   * {@code location} is shown as {@code blockData} on the client only, without altering the
   * world, firing physics, or loading chunks. The fake is purely visual and self-corrects on
   * the client's next real chunk update, so it can never compromise destination safety
   * (S-001..S-007).
   *
   * <p>Prefer {@link #sendClientBlockChanges(Map)} for more than one block: a bulk send is
   * binned into one multi-block packet per chunk section, avoiding the packet/CPU spam of many
   * single-block sends.
   *
   * <p>The default implementation is a no-op; platform adapters override it.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the
   * owning region thread on Folia).
   *
   * @param location  the block position to change on the client; must not be {@code null}
   * @param blockData the platform-neutral block-data string to show (e.g. {@code "minecraft:air"})
   */
  default void sendClientBlockChange(RTPLocation location, String blockData) {
    // no-op by default; platform adapters override with the native client-side block change
  }

  /**
   * Binned bulk variant of {@link #sendClientBlockChange(RTPLocation, String)}: sends all of
   * {@code changes} to this player's client in as few packets as the platform allows (typically
   * one multi-block-change packet per affected chunk section), so dissolving/restoring a large
   * region costs a handful of packets instead of one per block.
   *
   * <p>Each entry maps a block position to the platform-neutral block-data string to display
   * there. Entries with a {@code null} value, or whose chunk is not currently loaded, are
   * skipped (no chunk load is triggered, REQ-RTP-S-005). Nothing about the real world changes.
   *
   * <p>The default implementation falls back to per-block
   * {@link #sendClientBlockChange(RTPLocation, String)} calls so existing implementations remain
   * source- and binary-compatible; platform adapters override it with a single batched send.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the
   * owning region thread on Folia).
   *
   * @param changes the block positions mapped to the block-data strings to show; must not be
   *                {@code null}
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
   * Shows or updates a personal, on-screen progress bar (e.g. a boss-bar) for this player only,
   * keyed by a stable, caller-chosen {@code id}.
   *
   * <p>Unlike the server-wide, permission-scoped
   * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#updateProgressBars(Map)}
   * surface, this targets a single player, so it is suited to per-player feedback such as a
   * teleport-warmup or queue-position countdown. Calling again with the same {@code id} updates
   * the existing bar in place; distinct ids show distinct bars.
   *
   * <p>The {@code title} may contain legacy {@code &x} / {@code #RRGGBB} color codes; the platform
   * decides how (or whether) to render them. {@code progress} is a fill fraction in {@code [0, 1]}
   * (clamped by the implementation).
   *
   * <p>The default implementation is a no-op so existing {@link RTPPlayer} implementations (and
   * addon test doubles) remain source- and binary-compatible; platform adapters override it.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the owning
   * region thread on Folia).
   *
   * @param id       a stable, caller-chosen bar id; must not be {@code null}
   * @param title    the bar title, possibly containing color codes; {@code null} is treated as empty
   * @param progress fill fraction in {@code [0, 1]}
   */
  default void showProgressBar(String id, String title, double progress) {
    // no-op by default; platform adapters override with the native per-player progress bar
  }

  /**
   * Hides and discards a personal progress bar previously shown to this player via
   * {@link #showProgressBar(String, String, double)} under the same {@code id}. Unknown ids are
   * ignored.
   *
   * <p>The default implementation is a no-op; platform adapters override it.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the owning
   * region thread on Folia).
   *
   * @param id the bar id to clear; must not be {@code null}
   */
  default void clearProgressBar(String id) {
    // no-op by default; platform adapters override with the native per-player progress bar
  }

  /**
   * Returns this player's current server-side view distance in chunks, or a non-positive value
   * when the platform exposes no per-player view-distance API.
   *
   * <p>This is the per-player <i>delivery</i> view distance (the radius of chunks the server
   * tracks and streams to this client), not the chunk radius RTP itself pre-loads around a
   * destination. It is purely runtime state and is never persisted; a server restart
   * re-negotiates it to the world/server default.
   *
   * <p>The default implementation returns {@code -1} to signal "unsupported", so callers can
   * skip any view-distance behaviour on platforms without a per-player API. Platform adapters
   * override it with the native getter.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the
   * owning region thread on Folia).
   *
   * @return the current per-player view distance in chunks, or {@code -1} if unsupported
   */
  default int getViewDistance() {
    return -1;
  }

  /**
   * Sets this player's server-side view distance in chunks (per-player delivery radius). Used by
   * the teleport view-distance clamp and steady restore (ADR-072) to shrink the chunk-tracking
   * ring the engine expands when the player is placed, then ramp it back up over time.
   *
   * <p>The value is runtime-only and never persisted. Implementations should clamp it to the
   * platform's supported range (the minimum renderable view distance is 2 chunks). On a platform
   * with no per-player view-distance API the default implementation is a no-op, so the clamp
   * feature silently degrades to "off" rather than failing.
   *
   * <p>Must be called from the thread that owns the player (the main server thread, or the
   * owning region thread on Folia).
   *
   * @param viewDistance the desired per-player view distance in chunks
   */
  default void setViewDistance(int viewDistance) {
    // no-op by default; platform adapters override with the native per-player view-distance call
  }
}
