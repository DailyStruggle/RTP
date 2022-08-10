package io.github.dailystruggle.rtp.bukkit.events;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class TeleportCancelEvent extends Event {
    private final UUID playerId;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    public TeleportCancelEvent(UUID playerId) {
        super(!Bukkit.isPrimaryThread());
        this.playerId = playerId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public UUID getPlayerId() {
        return playerId;
    }
}
