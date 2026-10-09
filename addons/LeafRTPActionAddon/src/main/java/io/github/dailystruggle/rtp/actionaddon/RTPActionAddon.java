package io.github.dailystruggle.rtp.actionaddon;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.addon.RTPAddon;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.action.ActionCacheWarmTask;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.commands.action.ActionSubCmd;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys;
import java.io.File;
import java.util.logging.Level;

/**
 * Platform-agnostic addon implementing the declarative scripted action subsystem (ADR-093).
 *
 * <p>Discovered via {@link java.util.ServiceLoader} on Paper, Folia, Fabric, and NeoForge alike.
 * Bundled inside the RTP jar and self-extracting into {@code <datafolder>/addons/}; completely
 * removable by deleting the jar (zero residual runtime overhead when omitted).
 */
public final class RTPActionAddon implements RTPAddon {

  private ActionManager actionManager;
  private ActionCacheWarmTask cacheWarmTask;
  private ActionSubCmd actionSubCmd;

  @Override
  public String name() {
    return "LeafRTPActionAddon";
  }

  @Override
  public void onLoad() {
    RTP.log(Level.INFO, "[LeafRTPActionAddon] Initializing declarative action subsystem (ADR-093)...");

    actionManager = new ActionManager();
    RTP.actionManager = actionManager;
    RTPAPI.actionService = actionManager;

    // Load definitions
    loadActionConfigurations();

    // Re-register and reload on /rtp reload
    Configs.onReload(this::loadActionConfigurations);

    // Register /rtp action subcommands onto baseCommand if present
    if (RTP.baseCommand != null) {
      actionSubCmd = new ActionSubCmd(RTP.baseCommand);
      RTP.baseCommand.addSubCommand(actionSubCmd);
      RTP.baseCommand.getCommandLookup().put("RUN", actionSubCmd);
      RTP.baseCommand.getCommandLookup().put("TRIGGER", actionSubCmd);
    }

    // Register dynamic root action commands if any action definitions declare them
    actionManager.registerAllCommands();

    // Start background pre-warming task (ADR-097)
    cacheWarmTask = new ActionCacheWarmTask(actionManager);
    RTP.actionCacheWarmTask = cacheWarmTask;
    if (RTP.scheduler != null) {
      // Periodic pre-warm task every 5 seconds (100 ticks)
      RTP.scheduler.runTaskTimerAsynchronously(cacheWarmTask, 100L, 100L);
    }

    RTP.log(Level.INFO, "[LeafRTPActionAddon] Subsystem loaded with " + actionManager.getActionIds().size() + " action definitions.");
  }

  private void loadActionConfigurations() {
    try {
      File pluginDir = RTP.serverAccessor.getPluginDirectory();
      String locale = "en";
      if (RTP.configs != null) {
        MultiConfigParser<ActionKeys> actionsParser =
            new MultiConfigParser<>(
                ActionKeys.class,
                "actions",
                "1.0",
                pluginDir,
                RTPActionAddon.class.getClassLoader(),
                "definitions/actions",
                locale);
        RTP.configs.putParser(actionsParser);
        actionManager.clearDefinitions();
        ActionConfigLoader.loadActions(actionsParser, actionManager);
      } else {
        actionManager.clearDefinitions();
        ActionConfigLoader.loadActions(pluginDir, actionManager);
      }
      actionManager.registerAllCommands();
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[LeafRTPActionAddon] Failed to load action configurations", e);
    }
  }

  @Override
  public void onUnload() {
    RTP.log(Level.INFO, "[LeafRTPActionAddon] Unloading action subsystem...");

    if (actionManager != null) {
      actionManager.clearCaches();
      actionManager.clearDefinitions();
    }

    if (RTP.baseCommand != null && actionSubCmd != null) {
      RTP.baseCommand.getCommandLookup().remove("ACTION");
      RTP.baseCommand.getCommandLookup().remove("RUN");
      RTP.baseCommand.getCommandLookup().remove("TRIGGER");
    }

    if (RTP.actionManager == actionManager) {
      RTP.actionManager = null;
    }
    if (RTPAPI.actionService == actionManager) {
      RTPAPI.actionService = null;
    }
    if (RTP.actionCacheWarmTask == cacheWarmTask) {
      RTP.actionCacheWarmTask = null;
    }

    actionManager = null;
    cacheWarmTask = null;
    actionSubCmd = null;
  }
}
