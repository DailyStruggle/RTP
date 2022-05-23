package io.github.dailystruggle.rtp.bukkit.commands.parameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.command.CommandSender;

import java.util.Set;
import java.util.function.BiFunction;

public class RegionParameter extends BukkitParameter {
    public RegionParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission,description,isRelevant);
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return RTP.getInstance().selectionAPI.regionNames();
    }
}
