package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class RegionParameter extends CommandParameter {
    public RegionParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission,description, isRelevant);
        subParamMap.putIfAbsent("DEFAULT",new ConcurrentHashMap<>());
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return RTP.selectionAPI.regionNames();
    }

    @Override
    public Map<String,CommandParameter> subParams(String parameter) {
        return subParamMap.get("DEFAULT");
    }

    public void put(Map<String, CommandParameter> params) {
        subParamMap.put("DEFAULT",params);
    }

    public void put(String name, CommandParameter param) {
        subParamMap.get("DEFAULT").put(name,param);
    }
}
