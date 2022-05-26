package io.github.dailystruggle.rtp.bukkit.commands.parameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.bukkit.command.CommandSender;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Level;

public class ShapeParameter extends BukkitParameter {
    public ShapeParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
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
