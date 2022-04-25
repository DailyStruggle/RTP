package leafcraft.rtp.bukkit.commands.parameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import leafcraft.rtp.api.RTPAPI;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.Set;
import java.util.function.BiFunction;

public class RegionParameter extends BukkitParameter {
    public RegionParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission,description,isRelevant);
    }

    @Override
    public Set<String> values() {
        return RTPAPI.getInstance().selectionAPI.regionNames();
    }
}
