package io.github.dailystruggle.rtp.common.commands.info;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tools.ParseString;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InfoCmd extends BaseRTPCmdImpl {
    private static final Map<String, Function<RTPWorld, String>> worldDataLookup = new ConcurrentHashMap<>();
    private static final Map<String, Function<Region, String>> regionDataLookup = new ConcurrentHashMap<>();
    static {
        worldDataLookup.put("world", RTPWorld::name);
        worldDataLookup.put("region", world -> RTP.getInstance().selectionAPI.getRegion(world).name);
        worldDataLookup.put("requirePermission", world -> {
            MultiConfigParser<WorldKeys> worlds = (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
            ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
            return parser.getConfigValue(WorldKeys.requirePermission,false).toString();
        });
        worldDataLookup.put("override", world -> {
            MultiConfigParser<WorldKeys> worlds = (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
            ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
            return parser.getConfigValue(WorldKeys.override,"[0]").toString();
        });

        regionDataLookup.put("region", region -> region.name);
        regionDataLookup.put("world", region -> region.getWorld().name());
        regionDataLookup.put("shape", region -> region.getShape().name);
        regionDataLookup.put("queueLen", region -> String.valueOf(region.getPublicQueueLength()));
        regionDataLookup.put("queued", region -> String.valueOf(region.locationQueue.size()));
        regionDataLookup.put("worldBorderOverride", region -> {
            boolean wbo = false;
            EnumMap<RegionKeys, Object> data = region.getData();
            Object o = data.getOrDefault(RegionKeys.worldBorderOverride,false);
            if(o instanceof Boolean) wbo = (Boolean) o;
            else if(o instanceof String) {
                wbo = Boolean.parseBoolean((String) o);
                data.put(RegionKeys.worldBorderOverride,wbo);
            }
            return String.valueOf(wbo);
        });
        regionDataLookup.put("requirePermission", region -> {
            boolean req = false;
            EnumMap<RegionKeys, Object> data = region.getData();
            Object o = data.getOrDefault(RegionKeys.requirePermission,false);
            if(o instanceof Boolean) req = (Boolean) o;
            else if(o instanceof String) {
                req = Boolean.parseBoolean((String) o);
                data.put(RegionKeys.requirePermission, req);
            }
            return String.valueOf(req);
        });

    }

    public InfoCmd(@Nullable CommandsAPICommand parent) {
        super(parent);
        addParameter("world", new WorldParameter("rtp.info","check on a world's configuration", (uuid, s) -> true));
        addParameter("region", new RegionParameter("rtp.info","check on a region's state and configuration", (uuid, s) -> true));
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String permission() {
        return "rtp.info";
    }

    @Override
    public String description() {
        return "check the current state of the plugin";
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

        if(parameterValues.size()==0) {
            String title = lang.getConfigValue(MessagesKeys.infoTitle, "").toString();
            String chunks = lang.getConfigValue(MessagesKeys.infoChunks, "").toString();
            String worldHeader = lang.getConfigValue(MessagesKeys.infoWorldHeader, "").toString();
            String worlds = lang.getConfigValue(MessagesKeys.infoWorld, "").toString();
            String regionHeader = lang.getConfigValue(MessagesKeys.infoRegionHeader, "").toString();
            String regions = lang.getConfigValue(MessagesKeys.infoRegion, "").toString();

            RTP.serverAccessor.sendMessage(callerId,title);
            RTP.serverAccessor.sendMessage(callerId,chunks);
            RTP.serverAccessor.sendMessage(callerId,worldHeader);
            RTP.serverAccessor.getRTPWorlds().forEach(world -> {
                String msg = StringUtils.replace(worlds,"[world]",world.name());
                RTP.serverAccessor.sendMessageAndSuggest(callerId,msg,"rtp info world:"+world.name());
            });
            RTP.serverAccessor.sendMessage(callerId,regionHeader);
            RTP.getInstance().selectionAPI.permRegionLookup.values().forEach(region -> {
                String msg = StringUtils.replace(regions,"[region]",region.name);
                RTP.serverAccessor.sendMessageAndSuggest(callerId,msg,"rtp info region:"+region.name);
            });
            return true;
        }

        Set<Character> front = new HashSet<>(Arrays.asList('[','%'));
        Set<Character> back = new HashSet<>(Arrays.asList(']','%'));

        List<String> worldNames = parameterValues.get("world");
        if(worldNames!=null) {
            Object worldInfoObj = lang.getConfigValue(MessagesKeys.worldInfo, "");
            if(!(worldInfoObj instanceof List)) return true;
            List<String> worldInfo = ((List<?>) worldInfoObj).stream().map(String::valueOf).collect(Collectors.toList());
            for(String worldName : worldNames) {
                RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
                if(rtpWorld==null || !rtpWorld.isActive()) continue;
                ArrayList<String> worldInfoCopy = new ArrayList<>(worldInfo);
                List<String> strings = worldInfoCopy.stream().map(s -> {
                    Set<String> keywords = ParseString.keywords(s, worldDataLookup.keySet(), front, back);
                    Map<String, String> placeholders = new HashMap<>();
                    worldDataLookup.forEach((s1, rtpWorldStringFunction) -> {
                        if (!keywords.contains(s1)) return;
                        placeholders.put(s1, rtpWorldStringFunction.apply(rtpWorld));
                    });
                    StrSubstitutor sub = new StrSubstitutor(placeholders, "[", "]");
                    StrSubstitutor sub2 = new StrSubstitutor(placeholders, "%", "%");
                    return sub2.replace(sub.replace(s));
                }).collect(Collectors.toList());
                strings.forEach(s -> RTP.serverAccessor.sendMessage(callerId,s));
            }
        }

        List<String> regionNames = parameterValues.get("region");
        if(regionNames!=null) {
            Object regionInfoObj = lang.getConfigValue(MessagesKeys.regionInfo, "");
            if(!(regionInfoObj instanceof List)) return true;
            List<String> regionInfo = ((List<?>) regionInfoObj).stream().map(String::valueOf).collect(Collectors.toList());
            for(String regionName : regionNames) {
                Region region = RTP.getInstance().selectionAPI.getRegion(regionName);
                if(region ==null) continue;
                ArrayList<String> regionInfoCopy = new ArrayList<>(regionInfo);
                List<String> strings = regionInfoCopy.stream().map(s -> {
                    Set<String> keywords = ParseString.keywords(s, regionDataLookup.keySet(), front, back);
                    Map<String, String> placeholders = new HashMap<>();
                    regionDataLookup.forEach((s1, rtpRegionStringFunction) -> {
                        if (!keywords.contains(s1)) return;
                        placeholders.put(s1, rtpRegionStringFunction.apply(region));
                    });
                    StrSubstitutor sub = new StrSubstitutor(placeholders, "[", "]");
                    StrSubstitutor sub2 = new StrSubstitutor(placeholders, "%", "%");
                    return sub2.replace(sub.replace(s));
                }).collect(Collectors.toList());
                strings.forEach(s -> RTP.serverAccessor.sendMessage(callerId,s));
            }
        }

        return true;
    }
}
