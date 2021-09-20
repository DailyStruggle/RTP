package leafcraft.rtp.API.customEvents;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class TeleportCommandSuccessEvent extends Event {
    private final CommandSender sender;
    private final Player player;
    private static final HandlerList HANDLERS_LIST = new HandlerList();

    public TeleportCommandSuccessEvent(CommandSender sender, Player player) {
        super(true);
        this.sender = sender;
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

    public CommandSender getSender() {
        return sender;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }
}
