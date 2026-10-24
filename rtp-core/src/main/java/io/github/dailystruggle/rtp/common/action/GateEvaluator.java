package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.Vocabulary;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluator for declarative MVP gates (ADR-093 Section 3).
 *
 * <p>Supported gate types:
 * <ol>
 *   <li>{@code scoreboard}: compares entity/session score against objective and match expr</li>
 *   <li>{@code spatial}: withinBoundary, distance, elevationDelta, target coordinates</li>
 *   <li>{@code time}: elapsed, remaining duration thresholds</li>
 *   <li>{@code predicate}: invokes externally registered programmatic predicate</li>
 *   <li>{@code command}: dispatches console command and tests for successful exit status</li>
 * </ol>
 *
 * <p>All gates within a step are conjunctive. Unrecognized gate keys fail closed.
 */
public final class GateEvaluator {

  private static final String OBJECTIVE_KEY = "objective";
  private static final String PREDICATE_GATE = "predicate";
  private static final String SCOREBOARD_GATE = "scoreboard";
  private static final String SPATIAL_GATE = "spatial";
  private static final String PLAYERS_GATE = "players";
  private static final String EXECUTE_KEY = "execute";
  private static final String REMAINING_KEY = "remaining";
  private static final String WITHIN_BOUNDARY_KEY = "withinBoundary";
  private static final String WORLD_KEY = "world";
  private static final String PARTICIPANTS_GATE = "participants";
  private static final String QUEUE_GATE = "queue";
  private static final String COUNT_KEY = "count";
  private static final String ELEVATION_DELTA_KEY = "elevationDelta";
  private static final String DISTANCE_KEY = "distance";
  private static final String ELAPSED_KEY = "elapsed";
  private static final String COMMAND_GATE = "command";
  private static final String TARGET_KEY = "target";
  private static final String PARTICIPANT_COUNT_GATE = "participantcount";
  private static final String PLAYER_COUNT_KEY = "playerCount";
  private static final String SHAPE_KEY = "shape";
  private static final String MATCHES_KEY = "matches";
  private static final String RANGE_KEY = "range";
  private static final String REGION_KEY = "region";

