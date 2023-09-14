package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class RandomSelectQueueEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final RTPCommandSender sender;
    private final RTPPlayer player;

    public RandomSelectQueueEvent( RTPCommandSender sender, RTPPlayer player ) {
        super( !Bukkit.isPrimaryThread() );
        this.sender = sender;
        this.player = player;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public RTPPlayer getPlayer() {
        return player;
    }

    public RTPCommandSender getSender() {
        return sender;
    }


}
