package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.substitutions.RTPPlayer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class TeleportCancelEvent extends Event {
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final RTPLocation to;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    public TeleportCancelEvent(RTPCommandSender sender, RTPPlayer player, RTPLocation to, boolean async) {
        super(async);
        this.sender = sender;
        this.player = player;
        this.to = to;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public RTPPlayer getPlayer() {
        return player;
    }

    public RTPLocation getTo() {
        return to;
    }

    public RTPCommandSender getSender() {
        return sender;
    }
}
