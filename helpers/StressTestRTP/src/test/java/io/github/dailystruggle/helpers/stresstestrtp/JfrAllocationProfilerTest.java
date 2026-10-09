package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class JfrAllocationProfilerTest {

    @Test
    @DisplayName("profiler initializes and uses custom tempDir if provided")
    void testCustomTempDirLifecycle(@TempDir Path tempDir) {
        Path customDir = tempDir.resolve("jfr-temp");
        JfrAllocationProfiler profiler = new JfrAllocationProfiler(
                Logger.getLogger("test"),
                false, // disabled to test lifecycle without triggering actual flight recorder
                "300/s",
                1024L * 1024L,
                Collections.emptyMap(),
                customDir
        );

        assertFalse(profiler.available());
        assertEquals("", profiler.scope());

        // Should not throw and should safely no-op when not capable
        profiler.beginPhase("test-phase");
        JfrAllocationProfiler.Result result = profiler.endPhaseAndParse();
        assertNotNull(result);
        assertFalse(result.available());
    }

    @Test
    @DisplayName("profiler handles default constructor with null tempDir")
    void testDefaultConstructor() {
        JfrAllocationProfiler profiler = new JfrAllocationProfiler(
                Logger.getLogger("test"),
                false,
                "300/s",
                1024L * 1024L,
                Collections.emptyMap()
        );

        assertFalse(profiler.available());
    }

    @Test
    @DisplayName("ramp stage labels resolve to their target's tracked package")
    void rampStageLabelMapsToTarget() {
        assertEquals("rtp", JfrAllocationProfiler.baseLabel("rtp@ramp0-20tps"));
        assertEquals("betterrtp", JfrAllocationProfiler.baseLabel("BetterRTP"));
        assertEquals("", JfrAllocationProfiler.baseLabel(null));
        JfrAllocationProfiler profiler = new JfrAllocationProfiler(
                Logger.getLogger("test"),
                false,
                "300/s",
                1024L * 1024L,
                java.util.Map.of("rtp", "io.github.dailystruggle.rtp")
        );
        assertEquals("io.github.dailystruggle.rtp", profiler.targetPackageFor("rtp@ramp2-100tps"));
        assertEquals("", profiler.targetPackageFor("ezrtp@ramp0-20tps"));
    }
}
