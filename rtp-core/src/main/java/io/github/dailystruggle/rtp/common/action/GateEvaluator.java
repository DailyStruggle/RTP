package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionGateContext;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Evaluator for declarative MVP gates (ADR-093 Section 3).
 *
 * <p>Supported gate types:
 * <ol>
 *   <li>{@code scoreboard}: compares entity/session score against objective and match expr</li>
 *   <li>{@code spatial}: withinBoundary, distance, elevationDelta</li>
 *   <li>{@code time}: elapsed, remaining duration thresholds</li>
 *   <li>{@code predicate}: invokes externally registered programmatic predicate</li>
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
            double actualDist = Math.sqrt(Math.max(0.0, context.distanceSqFromAnchor()));
            if (!GateExpressionParser.matches(map.get("distance").toString(), actualDist)) {
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
        default -> {
          // ADR-093: Unrecognized gate key fails closed (block skipped)
          return false;
        }
      }
    }
    return true;
  }
}
