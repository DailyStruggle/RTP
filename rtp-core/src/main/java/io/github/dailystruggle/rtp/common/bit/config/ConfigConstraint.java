package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.KeyPath;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.selection.region.util.DistanceParser;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative ordering between two distance keys of one config block (ADR-121 section 6): {@code
 * lower < upper}, or {@code lower <= upper} when {@code equalAllowed}. Declared next to the loader
 * that reads the keys; evaluated by {@link ConfigConstraintCheck}.
 *
 * <p>Evaluated only when both keys are present and parse as distances: a missing, referenced
 * ({@code @<file>}) or mistyped value belongs to other checks. Bare numbers use {@code defaultUnit},
 * matching the owning loader, so {@code 4096b} and {@code 64} (chunks) compare correctly.
 *
 * @param scope directory ending in {@code /} (every document directly inside) or one file path
 * @param block dotted path to the block; first segment is a canonical top-level key; empty = root
 */
public record ConfigConstraint(String scope, String block, String lower, String upper, boolean equalAllowed,
                               SpatialUnit defaultUnit, BitCode code) {
    public ConfigConstraint {
        Objects.requireNonNull(scope, "scope");
        block = block == null ? "" : block.trim();
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
        defaultUnit = defaultUnit == null ? SpatialUnit.BLOCK : defaultUnit;
        Objects.requireNonNull(code, "code");
    }

    public List<BitFinding> evaluate(ConfigSnapshot snapshot) {
        List<BitFinding> findings = new ArrayList<>();
        if (snapshot == null) return findings;
        if (scope.endsWith("/")) {
            for (ConfigSnapshot.Document document : snapshot.documentsIn(scope).values()) evaluate(document, findings);
        } else {
            snapshot.document(scope).ifPresent(document -> evaluate(document, findings));
        }
        return findings;
    }

    private void evaluate(ConfigSnapshot.Document document, List<BitFinding> findings) {
        Map<?, ?> values = document.values();
        KeyPath path = null;
        if (!block.isEmpty()) {
            String[] segments = block.split("\\.");
            Object current = document.get(segments[0]);
            path = KeyPath.root(document.localizedKey(segments[0]));
            for (int i = 1; i < segments.length && current != null; i++) {
                Map<?, ?> map = asMap(current);
                current = map == null ? null : map.get(segments[i]);
                path = path.key(segments[i]);
            }
            values = asMap(current);
        }
        if (values == null || !values.containsKey(lower) || !values.containsKey(upper)) return;
        Object rawLower = values.get(lower);
        Object rawUpper = values.get(upper);
        Double lowerBlocks = blocks(rawLower);
        Double upperBlocks = blocks(rawUpper);
        if (lowerBlocks == null || upperBlocks == null) return;
        boolean holds = equalAllowed ? lowerBlocks <= upperBlocks : lowerBlocks < upperBlocks;
        if (holds) return;
        String lowerPath = path == null ? lower : path.key(lower).toString();
        String upperPath = path == null ? upper : path.key(upper).toString();
        String relation = equalAllowed ? " >= " : " > ";
        findings.add(BitFinding.of(code, document.path(), equalAllowed ? lowerPath : upperPath,
                upperPath + " (" + rawUpper + " = " + format(upperBlocks) + " blocks) must be"
                        + (equalAllowed ? " at least " : " greater than ") + lowerPath
                        + " (" + rawLower + " = " + format(lowerBlocks) + " blocks)",
                upper + relation + lower, code.remediation()));
    }

    private Double blocks(Object raw) {
        if (raw == null || raw instanceof Boolean) return null;
        if (raw instanceof String text && text.trim().startsWith("@")) return null;
        DistanceParser.ParsedDistance parsed = DistanceParser.parse(String.valueOf(raw), defaultUnit);
        return parsed == null ? null : parsed.toBlocks();
    }

    private static String format(double blocks) {
        return blocks == Math.rint(blocks) ? Long.toString((long) blocks) : Double.toString(blocks);
    }

    @SuppressWarnings("java:S1168") // Null identifies non-mapping config values and stops nested lookup.
    static Map<?, ?> asMap(Object value) {
        if (value instanceof RtpYamlSection section) return section.getValues(false);
        if (value instanceof Map<?, ?> map) return map;
        return null;
    }
}
