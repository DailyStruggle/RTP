package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.action.ConfinementBoundary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ActionKeys;
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
 * Loads and maps declarative scripted action definitions from {@link MultiConfigParser} or YAML sections (ADR-093).
 * File-system scanning, jar resource extraction, and config reloading are handled by {@link MultiConfigParser}.
 */
public final class ActionConfigLoader {

  private ActionConfigLoader() {}

  /**
   * Loads all action definitions from {@link MultiConfigParser} into {@link ActionManager}.
   *
   * @param actionsParser the multi-config parser managing definitions/actions
   * @param manager       the action manager
   */
  public static void loadActions(MultiConfigParser<ActionKeys> actionsParser, ActionManager manager) {
    if (actionsParser == null || manager == null) return;
    for (ConfigParser<ActionKeys> parser : actionsParser.configParserFactory.map.values()) {
      String id = parser.name.replace(".yml", "");
      try {
        ActionDefinition def = parseDefinition(id, parser);
        manager.registerAction(def);
        RTP.log(Level.FINE, "[RTP Action] Loaded scripted action: " + id);
      } catch (Exception e) {
        RTP.log(Level.WARNING, "[RTP Action] Failed parsing action config: " + parser.name, e);
      }
    }
  }

  /**
   * Legacy / direct filesystem overload for test setups without a full {@link MultiConfigParser}.
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
   * Parses an ActionDefinition from a {@link ConfigParser<ActionKeys>}.
   */
  public static ActionDefinition parseDefinition(String id, ConfigParser<ActionKeys> parser) {
    String alias = String.valueOf(parser.getConfigValue(ActionKeys.alias, id));
    String permission = String.valueOf(parser.getConfigValue(ActionKeys.permission, "rtp.action." + id));
    String description = String.valueOf(parser.getConfigValue(ActionKeys.description, ""));

    // 1. Placement
    Map<String, Object> placementMap = parser.getMap(ActionKeys.placement);
    ActionDefinition.PlacementSpec placement = parsePlacement(placementMap);

    // 2. Confinement
    Map<String, Object> confMap = parser.getMap(ActionKeys.confinement);
    ActionDefinition.ConfinementSpec confinement = parseConfinement(confMap);

    // 3. Lifecycle
    Map<String, Object> lifeMap = parser.getMap(ActionKeys.lifecycle);
    ActionDefinition.LifecycleSpec lifecycle = parseLifecycle(lifeMap);

    // 4. Command
    Map<String, Object> cmdMap = parser.getMap(ActionKeys.command);
    ActionDefinition.CommandSpec command = parseCommand(cmdMap);

    return new ActionDefinition(id, alias, permission, description, placement, confinement, lifecycle, command);
  }

  /**
   * Parses an ActionDefinition from an {@link RtpYamlSection}.
   */
  public static ActionDefinition parseDefinition(String id, RtpYamlSection root) {
    String alias = root.getString("alias", id);
    String permission = root.getString("permission", "rtp.action." + id);
    String description = root.getString("description", "");

    RtpYamlSection placementSec = root.getConfigurationSection("placement");
    ActionDefinition.PlacementSpec placement = (placementSec != null)
        ? parsePlacement(placementSec.getValues(false))
        : ActionDefinition.PlacementSpec.DEFAULT;

    RtpYamlSection confSec = root.getConfigurationSection("confinement");
    ActionDefinition.ConfinementSpec confinement = (confSec != null)
        ? parseConfinement(confSec.getValues(false))
        : ActionDefinition.ConfinementSpec.DEFAULT;

    RtpYamlSection lifeSec = root.getConfigurationSection("lifecycle");
    ActionDefinition.LifecycleSpec lifecycle = (lifeSec != null)
        ? parseLifecycle(lifeSec.getValues(false))
        : ActionDefinition.LifecycleSpec.EMPTY;

    RtpYamlSection cmdSec = root.getConfigurationSection("command");
    ActionDefinition.CommandSpec command = (cmdSec != null)
        ? parseCommand(cmdSec.getValues(false))
        : ActionDefinition.CommandSpec.EMPTY;

    return new ActionDefinition(id, alias, permission, description, placement, confinement, lifecycle, command);
  }

