package io.github.dailystruggle.bit;

import java.util.Objects;
import java.util.function.Consumer;

/** Platform-neutral BIT renderers. Callers supply all output transport. */
public final class BitOutputs {
    private BitOutputs() {}

    /** Human-readable lines beginning with the caller's prefix. */
    public static BitOutput text(String prefix) {
        String outputPrefix = Objects.requireNonNull(prefix, "prefix must not be null");
        return new BitOutput() {
            @Override
            public void write(BitResult result, Consumer<String> sink) {
                Objects.requireNonNull(result, "result must not be null");
                Objects.requireNonNull(sink, "sink must not be null");
                BitComponent component = result.component();
                sink.accept(outputPrefix + component.id() + " health=" + result.report().health()
                        + " tier=" + component.tier() + " subsystem=" + component.subsystem()
                        + " durationMs=" + result.durationMillis());
                for (BitFinding finding : result.report().findings()) {
                    sink.accept(outputPrefix + component.id() + " " + finding.severity() + " " + finding.code()
                            + " path=" + finding.path() + " message=" + finding.message());
                }
            }

            @Override
            public void writeNotRun(BitComponent component, Consumer<String> sink) {
                Objects.requireNonNull(component, "component must not be null");
                Objects.requireNonNull(sink, "sink must not be null");
                sink.accept(outputPrefix + component.id() + " status=NOT_RUN tier=" + component.tier()
                        + " subsystem=" + component.subsystem() + " sideEffect=" + component.sideEffect());
            }

            @Override
            public void writeEmpty(String selector, Consumer<String> sink) {
                Objects.requireNonNull(sink, "sink must not be null");
                sink.accept(outputPrefix + "no components matched selector=" + selector);
            }
        };
    }

    /** JSON lines with a component-specific prefix, suitable for log collectors. */
    public static BitOutput json(String prefix) {
        String outputPrefix = Objects.requireNonNull(prefix, "prefix must not be null");
        return new BitOutput() {
            @Override
            public void write(BitResult result, Consumer<String> sink) {
                Objects.requireNonNull(result, "result must not be null");
                Objects.requireNonNull(sink, "sink must not be null");
                sink.accept(outputPrefix + result.component().id() + "] " + result.toJson());
            }

            @Override
            public void writeNotRun(BitComponent component, Consumer<String> sink) {
                Objects.requireNonNull(component, "component must not be null");
                Objects.requireNonNull(sink, "sink must not be null");
                sink.accept(outputPrefix + component.id() + "] {\"component\":\""
                        + BitJson.escape(component.id()) + "\",\"subsystem\":\""
                        + BitJson.escape(component.subsystem()) + "\",\"tier\":\"" + component.tier()
                        + "\",\"sideEffect\":\"" + component.sideEffect() + "\",\"status\":\"NOT_RUN\"}");
            }

            @Override
            public void writeEmpty(String selector, Consumer<String> sink) {
                Objects.requireNonNull(sink, "sink must not be null");
                sink.accept(outputPrefix + "status] {\"selector\":\"" + BitJson.escape(selector)
                        + "\",\"status\":\"NO_COMPONENTS\"}");
            }
        };
    }
}
