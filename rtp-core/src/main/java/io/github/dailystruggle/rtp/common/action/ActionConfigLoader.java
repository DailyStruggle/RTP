package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads declarative scripted action definitions from {@code definitions/actions/*.yml} (ADR-093).
 */
public final class ActionConfigLoader {

  private ActionConfigLoader() {}

  /**
   * Scans {@code <pluginDir>/definitions/actions} and loads all {@code .yml} action files into {@link ActionManager}.
   *
   * @param pluginDirectory the root plugin data directory
   * @param manager         the action manager
   */
  public static void loadActions(File pluginDirectory, ActionManager manager) {
    if (pluginDirectory == null || manager == null) return;
    File actionsDir = new File(pluginDirectory, "definitions/actions");
    if (!actionsDir.exists() || !actionsDir.isDirectory()) {
      return;
    }

    File[] files = actionsDir.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("."));
    if (files == null) return;

    for (File file : files) {
      String id = file.getName().substring(0, file.getName().length() - 4);
      try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
        RtpYamlConfig yaml = RtpYamlConfig.parse(reader);
        ActionDefinition def = parseDefinition(id, yaml);
        manager.registerAction(def);
        RTP.log(Level.FINE, "[RTP Action] Loaded scripted action: " + id);
      } catch (Exception e) {
        RTP.log(Level.WARNING, "[RTP Action] Failed parsing action config: " + file.getName(), e);
      }
    }
  }

  /**
   * Parses an ActionDefinition from an {@link RtpYamlSection}.
   */
  public static ActionDefinition parseDefinition(String id, RtpYamlSection root) {
    String alias = root.getString("alias", id);
    String permission = root.getString("permission", "rtp.action." + id);
    String description = root.getString("description", "");

    // 1. Placement
    RtpYamlSection placementSec = root.getConfigurationSection("placement");
    ActionDefinition.PlacementSpec placement;
    if (placementSec != null) {
      String region = placementSec.getString("region");
      if (region == null) region = "default";
      String profile = placementSec.getString("profile");
      if (profile == null) profile = "default";
      RtpYamlSection paramsSec = placementSec.getConfigurationSection("parameters");
      int chunkRadius = 4;
      int minSep = 16;
      int elev = 8;
      Map<String, Object> params = new HashMap<>();
      if (paramsSec != null) {
        chunkRadius = paramsSec.getInt("subspaceChunkRadius");
        if (chunkRadius <= 0) chunkRadius = 4;
        minSep = paramsSec.getInt("minSeparation");
        if (minSep <= 0) minSep = 16;
        elev = paramsSec.getInt("elevationTolerance");
        if (elev <= 0) elev = 8;
        for (String k : paramsSec.getKeys(false)) {
          params.put(k, paramsSec.get(k));
        }
      }
      // Anchor source selection (ADR-095): regionQueue (default), entity, claimHazard, fixed.
      // Folded into the parameters map so the PlacementSpec record stays signature-stable.
      String anchorType = placementSec.getString("anchor");
      if (anchorType != null && !anchorType.isBlank()) {
        params.put("anchor", anchorType.trim());
      }
      placement = new ActionDefinition.PlacementSpec(region, profile, chunkRadius, minSep, elev, params);
    } else {
      placement = ActionDefinition.PlacementSpec.DEFAULT;
    }

    // 2. Confinement
    RtpYamlSection confSec = root.getConfigurationSection("confinement");
    ActionDefinition.ConfinementSpec confinement;
    if (confSec != null) {
      String bStr = confSec.getString("boundary");
      if (bStr == null) bStr = "SUBSPACE";
      bStr = bStr.toUpperCase();
      ConfinementBoundary boundary = switch (bStr) {
        case "REGION" -> ConfinementBoundary.REGION;
        case "LEASH" -> ConfinementBoundary.LEASH;
        default -> ConfinementBoundary.SUBSPACE;
      };
      String durStr = confSec.getString("duration");
      long durSec = GateExpressionParser.parseDurationSeconds(durStr, 300L);
      double leash = confSec.getDouble("leashRadius");
      if (leash <= 0.0) leash = 64.0;
      confinement = new ActionDefinition.ConfinementSpec(boundary, durSec, leash);
    } else {
      confinement = ActionDefinition.ConfinementSpec.DEFAULT;
    }

    // 3. Lifecycle
    RtpYamlSection lifeSec = root.getConfigurationSection("lifecycle");
    ActionDefinition.LifecycleSpec lifecycle;
    if (lifeSec != null) {
      List<ActionDefinition.LifecycleStep> onStart = parseSteps(lifeSec.getList("onStart"));
      List<ActionDefinition.LifecycleStep> onBoundaryViolation = parseSteps(lifeSec.getList("onBoundaryViolation"));
      List<ActionDefinition.LifecycleStep> onExpire = parseSteps(lifeSec.getList("onExpire"));
      List<ActionDefinition.LifecycleStep> onDeath = parseSteps(lifeSec.getList("onDeath"));
      lifecycle = new ActionDefinition.LifecycleSpec(onStart, onBoundaryViolation, onExpire, onDeath);
    } else {
      lifecycle = ActionDefinition.LifecycleSpec.EMPTY;
    }

    return new ActionDefinition(id, alias, permission, description, placement, confinement, lifecycle);
  }

  @SuppressWarnings("unchecked")
  private static List<ActionDefinition.LifecycleStep> parseSteps(List<?> rawList) {
    if (rawList == null || rawList.isEmpty()) return Collections.emptyList();
    List<ActionDefinition.LifecycleStep> steps = new ArrayList<>();

    for (Object item : rawList) {
      Map<String, Object> typedMap = null;
      if (item instanceof Map<?, ?> map) {
        typedMap = (Map<String, Object>) map;
      } else if (item instanceof RtpYamlSection sec) {
        typedMap = sec.getValues(false);
      }
      if (typedMap == null) continue;

      // Check if it's a gated step: { gate: { ... }, run: [ ... ] }
      if (typedMap.containsKey("gate") && typedMap.containsKey("run")) {
        Object gObj = typedMap.get("gate");
        Map<String, Object> gateConfig = (gObj instanceof RtpYamlSection s)
            ? s.getValues(false)
            : (gObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap());
        List<?> runList = (List<?>) typedMap.get("run");
        List<ActionDefinition.CommandAction> actions = parseActions(runList);
        steps.add(new ActionDefinition.LifecycleStep(gateConfig, actions));
      } else {
        // Ungated step: single action map, e.g. { CONSOLE: "..." } or { FOR_EACH: { ... } }
        List<ActionDefinition.CommandAction> actions = parseActionFromMap(typedMap);
        steps.add(new ActionDefinition.LifecycleStep(Collections.emptyMap(), actions));
      }
    }
    return steps;
  }

  @SuppressWarnings("unchecked")
  private static List<ActionDefinition.CommandAction> parseActions(List<?> rawList) {
    if (rawList == null || rawList.isEmpty()) return Collections.emptyList();
    List<ActionDefinition.CommandAction> actions = new ArrayList<>();
    for (Object item : rawList) {
      if (item instanceof Map<?, ?> map) {
        actions.addAll(parseActionFromMap((Map<String, Object>) map));
      } else if (item instanceof RtpYamlSection sec) {
        actions.addAll(parseActionFromMap(sec.getValues(false)));
      }
    }
    return actions;
  }

  @SuppressWarnings("unchecked")
  private static List<ActionDefinition.CommandAction> parseActionFromMap(Map<String, Object> map) {
    List<ActionDefinition.CommandAction> res = new ArrayList<>();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String key = entry.getKey().toUpperCase();
      Object val = entry.getValue();

      switch (key) {
        case "CONSOLE" -> res.add(ActionDefinition.CommandAction.console(val != null ? val.toString() : ""));
        case "PLAYER" -> res.add(ActionDefinition.CommandAction.player(val != null ? val.toString() : ""));
        case "ACTION" -> res.add(ActionDefinition.CommandAction.action(val != null ? val.toString() : ""));
        case "FOR_EACH" -> {
          if (val instanceof RtpYamlSection sec) {
            List<ActionDefinition.CommandAction> subActions = parseActionFromMap(sec.getValues(false));
            res.add(ActionDefinition.CommandAction.forEach(subActions));
          } else if (val instanceof Map<?, ?> subMap) {
            List<ActionDefinition.CommandAction> subActions = parseActionFromMap((Map<String, Object>) subMap);
            res.add(ActionDefinition.CommandAction.forEach(subActions));
          } else if (val instanceof List<?> subList) {
            List<ActionDefinition.CommandAction> subActions = parseActions(subList);
            res.add(ActionDefinition.CommandAction.forEach(subActions));
          }
        }
        default -> {
          // Ignore gate keys or unknown properties
        }
      }
    }
    return res;
  }
}
