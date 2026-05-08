package io.github.dailystruggle.effectsapi.bukkit.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by {@code GlideEffect} when a gliding player lands (touches ground,
 * runs out of timeout, disconnects, or is placed at shutdown). Migrated from
 * {@code addons/RTP_Glide} (effects-api-ADR-001).
 */
public class PlayerLandEvent extends Event {
    public enum Reason {
        /** Player touched a solid/liquid block beneath them. */
        GROUNDED,
        /** Watchdog timeout (configured {@code landingTimeout}) elapsed. */
        TIMEOUT,
        /** Player disconnected while gliding. */
        DISCONNECT,
        /** Server stop / plugin disable; player placed at safe block below. */
        SHUTDOWN,
        /** Server stop / plugin disable; emergency platform synthesized. */
        SHUTDOWN_PLATFORM
    }

    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final Player player;
    private final Reason reason;

    public PlayerLandEvent(Player player) {
        this(player, Reason.GROUNDED);
    }

    public PlayerLandEvent(Player player, Reason reason) {
        this.player = player;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    public Reason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }
}
