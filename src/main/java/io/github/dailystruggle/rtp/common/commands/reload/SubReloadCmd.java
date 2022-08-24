package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmd;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class SubReloadCmd<T extends Enum<T>> extends BaseRTPCmdImpl {


    private final String name;
    private final String permission;
    private final String description;
    private final Class<T> configClass;

    public SubReloadCmd(@Nullable CommandsAPICommand parent, String name, String permission, String description, Class<T> configClass) {
        super(parent);
        this.name = name;
        this.permission = permission;
        this.description = description;
        this.configClass = configClass;
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
        return subReload(callerId,RTP.getInstance().configs.getParser(configClass));
    }

    public boolean subReload(UUID senderID, FactoryValue<?> factoryValue) {
        if(factoryValue instanceof MultiConfigParser multiConfigParser) {
            return subReloadMulti(senderID,multiConfigParser);
        }
        else if(factoryValue instanceof ConfigParser configParser) {
            return subReloadSingle(senderID,configParser);
        }
        return false;
    }


    public boolean subReloadSingle(UUID senderId, ConfigParser<?> parser) {
        RTPServerAccessor serverAccessor = RTP.serverAccessor;
        Configs configs = RTP.getInstance().configs;

        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) configs.getParser(LangKeys.class);
        if(lang == null) return true;

        String msg = String.valueOf(lang.getConfigValue(LangKeys.reloading,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);

        parser.check(parser.version, parser.pluginDirectory, null);

        msg = String.valueOf(lang.getConfigValue(LangKeys.reloaded,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);

        return true;
    }

    public boolean subReloadMulti(UUID senderId, MultiConfigParser<?> parser) {
        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        if(lang == null) return true;

        RTPServerAccessor serverAccessor = RTP.serverAccessor;
        RTPCommandSender commandSender = serverAccessor.getSender(senderId);

        String msg = String.valueOf(lang.getConfigValue(LangKeys.reloading,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);
        serverAccessor.reset();

        CommandsAPI.commandPipeline.clear();

        final RTP instance = RTP.getInstance();

        MultiConfigParser<?> newParser = new MultiConfigParser<>(parser.myClass, parser.name, "1.0", parser.pluginDirectory);
        if(parser.myClass.equals(RegionKeys.class)) {
            RTP.getInstance().selectionAPI.permRegionLookup.clear();
            for(ConfigParser<?> regionConfig : newParser.configParserFactory.map.values()) {
                EnumMap<RegionKeys, Object> data = (EnumMap<RegionKeys, Object>) regionConfig.getData();

                String worldName = String.valueOf(data.get(RegionKeys.world));
                RTPWorld world;
                if(worldName.startsWith("[") && worldName.endsWith("]")) {
                    int num = Integer.parseInt(worldName.substring(1,worldName.length()-1));
                    world = RTP.serverAccessor.getRTPWorlds().get(num);
                }
                else world = serverAccessor.getRTPWorld(worldName);
                if(world == null) {
                    new IllegalArgumentException("world not found - " + worldName).printStackTrace(); //don't need to throw
                    continue;
                }
                data.put(RegionKeys.world,world);

                Object shapeObj = data.get(RegionKeys.shape);
                Shape<?> shape;
                if(shapeObj instanceof MemorySection shapeSection) {
                    final Map<String, Object> shapeMap = shapeSection.getMapValues(true);
                    String shapeName = String.valueOf(shapeMap.get("name"));
                    Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
                    shape = (Shape<?>) factory.getOrDefault(shapeName);
                    EnumMap<?, Object> shapeData = shape.getData();
                    for(var e : shapeData.entrySet()) {
                        String name = e.getKey().name();
                        if(shapeMap.containsKey(name)) {
                            e.setValue(shapeMap.get(name));
                        }
                        else {
                            String altName = shape.language_mapping.get(name).toString();
                            if(altName!=null && shapeMap.containsKey(altName)) {
                                e.setValue(shapeMap.get(altName));
                            }
                        }
                    }
                    shape.setData(shapeData);
                    data.put(RegionKeys.shape,shape);
                }
                else throw new IllegalArgumentException("shape was not a section\n" + shapeObj);


                Object vertObj = data.get(RegionKeys.vert);
                if(vertObj instanceof MemorySection vertSection) {
                    final Map<String, Object> vertMap = vertSection.getMapValues(true);
                    String shapeName = String.valueOf(vertMap.get("name"));
                    Factory<VerticalAdjustor<?>> factory = (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
                    VerticalAdjustor<?> vert = (VerticalAdjustor<?>) factory.getOrDefault(shapeName);
                    EnumMap<?, Object> vertData = vert.getData();
                    for(var e : vertData.entrySet()) {
                        String name = e.getKey().name();
                        if(vertMap.containsKey(name)) {
                            e.setValue(vertMap.get(name));
                        }
                        else {
                            String altName = vert.language_mapping.get(name).toString();
                            if(altName!=null && vertMap.containsKey(altName)) {
                                e.setValue(vertMap.get(altName));
                            }
                        }
                    }
                    vert.setData(vertData);
                    data.put(RegionKeys.vert, vert);
                }
                else throw new IllegalArgumentException();

                Region region = new Region(regionConfig.name.replace(".yml",""), data);
                RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(
                        ()->RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region), 5));
            }
        }
        else if(parser.myClass.equals(WorldKeys.class)) {
            for(RTPWorld world : serverAccessor.getRTPWorlds()) {
                newParser.getParser(world.name());
            }
        }

        instance.configs.multiConfigParserMap.put(parser.myClass,newParser);

        msg = String.valueOf(lang.getConfigValue(LangKeys.reloaded,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, commandSender.uuid(),msg);

        return true;
    }
}
