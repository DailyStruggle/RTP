package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

public class SubReloadCmd<T extends Enum<T>> extends BaseRTPCmdImpl {

  private static final Pattern filenamePattern =
      Pattern.compile("\\[filename]", Pattern.CASE_INSENSITIVE);
  private final String name;
  private final String permission;
  private final String description;
  private final Class<T> configClass;

  public SubReloadCmd(
      @Nullable CommandsAPICommand parent,
      String name,
      String permission,
      String description,
      Class<T> configClass) {
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
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
    return subReload(callerId, RTP.configs.getParser(configClass));
  }

  public boolean subReload(UUID senderID, FactoryValue<?> factoryValue) {
    if (factoryValue instanceof MultiConfigParser) {
      return subReloadMulti(senderID, (MultiConfigParser<?>) factoryValue);
    } else if (factoryValue instanceof ConfigParser) {
      return subReloadSingle(senderID, (ConfigParser<?>) factoryValue);
    }
    RTP.getInstance().miscSyncTasks.add(new RTPRunnable(() -> RTP.reloading.set(false), 1));
    return false;
  }

  public boolean subReloadSingle(UUID senderId, ConfigParser<?> parser) {
    RTPServerAccessor serverAccessor = RTP.serverAccessor;
    Configs configs = RTP.configs;

    if (RTP.configs == null) return true;

    String msg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.reloading, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);

    parser.check(parser.version, parser.pluginDirectory, null);

    msg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.reloaded, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);

    return true;
  }

  public boolean subReloadMulti(UUID senderId, MultiConfigParser<?> parser) {
    if (RTP.configs == null) return true;

    RTPServerAccessor serverAccessor = RTP.serverAccessor;
    RTPCommandSender commandSender = serverAccessor.getSender(senderId);

    String msg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.reloading, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);

    MultiConfigParser<?> newParser =
        new MultiConfigParser<>(
            parser.myClass, parser.name, "1.0", parser.pluginDirectory, parser.directory);
    if (parser.myClass.equals(RegionKeys.class)) {
      @SuppressWarnings("unchecked")
      MultiConfigParser<RegionKeys> regions = (MultiConfigParser<RegionKeys>) newParser;
      for (Region r : RTP.selectionAPI.permRegionLookup.values()) {
        r.shutDown();
      }
      RTP.selectionAPI.permRegionLookup.clear();
      // Lobby backends never register local regions; matches
      // Configs.reloadRegions() gating so /rtp reload regions on a lobby stays a no-op.
      if (RTP.lobbyMode) {
        RTP.configs.multiConfigParserMap.put(parser.myClass, newParser);
        msg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.reloaded, ""));
        if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
        serverAccessor.sendMessage(RTPAPI.serverId, commandSender.uuid(), msg);
        return true;
      }
      for (ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
        RegionSettings settings = RegionConfigLoader.load(regionConfig);
        String configuredWorldName =
            RegionConfigLoader.detectFallbackConfiguredWorld(regionConfig, settings);
        boolean worldFallback = configuredWorldName != null;
        if (worldFallback) {
          RTP.log(
              java.util.logging.Level.INFO,
              "[RTP] Region '" + settings.name() + "' configured for world '"
                  + configuredWorldName
                  + "' which isn't loaded yet; region is dormant and will activate when the "
                  + "world loads.");
        }
        Region region = new Region(settings.name(), settings, worldFallback, configuredWorldName);
        RTP.selectionAPI.permRegionLookup.put(region.name, region);
      }
    } else if (parser.myClass.equals(WorldKeys.class)) {
      for (RTPWorld<?> world : serverAccessor.getRTPWorlds()) {
        newParser.getParser(world.name());
      }
    }

    RTP.configs.multiConfigParserMap.put(parser.myClass, newParser);

    msg = String.valueOf(RTP.configs.getConfigValue(SystemMessages.reloaded, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    serverAccessor.sendMessage(RTPAPI.serverId, commandSender.uuid(), msg);

    return true;
  }
}
