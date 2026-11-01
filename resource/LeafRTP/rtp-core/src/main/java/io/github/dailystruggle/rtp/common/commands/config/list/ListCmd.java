package io.github.dailystruggle.rtp.common.commands.config.list;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;

import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

public class ListCmd extends BaseRTPCmdImpl {
  private final String name;
  private final Supplier<Set<String>> values;
  private final RtpYamlConfig file;
  private final String key;

  public ListCmd(
      String name,
      @Nullable CommandsAPICommand parent,
      Supplier<Set<String>> values,
      RtpYamlConfig file,
      String key) {
    super(parent);
    this.name = name;
    this.values = values;
    this.file = file;
    this.key = key;

    MiscTasks.async().add(new RTPRunnable(this::addCommands, 20));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public String description() {
    return "update a list in this configuration file";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);
    addCommands();

    String updateMsg = String.valueOf(ActiveConfigs.get().getConfigValue(SystemMessages.updating, ""));
    if (updateMsg != null) updateMsg = updateMsg.replace("[filename]", name);
    RTPRuntime.serverAccessor().sendMessage(CommandsAPI.serverId, callerId, updateMsg);

    RTPRuntime.scheduler().runTaskAsynchronously(() -> {
      List<String> stringList = file.getStringList(key);

      List<String> add = parameterValues.get("add");
      if (add != null) {
        stringList.addAll(add);
      }

      List<String> remove = parameterValues.get("remove");
      if (remove != null) {
        stringList.removeAll(remove);
      }

      file.set(key, stringList);
      try {
        file.save();
      } catch (IOException e) {
        RTPRuntime.log(Level.WARNING, e.getMessage(), e);
        return;
      }

      String updatedMsg = String.valueOf(ActiveConfigs.get().getConfigValue(SystemMessages.updated, ""));
      if (updatedMsg != null) updatedMsg = updatedMsg.replace("[filename]", name);
      RTPRuntime.serverAccessor().sendMessage(CommandsAPI.serverId, callerId, updatedMsg);
    });

    return true;
  }

  public void addCommands() {
    addParameter("add", new ListAddParameter(values, file, key));
    addParameter("remove", new ListRemoveParameter(file, key));
  }
}
