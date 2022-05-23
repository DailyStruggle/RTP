package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class RandomPreTeleportEvent extends Event {
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public RandomPreTeleportEvent(RTPCommandSender sender, RTPPlayer player) {
        super(true);
        this.sender = sender;
        this.player = player;
    }

    public RTPPlayer getPlayer() {
        return player;
    }

    public RTPCommandSender getSender() {
        return sender;
    }

    
}
