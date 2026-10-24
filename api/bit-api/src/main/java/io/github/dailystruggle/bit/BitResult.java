package io.github.dailystruggle.bit;

import java.util.Objects;

/** One component's result, including timing and component metadata. */
public record BitResult(BitComponent component, BitReport report, long durationMillis, long completedAtMillis) {
    public BitResult {
        Objects.requireNonNull(component, "component must not be null");
        Objects.requireNonNull(report, "report must not be null");
        if (durationMillis < 0) throw new IllegalArgumentException("durationMillis must not be negative");
        if (completedAtMillis < 0) throw new IllegalArgumentException("completedAtMillis must not be negative");
    }

    /** Writes the component and report as a self-contained JSON object. */
    public String toJson() {
        return "{\"component\":\"" + BitJson.escape(component.id())
                + "\",\"subsystem\":\"" + BitJson.escape(component.subsystem())
                + "\",\"tier\":\"" + component.tier().name()
                + "\",\"sideEffect\":\"" + component.sideEffect().name()
                + "\",\"status\":\"COMPLETED\",\"durationMillis\":" + durationMillis
                + ",\"completedAtMillis\":" + completedAtMillis
                + ",\"report\":" + report.toJson() + "}";
    }
}
