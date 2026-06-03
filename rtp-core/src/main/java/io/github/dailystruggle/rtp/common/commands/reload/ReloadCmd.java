package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

public class ReloadCmd extends BaseRTPCmdImpl {

  private static final Pattern filenamePattern =
      Pattern.compile("\\[filename]", Pattern.CASE_INSENSITIVE);

  public ReloadCmd(@Nullable CommandsAPICommand parent) {
    super(parent);

    addCommands();
  }

  public void addCommands() {
    final Configs configs = RTP.configs;
    if (configs == null) {
      // Configs are not loaded yet (e.g. the command tree is being built before
      // the rtp-core singleton finished bootstrapping its Configs - this is the
      // normal case on NeoForge, where RegisterCommandsEvent fires during the
      // initial datapack load). Reschedule and bail; dereferencing `configs`
      // below would NPE. RTP.getInstance() can itself be null this early, so
      // guard it before queueing the retry.
      RTP rtp = RTP.getInstance();
      if (rtp != null) {
        rtp.miscAsyncTasks.add(new RTPRunnable(this::addCommands, 1));
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

    String msg = msg(MessagesKeys.reloading, "");
    if (!msg.isEmpty()) {
      msg =
          filenamePattern
              .matcher(msg)
              .replaceAll("configs"); // msg = msg.replaceAll( "\\[filename]", "configs" );
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg);
    }

    RTP.scheduler.runTaskAsynchronously(() -> {
      boolean b = RTP.configs.reload();
      if (!b) throw new IllegalStateException("reload failed");

      // Drop the decoded-schematic cache so a swapped/edited <region>.schem file on disk is
      // re-read on the next teleport instead of serving the stale in-memory copy (ADR-058).
      io.github.dailystruggle.rtp.api.schematic.AbstractFileSchematicPaster.clearCache();

      String msgReloaded = msg(MessagesKeys.reloaded, "");
      if (!msgReloaded.isEmpty()) {
        msgReloaded =
            filenamePattern
                .matcher(msgReloaded)
                .replaceAll("configs"); // msg.replaceAll( "\\[filename]", "configs" );
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, msgReloaded);
      }

      RTP.getInstance()
              .miscSyncTasks
              .add(
                      new RTPRunnable(
                              () -> {
                                RTP.reloading.set(false);
                              },
                              1));
    });

    return true;
  }
}
