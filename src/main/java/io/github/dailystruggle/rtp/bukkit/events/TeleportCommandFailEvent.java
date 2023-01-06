package io.github.dailystruggle.rtp.bukkit.events;

import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class TeleportCommandFailEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final RTPCommandSender sender;
    private String failMsg;

    public TeleportCommandFailEvent(RTPCommandSender sender, String failMsg) {
        super(!Bukkit.isPrimaryThread());
        this.sender = sender;
        this.failMsg = failMsg;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
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
