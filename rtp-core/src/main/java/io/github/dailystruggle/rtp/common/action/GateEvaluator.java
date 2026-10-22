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

  public static final Vocabulary GATE_TYPES = new Vocabulary("gate type", List.of(
      "scoreboard", "spatial", "time", "predicate", "players", "participants", "queue", "participantcount", "command"));
  public static final Map<String, Set<String>> GATE_SUB_KEYS = Map.of(
      "scoreboard", Set.of("objective", "matches", "range"),
      "spatial", Set.of("withinBoundary", "distance", "target", "elevationDelta", "shape", "region", "playerCount", "world"),
      "time", Set.of("elapsed", "remaining"),
      "predicate", Set.of(),
      "players", Set.of("matches", "range", "count"),
      "participants", Set.of("matches", "range", "count"),
      "queue", Set.of("matches", "range", "count"),
      "participantcount", Set.of("matches", "range", "count"),
      "command", Set.of("execute", "run"));
  public static final Map<String, List<Set<String>>> GATE_REQUIRED_SUB_KEYS = Map.of(
      "scoreboard", List.of(Set.of("objective"), Set.of("matches", "range")),
      "players", List.of(Set.of("matches", "range", "count")),
      "participants", List.of(Set.of("matches", "range", "count")),
      "queue", List.of(Set.of("matches", "range", "count")),
      "participantcount", List.of(Set.of("matches", "range", "count")),
      "command", List.of(Set.of("execute", "run")));

  private static final Pattern COORD_SEPARATORS = Pattern.compile("[,\\s]+");

  private GateEvaluator() {}

  /**
   * Checks whether the given gate configuration has conditions requiring more than one participant
   * (e.g. {@code players: ">= 2"}, {@code participants: "> 1"}).
   */
  public static boolean requiresMultipleParticipants(List<Map<String, Object>> gates) {
    if (gates == null || gates.isEmpty()) return false;
    for (Map<String, Object> gateConfig : gates) {
      if (gateConfig == null || gateConfig.isEmpty()) continue;
      for (Map.Entry<String, Object> entry : gateConfig.entrySet()) {
        String key = entry.getKey().toLowerCase();
        if (key.equals("players") || key.equals("participants") || key.equals("queue") || key.equals("participantcount")) {
          Object val = entry.getValue();
          String matchExpr = null;
          if (val instanceof Map<?, ?> m) {
            Object matches = m.get("matches");
            if (matches == null) matches = m.get("range");
            if (matches == null) matches = m.get("count");
            if (matches != null) matchExpr = matches.toString();
          } else if (val != null) {
            matchExpr = val.toString();
          }
          if (matchExpr != null && !matchExpr.isBlank() && !GateExpressionParser.matches(matchExpr, 1.0)) {
            return true;
          }
        }
      }
    }
    return false;
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

  @SuppressWarnings("unchecked")
  static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens,
      GateTrace trace) {
    if (context != null && context.context() != null) {
      for (Predicate<ActionGateContext> validator : context.context().gateValidators()) {
        if (validator != null && !validator.test(context)) {
          return completeGateEvaluation(gateConfig, context, false, trace);
        }
      }
    }

    if (gateConfig == null || gateConfig.isEmpty()) {
      return completeGateEvaluation(gateConfig, context, true, trace);
    }

    for (Map.Entry<String, Object> entry : gateConfig.entrySet()) {
      String gateType = entry.getKey().toLowerCase();
      Object val = unwrapYamlValue(entry.getValue());

      switch (gateType) {
        case "scoreboard" -> {
          if (!(val instanceof Map<?, ?> map)) return completeGateEvaluation(gateConfig, context, false, trace);
          Object objName = map.get("objective");
          Object matches = map.get("matches");
          if (matches == null) matches = map.get("range");
          if (objName == null || matches == null) return completeGateEvaluation(gateConfig, context, false, trace);

          // ADR-093 §4: Managed action scoreboard objectives
          String objStr = objName.toString().trim();
          double scoreValue = 0.0;
          if ("rtp_violations".equalsIgnoreCase(objStr)) {
            scoreValue = context.violations();
          } else if ("rtp_in_bounds".equalsIgnoreCase(objStr)) {
            scoreValue = context.inBounds() ? 1.0 : 0.0;
          } else if ("rtp_time_left".equalsIgnoreCase(objStr)) {
            scoreValue = context.remainingSeconds();
          } else if ("rtp_dist_sq".equalsIgnoreCase(objStr)) {
            scoreValue = context.distanceSqFromAnchor();
          } else if ("rtp_session_id".equalsIgnoreCase(objStr)) {
            if (context.sessionId() != null) {
              int rawHash = context.sessionId().hashCode();
              scoreValue = (rawHash == Integer.MIN_VALUE) ? Integer.MAX_VALUE : Math.abs(rawHash);
            } else {
              scoreValue = 0.0;
            }
          } else if ("rtp_alive".equalsIgnoreCase(objStr)) {
            // Count alive session participants via server accessor if available
            RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
            if (context == null) {
              scoreValue = 0.0;
            } else if (trace != null) {
              scoreValue = context.participantCount();
            } else if (accessor != null && io.github.dailystruggle.rtp.api.RTPAPI.actionService != null) {
              scoreValue = io.github.dailystruggle.rtp.api.RTPAPI.actionService.getSession(context.sessionId())
                  .map(s -> {
                    int c = 0;
                    for (UUID pid : s.participants()) {
                      io.github.dailystruggle.rtp.api.entity.RTPPlayer p = accessor.getPlayer(pid);
                      if (p != null && p.isOnline()) c++;
                    }
                    return (double) (c > 0 ? c : s.participants().size());
                  }).orElse(1.0);
            } else {
              scoreValue = 1.0;
            }
          } else {
            RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
            UUID pid = context.participantId();
            Integer realScore = (accessor != null && pid != null)
                ? accessor.getScoreboardScore(pid, objStr)
                : null;
            if (realScore != null) {
              scoreValue = realScore.doubleValue();
            } else if (trace == null) {
              ActionCbitCounters.recordMissingObjective(objStr);
            }
          }
          if (!GateExpressionParser.matches(matches.toString(), scoreValue)) {
            return completeGateEvaluation(gateConfig, context, false, trace);
          }
        }
        case "spatial" -> {
          if (!(val instanceof Map<?, ?> map)) return completeGateEvaluation(gateConfig, context, false, trace);
          if (map.containsKey("withinBoundary")) {
            boolean req = Boolean.parseBoolean(map.get("withinBoundary").toString());
            if (context.inBounds() != req) return completeGateEvaluation(gateConfig, context, false, trace);
          }
          if (map.containsKey("distance")) {
            double actualDist;
            Object targetObj = map.get("target");
            if (targetObj != null && !targetObj.toString().equalsIgnoreCase("anchor")) {
              // Custom coordinate target: "x,y,z" or "x,z"
              String[] parts = COORD_SEPARATORS.split(targetObj.toString());
              if (parts.length >= 2 && context.currentX() != null && context.currentZ() != null) {
                try {
                  double tx = Double.parseDouble(parts[0]);
                  double tz = Double.parseDouble(parts.length >= 3 ? parts[2] : parts[1]);
                  double dx = context.currentX() - tx;
                  double dz = context.currentZ() - tz;
                  actualDist = Math.hypot(dx, dz);
                } catch (NumberFormatException e) {
                  return completeGateEvaluation(gateConfig, context, false, trace);
                }
              } else {
                return completeGateEvaluation(gateConfig, context, false, trace);
              }
            } else {
              actualDist = Math.sqrt(Math.max(0.0, context.distanceSqFromAnchor()));
            }
            if (!GateExpressionParser.matches(map.get("distance").toString(), actualDist)) {
              return completeGateEvaluation(gateConfig, context, false, trace);
            }
          }
          if (map.containsKey("elevationDelta")) {
            Double delta = context.elevationDelta();
            if (delta == null) {
              delta = 0.0; // current or anchor Y unknown
            }
            if (!GateExpressionParser.matches(map.get("elevationDelta").toString(), delta)) {
              return completeGateEvaluation(gateConfig, context, false, trace);
            }
          }
          if (map.containsKey("shape") || map.containsKey("region") || map.containsKey("playerCount")) {
            io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape = null;
            if (map.containsKey("shape") && map.get("shape") instanceof Map<?, ?> smap) {
              Map<String, Object> shapeData = new HashMap<>((Map<String, Object>) smap);
              if (!shapeData.containsKey("centerRadius") && !shapeData.containsKey("centerradius")) {
                shapeData.put("centerRadius", 0);
              }
              shape = io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader.deserializeShape(shapeData);
            } else if (map.containsKey("region")) {
              String rName = String.valueOf(map.get("region"));
              io.github.dailystruggle.rtp.common.selection.region.Region r =
                  (ActiveSelection.get() != null) ? ActiveSelection.get().getRegion(rName) : null;
              if (r != null) {
                shape = r.getShape();
              }
            }
            if (map.containsKey("shape") && shape == null) {
              return completeGateEvaluation(gateConfig, context, false, trace);
            }
            if (shape != null) {
              // If context has current coords, check contains
              if (context.currentX() != null && context.currentZ() != null) {
                int chunkX = (int) Math.floor(context.currentX() / 16.0);
                int chunkZ = (int) Math.floor(context.currentZ() / 16.0);
                if (!shape.contains(chunkX, chunkZ)) {
                  return completeGateEvaluation(gateConfig, context, false, trace);
                }
              }
              // If playerCount condition is present, count online players inside shape
              if (map.containsKey("playerCount")) {
                String reqCount = String.valueOf(map.get("playerCount"));
                int matchingPlayers = 0;
                String targetWorld = map.containsKey("world") ? String.valueOf(map.get("world")) : null;
                RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
                if (trace != null) {
                  matchingPlayers = context.participantCount();
                  if (targetWorld != null && !targetWorld.equalsIgnoreCase("simulation")) matchingPlayers = 0;
                } else if (accessor != null) {
                  for (io.github.dailystruggle.rtp.api.entity.RTPPlayer p : accessor.getOnlinePlayers()) {
                    if (p != null) {
                      io.github.dailystruggle.rtp.api.world.RTPLocation loc = p.getLocation();
                      if (loc != null && loc.world() != null) {
                        if (targetWorld != null && !loc.world().name().equalsIgnoreCase(targetWorld)) continue;
                        int cx = (int) Math.floor(loc.x() / 16.0);
                        int cz = (int) Math.floor(loc.z() / 16.0);
                        if (shape.contains(cx, cz)) {
                          matchingPlayers++;
                        }
                      }
                    }
                  }
                }
                if (!GateExpressionParser.matches(reqCount, matchingPlayers)) {
                  return completeGateEvaluation(gateConfig, context, false, trace);
                }
              }
            } else if (map.containsKey("playerCount")) {
              // No shape configured but playerCount specified
              return completeGateEvaluation(gateConfig, context, false, trace);
            }
          }
        }
        case "time" -> {
          if (!(val instanceof Map<?, ?> map)) return completeGateEvaluation(gateConfig, context, false, trace);
          if (map.containsKey("elapsed")
              && !GateExpressionParser.matches(map.get("elapsed").toString(), context.elapsedSeconds())) {
            return completeGateEvaluation(gateConfig, context, false, trace);
          }
          if (map.containsKey("remaining")
              && !GateExpressionParser.matches(map.get("remaining").toString(), context.remainingSeconds())) {
            return completeGateEvaluation(gateConfig, context, false, trace);
          }
        }
        case "predicate" -> {
          String predName = val.toString().trim();
          Predicate<ActionGateContext> predicate =
              (externalPredicates == null) ? null : externalPredicates.get(predName);
          if (predicate == null || !predicate.test(context)) {
            return completeGateEvaluation(gateConfig, context, false, trace);
          }
        }
        case "players", "participants", "queue", "participantcount" -> {
          String matchExpr = null;
          if (val instanceof Map<?, ?> m) {
            Object matches = m.get("matches");
            if (matches == null) matches = m.get("range");
            if (matches == null) matches = m.get("count");
            if (matches != null) matchExpr = matches.toString();
          } else if (val != null) {
            matchExpr = val.toString();
          }
          if (matchExpr == null || matchExpr.isBlank()) return completeGateEvaluation(gateConfig, context, false, trace);
          int count = (context != null) ? context.participantCount() : 0;
          if (!GateExpressionParser.matches(matchExpr, count)) {
            return completeGateEvaluation(gateConfig, context, false, trace);
          }
        }
        case "command" -> {
          List<String> rawCommands = new ArrayList<>();
          if (val instanceof Map<?, ?> map) {
            Object exec = map.get("execute");
            if (exec == null) exec = map.get("run");
            if (exec instanceof List<?> list) {
              for (Object o : list) if (o != null) rawCommands.add(o.toString());
            } else if (exec != null) {
              rawCommands.add(exec.toString());
            }
          } else if (val instanceof List<?> list) {
            for (Object o : list) if (o != null) rawCommands.add(o.toString());
          } else if (val instanceof String s) {
            rawCommands.add(s);
          }
          if (rawCommands.isEmpty()) return completeGateEvaluation(gateConfig, context, false, trace);

          RTPServerAccessor accessor = trace == null ? RTPRuntime.serverAccessor() : null;
          if (trace == null && accessor == null) return completeGateEvaluation(gateConfig, context, false, null);

          Map<String, Object> tokens = new HashMap<>();
          if (additionalTokens != null && !additionalTokens.isEmpty()) {
            tokens.putAll(additionalTokens);
          }
          tokens.put("session_id", context.sessionId().toString().substring(0, 8));
          if (context.participantId() != null) {
            tokens.put("player", context.participantId());
            tokens.put("violator", context.participantId());
          }

          for (String cmdTemplate : rawCommands) {
            if (cmdTemplate == null || cmdTemplate.isBlank()) continue;

            // Check if command references a target token that is empty/unspecified
            // e.g., name=[target_name_1] where target is empty -> wildcard match (skip command)
            if (ActionPlaceholderSanitizer.hasMissingTarget(cmdTemplate, tokens)) {
              continue; // Wildcard target passes this command check
            }

            String substituted = ActionPlaceholderSanitizer.substitute(cmdTemplate, tokens);
            if (trace == null) ActionCbitCounters.recordUnresolvedPlaceholders(substituted);
            // Substitution can compose a target placeholder the pre-check could not see,
            // e.g. "[target_[n]]" -> "[target_1]"; never dispatch it unresolved.
            if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target")) {
              continue; // Wildcard unmapped target passes this command check
            }

            // Reliability: evaluate 'execute if/unless entity @X[name=,tag=]' predicate
            // checks natively via scoreboard tags. Bukkit's dispatchCommand return value
            // does not reflect the predicate result for vanilla 'execute if', so trusting
            // it silently passes/fails gates (and leaks "Test failed" chat feedback).
            if (trace != null) {
              if (!trace.simulateCommand(substituted)) {
                return completeGateEvaluation(gateConfig, context, false, trace);
              }
              continue;
            }

            Optional<Boolean> nativeResult = evaluateEntityPredicate(substituted, accessor);
            if (nativeResult.isPresent()) {
              if (!nativeResult.get()) return completeGateEvaluation(gateConfig, context, false, null);
              continue; // predicate satisfied; do not dispatch the vanilla command
            }

            boolean success = accessor.executeCommand(new UUID(0, 0), substituted);
            if (!success) return completeGateEvaluation(gateConfig, context, false, null);
          }
        }
        default -> {
          // ADR-093: Unrecognized gate key fails closed (block skipped)
          return completeGateEvaluation(gateConfig, context, false, trace);
        }
      }
    }
    return completeGateEvaluation(gateConfig, context, true, trace);
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
