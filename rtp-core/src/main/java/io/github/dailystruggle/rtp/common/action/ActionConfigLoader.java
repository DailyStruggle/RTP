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
import java.util.LinkedHashMap;
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
    if (parser == null) {
      return parseDefinition(id, (RtpYamlSection) null);
    }

    RtpYamlSection root = parser.getYamlRoot();
    if (root != null) {
      return parseDefinition(id, root);
    }

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

    // 5. Gates (ADR-093)
    List<Map<String, Object>> gateList = new ArrayList<>();
    Object rawGate = parser.getData(ActionKeys.gate);
    if (rawGate == null) rawGate = parser.getData(ActionKeys.gates);
    if (rawGate == null) rawGate = parser.getConfigValue(ActionKeys.gate, null);
    if (rawGate == null) rawGate = parser.getConfigValue(ActionKeys.gates, null);
    if (rawGate instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          gateList.add((Map<String, Object>) m);
        } else if (item instanceof RtpYamlSection sec) {
          gateList.add(sec.getValues(false));
        }
      }
    } else if (rawGate instanceof Map<?, ?> m) {
      gateList.add((Map<String, Object>) m);
    } else if (rawGate instanceof RtpYamlSection sec) {
      gateList.add(sec.getValues(false));
    }

    return new ActionDefinition(id, alias, permission, description, placement, confinement, lifecycle, command, gateList);
  }

  /**
   * Parses an ActionDefinition from an {@link RtpYamlSection}.
   */
  public static ActionDefinition parseDefinition(String id, RtpYamlSection root) {
    if (root == null) {
      return new ActionDefinition(
          id, id, "rtp.action." + id, "",
          ActionDefinition.PlacementSpec.DEFAULT,
          ActionDefinition.ConfinementSpec.DEFAULT,
          ActionDefinition.LifecycleSpec.EMPTY,
          ActionDefinition.CommandSpec.EMPTY);
    }
    String alias = root.getString("alias", id);
    String permission = root.getString("permission", "rtp.action." + id);
    String description = root.getString("description", "");

    Object rawPlacement = root.get("placement");
    ActionDefinition.PlacementSpec placement = ActionDefinition.PlacementSpec.DEFAULT;
    if (rawPlacement instanceof String s && ("none".equalsIgnoreCase(s.trim()) || "false".equalsIgnoreCase(s.trim()) || "disabled".equalsIgnoreCase(s.trim()))) {
      placement = ActionDefinition.PlacementSpec.DISABLED;
    } else if (rawPlacement instanceof Boolean b && !b) {
      placement = ActionDefinition.PlacementSpec.DISABLED;
    } else if (rawPlacement instanceof Map<?, ?> m) {
      placement = parsePlacement((Map<String, Object>) m);
    } else {
      RtpYamlSection placementSec = root.getConfigurationSection("placement");
      if (placementSec != null) {
        placement = parsePlacement(placementSec.getValues(false));
      }
    }

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

    // Action-level pre-execution gates (single map or list of maps)
    List<Map<String, Object>> gateList = new ArrayList<>();
    Object rawGate = root.get("gate");
    if (rawGate == null) rawGate = root.get("gates");

    if (rawGate instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m) {
          gateList.add((Map<String, Object>) m);
        } else if (item instanceof RtpYamlSection sec) {
          gateList.add(sec.getValues(false));
        }
      }
    } else if (rawGate instanceof Map<?, ?> m) {
      gateList.add((Map<String, Object>) m);
    } else {
      RtpYamlSection gateSec = root.getConfigurationSection("gate");
      if (gateSec == null) gateSec = root.getConfigurationSection("gates");
      if (gateSec != null) {
        gateList.add(gateSec.getValues(false));
      }
    }

    return new ActionDefinition(id, alias, permission, description, placement, confinement, lifecycle, command, gateList);
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

    List<io.github.dailystruggle.rtp.api.action.ParameterSpec> parameters = parseParameters(map.get("parameters"));
    Map<String, ActionDefinition.SubcommandSpec> subcommands = parseSubcommands(map.get("subcommands"));

    return new ActionDefinition.CommandSpec(name, perm, desc, aliases, parameters, subcommands);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ActionDefinition.SubcommandSpec> parseSubcommands(Object raw) {
    if (raw == null) return Collections.emptyMap();
    Map<String, ActionDefinition.SubcommandSpec> result = new LinkedHashMap<>();

    Map<String, Object> subMap = null;
    if (raw instanceof Map<?, ?> m) {
      subMap = (Map<String, Object>) m;
    } else if (raw instanceof RtpYamlSection sec) {
      subMap = sec.getValues(false);
    }
    if (subMap == null) return Collections.emptyMap();

    for (Map.Entry<String, Object> entry : subMap.entrySet()) {
      String subName = entry.getKey();
      if (subName == null || subName.isBlank()) continue;
      Object val = entry.getValue();
      Map<String, Object> cfg = null;
      if (val instanceof Map<?, ?> m) {
        cfg = (Map<String, Object>) m;
      } else if (val instanceof RtpYamlSection sec) {
        cfg = sec.getValues(false);
      }
      if (cfg == null) continue;

      String perm = cfg.containsKey("permission") ? String.valueOf(cfg.get("permission")) : "";
      String desc = cfg.containsKey("description") ? String.valueOf(cfg.get("description")) : "";

      List<String> aliases = new ArrayList<>();
      Object rawAliases = cfg.get("aliases");
      if (rawAliases instanceof List<?> list) {
        for (Object o : list) {
          if (o != null) aliases.add(String.valueOf(o).trim());
        }
      } else if (rawAliases instanceof String s && !s.isBlank()) {
        aliases.add(s.trim());
      }

      List<ActionDefinition.CommandAction> actions = new ArrayList<>();
      Object rawRun = cfg.get("run");
      if (rawRun == null) rawRun = cfg.get("actions");
      if (rawRun instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> m) {
            actions.addAll(parseActionFromMap((Map<String, Object>) m));
          } else if (item instanceof RtpYamlSection sec) {
            actions.addAll(parseActionFromMap(sec.getValues(false)));
          }
        }
      } else if (rawRun instanceof Map<?, ?> m) {
        actions.addAll(parseActionFromMap((Map<String, Object>) m));
      } else if (rawRun instanceof RtpYamlSection sec) {
        actions.addAll(parseActionFromMap(sec.getValues(false)));
      }

      result.put(subName.toLowerCase(), new ActionDefinition.SubcommandSpec(subName, perm, desc, aliases, actions));
    }

    return result;
  }

  /**
   * Parses declarative command parameters (ADR-098). Each parameter is validated immediately;
   * a symbolic default illegal for the declared type (or a blank name) throws to fail fast at load.
   */
  @SuppressWarnings("unchecked")
  private static List<io.github.dailystruggle.rtp.api.action.ParameterSpec> parseParameters(Object raw) {
    List<io.github.dailystruggle.rtp.api.action.ParameterSpec> parameters = new ArrayList<>();
    List<Object> rawList = new ArrayList<>();
    if (raw instanceof List<?> list) {
      rawList.addAll(list);
    } else if (raw instanceof RtpYamlSection sec) {
      rawList.addAll(sec.getValues(false).values());
    } else if (raw instanceof Map<?, ?> m) {
      rawList.addAll(m.values());
    } else if (raw != null) {
      return parameters;
    } else {
      return parameters;
    }

    for (Object o : rawList) {
      Map<String, Object> pMap;
      if (o instanceof Map<?, ?> m) {
        pMap = (Map<String, Object>) m;
      } else if (o instanceof RtpYamlSection sec) {
        pMap = sec.getValues(false);
      } else {
        continue;
      }

      String pName = pMap.containsKey("name") ? String.valueOf(pMap.get("name")) : "";
      io.github.dailystruggle.rtp.api.action.ParameterType pType =
          io.github.dailystruggle.rtp.api.action.ParameterType.parse(
              pMap.containsKey("type") ? String.valueOf(pMap.get("type")) : null);
      boolean required = pMap.get("required") instanceof Boolean b && b;
      String pPerm = pMap.containsKey("permission") ? String.valueOf(pMap.get("permission")) : "";
      String pDefault = pMap.containsKey("default") ? String.valueOf(pMap.get("default")) : "";

      io.github.dailystruggle.rtp.api.action.ParameterSpec spec =
          new io.github.dailystruggle.rtp.api.action.ParameterSpec(pName, pType, required, pPerm, pDefault);
      spec.validate(); // fail-fast on config error (invalid symbolic default / blank name)
      parameters.add(spec);
    }
    return parameters;
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

    boolean enabled = true;
    if (map.containsKey("enabled")) {
      Object enObj = map.get("enabled");
      if (enObj instanceof Boolean b) enabled = b;
      else if (enObj != null) enabled = Boolean.parseBoolean(enObj.toString());
    }

    return new ActionDefinition.PlacementSpec(enabled, region, shapeName, radius, centerRadius, minSep, elev, params, retries, cacheSize);
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
    String bStr = map.containsKey("boundary") ? String.valueOf(map.get("boundary")).toUpperCase() : null;
    ConfinementBoundary boundary = null;
    if (bStr != null) {
      boundary = switch (bStr) {
        case "REGION" -> ConfinementBoundary.REGION;
        case "LEASH" -> ConfinementBoundary.LEASH;
        case "SHAPE" -> ConfinementBoundary.SHAPE;
        default -> ConfinementBoundary.SUBSPACE;
      };
    }

    String shapeName = "SQUARE";
    int radius = 0;
    int centerRadius = 0;

    Object shapeObj = map.get("shape");
    Map<String, Object> shapeMap = null;
    if (shapeObj instanceof Map<?, ?> m) {
      shapeMap = (Map<String, Object>) m;
    } else if (shapeObj instanceof RtpYamlSection sec) {
      shapeMap = sec.getValues(false);
    } else if (shapeObj != null) {
      shapeName = shapeObj.toString().trim().toUpperCase();
      if (boundary == null) boundary = ConfinementBoundary.SHAPE;
    }

    if (shapeMap != null) {
      if (shapeMap.containsKey("name")) {
        shapeName = String.valueOf(shapeMap.get("name")).trim().toUpperCase();
      } else if (shapeMap.containsKey("shape")) {
        shapeName = String.valueOf(shapeMap.get("shape")).trim().toUpperCase();
      }
      if (shapeMap.containsKey("radius")) {
        radius = parseRadius(shapeMap.get("radius"), 0);
      }
      if (shapeMap.containsKey("centerRadius")) {
        centerRadius = parseRadius(shapeMap.get("centerRadius"), 0);
      }
      if (boundary == null) boundary = ConfinementBoundary.SHAPE;
    }

    if (map.containsKey("radius") && radius <= 0) {
      radius = parseRadius(map.get("radius"), 0);
      if (boundary == null) boundary = ConfinementBoundary.SHAPE;
    }
    if (map.containsKey("centerRadius") && centerRadius <= 0) {
      centerRadius = parseRadius(map.get("centerRadius"), 0);
    }

    if (boundary == null) {
      boundary = ConfinementBoundary.SUBSPACE;
    }

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
    boolean cancellable = true;
    if (map.containsKey("cancellable")) {
      Object cVal = map.get("cancellable");
      cancellable = !(Boolean.FALSE.equals(cVal) || "false".equalsIgnoreCase(String.valueOf(cVal)));
    }

    double damageAmount = 0.0;
    if (map.get("damage") instanceof Number n) {
      damageAmount = n.doubleValue();
    } else if (map.get("damageAmount") instanceof Number n) {
      damageAmount = n.doubleValue();
    } else if (map.containsKey("damage")) {
      try {
        damageAmount = Double.parseDouble(String.valueOf(map.get("damage")));
      } catch (NumberFormatException ignored) {}
    } else if (map.containsKey("damageAmount")) {
      try {
        damageAmount = Double.parseDouble(String.valueOf(map.get("damageAmount")));
      } catch (NumberFormatException ignored) {}
    }

    double damageBuffer = 0.0;
    if (map.get("damageBuffer") instanceof Number n) {
      damageBuffer = n.doubleValue();
    } else if (map.containsKey("damageBuffer")) {
      try {
        damageBuffer = Double.parseDouble(String.valueOf(map.get("damageBuffer")));
      } catch (NumberFormatException ignored) {}
    }

    long damageInterval = 1L;
    if (map.containsKey("damageInterval")) {
      damageInterval = GateExpressionParser.parseDurationSeconds(String.valueOf(map.get("damageInterval")), 1L);
    } else if (map.get("damageIntervalSeconds") instanceof Number n) {
      damageInterval = n.longValue();
    }

    double maxDistanceOutside = 0.0;
    if (map.get("maxDistanceOutside") instanceof Number n) {
      maxDistanceOutside = n.doubleValue();
    } else if (map.get("outsideLimit") instanceof Number n) {
      maxDistanceOutside = n.doubleValue();
    } else if (map.get("maxOutsideDistance") instanceof Number n) {
      maxDistanceOutside = n.doubleValue();
    } else if (map.containsKey("maxDistanceOutside")) {
      try {
        maxDistanceOutside = Double.parseDouble(String.valueOf(map.get("maxDistanceOutside")));
      } catch (NumberFormatException ignored) {}
    } else if (map.containsKey("outsideLimit")) {
      try {
        maxDistanceOutside = Double.parseDouble(String.valueOf(map.get("outsideLimit")));
      } catch (NumberFormatException ignored) {}
    } else if (map.containsKey("maxOutsideDistance")) {
      try {
        maxDistanceOutside = Double.parseDouble(String.valueOf(map.get("maxOutsideDistance")));
      } catch (NumberFormatException ignored) {}
    }

    List<ActionDefinition.CommandAction> outsideActions = Collections.emptyList();
    if (map.containsKey("outsideActions")) {
      Object oaObj = map.get("outsideActions");
      if (oaObj instanceof List<?> list) {
        outsideActions = parseActions(list);
      } else if (oaObj instanceof Map<?, ?> m) {
        outsideActions = parseActionFromMap((Map<String, Object>) m);
      } else if (oaObj instanceof String s) {
        outsideActions = List.of(ActionDefinition.CommandAction.action(s));
      }
    } else if (map.containsKey("outsideAction")) {
      Object oaObj = map.get("outsideAction");
      if (oaObj instanceof List<?> list) {
        outsideActions = parseActions(list);
      } else if (oaObj instanceof Map<?, ?> m) {
        outsideActions = parseActionFromMap((Map<String, Object>) m);
      } else if (oaObj instanceof String s) {
        outsideActions = List.of(ActionDefinition.CommandAction.action(s));
      }
    } else if (map.containsKey("onOutsideLimit")) {
      Object oaObj = map.get("onOutsideLimit");
      if (oaObj instanceof List<?> list) {
        outsideActions = parseActions(list);
      } else if (oaObj instanceof Map<?, ?> m) {
        outsideActions = parseActionFromMap((Map<String, Object>) m);
      } else if (oaObj instanceof String s) {
        outsideActions = List.of(ActionDefinition.CommandAction.action(s));
      }
    }

    return new ActionDefinition.ConfinementSpec(
        boundary, durSec, leash, initialSize, shrinkTo, shrinkOver, cancellable, shapeName, radius, centerRadius,
        damageAmount, damageBuffer, damageInterval, maxDistanceOutside, outsideActions);
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
    List<ActionDefinition.LifecycleStep> onEnqueue = parseSteps(toList(map.get("onEnqueue")));
    if (onEnqueue.isEmpty() && map.containsKey("onWait")) {
      onEnqueue = parseSteps(toList(map.get("onWait")));
    }
    List<ActionDefinition.LifecycleStep> onCancel = parseSteps(toList(map.get("onCancel")));
    return new ActionDefinition.LifecycleSpec(onStart, onBoundaryViolation, onExpire, onDeath, onEnqueue, onCancel);
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
        case "MESSAGE", "TELL", "MSG" -> res.add(ActionDefinition.CommandAction.message(val != null ? val.toString() : ""));
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
