package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

public class ReloadCmd extends BaseRTPCmdImpl {

  private static final Pattern filenamePattern =
      Pattern.compile("\\[filename]", Pattern.CASE_INSENSITIVE);

  public ReloadCmd(@Nullable CommandsAPICommand parent) {
    super(parent);

    addCommands();
  }

  public void addCommands() {
    final Configs configs = ActiveConfigs.get();
    if (configs == null) {
      // Configs are not loaded yet (e.g. the command tree is being built before
      // the rtp-core singleton finished bootstrapping its Configs - this is the
      // normal case on NeoForge, where RegisterCommandsEvent fires during the
      // initial datapack load). Reschedule and bail; dereferencing `configs`
      // below would NPE. RTP.getInstance() can itself be null this early, so
      // guard it before queueing the retry.
      TeleportSessions sessions = TeleportSessions.current();
      if (sessions != null) {
        MiscTasks.async().add(new RTPRunnable(this::addCommands, 1));
      }
      return;
    }
    for (ConfigParser<?> value : configs.configParserMap.values()) {
      String name = value.name.replace(".yml", "");
      if (getCommandLookup().containsKey(name)) continue;
      addSubCommand(
          new SubReloadCmd<>(
              this, value.name, "rtp.reload", "reload only " + value.name, value.myClass));
    }

    for (Map.Entry<Class<?>, MultiConfigParser<?>> e : configs.multiConfigParserMap.entrySet()) {
      MultiConfigParser<? extends Enum<?>> value = e.getValue();
      if (getCommandLookup().containsKey(value.name)) continue;
      addSubCommand(
          new SubReloadCmd<>(
              this, value.name, "rtp.reload", "reload only " + value.name, value.myClass));
    }
  }

  @Override
  public String name() {
    return "reload";
  }

  @Override
  public String permission() {
    return "rtp.reload";
  }

  @Override
  public boolean onCommand(
      UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(senderId, parameterValues, null);

    RTP.reloading.set(true);

    String msg = msg(SystemMessages.reloading, "");
    if (!msg.isEmpty()) {
      msg =
          filenamePattern
              .matcher(msg)
              .replaceAll("configs"); // msg = msg.replaceAll( "\\[filename]", "configs" );
      RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, senderId, msg);
    }

    try {
      RTPRuntime.scheduler().runTaskAsynchronously(() -> {
        try {
          Configs active = ActiveConfigs.get();
          boolean b = ReloadSupport.reloadAndRefresh(active);
          if (!b) {
            var rejection = active.lastReloadRejection();
            if (rejection.isPresent()) {
              ReloadSupport.sendReloadRejected(senderId, rejection.get());
              return;
            }
            throw new IllegalStateException("reload failed");
          }

          String msgReloaded = msg(SystemMessages.reloaded, "");
          if (!msgReloaded.isEmpty()) {
            msgReloaded =
                filenamePattern
                    .matcher(msgReloaded)
                    .replaceAll("configs"); // msg.replaceAll( "\\[filename]", "configs" );
            RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, senderId, msgReloaded);
          }
        } catch (RuntimeException failure) {
          sendReloadFailure(senderId, failure);
        } finally {
          RTP.reloading.set(false);
        }
      });
    } catch (RuntimeException failure) {
      RTP.reloading.set(false);
      sendReloadFailure(senderId, failure);
    }

    return true;
  }

  private void sendReloadFailure(UUID senderId, RuntimeException failure) {
    RTPRuntime.log(Level.SEVERE, "Failed to reload configs: " + failure.getMessage(), failure);
    String fallback = "&c[P0] Failed to reload configs. Check the server log.";
    String failureMessage = fallback;
    try {
      String configured = msg(SystemMessages.reloadFailed, fallback);
      if (configured != null && !configured.isBlank()) failureMessage = configured;
    } catch (RuntimeException messageFailure) {
      RTPRuntime.log(Level.WARNING, "Could not read the reload failure message", messageFailure);
    }
    RTPRuntime.serverAccessor().sendMessage(RTPAPI.serverId, senderId, failureMessage);
  }
}
