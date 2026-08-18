package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.jetbrains.annotations.Nullable;

/** Top-level {@code /rtp config} command. Dynamically registers sub-commands for each loaded config file. */
public class ConfigCmd extends BaseRTPCmdImpl {

  /**
   * Constructs the config command and schedules sub-command registration.
   *
   * @param parent the parent command tree, or {@code null}
   */
  public ConfigCmd(@Nullable CommandsAPICommand parent) {
    super(parent);

    RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(this::addCommands, 5));
  }

  @Override
  public String name() {
    return "config";
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    return true;
  }

  /** Registers sub-commands for each loaded config file. Called asynchronously after startup. */
  public void addCommands() {
    if (!getCommandLookup().containsKey("language")) {
      addSubCommand(new LanguageCmd(this));
    }

    // Register the submit-landing leaf for the menu config-search anvil prompt. The
    // handler is filled in later by the platform wiring (e.g. RTPCmdBukkit)
    // once a renderer is available; without a handler the leaf is a no-op
    // with WARN log, which is also the disabled-state contract.
    if (!getCommandLookup().containsKey("search")) {
      addSubCommand(new ConfigSearchSubCmd(this));
    }

    final Configs configs = RTP.configs;
    for (ConfigParser<?> value : configs.configParserMap.values()) {
      String bare = value.name.replace(".yml", "");
      if (getCommandLookup().containsKey(bare)) continue;
      SubConfigCmd sub = new SubConfigCmd(this, value.name, value);
      addSubCommand(sub);
      // Register the same sub-command under the bare basename (e.g. "config"
      // in addition to "config.yml") so /rtp config <file> ... resolves
      // regardless of whether the caller writes the .yml suffix. Required by
      // the menu Apply pathway in MenuRedeemSubcommand#dispatchApplyStagedConfig,
      // which composes "/rtp config <bare> k=v" from the cart's normalized
      // (suffix-stripped) file key. TreeCommand stores keys upper-cased
      // (TreeCommand.java line 30), so write the alias the same way.
      getCommandLookup().put(bare.toUpperCase(java.util.Locale.ROOT), sub);
    }

    for (Map.Entry<Class<?>, MultiConfigParser<?>> e : configs.multiConfigParserMap.entrySet()) {
      MultiConfigParser<? extends Enum<?>> value = e.getValue();
      if (getCommandLookup().containsKey(value.name)) continue;
      SubConfigCmd sub = new SubConfigCmd(this, value.name, value);
      addSubCommand(sub);
      // MultiConfigParser names ("regions", "worlds") do not carry a .yml
      // suffix, but the same alias-via-bare-name rule is harmless: stripping
      // ".yml" from a name that doesn't contain it is a no-op, so the alias
      // and primary key collide on the same value - idempotent put.
      String bare = value.name.replace(".yml", "");
      getCommandLookup().put(bare.toUpperCase(java.util.Locale.ROOT), sub);
    }
  }
}
