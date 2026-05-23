package io.github.dailystruggle.rtp.proxy.common.selector;

import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curve;
import io.github.dailystruggle.rtp.proxy.common.selector.curve.Curves;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Parses a {@link LoadBalancerConfig} from the {@code loadBalancer} subtree of
 * {@code network-proxy.yml} (rtp-proxy-ADR-002). The loader is deliberately
 * lenient: missing keys fall back to {@link LoadBalancerConfig#defaults()},
 * unknown curve / input identifiers throw a descriptive
 * {@link IllegalArgumentException} so misconfiguration is surfaced loudly
 * rather than silently degrading scoring.
 *
 * <p>Two configuration shapes are accepted:</p>
 *
 * <ol>
 *   <li><b>Sub-configuration {@code terms} block</b> (preferred; rtp-proxy-ADR-004):
 *   each term is its own map with {@code input}, {@code weight}, and a nested
 *   {@code curve} sub-map. Sub-keys are flat scalars (no inline-brace YAML
 *   required by operator configs).
 *   <pre>
 *   loadBalancer:
 *     staleAfterMs: 30000
 *     terms:
 *       mspt:
 *         input: mspt
 *         weight: 1.0
 *         curve:
 *           type: sigmoid
 *           k: 8.0
 *           x0: 0.5
 *       queue:
 *         input: queueDepth
 *         weight: 1.0
 *         curve:
 *           type: exponential
 *           k: 3.0
 *     backends:
 *       backend-a:
 *         weight: 1.5
 *   </pre>
 *   The map key under {@code terms} is a free-form label (used only for
 *   ordering and operator readability); the {@code input} sub-key drives
 *   extraction. A list-of-maps form is also accepted.</li>
 *
 *   <li><b>Legacy flat weights</b>: {@code msptWeight}, {@code queueDepthWeight},
 *   {@code heapWeight}, {@code playerCountWeight}. Synthesized to linear terms
 *   by {@link LoadBalancerConfig}'s compact constructor.</li>
 * </ol>
 */
public final class LoadBalancerConfigYaml {

    private LoadBalancerConfigYaml() {}

    /**
     * Parses the {@code loadBalancer} subtree. Pass the inner map (i.e. the
     * value associated with the top-level {@code loadBalancer} key), not the
     * whole document.
     */
    public static LoadBalancerConfig fromMap(Map<String, Object> lb) {
        if (lb == null || lb.isEmpty()) return LoadBalancerConfig.defaults();
        LoadBalancerConfig d = LoadBalancerConfig.defaults();

        long staleAfterMs    = asLong  (lb.get("staleAfterMs"),     d.staleAfterMs());
        double msptW         = asDouble(lb.get("msptWeight"),        d.msptWeight());
        double queueW        = asDouble(lb.get("queueDepthWeight"),  d.queueDepthWeight());
        double heapW         = asDouble(lb.get("heapWeight"),        d.heapWeight());
        double playerW       = asDouble(lb.get("playerCountWeight"), d.playerCountWeight());

        Map<String, Double> backendWeights = readBackendWeights(lb.get("backends"));
        List<ScoringTerm> terms = readTerms(lb.get("terms"));

        return new LoadBalancerConfig(
                staleAfterMs, msptW, queueW, heapW, playerW,
                backendWeights, terms);
    }

    // ----- terms -------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<ScoringTerm> readTerms(Object node) {
        if (node == null) return List.of();
        if (node instanceof Map<?, ?> map) {
            // Preserve insertion order if the YAML loader returned one.
            Map<String, Object> ordered = (map instanceof LinkedHashMap
                    || map instanceof TreeMap) ? (Map<String, Object>) map : new LinkedHashMap<>((Map<String, Object>) map);
            List<ScoringTerm> out = new ArrayList<>(ordered.size());
            for (Map.Entry<String, Object> e : ordered.entrySet()) {
                ScoringTerm t = readOneTerm(e.getKey(), e.getValue());
                if (t != null) out.add(t);
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<ScoringTerm> out = new ArrayList<>(list.size());
            for (Object o : list) {
                ScoringTerm t = readOneTerm(null, o);
                if (t != null) out.add(t);
            }
            return out;
        }
        throw new IllegalArgumentException(
                "loadBalancer.terms must be a map or list, got " + node.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static ScoringTerm readOneTerm(String label, Object node) {
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "loadBalancer.terms entry '" + (label == null ? "?" : label)
                    + "' must be a map with input/weight/curve sub-keys");
        }
        Map<String, Object> term = (Map<String, Object>) raw;

        Object inputNode = term.get("input");
        if (inputNode == null && label != null) inputNode = label; // allow label to imply input
        if (inputNode == null) {
            throw new IllegalArgumentException(
                    "loadBalancer.terms entry is missing 'input' (label='" + label + "')");
        }
        MetricInput input = MetricInput.fromConfigId(String.valueOf(inputNode));

        double weight = asDouble(term.get("weight"), 1.0);

        Curve curve;
        Object curveNode = term.get("curve");
        if (curveNode == null) {
            curve = Curves.linear();
        } else if (curveNode instanceof Map<?, ?> cm) {
            curve = Curves.fromConfig((Map<String, Object>) cm);
        } else if (curveNode instanceof String s) {
            // Shorthand: curve: linear
            curve = Curves.fromConfig(Map.of("type", s));
        } else {
            throw new IllegalArgumentException(
                    "loadBalancer.terms." + label + ".curve must be a map or string, got "
                    + curveNode.getClass().getSimpleName());
        }
        return new ScoringTerm(input, weight, curve);
    }

    // ----- backend weights ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Double> readBackendWeights(Object node) {
        if (node == null) return Map.of();
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(
                    "loadBalancer.backends must be a map, got " + node.getClass().getSimpleName());
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<String, Object>) raw).entrySet()) {
            String serverId = String.valueOf(e.getKey());
            Object v = e.getValue();
            double w;
            if (v instanceof Map<?, ?> sub) {
                w = asDouble(((Map<String, Object>) sub).get("weight"), 1.0);
            } else {
                w = asDouble(v, 1.0);
            }
            out.put(serverId, w);
        }
        return out;
    }

    // ----- scalar coercion ---------------------------------------------------

    private static double asDouble(Object o, double fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static long asLong(Object o, long fallback) {
        if (o == null) return fallback;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    // Suppress unused-import warning when not all helpers are wired.
    @SuppressWarnings("unused")
    private static String norm(String s) { return Objects.requireNonNull(s).toLowerCase(Locale.ROOT); }
}
