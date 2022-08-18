package io.github.dailystruggle.rtp.common.commands.update;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.FloatParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.IntegerParameter;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class SubUpdateCmd <T extends Enum<T>> extends BaseRTPCmdImpl {


    private final String name;
    private final String permission;
    private final String description;
    private final Class<T> configClass;

    public SubUpdateCmd(@Nullable CommandsAPICommand parent, String name, String permission, String description, Class<T> configClass) {
        super(parent);
        this.name = name;
        this.permission = permission;
        this.description = description;
        this.configClass = configClass;
        addParameters();
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String permission() {
        return permission;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(nextCommand!=null) return true;

        FactoryValue<?> factoryValue = RTP.getInstance().configs.getParser(configClass);
        if(factoryValue instanceof ConfigParser configParser) {
            for(var e : parameterValues.entrySet()) {
                String key = e.getKey();
                String value = e.getValue().get(0);

                if(key == null || value == null) continue;

                configParser.set(key,value);
            }

            try {
                configParser.yamlFile.save();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        else return true;



        return true;
    }

    public void addParameters() {
        FactoryValue<?> value = RTP.getInstance().configs.getParser(configClass);

        if(value instanceof ConfigParser configParser) {
            EnumMap<?,?> data = configParser.getData();

            for (var e : data.entrySet()) {
                String name = e.getKey().name();
                if(name.equalsIgnoreCase("version")) continue;
                String s = configParser.language_mapping.get(name).toString();
                Object o = e.getValue();



                if (o instanceof String) {
                    addParameter(s, new CommandParameter("rtp.update", "", (uuid, s1) -> true) {
                        @Override
                        public Set<String> values() {
                            return new HashSet<>();
                        }
                    });
                } else if (o instanceof Boolean) {
                    addParameter(s, new BooleanParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof Integer || o instanceof Long) {
                    addParameter(s, new IntegerParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof Double || o instanceof Float) {
                    addParameter(s, new FloatParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof Shape) {
                    addParameter(s, new ShapeParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof VerticalAdjustor) {
                    addParameter(s, new VertParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof Region) {
                    addParameter(s, new RegionParameter("rtp.update", "", (uuid, s1) -> true));
                }
            }
        }
    }
}
