package io.github.dailystruggle.bit;

/** Result of a Power-On Built-In Test. */
public record PbitResult(
        boolean passed,
        String componentName,
        long durationNanos,
        String diagnosticMessage,
        Throwable error) {

    public PbitResult {
        componentName = componentName == null ? "" : componentName;
        diagnosticMessage = diagnosticMessage == null ? "" : diagnosticMessage;
        durationNanos = Math.max(0L, durationNanos);
    }

    public static PbitResult ok(String componentName, long durationNanos) {
        return ok(componentName, durationNanos, "Operational");
    }

    public static PbitResult ok(String componentName, long durationNanos, String message) {
        return new PbitResult(true, componentName, durationNanos, message, null);
    }

    public static PbitResult failed(String componentName, long durationNanos, String message, Throwable error) {
        return new PbitResult(false, componentName, durationNanos, message, error);
    }
}
