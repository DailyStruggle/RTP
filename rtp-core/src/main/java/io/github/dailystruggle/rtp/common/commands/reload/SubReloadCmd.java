package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.config.ConfigCandidateGate;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.network.NetworkHooks;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;

import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

/**
 * Subcommand reloading a single configuration file or multi-config directory asynchronously off the
 * main thread after passing the candidate gate.
 *
 * @param <T> configuration enum key type
 * @governedBy ADR-021
 * @governedBy ADR-121
 * @trace REQ-API-F-009
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
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
    return subReload(callerId, ActiveConfigs.get().getParser(configClass));
  }

  public boolean subReload(UUID senderID, FactoryValue<?> factoryValue) {
    if (factoryValue instanceof MultiConfigParser) {
      return subReloadMulti(senderID, (MultiConfigParser<?>) factoryValue);
    } else if (factoryValue instanceof ConfigParser) {
      return subReloadSingle(senderID, (ConfigParser<?>) factoryValue);
    }
    MiscTasks.sync().add(new RTPRunnable(() -> RTP.reloading.set(false), 1));
    return false;
  }

  @SuppressWarnings("java:S3516") // Method returns true on reload completion
  public boolean subReloadSingle(UUID senderId, ConfigParser<?> parser) {
    if (ActiveConfigs.get() == null || parser == null) return false;
    if (!RTP.reloading.compareAndSet(false, true)) return false;

    RTPServerAccessor serverAccessor = RTPRuntime.serverAccessor();
    String msg = String.valueOf(ActiveConfigs.get().getConfigValue(SystemMessages.reloading, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    if (serverAccessor != null) {
      serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);
    }

    try {
      RTPRuntime.scheduler().runAsync(() -> {
        try {
          if (!candidateAccepted(senderId)) return;
          parser.check(parser.version, parser.pluginDirectory, null);

          Configs active = ActiveConfigs.get();
          if (active != null && serverAccessor != null) {
            String reloadedMsg = String.valueOf(active.getConfigValue(SystemMessages.reloaded, ""));
            if (reloadedMsg != null) {
              reloadedMsg = filenamePattern.matcher(reloadedMsg).replaceAll(parser.name);
            }
            serverAccessor.sendMessage(RTPAPI.serverId, senderId, reloadedMsg);
          }
        } catch (RuntimeException failure) {
          RTPRuntime.log(
              Level.SEVERE,
              "Failed to reload config " + parser.name + ": " + failure.getMessage(),
              failure);
        } finally {
          RTP.reloading.set(false);
        }
      });
    } catch (RuntimeException failure) {
      RTP.reloading.set(false);
      RTPRuntime.log(
          Level.SEVERE,
          "Failed to schedule config reload for " + parser.name + ": " + failure.getMessage(),
          failure);
      return false;
    }

    return true;
  }

  @SuppressWarnings("java:S3516") // Method returns true on reload completion
  public boolean subReloadMulti(UUID senderId, MultiConfigParser<?> parser) {
    if (ActiveConfigs.get() == null || parser == null) return false;
    if (!RTP.reloading.compareAndSet(false, true)) return false;

    RTPServerAccessor serverAccessor = RTPRuntime.serverAccessor();
    RTPCommandSender commandSender = serverAccessor != null ? serverAccessor.getSender(senderId) : null;
    UUID targetSenderId = commandSender != null ? commandSender.uuid() : senderId;

    String msg = String.valueOf(ActiveConfigs.get().getConfigValue(SystemMessages.reloading, ""));
    if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
    if (serverAccessor != null) {
      serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);
    }

    try {
      RTPRuntime.scheduler().runAsync(() -> {
        boolean handedOffToSync = false;
        try {
          if (!candidateAccepted(senderId)) return;

          MultiConfigParser<?> newParser =
              new MultiConfigParser<>(
                  parser.myClass, parser.name, parser.version, parser.pluginDirectory,
                  parser.directory, parser.locale);
          if (parser.myClass.equals(RegionKeys.class) || parser.myClass.equals(WorldKeys.class)) {
            handedOffToSync = true;
            RTPRuntime.scheduler().runSync(() -> {
              try {
                applyMultiReloadSync(targetSenderId, parser, newParser, serverAccessor);
              } catch (RuntimeException failure) {
                RTPRuntime.log(
                    Level.SEVERE,
                    "Failed to apply multi-config reload for " + parser.name + ": " + failure.getMessage(),
                    failure);
              } finally {
                RTP.reloading.set(false);
              }
            });
            return;
          }

          finishMultiReload(targetSenderId, parser, newParser, serverAccessor);
        } catch (RuntimeException failure) {
          handedOffToSync = false;
          RTPRuntime.log(
              Level.SEVERE,
              "Failed to reload multi-config " + parser.name + ": " + failure.getMessage(),
              failure);
        } finally {
          if (!handedOffToSync) {
            RTP.reloading.set(false);
          }
        }
      });
    } catch (RuntimeException failure) {
      RTP.reloading.set(false);
      RTPRuntime.log(
          Level.SEVERE,
          "Failed to schedule multi-config reload for " + parser.name + ": " + failure.getMessage(),
          failure);
      return false;
    }

    return true;
  }

  private void applyMultiReloadSync(
      UUID targetSenderId,
      MultiConfigParser<?> parser,
      MultiConfigParser<?> newParser,
      @Nullable RTPServerAccessor serverAccessor) {
    Configs active = ActiveConfigs.get();
    if (active == null) return;
    if (parser.myClass.equals(RegionKeys.class)) {
      @SuppressWarnings("unchecked")
      MultiConfigParser<RegionKeys> regions = (MultiConfigParser<RegionKeys>) newParser;
      for (Region r : ActiveSelection.get().permRegionLookup.values()) {
        r.shutDown();
      }
      ActiveSelection.get().permRegionLookup.clear();
      // Lobby backends never register local regions; matches
      // Configs.reloadRegions() gating so /rtp reload regions on a lobby stays a no-op.
      if (NetworkHooks.lobbyMode()) {
        finishMultiReload(targetSenderId, parser, newParser, serverAccessor);
        return;
      }
      for (ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
        RegionSettings settings = RegionConfigLoader.load(regionConfig);
        String configuredWorldName =
            RegionConfigLoader.detectFallbackConfiguredWorld(regionConfig, settings);
        boolean worldFallback = configuredWorldName != null;
        if (worldFallback) {
          RTPRuntime.log(
              Level.INFO,
              "[RTP] Region '" + settings.name() + "' configured for world '"
                  + configuredWorldName
                  + "' which isn't loaded yet; region is dormant and will activate when the "
                  + "world loads.");
        }
        Region region = new Region(settings.name(), settings, worldFallback, configuredWorldName);
        ActiveSelection.get().permRegionLookup.put(region.name, region);
      }
    } else if (parser.myClass.equals(WorldKeys.class) && serverAccessor != null) {
      for (RTPWorld<?> world : serverAccessor.getRTPWorlds()) {
        newParser.getParser(world.name());
      }
    }

    finishMultiReload(targetSenderId, parser, newParser, serverAccessor);
  }

  private void finishMultiReload(
      UUID targetSenderId,
      MultiConfigParser<?> parser,
      MultiConfigParser<?> newParser,
      @Nullable RTPServerAccessor serverAccessor) {
    Configs active = ActiveConfigs.get();
    if (active == null) return;
    active.multiConfigParserMap.put(parser.myClass, newParser);

    if (serverAccessor != null) {
      String msg = String.valueOf(active.getConfigValue(SystemMessages.reloaded, ""));
      if (msg != null) msg = filenamePattern.matcher(msg).replaceAll(parser.name);
      serverAccessor.sendMessage(RTPAPI.serverId, targetSenderId, msg);
    }
  }

  private boolean candidateAccepted(UUID senderId) {
    Configs configs = ActiveConfigs.get();
    if (configs == null) return false;
    ConfigCandidateGate.Result gate =
        ConfigCandidateGate.evaluate(configs.pluginDirectory.toPath(), Map.of());
    if (gate.rejected()) {
      ReloadSupport.sendReloadRejected(senderId, gate);
      return false;
    }
    return true;
  }
}
