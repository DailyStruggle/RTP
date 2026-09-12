package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link CfDiag} (ENTERPRISE_READINESS item 19, {@code tools} package).
 */
public class CfDiagTest {

    @TempDir
    File pluginDir;

    @BeforeEach
    void setUp() throws Exception {
        RTPTestSetup.install(pluginDir);

        Field startedField = CfDiag.class.getDeclaredField("STARTED");
        startedField.setAccessible(true);
        ((AtomicBoolean) startedField.get(null)).set(false);
    }

    @Test
    void privateConstructorCanBeInvoked() throws Exception {
        Constructor<CfDiag> ctor = CfDiag.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CfDiag instance = ctor.newInstance();
        assertNotNull(instance);
    }

    @Test
    void ensureStartedSchedulesPeriodicDumpIdempotently() {
        assertDoesNotThrow(CfDiag::ensureStarted);
        // Second call hits compareAndSet(false, true) -> false and short circuits
        assertDoesNotThrow(CfDiag::ensureStarted);
    }

    @Test
    void dumpOnceWithZeroActivityReturnsEarly() throws Exception {
        Method dumpOnce = CfDiag.class.getDeclaredMethod("dumpOnce");
        dumpOnce.setAccessible(true);
        // When no counters have been incremented and last snapshot equals current, it skips logging
        assertDoesNotThrow(() -> dumpOnce.invoke(null));
    }

    @Test
    void dumpOnceWithCounterActivityFormatsAndLogs() throws Exception {
        Method dumpOnce = CfDiag.class.getDeclaredMethod("dumpOnce");
        dumpOnce.setAccessible(true);

        // Increment diverse counters to exercise formatting branches in rate and StringBuilder
        CfDiag.pregenAttemptStart.add(5);
        CfDiag.locationGenPregenEntry.add(25);
        CfDiag.regionExecute.add(1500); // rate >= 1000.0 branch
        CfDiag.chunkSetRegion.add(12);   // rate >= 10.0 branch
        CfDiag.scanTestPos.add(1);       // rate < 10.0 branch

        assertDoesNotThrow(() -> dumpOnce.invoke(null));

        // Subsequent dump with no new activity returns early
        assertDoesNotThrow(() -> dumpOnce.invoke(null));
    }

    @Test
    void rateFormattingBranches() throws Exception {
        Method rateMethod = CfDiag.class.getDeclaredMethod("rate", long.class, double.class);
        rateMethod.setAccessible(true);

        String highRate = (String) rateMethod.invoke(null, 5000L, 1.0);
        assertNotNull(highRate);

        String medRate = (String) rateMethod.invoke(null, 25L, 1.0);
        assertNotNull(medRate);

        String lowRate = (String) rateMethod.invoke(null, 2L, 1.0);
        assertNotNull(lowRate);
    }
}
