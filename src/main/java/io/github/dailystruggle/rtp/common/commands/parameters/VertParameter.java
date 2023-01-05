package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class VertParameter extends CommandParameter {
    public VertParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
        Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        factory.map.forEach((s, verticalAdjustor) -> {
            put(verticalAdjustor.name, verticalAdjustor.getParameters());
        });

    }

    @Override
    public Set<String> values() {
        Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
        factory.map.forEach((s, verticalAdjustor) -> {
            if (!subParamMap.containsKey(s.toLowerCase())) {
                put(verticalAdjustor.name, verticalAdjustor.getParameters());
            }
        });
        return RTP.factoryMap.get(RTP.factoryNames.vert).map.keySet();
    }

    @Override
    public Map<String, CommandParameter> subParams(String parameter) {
        parameter = parameter.toLowerCase();
        Factory<?> factory = RTP.factoryMap.get(RTP.factoryNames.vert);
        Map<String, CommandParameter> map = subParamMap.get(parameter);
        if (map != null) {
            return map;
        }
        VerticalAdjustor<?> value = (VerticalAdjustor<?>) factory.get(parameter);
        if (value != null) {
            map = value.getParameters();
            subParamMap.put(parameter, map);
            return map;
        }
        return new ConcurrentHashMap<>();
    }

    public void put(String name, Map<String, CommandParameter> params) {
        Map<String, CommandParameter> lowercase = new ConcurrentHashMap<>();
        params.forEach((s, parameter) -> lowercase.put(s.toLowerCase(), parameter));
        subParamMap.put(name.toLowerCase(), lowercase);
    }
}
