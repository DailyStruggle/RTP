package io.github.dailystruggle.rtp.anvil.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

@SuppressWarnings("removal")
class BitCompatibilityTest {
    @Test
    void legacyImplementationAdaptsToStandaloneContract() {
        BuiltInTestable legacy = new BuiltInTestable() {
            @Override
            public PbitResult executePbit() {
                return PbitResult.ok("legacy", 17L, "ready");
            }

            @Override
            public BitHealth getHealthStatus() {
                return BitHealth.DEGRADED;
            }
        };

        io.github.dailystruggle.bit.BuiltInTestable adapted = legacy.asBit();
        io.github.dailystruggle.bit.PbitResult result = adapted.executePbit();
        assertEquals(true, result.passed());
        assertEquals("legacy", result.componentName());
        assertEquals(17L, result.durationNanos());
        assertEquals("ready", result.diagnosticMessage());
        assertEquals(io.github.dailystruggle.bit.BitHealth.DEGRADED, adapted.getHealthStatus());
        assertEquals(io.github.dailystruggle.bit.BitHealth.FAILED, BitHealth.FAULTED.asBit());
        assertFalse(PbitResult.failed("legacy", 1L, "failed", null).asBit().passed());
    }

    @Test
    void adapterCapturesLegacyPbitExceptionsAsFailureResults() {
        BuiltInTestable legacy = new BuiltInTestable() {
            @Override
            public PbitResult executePbit() {
                throw new IllegalStateException("legacy failure");
            }

            @Override
            public BitHealth getHealthStatus() {
                return BitHealth.HEALTHY;
            }
        };

        io.github.dailystruggle.bit.PbitResult result = legacy.asBit().executePbit();
        assertFalse(result.passed());
        assertEquals("legacy failure", result.error().getMessage());
    }
}
