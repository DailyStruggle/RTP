package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class WorldParameter extends CommandParameter {
    public WorldParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission,description, isRelevant);
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return RTP.serverAccessor.getRTPWorlds().stream().map(RTPWorld::name).collect(Collectors.toSet());
    }
}
