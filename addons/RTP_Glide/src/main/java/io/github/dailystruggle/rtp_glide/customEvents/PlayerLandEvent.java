package io.github.dailystruggle.rtp_glide.customEvents;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a gliding player lands
 */
public class PlayerLandEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    private final Player player;

    /**
     * Constructor for PlayerLandEvent
     *
     * @param player the player who landed
     */
    public PlayerLandEvent(Player player) {
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


