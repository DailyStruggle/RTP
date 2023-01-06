package io.github.dailystruggle.rtp.bukkit.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class TeleportCancelEvent extends Event {
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private final UUID playerId;

    public TeleportCancelEvent(UUID playerId) {
        super(!Bukkit.isPrimaryThread());
        this.playerId = playerId;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
