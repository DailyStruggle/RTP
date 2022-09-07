package io.github.dailystruggle.rtp.common.commands.update;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.FloatParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.IntegerParameter;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.ShapeParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.VertParameter;
import io.github.dailystruggle.rtp.common.commands.update.list.ListCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

public class SubUpdateCmd extends BaseRTPCmdImpl {


    private final String name;
    private final FactoryValue<?> factoryValue;

    public SubUpdateCmd(@Nullable CommandsAPICommand parent, String name, FactoryValue<?> factoryValue) {
        super(parent);
        this.name = name;
        this.factoryValue = factoryValue;
        addParameters();
    }


    @Override
    public String name() {
        return name;
    }

    @Override
    public String permission() {
        return "rtp.update";
    }

    @Override
    public String description() {
        return "update sections of this configuration";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(nextCommand!=null) return true;

        addParameters();
        if(factoryValue instanceof ConfigParser) {
            ConfigParser<?> configParser = (ConfigParser<?>) factoryValue;
            ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
            String msg = String.valueOf(lang.getConfigValue(LangKeys.updating,""));
            if(msg!=null) msg = StringUtils.replaceIgnoreCase(msg,"[filename]", factoryValue.name);
            RTP.serverAccessor.sendMessage(CommandsAPI.serverId, callerId,msg);

            for(Map.Entry<String,List<String>> e : parameterValues.entrySet()) {
                String key = e.getKey();
                String value = e.getValue().get(0);

                if(key == null || value == null) continue;

                //todo: shape and vert updates
                //todo: update internal data accordingly. maybe auto reload after update?

                configParser.set(key,value);
            }

            try {
                configParser.yamlFile.save();
            } catch (IOException ex) {
                ex.printStackTrace();
            }

            msg = String.valueOf(lang.getConfigValue(LangKeys.updated,""));
            if(msg!=null) msg = StringUtils.replaceIgnoreCase(msg,"[filename]", configParser.name);
            RTP.serverAccessor.sendMessage(CommandsAPI.serverId, callerId,msg);
        }
        else return true;



        return true;
    }

    public void addParameters() {
        if(factoryValue == null) return;

        if(factoryValue instanceof ConfigParser) {
            ConfigParser<?> configParser = (ConfigParser<?>) this.factoryValue;
            EnumMap<?,?> data = configParser.getData();
            for (Map.Entry<? extends Enum<?>,?> e : data.entrySet()) {
                String name = e.getKey().name();
                if(name.equalsIgnoreCase("version")) continue;
                String s = name;
                Object nameObj = configParser.language_mapping.get(name);
                if(nameObj!=null) s = nameObj.toString();
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
                } else if (o instanceof MemorySection) {
                    if(s.equalsIgnoreCase("shape")) addParameter(s, new ShapeParameter("rtp.update", "", (uuid, s1) -> true));
                    else if(s.equalsIgnoreCase("vert")) addParameter(s, new VertParameter("rtp.update", "", (uuid, s1) -> true));
                } else if (o instanceof List) {
                    Supplier<Set<String>> values = HashSet::new;
                    if(StringUtils.containsIgnoreCase(name,"block")) {
                        values = () -> RTP.serverAccessor.materials();
                    }
                    else if(StringUtils.containsIgnoreCase(name,"biome")) {
                        values = () -> RTP.serverAccessor.getBiomes();
                    }
                    addSubCommand(new ListCmd(name,this,values,configParser.yamlFile,s));
                }
            }
        }
        else if(factoryValue instanceof MultiConfigParser) {
            MultiConfigParser parser = (MultiConfigParser) factoryValue;
            for(Object e : parser.configParserFactory.map.entrySet()) {
                if(e instanceof Map.Entry) {
                    Map.Entry<?,?> entry = (Map.Entry<?, ?>) e;
                    Object entryValue = entry.getValue();
                    if(entryValue instanceof FactoryValue)
                        addSubCommand(new SubUpdateCmd(this, entry.getKey().toString(), (FactoryValue<?>) entryValue));
                }
            }
        }
    }
}