  @SuppressWarnings("unchecked")
  private static ActionDefinition.CommandSpec parseCommand(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return ActionDefinition.CommandSpec.EMPTY;
    }
    String name = map.containsKey("name") ? String.valueOf(map.get("name")) : "";
    if (name.isBlank() && map.containsKey("alias")) {
      name = String.valueOf(map.get("alias"));
    }
    String perm = map.containsKey("permission") ? String.valueOf(map.get("permission")) : "";
    String desc = map.containsKey("description") ? String.valueOf(map.get("description")) : "";

    List<String> aliases = new ArrayList<>();
    Object rawAliases = map.get("aliases");
    if (rawAliases instanceof List<?> list) {
      for (Object o : list) {
        if (o != null) aliases.add(String.valueOf(o).trim());
      }
    } else if (rawAliases instanceof String s && !s.isBlank()) {
      aliases.add(s.trim());
    }

    return new ActionDefinition.CommandSpec(name, perm, desc, aliases);
  }

  @SuppressWarnings("unchecked")
  private static ActionDefinition.PlacementSpec parsePlacement(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return ActionDefinition.PlacementSpec.DEFAULT;
    }
    String region = map.containsKey("region") ? String.valueOf(map.get("region")) : "default";

    // Parse shape: canonical shape map (matching regions/*.yml) or scalar shape name
    String shapeName = "SQUARE";
    int radius = 64; // default 64 blocks (4 chunks)
    int centerRadius = 0;
    double weight = 1.0;

    Object rawShape = map.get("shape");
    Map<String, Object> shapeMap = null;
    if (rawShape instanceof Map<?, ?> m) {
      shapeMap = (Map<String, Object>) m;
    } else if (rawShape instanceof RtpYamlSection sec) {
      shapeMap = sec.getValues(false);
    } else if (rawShape instanceof String s && !s.isBlank()) {
      shapeName = s.trim().toUpperCase();
    }

    if (shapeMap != null) {
      if (shapeMap.containsKey("name")) {
        shapeName = String.valueOf(shapeMap.get("name")).trim().toUpperCase();
      }
      if (shapeMap.containsKey("radius")) {
        radius = parseRadius(shapeMap.get("radius"), 64);
      }
      if (shapeMap.containsKey("centerRadius")) {
        centerRadius = parseRadius(shapeMap.get("centerRadius"), 0);
      }
      if (shapeMap.get("weight") instanceof Number num) {
        weight = num.doubleValue();
      }
    }

    int minSep = 16;
    int elev = 8;
    if (map.get("minSeparation") instanceof Number n) minSep = n.intValue();
    if (map.get("elevationTolerance") instanceof Number n) elev = n.intValue();

    Map<String, Object> params = new HashMap<>();
    params.put("shapeName", shapeName);
    params.put("radius", radius);
    params.put("centerRadius", centerRadius);
    params.put("weight", weight);

    if (map.get("clusterSeparation") instanceof Number n) params.put("clusterSeparation", n.intValue());
    if (map.get("teammateSeparation") instanceof Number n) params.put("teammateSeparation", n.intValue());
    if (map.get("groupSeparation") instanceof Number n) params.put("groupSeparation", n.intValue());

    Object rawParams = map.get("parameters");
    Map<String, Object> paramsMap = null;
    if (rawParams instanceof Map<?, ?> m) {
      paramsMap = (Map<String, Object>) m;
    } else if (rawParams instanceof RtpYamlSection sec) {
      paramsMap = sec.getValues(false);
    }

    if (paramsMap != null) {
      if (paramsMap.get("minSeparation") instanceof Number n) minSep = n.intValue();
      if (paramsMap.get("elevationTolerance") instanceof Number n) elev = n.intValue();
      params.putAll(paramsMap);
    }

    if (map.containsKey("anchor")) {
      String anchorType = String.valueOf(map.get("anchor"));
      if (!anchorType.isBlank()) {
        params.put("anchor", anchorType.trim());
      }
    }

    int retries = 3;
    if (map.get("retries") instanceof Number n && n.intValue() > 0) {
      retries = n.intValue();
    }
    int cacheSize = 0;
    if (map.get("cacheSize") instanceof Number n && n.intValue() >= 0) {
      cacheSize = n.intValue();
    }

    return new ActionDefinition.PlacementSpec(region, shapeName, radius, minSep, elev, params, retries, cacheSize);
  }

  private static int parseRadius(Object raw, int defaultBlocks) {
    if (raw == null) return defaultBlocks;
    if (raw instanceof Number n) {
      return n.intValue();
    }
    String s = String.valueOf(raw).trim();
    io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.ParsedDistance d =
        io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser.parse(
            s, io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit.BLOCK);
    if (d != null) {
      return (int) Math.round(d.toBlocks());
    }
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return defaultBlocks;
    }
  }

  private static ActionDefinition.ConfinementSpec parseConfinement(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return ActionDefinition.ConfinementSpec.DEFAULT;
    }
    String bStr = map.containsKey("boundary") ? String.valueOf(map.get("boundary")).toUpperCase() : "SUBSPACE";
    ConfinementBoundary boundary = switch (bStr) {
      case "REGION" -> ConfinementBoundary.REGION;
      case "LEASH" -> ConfinementBoundary.LEASH;
      default -> ConfinementBoundary.SUBSPACE;
    };
    String durStr = map.containsKey("duration") ? String.valueOf(map.get("duration")) : null;
    long durSec = GateExpressionParser.parseDurationSeconds(durStr, 300L);
    double leash = 64.0;
    if (map.get("leashRadius") instanceof Number n && n.doubleValue() > 0.0) {
      leash = n.doubleValue();
    }
    double initialSize = 0.0;
    if (map.containsKey("initialSize")) {
      initialSize = parseRadius(map.get("initialSize"), 0);
    }
    double shrinkTo = 0.0;
    if (map.containsKey("shrinkTo")) {
      shrinkTo = parseRadius(map.get("shrinkTo"), 0);
    }
    long shrinkOver = 0L;
    if (map.containsKey("shrinkOver")) {
      shrinkOver = GateExpressionParser.parseDurationSeconds(String.valueOf(map.get("shrinkOver")), 0L);
    }
    return new ActionDefinition.ConfinementSpec(boundary, durSec, leash, initialSize, shrinkTo, shrinkOver);
  }

  @SuppressWarnings("unchecked")
  private static ActionDefinition.LifecycleSpec parseLifecycle(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return ActionDefinition.LifecycleSpec.EMPTY;
    }
    List<ActionDefinition.LifecycleStep> onStart = parseSteps(toList(map.get("onStart")));
    List<ActionDefinition.LifecycleStep> onBoundaryViolation = parseSteps(toList(map.get("onBoundaryViolation")));
    List<ActionDefinition.LifecycleStep> onExpire = parseSteps(toList(map.get("onExpire")));
    List<ActionDefinition.LifecycleStep> onDeath = parseSteps(toList(map.get("onDeath")));
    return new ActionDefinition.LifecycleSpec(onStart, onBoundaryViolation, onExpire, onDeath);
  }

  private static List<?> toList(Object obj) {
    if (obj instanceof List<?> l) return l;
    return Collections.emptyList();
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
      long delaySeconds = 0L;
      if (typedMap.containsKey("delay")) {
        delaySeconds = GateExpressionParser.parseDurationSeconds(String.valueOf(typedMap.get("delay")), 0L);
      } else if (typedMap.containsKey("delaySeconds") && typedMap.get("delaySeconds") instanceof Number n) {
        delaySeconds = n.longValue();
      }

      if (typedMap.containsKey("gate") && typedMap.containsKey("run")) {
        Object gObj = typedMap.get("gate");
        Map<String, Object> gateConfig = (gObj instanceof RtpYamlSection s)
            ? s.getValues(false)
            : (gObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Collections.emptyMap());
        List<?> runList = (List<?>) typedMap.get("run");
        List<ActionDefinition.CommandAction> actions = parseActions(runList);
        steps.add(new ActionDefinition.LifecycleStep(gateConfig, actions, delaySeconds));
      } else {
        // Ungated step: single action map, e.g. { CONSOLE: "..." } or { FOR_EACH: { ... } }
        List<ActionDefinition.CommandAction> actions = parseActionFromMap(typedMap);
        steps.add(new ActionDefinition.LifecycleStep(Collections.emptyMap(), actions, delaySeconds));
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
