package io.github.dailystruggle.rtp.anvil.bit;

/**
 * Standard SPI for components supporting self-diagnostic Built-In Testing (BIT).
 */
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
}
