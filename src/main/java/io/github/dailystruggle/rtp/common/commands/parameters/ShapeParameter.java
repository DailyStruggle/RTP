package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class ShapeParameter extends CommandParameter {
    public ShapeParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission,description, isRelevant);
    }

    private final Map<String,Map<String,CommandParameter>> subParams = new ConcurrentHashMap<>();

    @Override
    public Set<String> values() {
        Factory<?> shapeFactory = RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
        Enumeration<String> listEnum = shapeFactory.list();
        Set<String> res = new HashSet<>();
        while (listEnum.hasMoreElements()) {
            res.add(listEnum.nextElement());
        }
        return res;
    }

    @Override
    public Map<String, CommandParameter> subParams(String parameter) {
        parameter = parameter.toUpperCase();
        Factory<?> shapeFactory = RTP.getInstance().factoryMap.get(RTP.factoryNames.shape);
        if(subParams.containsKey(parameter)) {
            return subParams.get(parameter);
        }
        if(shapeFactory.contains(parameter)) {
            Shape<?> shape = (Shape<?>) shapeFactory.getOrDefault(parameter);
            Map<String,CommandParameter> res = shape.getParameters();
            putShape(parameter,res);
            return res;
        }
        return new ConcurrentHashMap<>();
    }

    public void putShape(String name, Map<String, CommandParameter> params) {
        subParams.put(name.toUpperCase(),params);
    }
}
