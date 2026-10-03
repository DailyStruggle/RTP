package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
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
          if (matchExpr != null && !matchExpr.isBlank()) {
            if (!GateExpressionParser.matches(matchExpr, 1.0)) {
              return true;
            }
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
    if (context != null && context.context() != null) {
      for (Predicate<ActionGateContext> validator : context.context().gateValidators()) {
        if (validator != null && !validator.test(context)) {
          return false;
        }
      }
    }
    if (gates == null || gates.isEmpty()) {
      return true;
    }
    for (Map<String, Object> gateConfig : gates) {
      if (!evaluate(gateConfig, context, externalPredicates, additionalTokens)) {
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
  @SuppressWarnings("unchecked")
  public static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates,
      Map<String, Object> additionalTokens) {
    if (context != null && context.context() != null) {
      for (Predicate<ActionGateContext> validator : context.context().gateValidators()) {
        if (validator != null && !validator.test(context)) {
          return false;
        }
      }
    }

    if (gateConfig == null || gateConfig.isEmpty()) {
      return true;
    }

    for (Map.Entry<String, Object> entry : gateConfig.entrySet()) {
      String gateType = entry.getKey().toLowerCase();
      Object val = entry.getValue();

      switch (gateType) {
        case "scoreboard" -> {
          if (!(val instanceof Map<?, ?> map)) return false;
          Object objName = map.get("objective");
          Object matches = map.get("matches");
          if (matches == null) matches = map.get("range");
          if (objName == null || matches == null) return false;

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
            RTPServerAccessor accessor = RTP.serverAccessor;
            if (accessor != null && io.github.dailystruggle.rtp.api.RTPAPI.actionService != null) {
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
          }
          if (!GateExpressionParser.matches(matches.toString(), scoreValue)) {
            return false;
          }
        }
        case "spatial" -> {
          if (!(val instanceof Map<?, ?> map)) return false;
          if (map.containsKey("withinBoundary")) {
            boolean req = Boolean.parseBoolean(map.get("withinBoundary").toString());
            if (context.inBounds() != req) return false;
          }
          if (map.containsKey("distance")) {
            double actualDist;
            Object targetObj = map.get("target");
            if (targetObj != null && !targetObj.toString().equalsIgnoreCase("anchor")) {
              // Custom coordinate target: "x,y,z" or "x,z"
              String[] parts = targetObj.toString().split("[,\\s]+");
              if (parts.length >= 2 && context.currentX() != null && context.currentZ() != null) {
                try {
                  double tx = Double.parseDouble(parts[0]);
                  double tz = Double.parseDouble(parts.length >= 3 ? parts[2] : parts[1]);
                  double dx = context.currentX() - tx;
                  double dz = context.currentZ() - tz;
                  actualDist = Math.hypot(dx, dz);
                } catch (NumberFormatException e) {
                  return false;
                }
              } else {
                return false;
              }
            } else {
              actualDist = Math.sqrt(Math.max(0.0, context.distanceSqFromAnchor()));
            }
            if (!GateExpressionParser.matches(map.get("distance").toString(), actualDist)) {
              return false;
            }
          }
          if (map.containsKey("elevationDelta")) {
            Double delta = context.elevationDelta();
            if (delta == null) {
              if (context.currentY() != null && context.anchorY() != null) {
                delta = Math.abs(context.currentY() - context.anchorY());
              } else {
                delta = 0.0;
              }
            }
            if (!GateExpressionParser.matches(map.get("elevationDelta").toString(), delta)) {
              return false;
            }
          }
          if (map.containsKey("shape") || map.containsKey("region") || map.containsKey("playerCount")) {
            io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape = null;
            if (map.containsKey("shape") && map.get("shape") instanceof Map<?, ?> smap) {
              shape = io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader.deserializeShape(
                  (Map<String, Object>) smap);
            } else if (map.containsKey("region")) {
              String rName = String.valueOf(map.get("region"));
              io.github.dailystruggle.rtp.common.selection.region.Region r =
                  RTP.selectionAPI.getRegion(rName);
              if (r != null) {
                shape = r.getShape();
              }
            }
            if (shape != null) {
              // If context has current coords, check contains
              if (context.currentX() != null && context.currentZ() != null) {
                int chunkX = (int) Math.floor(context.currentX() / 16.0);
                int chunkZ = (int) Math.floor(context.currentZ() / 16.0);
                if (!shape.contains(chunkX, chunkZ)) {
                  return false;
                }
              }
              // If playerCount condition is present, count online players inside shape
              if (map.containsKey("playerCount")) {
                String reqCount = String.valueOf(map.get("playerCount"));
                int matchingPlayers = 0;
                String targetWorld = map.containsKey("world") ? String.valueOf(map.get("world")) : null;
                RTPServerAccessor accessor = RTP.serverAccessor;
                if (accessor != null) {
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
                  return false;
                }
              }
            } else if (map.containsKey("playerCount")) {
              // No shape configured but playerCount specified
              return false;
            }
          }
        }
        case "time" -> {
          if (!(val instanceof Map<?, ?> map)) return false;
          if (map.containsKey("elapsed")) {
            if (!GateExpressionParser.matches(map.get("elapsed").toString(), context.elapsedSeconds())) {
              return false;
            }
          }
          if (map.containsKey("remaining")) {
            if (!GateExpressionParser.matches(map.get("remaining").toString(), context.remainingSeconds())) {
              return false;
            }
          }
        }
        case "predicate" -> {
          String predName = val.toString().trim();
          Predicate<ActionGateContext> predicate =
              (externalPredicates == null) ? null : externalPredicates.get(predName);
          if (predicate == null || !predicate.test(context)) {
            return false;
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
          if (matchExpr == null || matchExpr.isBlank()) return false;
          int count = (context != null) ? context.participantCount() : 0;
          if (!GateExpressionParser.matches(matchExpr, count)) {
            return false;
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
          if (rawCommands.isEmpty()) return false;

          RTPServerAccessor accessor = RTP.serverAccessor;
          if (accessor == null) return false;

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
            if (ActionPlaceholderSanitizer.containsUnresolvedPrefix(substituted, "target")) {
              continue; // Wildcard unmapped target passes this command check
            }

            // Reliability: evaluate 'execute if/unless entity @X[name=,tag=]' predicate
            // checks natively via scoreboard tags. Bukkit's dispatchCommand return value
            // does not reflect the predicate result for vanilla 'execute if', so trusting
            // it silently passes/fails gates (and leaks "Test failed" chat feedback).
            Optional<Boolean> nativeResult = evaluateEntityPredicate(substituted, accessor);
            if (nativeResult.isPresent()) {
              if (!nativeResult.get()) return false;
              continue; // predicate satisfied; do not dispatch the vanilla command
            }

            boolean success = accessor.executeCommand(new UUID(0, 0), substituted);
            if (!success) return false;
          }
        }
        default -> {
          // ADR-093: Unrecognized gate key fails closed (block skipped)
          return false;
        }
      }
    }
    return true;
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
