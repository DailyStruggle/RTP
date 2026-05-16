package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import org.jetbrains.annotations.Nullable;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Subcommand: /rtp config language &lt;locale&gt;
 *
 * <p>Writes the chosen locale to {@code language.yml} and triggers a full reload so that all
 * config parsers immediately switch to the new locale. Satisfies REQ-RTP-F-013 (user-facing
 * messages remain configurable) and honours S-004 (failures are reported, never swallowed).
 */
public class LanguageCmd extends BaseRTPCmdImpl {

  /** Locales bundled inside the JAR under {@code lang/<locale>/}. */
  private static final Set<String> BUNDLED_LOCALES =
      Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("en", "de", "es", "fr", "nl")));

  private static final String PARAM_LOCALE = "locale";

  public LanguageCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        PARAM_LOCALE,
        new CommandParameter("rtp.config", "language locale to activate", (uuid, s) -> true) {
          @Override
          public Set<String> values() {
            return BUNDLED_LOCALES;
          }
        });
  }

  @Override
  public String name() {
    return "language";
  }

  @Override
  public String permission() {
    return "rtp.config";
  }

  @Override
  public String description() {
    return "change the active language / locale";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    List<String> localeArgs = parameterValues.get(PARAM_LOCALE);
    if (localeArgs == null || localeArgs.isEmpty()) {
      msgInvalidCommand(callerId, "LANG");
      return false;
    }

    String raw = localeArgs.get(0);
    String locale = LanguageBootstrap.sanitize(raw);
    if (locale.equals(LanguageBootstrap.DEFAULT_LOCALE) && !raw.equalsIgnoreCase(LanguageBootstrap.DEFAULT_LOCALE)) {
      // sanitize() fell back to default because raw was invalid
      msgBadParameter(callerId, PARAM_LOCALE, raw, "LANG");
      return false;
    }

    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String updateMsg = String.valueOf(lang.getConfigValue(MessagesKeys.updating, ""));
    updateMsg = updateMsg.replace("[filename]", LanguageBootstrap.FILE_NAME);
    RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updateMsg);

    final String finalLocale = locale;
    RTP.scheduler.runTaskAsynchronously(() -> {
      File pluginDir = RTP.serverAccessor.getPluginDirectory();
      File file = new File(pluginDir, LanguageBootstrap.FILE_NAME);

      try {
        RtpYamlConfig yaml = new RtpYamlConfig(file.getPath());
        if (file.exists()) {
          yaml.loadWithComments();
        }
        yaml.set(LanguageBootstrap.KEY, finalLocale);
        yaml.save();
      } catch (IOException e) {
        RTP.log(Level.WARNING, "[RTP] LanguageCmd failed to write " + LanguageBootstrap.FILE_NAME, e);
        msgInvalidCommand(callerId, "LANG");
        return;
      }

      String updatedMsg = String.valueOf(lang.getConfigValue(MessagesKeys.updated, ""));
      updatedMsg = updatedMsg.replace("[filename]", LanguageBootstrap.FILE_NAME);
      RTP.serverAccessor.sendMessage(RTPAPI.serverId, callerId, updatedMsg);

      CommandsAPICommand reload =
          RTP.baseCommand.getCommandLookup().getOrDefault("reload", new ReloadCmd(RTP.baseCommand));
      reload.onCommand(callerId, new HashMap<>(), null);
    });

    return true;
  }
}
