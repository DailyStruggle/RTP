package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandFailEvent;
import io.github.dailystruggle.rtp.bukkit.events.TeleportCommandSuccessEvent;
import io.github.dailystruggle.rtp.common.commands.RTPCommandEvents;

import org.bukkit.Bukkit;

/**
 * Bukkit subscriber that republishes the platform-neutral {@code /rtp} command
 * outcome callbacks ({@link RTPCommandEvents}) onto the Bukkit plugin event bus
 * as {@link TeleportCommandSuccessEvent} / {@link TeleportCommandFailEvent}.
 *
 * <p>This is the Bukkit half of the events seam introduced when the bespoke
 * {@code RTPCmdBukkit} command root was folded into the platform-neutral
 * {@code CoreRtpRoot} (ADR-070): instead of the root subclassing a Bukkit
 * command type purely to fire these events, the Bukkit bootstrap subscribes
 * this callback once at startup. Platforms with no plugin-event-bus analogue
 * (Fabric / NeoForge) subscribe nothing, so the neutral root's
 * {@code successEvent} / {@code failEvent} fan-out is a no-op there.</p>
 */
public final class BukkitCommandEvents {

  private static boolean registered = false;

  private BukkitCommandEvents() {}

  /**
   * Subscribe the Bukkit event republishers exactly once. Idempotent across
   * plugin re-enables (e.g. {@code /reload}) so events are never double-fired.
   */
  public static synchronized void register() {
    if (registered) return;
    RTPCommandEvents.onSuccess(
        (sender, player) ->
            Bukkit.getPluginManager().callEvent(new TeleportCommandSuccessEvent(sender, player)));
    RTPCommandEvents.onFail(
        (sender, msg) ->
            Bukkit.getPluginManager().callEvent(new TeleportCommandFailEvent(sender, msg)));
    registered = true;
  }
}
