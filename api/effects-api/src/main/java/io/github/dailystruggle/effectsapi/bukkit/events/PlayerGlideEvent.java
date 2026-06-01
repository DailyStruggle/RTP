package io.github.dailystruggle.effectsapi.bukkit.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by {@code GlideEffect} when a player is placed into glide mode after
 * a teleport. Migrated from {@code addons/RTP_Glide} (effects-api-ADR-001).
 */
public class PlayerGlideEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final Player player;

    public PlayerGlideEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }
}
