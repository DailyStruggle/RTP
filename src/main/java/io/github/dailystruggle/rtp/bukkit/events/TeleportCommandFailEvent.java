package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class TeleportCommandFailEvent extends Event {
    private final RTPCommandSender sender;
    private String failMsg;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public TeleportCommandFailEvent(RTPCommandSender sender, String failMsg) {
        super(true);
        this.sender = sender;
        this.failMsg = failMsg;
    }

    public RTPCommandSender getSender() {
        return sender;
    }


    public String getFailMsg() {
        return failMsg;
    }

    public void setFailMsg(String failMsg) {
        this.failMsg = failMsg;
    }
}
