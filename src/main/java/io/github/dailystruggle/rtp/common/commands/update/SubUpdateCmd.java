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
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.commands.update.list.ListCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class SubUpdateCmd extends BaseRTPCmdImpl {


    private final String name;
    private final FactoryValue<?> factoryValue;

    public SubUpdateCmd(@Nullable CommandsAPICommand parent, String name, FactoryValue<?> factoryValue) {
        super(parent);
        this.name = name.toLowerCase();
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

        RTP.stop();
        RTP.serverAccessor.stop();

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
                configParser.save();
            } catch (IOException ex) {
                ex.printStackTrace();
            }

            msg = String.valueOf(lang.getConfigValue(LangKeys.updated,""));
            if(msg!=null) msg = StringUtils.replaceIgnoreCase(msg,"[filename]", configParser.name);
            RTP.serverAccessor.sendMessage(CommandsAPI.serverId, callerId,msg);
        }
        else if(factoryValue instanceof MultiConfigParser) {
            MultiConfigParser<?> parser = (MultiConfigParser<?>) this.factoryValue;
            List<String> remove = parameterValues.getOrDefault("remove", new ArrayList<>());
            for(String target : remove) {
                String configName = target;
                if(!StringUtils.endsWithIgnoreCase(configName,".yml")) configName = configName+".yml";
                ConfigParser<?> configParser = (ConfigParser<?>) parser.configParserFactory.get(configName);
                if(configParser == null) continue;
                parser.configParserFactory.map.remove(configName.toUpperCase());
                commandLookup.remove(target);
                configParser.yamlFile.getConfigurationFile().deleteOnExit();
            }

            List<String> add = parameterValues.getOrDefault("add", new ArrayList<>());
            for(String target : add) {
                parser.addParser(target);
                ConfigParser<?> configParser = parser.getParser(target);
                SubUpdateCmd subUpdateCmd = new SubUpdateCmd(this, configParser.name, configParser);
                subUpdateCmd.addParameters();
                addSubCommand(subUpdateCmd);
            }
        }

        CommandsAPICommand reload;
        reload = RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
        reload.onCommand(callerId,new HashMap<>(),null);

        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull UUID callerId,
                                               @NotNull Predicate<String> permissionCheckMethod,
                                               @NotNull String[] args) {
        addParameters();
        return super.onTabComplete(callerId,permissionCheckMethod,args);
    }

    public void addParameters() {
        parameterLookup.clear();
        commandLookup.clear();
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

                if(StringUtils.containsIgnoreCase(name,"world")) {
                    addParameter(s, new WorldParameter("rtp.update", "", (uuid, s1) -> true));
                }
                else if(StringUtils.containsIgnoreCase(name,"region")) {
                    addParameter(s, new RegionParameter("rtp.update", "", (uuid, s1) -> true));
                }
                else if (o instanceof String) {
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
            MultiConfigParser<?> parser = (MultiConfigParser<?>) factoryValue;
            for(Map.Entry<?,?> e : parser.configParserFactory.map.entrySet()) {
                Object entryValue = e.getValue();
                if(entryValue instanceof FactoryValue)
                    addSubCommand(new SubUpdateCmd(this, e.getKey().toString(), (FactoryValue<?>) entryValue));
            }
            addParameter("add", new CommandParameter("rtp.update","add a file", (uuid, s) -> true) {
                @Override
                public Set<String> values() {
                    return new HashSet<>();
                }
            });
            addParameter("remove", new CommandParameter("rtp.update","remove a file", (uuid, s) -> true) {
                @Override
                public Set<String> values() {
                    return parser.listParsers();
                }
            });
        }
    }
}
