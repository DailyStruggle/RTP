package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /rtp config <file> viewraw} &mdash; streams the raw contents of the
 * underlying YAML configuration file back to the caller, line by line.
 *
 * <p>This is the legacy chat-dump behavior previously offered by
 * {@code ViewSubConfigCmd}. It was split out as part of the
 * &quot;config view as book&quot; rework (checklist step 6 / proposal v3.6.1)
 * so {@code view} could open the interactive book menu while operators that
 * still need the raw dump (e.g. on platforms without the book renderer, or
 * for copy/paste diagnostics) retain a stable entry point.
 *
 * <p>Read-only by design: this command never mutates state.
 */
public class ViewRawSubConfigCmd extends BaseRTPCmdImpl {

  /**
   * Hard cap on the number of lines streamed per invocation. Protects
   * against accidental flooding of a player's chat by a malformed or
   * unusually large configuration file.
   */
  static final int MAX_LINES = 1000;

  private final ConfigParser<?> configParser;

  public ViewRawSubConfigCmd(@Nullable CommandsAPICommand parent, @NotNull ConfigParser<?> configParser) {
    super(parent);
    this.configParser = configParser;
  }

  @Override
  public String name() {
    return "viewraw";
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public String description() {
    return "stream the raw YAML contents of this configuration file to chat";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    RTP.scheduler.runTaskAsynchronously(() -> {
      File file = new File(configParser.pluginDirectory, configParser.name);
      if (!file.exists() || !file.isFile()) {
        String msg = "&c[RTP config/viewraw] file not found: " + file.getPath();
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        RTP.log(Level.WARNING, msg);
        return;
      }

      List<String> lines;
      try {
        lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        String msg = "&c[RTP config/viewraw] failed to read " + configParser.name + ": " + e.getMessage();
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, msg);
        RTP.log(Level.WARNING, msg, e);
        return;
      }

      String header = "&e--- " + configParser.name + " (" + lines.size() + " lines) ---";
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, header);

      int emitted = 0;
      for (String line : lines) {
        if (emitted >= MAX_LINES) {
          RTP.serverAccessor.sendMessage(
              RTPAPI.serverId,
              callerId,
              "&7... (truncated at " + MAX_LINES + " lines; open the file directly for the rest)");
          break;
        }
        RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, line);
        emitted++;
      }
    });

    return true;
  }
}
