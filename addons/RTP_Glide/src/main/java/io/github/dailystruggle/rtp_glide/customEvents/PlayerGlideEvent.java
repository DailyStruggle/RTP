package io.github.dailystruggle.rtp_glide.customEvents;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class PlayerGlideEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    private final Player player;

    public PlayerGlideEvent(Player player) {
        this.player = player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public Player getPlayer() {
        return player;
    }
}
