package io.github.dailystruggle.rtp_glide.customEvents;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a player starts gliding
 */
public class PlayerGlideEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    private final Player player;

    /**
     * Constructor for PlayerGlideEvent
     *
     * @param player the player who started gliding
     */
    public PlayerGlideEvent(Player player) {
        this.player = player;
    }

    /**
     * Get the handler list for this event
     *
     * @return the handler list
     */
    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    /**
     * Get the player involved in the event
     *
     * @return the player
     */
    public Player getPlayer() {
        return player;
    }
}


