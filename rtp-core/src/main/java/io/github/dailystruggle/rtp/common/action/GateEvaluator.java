package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
   * Evaluates a gate configuration block against the given context.
   *
   * @param gateConfig         the gate definition map from config
   * @param context            the gate context
   * @param externalPredicates custom predicates registered via ActionService
   * @return true if all defined conditions pass, false if any fails or an unknown gate is encountered
   */
  @SuppressWarnings("unchecked")
  public static boolean evaluate(
      Map<String, Object> gateConfig,
      ActionGateContext context,
      Map<String, Predicate<ActionGateContext>> externalPredicates) {

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

          // In MVP, rtp_violations is tracked directly in ActionGateContext
          String objStr = objName.toString().trim();
          double scoreValue = 0.0;
          if ("rtp_violations".equalsIgnoreCase(objStr)) {
            scoreValue = context.violations();
          } else if ("rtp_in_bounds".equalsIgnoreCase(objStr)) {
            scoreValue = context.inBounds() ? 1.0 : 0.0;
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
                    if (p == null) continue;
                    io.github.dailystruggle.rtp.api.world.RTPLocation loc = p.getLocation();
                    if (loc == null || loc.world() == null) continue;
                    if (targetWorld != null && !loc.world().name().equalsIgnoreCase(targetWorld)) continue;
                    int cx = (int) Math.floor(loc.x() / 16.0);
                    int cz = (int) Math.floor(loc.z() / 16.0);
                    if (shape.contains(cx, cz)) {
                      matchingPlayers++;
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
        case "command" -> {
          String rawCommand = null;
          if (val instanceof Map<?, ?> map) {
            Object exec = map.get("execute");
            if (exec == null) exec = map.get("run");
            if (exec != null) rawCommand = exec.toString();
          } else if (val instanceof String s) {
            rawCommand = s;
          }
          if (rawCommand == null || rawCommand.isBlank()) return false;

          RTPServerAccessor accessor = RTP.serverAccessor;
          if (accessor == null) return false;

          Map<String, Object> tokens = new HashMap<>();
          tokens.put("session_id", context.sessionId().toString().substring(0, 8));
          if (context.participantId() != null) {
            tokens.put("player", context.participantId());
            tokens.put("violator", context.participantId());
          }
          String substituted = ActionPlaceholderSanitizer.substitute(rawCommand, tokens);
          boolean success = accessor.executeCommand(new UUID(0, 0), substituted);
          if (!success) return false;
        }
        default -> {
          // ADR-093: Unrecognized gate key fails closed (block skipped)
          return false;
        }
      }
    }
    return true;
  }
}