  public static final Vocabulary GATE_TYPES = new Vocabulary("gate type", List.of(
      SCOREBOARD_GATE, SPATIAL_GATE, "time", PREDICATE_GATE, PLAYERS_GATE,
      PARTICIPANTS_GATE, QUEUE_GATE, PARTICIPANT_COUNT_GATE, COMMAND_GATE));
  public static final Map<String, Set<String>> GATE_SUB_KEYS = Map.of(
      SCOREBOARD_GATE, Set.of(OBJECTIVE_KEY, MATCHES_KEY, RANGE_KEY),
      SPATIAL_GATE, Set.of(WITHIN_BOUNDARY_KEY, DISTANCE_KEY, TARGET_KEY, ELEVATION_DELTA_KEY,
          SHAPE_KEY, REGION_KEY, PLAYER_COUNT_KEY, WORLD_KEY),
      "time", Set.of(ELAPSED_KEY, REMAINING_KEY),
      PREDICATE_GATE, Set.of(),
      PLAYERS_GATE, Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY),
      PARTICIPANTS_GATE, Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY),
      QUEUE_GATE, Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY),
      PARTICIPANT_COUNT_GATE, Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY),
      COMMAND_GATE, Set.of(EXECUTE_KEY, "run"));
  public static final Map<String, List<Set<String>>> GATE_REQUIRED_SUB_KEYS = Map.of(
      SCOREBOARD_GATE, List.of(Set.of(OBJECTIVE_KEY), Set.of(MATCHES_KEY, RANGE_KEY)),
      PLAYERS_GATE, List.of(Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY)),
      PARTICIPANTS_GATE, List.of(Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY)),
      QUEUE_GATE, List.of(Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY)),
      PARTICIPANT_COUNT_GATE, List.of(Set.of(MATCHES_KEY, RANGE_KEY, COUNT_KEY)),
      COMMAND_GATE, List.of(Set.of(EXECUTE_KEY, "run")));

  private static final Pattern COORD_SEPARATORS = Pattern.compile("[,\\s]+");

  private GateEvaluator() {}

  /**
   * Checks whether the given gate configuration has conditions requiring more than one participant
   * (e.g. {@code players: ">= 2"}, {@code participants: "> 1"}).
   */
  public static boolean requiresMultipleParticipants(List<Map<String, Object>> gates) {
    if (gates == null || gates.isEmpty()) return false;
    for (Map<String, Object> gateConfig : gates) {
      if (requiresMultipleParticipants(gateConfig)) return true;
    }
    return false;
  }

  private static boolean requiresMultipleParticipants(Map<String, Object> gateConfig) {
    if (gateConfig == null || gateConfig.isEmpty()) return false;
    for (Map.Entry<String, Object> entry : gateConfig.entrySet()) {
      if (!isParticipantCountGate(entry.getKey().toLowerCase())) continue;
      String expression = participantCountExpression(entry.getValue());
      if (expression != null && !expression.isBlank()
          && !GateExpressionParser.matches(expression, 1.0)) return true;
    }
    return false;
  }

  private static boolean isParticipantCountGate(String gateType) {
    return gateType.equals(PLAYERS_GATE) || gateType.equals(PARTICIPANTS_GATE)
        || gateType.equals(QUEUE_GATE) || gateType.equals(PARTICIPANT_COUNT_GATE);
  }

  /**
   * Evaluates a list of gate configuration blocks (AND-ed) against the given context and tokens.
   */
  public static boolean evaluateAll(
      List<Map<String, Object>> gates,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens) {
    return evaluateAll(gates, context, externalPredicates, additionalTokens, null);
  }

  static boolean evaluateAll(
      List<Map<String, Object>> gates,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens,
      GateTrace trace) {
    if (context != null && context.context() != null) {
      for (Predicate<ActionGateContext> validator : context.context().gateValidators()) {
        if (validator != null && !validator.test(context)) {
          if (trace != null) {
            trace.onOutcome(Map.of("context", "validator"), false,
                "A caller-supplied context validator rejected the action.");
          }
          return false;
        }
      }
    }
    if (gates == null || gates.isEmpty()) {
      return true;
    }
    for (Map<String, Object> gateConfig : gates) {
      if (!evaluate(gateConfig, context, externalPredicates, additionalTokens, trace)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Evaluates a gate configuration block against the given context.
   *
   * @param gateConfig         the gate definition map from config
   * @param context            the gate context
   * @param externalPredicates custom predicates registered via ActionService
   * @return true if all defined conditions pass, false if any fails or an unknown gate is encountered
   */
  public static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates) {
    return evaluate(gateConfig, context, externalPredicates, Collections.emptyMap());
  }

  /**
   * Evaluates a gate configuration block against the given context and tokens.
   *
   * @param gateConfig         the gate definition map from config
   * @param context            the gate context
   * @param externalPredicates custom predicates registered via ActionService
   * @param additionalTokens   context tokens for placeholder substitution
   * @return true if all defined conditions pass, false if any fails or an unknown gate is encountered
   */
  static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens) {
    return evaluate(gateConfig, context, externalPredicates, additionalTokens, null);
  }

  static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens,
      GateTrace trace) {
    if (!validatorsPass(context)) return completeGateEvaluation(gateConfig, context, false, trace);
    if (gateConfig == null || gateConfig.isEmpty()) {
      return completeGateEvaluation(gateConfig, context, true, trace);
    }
    for (Map.Entry<String, Object> entry : gateConfig.entrySet()) {
      Object value = unwrapYamlValue(entry.getValue());
      if (!evaluateGate(entry.getKey().toLowerCase(), value, context, externalPredicates, additionalTokens, trace)) {
        return completeGateEvaluation(gateConfig, context, false, trace);
      }
    }
    return completeGateEvaluation(gateConfig, context, true, trace);
  }

  private static boolean validatorsPass(ActionGateContext context) {
    if (context == null || context.context() == null) return true;
    for (Predicate<ActionGateContext> validator : context.context().gateValidators()) {
      if (validator != null && !validator.test(context)) return false;
    }
    return true;
  }

  private static boolean evaluateGate(
      String gateType,
      Object value,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens,
      GateTrace trace) {
    return switch (gateType) {
      case "scoreboard" -> evaluateScoreboardGate(value, context, trace);
      case "spatial" -> evaluateSpatialGate(value, context, trace);
      case "time" -> evaluateTimeGate(value, context);
      case "predicate" -> evaluatePredicateGate(value, context, externalPredicates);
      case "players", "participants", "queue", "participantcount" ->
          evaluateParticipantCountGate(value, context);
      case "command" -> evaluateCommandGate(value, context, additionalTokens, trace);
      default -> false;
    };
  }

  private static boolean evaluateScoreboardGate(Object value, ActionGateContext context, GateTrace trace) {
    if (!(value instanceof Map<?, ?> map)) return false;
    Object objective = map.get(OBJECTIVE_KEY);
    Object matches = map.get(MATCHES_KEY);
    if (matches == null) matches = map.get(RANGE_KEY);
    if (objective == null || matches == null) return false;
    double score = scoreboardValue(objective.toString().trim(), context, trace);
    return GateExpressionParser.matches(matches.toString(), score);
  }

  private static double scoreboardValue(String objective, ActionGateContext context, GateTrace trace) {
    return switch (objective.toLowerCase(java.util.Locale.ROOT)) {
      case "rtp_violations" -> context.violations();
      case "rtp_in_bounds" -> context.inBounds() ? 1.0 : 0.0;
      case "rtp_time_left" -> (double) context.remainingSeconds();
      case "rtp_dist_sq" -> context.distanceSqFromAnchor();
      case "rtp_session_id" -> sessionIdScore(context);
      case "rtp_alive" -> aliveScore(context, trace);
      default -> externalScore(objective, context, trace);
    };
  }

  private static double sessionIdScore(ActionGateContext context) {
    if (context.sessionId() == null) return 0.0;
    int hash = context.sessionId().hashCode();
    if (hash == Integer.MIN_VALUE) return Integer.MAX_VALUE;
    return Math.abs(hash);
  }

  private static double aliveScore(ActionGateContext context, GateTrace trace) {
    if (context == null) return 0.0;
    if (trace != null) return context.participantCount();
    RTPServerAccessor accessor = RTPRuntime.serverAccessor();
    if (accessor == null || io.github.dailystruggle.rtp.api.RTPAPI.actionService == null) return 1.0;
    return io.github.dailystruggle.rtp.api.RTPAPI.actionService.getSession(context.sessionId())
        .map(session -> aliveParticipantCount(session.participants(), accessor))
        .orElse(1.0);
  }

  private static double aliveParticipantCount(List<UUID> participants, RTPServerAccessor accessor) {
    int onlineCount = 0;
    for (UUID participantId : participants) {
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(participantId);
      if (player != null && player.isOnline()) onlineCount++;
    }
    return onlineCount > 0 ? onlineCount : participants.size();
  }

  private static double externalScore(String objective, ActionGateContext context, GateTrace trace) {
    RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
    UUID participantId = context.participantId();
    Integer score = accessor != null && participantId != null
        ? accessor.getScoreboardScore(participantId, objective)
        : null;
    if (score != null) return score.doubleValue();
    if (trace == null) ActionCbitCounters.recordMissingObjective(objective);
    return 0.0;
  }

  private static boolean evaluateSpatialGate(Object value, ActionGateContext context, GateTrace trace) {
    if (!(value instanceof Map<?, ?> map)) return false;
    if (map.containsKey(WITHIN_BOUNDARY_KEY)) {
      boolean required = Boolean.parseBoolean(map.get(WITHIN_BOUNDARY_KEY).toString());
      if (context.inBounds() != required) return false;
    }
    if (map.containsKey(DISTANCE_KEY) && !matchesDistance(map, context)) return false;
    if (map.containsKey(ELEVATION_DELTA_KEY) && !matchesElevationDelta(map, context)) return false;
    return evaluateShapeConstraints(map, context, trace);
  }

  private static boolean matchesDistance(Map<?, ?> map, ActionGateContext context) {
    Double distance = distanceFromTarget(map.get(TARGET_KEY), context);
    return distance != null && GateExpressionParser.matches(map.get(DISTANCE_KEY).toString(), distance);
  }

  private static Double distanceFromTarget(Object target, ActionGateContext context) {
    if (target == null || target.toString().equalsIgnoreCase("anchor")) {
      return Math.sqrt(Math.max(0.0, context.distanceSqFromAnchor()));
    }
    String[] parts = COORD_SEPARATORS.split(target.toString());
    if (parts.length < 2 || context.currentX() == null || context.currentZ() == null) return null;
    try {
      double targetX = Double.parseDouble(parts[0]);
      double targetZ = Double.parseDouble(parts[parts.length >= 3 ? 2 : 1]);
      double deltaX = context.currentX() - targetX;
      double deltaZ = context.currentZ() - targetZ;
      return Math.hypot(deltaX, deltaZ);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static boolean matchesElevationDelta(Map<?, ?> map, ActionGateContext context) {
    Double delta = context.elevationDelta();
    return GateExpressionParser.matches(
        map.get(ELEVATION_DELTA_KEY).toString(), delta == null ? 0.0 : delta);
  }

  private static boolean evaluateShapeConstraints(
      Map<?, ?> map, ActionGateContext context, GateTrace trace) {
    if (!map.containsKey(SHAPE_KEY) && !map.containsKey(REGION_KEY)
        && !map.containsKey(PLAYER_COUNT_KEY)) return true;
    io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape = resolveShape(map);
    if (map.containsKey(SHAPE_KEY) && shape == null) return false;
    if (shape == null) return !map.containsKey(PLAYER_COUNT_KEY);
    if (!shapeContainsCurrentPosition(shape, context)) return false;
    return !map.containsKey(PLAYER_COUNT_KEY) || matchesPlayerCount(map, shape, context, trace);
  }

  @SuppressWarnings("unchecked")
  private static io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> resolveShape(
      Map<?, ?> map) {
    if (map.containsKey(SHAPE_KEY) && map.get(SHAPE_KEY) instanceof Map<?, ?> shapeMap) {
      Map<String, Object> shapeData = new HashMap<>((Map<String, Object>) shapeMap);
      if (!shapeData.containsKey("centerRadius") && !shapeData.containsKey("centerradius")) {
        shapeData.put("centerRadius", 0);
      }
      return io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader.deserializeShape(shapeData);
    }
    if (!map.containsKey(REGION_KEY)) return null;
    String regionName = String.valueOf(map.get(REGION_KEY));
    var selection = ActiveSelection.get();
    io.github.dailystruggle.rtp.common.selection.region.Region region =
        selection == null ? null : selection.getRegion(regionName);
    return region == null ? null : region.getShape();
  }

  private static boolean shapeContainsCurrentPosition(
      io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape,
      ActionGateContext context) {
    if (context.currentX() == null || context.currentZ() == null) return true;
    int chunkX = (int) Math.floor(context.currentX() / 16.0);
    int chunkZ = (int) Math.floor(context.currentZ() / 16.0);
    return shape.contains(chunkX, chunkZ);
  }

  private static boolean matchesPlayerCount(
      Map<?, ?> map,
      io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape,
      ActionGateContext context,
      GateTrace trace) {
    String requirement = String.valueOf(map.get(PLAYER_COUNT_KEY));
    String targetWorld = map.containsKey(WORLD_KEY) ? String.valueOf(map.get(WORLD_KEY)) : null;
    int count = countPlayersInShape(shape, context, trace, targetWorld);
    return GateExpressionParser.matches(requirement, count);
  }

  private static int countPlayersInShape(
      io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape,
      ActionGateContext context,
      GateTrace trace,
      String targetWorld) {
    if (trace != null) {
      return targetWorld == null || targetWorld.equalsIgnoreCase("simulation")
          ? context.participantCount()
          : 0;
    }
    RTPServerAccessor accessor = RTPRuntime.serverAccessor();
    if (accessor == null) return 0;
    int count = 0;
    for (io.github.dailystruggle.rtp.api.entity.RTPPlayer player : accessor.getOnlinePlayers()) {
      if (player != null) {
        io.github.dailystruggle.rtp.api.world.RTPLocation location = player.getLocation();
        if (location != null && location.world() != null
            && (targetWorld == null || location.world().name().equalsIgnoreCase(targetWorld))) {
          int chunkX = (int) Math.floor(location.x() / 16.0);
          int chunkZ = (int) Math.floor(location.z() / 16.0);
          if (shape.contains(chunkX, chunkZ)) count++;
        }
      }
    }
    return count;
  }

  private static boolean evaluateTimeGate(Object value, ActionGateContext context) {
    if (!(value instanceof Map<?, ?> map)) return false;
    if (map.containsKey(ELAPSED_KEY)
        && !GateExpressionParser.matches(map.get(ELAPSED_KEY).toString(), (double) context.elapsedSeconds())) return false;
    return !map.containsKey(REMAINING_KEY)
        || GateExpressionParser.matches(map.get(REMAINING_KEY).toString(), (double) context.remainingSeconds());
  }

  private static boolean evaluatePredicateGate(
      Object value,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates) {
    Predicate<ActionGateContext> predicate = externalPredicates == null
        ? null
        : externalPredicates.get(value.toString().trim());
    return predicate != null && predicate.test(context);
  }

  private static boolean evaluateParticipantCountGate(Object value, ActionGateContext context) {
    String expression = participantCountExpression(value);
    if (expression == null || expression.isBlank()) return false;
    int count = context == null ? 0 : context.participantCount();
    return GateExpressionParser.matches(expression, count);
  }

  private static String participantCountExpression(Object value) {
    if (value instanceof Map<?, ?> map) {
      Object expression = map.get(MATCHES_KEY);
      if (expression == null) expression = map.get(RANGE_KEY);
      if (expression == null) expression = map.get(COUNT_KEY);
      return expression == null ? null : expression.toString();
    }
    return value == null ? null : value.toString();
  }

  private static boolean evaluateCommandGate(
      Object value,
      ActionGateContext context,
      Map<String, Object> additionalTokens,
      GateTrace trace) {
    List<String> commands = commandTemplates(value);
    if (commands.isEmpty()) return false;
    RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
    if (trace == null && accessor == null) return false;
    Map<String, Object> tokens = new HashMap<>();
    if (additionalTokens != null && !additionalTokens.isEmpty()) tokens.putAll(additionalTokens);
    tokens.put("session_id", context.sessionId().toString().substring(0, 8));
    if (context.participantId() != null) {
      tokens.put("player", context.participantId());
      tokens.put("violator", context.participantId());
    }
    for (String template : commands) {
      if (shouldEvaluateCommand(template, tokens)
          && !evaluateCommandTemplate(template, tokens, accessor, trace)) return false;
    }
    return true;
  }

  private static boolean shouldEvaluateCommand(String template, Map<String, Object> tokens) {
    return template != null && !template.isBlank()
        && !ActionPlaceholderSanitizer.hasMissingTarget(template, tokens);
  }

  private static List<String> commandTemplates(Object value) {
    List<String> commands = new ArrayList<>();
    if (value instanceof Map<?, ?> map) {
      Object execute = map.get(EXECUTE_KEY);
      if (execute == null) execute = map.get("run");
      addCommandValues(commands, execute);
    } else if (value instanceof List<?> list) {
      addCommandValues(commands, list);
    } else if (value instanceof String command) {
      commands.add(command);
    }
    return commands;
  }

  private static void addCommandValues(List<String> commands, Object value) {
    if (value instanceof List<?> list) {
      for (Object command : list) {
        if (command != null) commands.add(command.toString());
      }
    } else if (value != null) {
      commands.add(value.toString());
    }
  }

  private static boolean evaluateCommandTemplate(
      String template, Map<String, Object> tokens, RTPServerAccessor accessor, GateTrace trace) {
    String substituted = ActionPlaceholderSanitizer.substitute(template, tokens);
    if (trace == null) ActionCbitCounters.recordUnresolvedPlaceholders(substituted);
    if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, TARGET_KEY)) return true;
    if (trace != null) return trace.simulateCommand(substituted);
    return evaluateEntityPredicate(substituted, accessor)
        .orElseGet(() -> accessor.executeCommand(new UUID(0, 0), substituted));
  }

  private static boolean completeGateEvaluation(
      Map<String, Object> gateConfig, ActionGateContext context, boolean passed, GateTrace trace) {
    if (trace == null) {
      ActionCbitCounters.recordGateOutcome(gateConfig, context == null ? null : context.actionId(), passed);
    }
    if (trace != null && gateConfig != null && !gateConfig.isEmpty()) {
      Object normalized = unwrapYamlValue(gateConfig);
      Map<String, Object> tracedConfig = new HashMap<>();
      if (normalized instanceof Map<?, ?> map) {
        map.forEach((key, value) -> tracedConfig.put(String.valueOf(key), value));
      }
      String reason = passed
          ? "All configured gate conditions matched."
          : "Gate rejected the context for condition(s): " + tracedConfig;
      trace.onOutcome(tracedConfig, passed, reason);
    }
    return passed;
  }

  private static Object unwrapYamlValue(Object value) {
    if (value instanceof RtpYamlSection section) return unwrapYamlValue(section.getValues(false));
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> normalized = new HashMap<>();
      map.forEach((key, child) -> normalized.put(String.valueOf(key), unwrapYamlValue(child)));
      return normalized;
    }
    if (value instanceof List<?> values) {
      List<Object> normalized = new ArrayList<>(values.size());
      for (Object child : values) normalized.add(unwrapYamlValue(child));
      return normalized;
    }
    return value;
  }

  private static final Pattern ENTITY_PREDICATE_PATTERN = Pattern.compile(
      "^execute\\s+(if|unless)\\s+entity\\s+@[aeprs](?:\\[(.*)\\])?\\s*$",
      Pattern.CASE_INSENSITIVE);

  /**
   * Natively evaluates a vanilla {@code execute if/unless entity @<selector>[name=,tag=]} predicate
   * against live player scoreboard tags, returning the predicate outcome.
   *
   * <p>This exists because platform command dispatch (e.g. Bukkit {@code dispatchCommand}) returns
   * {@code true} for a dispatched vanilla {@code execute if entity ...} regardless of whether the
   * predicate matched, which makes command-gate results unreliable and leaks "Test failed" feedback.
   *
   * @param command  the fully-substituted command string
   * @param accessor server accessor for tag/name lookups
   * @return {@code Optional.of(Boolean)} for a recognized entity predicate, or {@code Optional.empty()} if the
   *     command is not a recognized {@code execute if/unless entity} tag/name predicate (caller
   *     should fall back to normal command dispatch)
   */
  private static Optional<Boolean> evaluateEntityPredicate(String command, RTPServerAccessor accessor) {
    if (command == null || accessor == null) return Optional.empty();
    Matcher m = ENTITY_PREDICATE_PATTERN.matcher(command.trim());
    if (!m.matches()) return Optional.empty();

    boolean unless = "unless".equalsIgnoreCase(m.group(1));
    String selector = m.group(2); // selector arguments inside [...], may be null

    String requiredName = null;
    String requiredTag = null;
    if (selector != null && !selector.isBlank()) {
      for (String arg : selector.split(",")) {
        String[] kv = arg.split("=", 2);
        if (kv.length != 2) continue;
        String key = kv[0].trim().toLowerCase();
        String value = kv[1].trim();
        if (key.equals("name")) {
          requiredName = value;
        } else if (key.equals("tag")) {
          requiredTag = value;
        }
        // Unsupported selector arguments are ignored (name/tag are sufficient for reciprocity gates)
      }
    }

    // Only handle tag-based predicates natively; anything else falls back to dispatch.
    if (requiredTag == null || requiredTag.isBlank()) return Optional.empty();

    boolean matched = entityMatches(requiredName, requiredTag, accessor);
    return Optional.of(unless != matched);
  }

  private static boolean entityMatches(String requiredName, String requiredTag, RTPServerAccessor accessor) {
    if (requiredName != null && !requiredName.isBlank()) {
      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = accessor.getPlayer(requiredName);
      if (player == null) return false;
      Set<String> tags = accessor.getScoreboardTags(player.uuid());
      return tags != null && tags.contains(requiredTag);
    }
    // No name constraint: match if any online player carries the required tag.
    for (io.github.dailystruggle.rtp.api.entity.RTPPlayer p : accessor.getOnlinePlayers()) {
      if (p == null) continue;
      Set<String> tags = accessor.getScoreboardTags(p.uuid());
      if (tags != null && tags.contains(requiredTag)) {
        return true;
      }
    }
    return false;
  }
}
