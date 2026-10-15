package io.github.dailystruggle.rtp.anvil.bit;

/**
 * Result of a Power-On Built-In Test (PBIT) run.
 *
 * @param passed            whether the diagnostic verification succeeded
 * @param componentName     identifying name of the tested component
 * @param durationNanos     elapsed execution duration in nanoseconds
 * @param diagnosticMessage human-readable summary of status or failure reason
 * @param error             exception thrown during test execution, or {@code null} if passed
 */
public record PbitResult(
        boolean passed,
        String componentName,
        long durationNanos,
        String diagnosticMessage,
        Throwable error
) {
    public static PbitResult ok(String componentName, long durationNanos) {
        return new PbitResult(true, componentName, durationNanos, "Operational", null);
    }

    public static PbitResult ok(String componentName, long durationNanos, String message) {
        return new PbitResult(true, componentName, durationNanos, message, null);
    }

    public static PbitResult failed(String componentName, long durationNanos, String message, Throwable error) {
        return new PbitResult(false, componentName, durationNanos, message, error);
    }
}
