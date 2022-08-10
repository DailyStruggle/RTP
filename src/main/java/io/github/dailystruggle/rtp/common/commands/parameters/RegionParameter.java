package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

public class RegionParameter extends CommandParameter {
    public RegionParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission,description, isRelevant);
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return RTP.getInstance().selectionAPI.regionNames();
    }
}
