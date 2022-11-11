package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        return subReload(callerId,RTP.configs.getParser(configClass));
    }

    public boolean subReload(UUID senderID, FactoryValue<?> factoryValue) {
        if(factoryValue instanceof MultiConfigParser) {
            return subReloadMulti(senderID, (MultiConfigParser<?>) factoryValue);
        }
        else if(factoryValue instanceof ConfigParser) {
            return subReloadSingle(senderID, (ConfigParser<?>) factoryValue);
        }
        return false;
    }


    public boolean subReloadSingle(UUID senderId, ConfigParser<?> parser) {
        RTPServerAccessor serverAccessor = RTP.serverAccessor;
        Configs configs = RTP.configs;

        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) configs.getParser(MessagesKeys.class);
        if(lang == null) return true;

        String msg = String.valueOf(lang.getConfigValue(MessagesKeys.reloading,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);

        parser.check(parser.version, parser.pluginDirectory, null);

        msg = String.valueOf(lang.getConfigValue(MessagesKeys.reloaded,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);

        return true;
    }

    public boolean subReloadMulti(UUID senderId, MultiConfigParser<?> parser) {
        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if(lang == null) return true;

        RTPServerAccessor serverAccessor = RTP.serverAccessor;
        RTPCommandSender commandSender = serverAccessor.getSender(senderId);

        String msg = String.valueOf(lang.getConfigValue(MessagesKeys.reloading,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);

        CommandsAPI.commandPipeline.clear();

        final RTP instance = RTP.getInstance();

        MultiConfigParser<?> newParser = new MultiConfigParser<>(parser.myClass, parser.name, "1.0", parser.pluginDirectory);
        if(parser.myClass.equals(RegionKeys.class)) {
            MultiConfigParser<RegionKeys> regions = (MultiConfigParser<RegionKeys>) newParser;
            RTP.getInstance().selectionAPI.permRegionLookup.clear();
            for(ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
                EnumMap<RegionKeys, Object> data = regionConfig.getData();
                Region region = new Region(regionConfig.name.replace(".yml",""), data);
                RTP.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
            }
        }
        else if(parser.myClass.equals(WorldKeys.class)) {
            for(RTPWorld world : serverAccessor.getRTPWorlds()) {
                newParser.getParser(world.name());
            }
        }

        instance.configs.multiConfigParserMap.put(parser.myClass,newParser);

        msg = String.valueOf(lang.getConfigValue(MessagesKeys.reloaded,""));
        if(msg!=null) msg = StringUtils.replace(msg,"[filename]", parser.name);
        serverAccessor.sendMessage(CommandsAPI.serverId, commandSender.uuid(),msg);

        return true;
    }
}
