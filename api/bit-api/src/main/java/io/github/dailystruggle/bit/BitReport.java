package io.github.dailystruggle.bit;

import java.util.List;
import java.util.Objects;

/** Immutable report with a versioned JSON representation. */
public record BitReport(int schema, BitHealth health, List<BitFinding> findings) {
    public static final int CURRENT_SCHEMA = 1;

    public BitReport {
        if (schema < 1) {
            throw new IllegalArgumentException("schema must be positive");
        }
        Objects.requireNonNull(health, "health must not be null");
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static BitReport of(List<BitFinding> findings) {
        List<BitFinding> copy = findings == null ? List.of() : List.copyOf(findings);
        BitHealth health = BitHealth.HEALTHY;
        for (BitFinding finding : copy) {
            BitHealth candidate = BitLevels.health(finding.severity());
            if (candidate.ordinal() > health.ordinal()) health = candidate;
        }
        return new BitReport(CURRENT_SCHEMA, health, copy);
    }

    /** Writes JSON without binding the shared API to a JSON library. */
    public String toJson() {
        StringBuilder json = new StringBuilder("{\"schema\":").append(schema)
                .append(",\"health\":\"").append(health.name()).append("\",\"findings\":[");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) json.append(',');
            BitFinding finding = findings.get(i);
            json.append("{\"severity\":\"").append(finding.severity().name())
                    .append("\",\"id\":").append(finding.codeId())
                    .append(",\"ns\":\"").append(escape(finding.namespace())).append('"')
                    .append(",\"code\":\"").append(escape(finding.code()))
                    .append("\",\"source\":\"").append(escape(finding.source()))
                    .append("\",\"path\":\"").append(escape(finding.path()))
                    .append("\",\"message\":\"").append(escape(finding.message()))
                    .append("\",\"expected\":\"").append(escape(finding.expected()))
                    .append("\",\"suggestion\":\"").append(escape(finding.suggestion())).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String escape(String value) {
        return BitJson.escape(value);
    }
}
