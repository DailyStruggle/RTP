package io.github.dailystruggle.rtp.anvil.bit;

/**
 * Standard SPI for components supporting self-diagnostic Built-In Testing (BIT).
 */
@Deprecated(forRemoval = true)
public interface BuiltInTestable {

    /**
     * Executes the component's startup diagnostic test (PBIT).
     *
     * <p>PBIT runs once during subsystem initialization or on demand. It must complete
     * rapidly (typically &lt; 5 ms) and shall never throw unchecked exceptions out of the call;
     * any failure must be encapsulated in the returned {@link PbitResult}.</p>
     *
     * @return the outcome of the diagnostic verification
     */
    PbitResult executePbit();

    /**
     * Returns the current operational health state derived from PBIT and ongoing CBIT history.
     */
    BitHealth getHealthStatus();

    /** Adapts this legacy SPI to the standalone BIT API without changing implementers. */
    default io.github.dailystruggle.bit.BuiltInTestable asBit() {
        return new io.github.dailystruggle.bit.BuiltInTestable() {
            @Override
            public io.github.dailystruggle.bit.PbitResult executePbit() {
                try {
                    PbitResult result = BuiltInTestable.this.executePbit();
                    return result == null
                            ? io.github.dailystruggle.bit.PbitResult.failed(
                                    BuiltInTestable.this.getClass().getSimpleName(), 0L, "Legacy PBIT returned null", null)
                            : result.asBit();
                } catch (Throwable failure) {
                    return io.github.dailystruggle.bit.PbitResult.failed(
                            BuiltInTestable.this.getClass().getSimpleName(), 0L, "Legacy PBIT threw", failure);
                }
            }

            @Override
            public io.github.dailystruggle.bit.BitHealth getHealthStatus() {
                try {
                    BitHealth health = BuiltInTestable.this.getHealthStatus();
                    return health == null ? io.github.dailystruggle.bit.BitHealth.FAILED : health.asBit();
                } catch (Throwable failure) {
                    return io.github.dailystruggle.bit.BitHealth.FAILED;
                }
            }
        };
    }
}
