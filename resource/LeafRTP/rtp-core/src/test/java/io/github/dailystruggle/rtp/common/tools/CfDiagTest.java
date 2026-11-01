package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        // >= 1000.0 branch
        String highRate = (String) rateMethod.invoke(null, 5000L, 1.0);
        assertEquals("5000", highRate);

        String exact1000 = (String) rateMethod.invoke(null, 1000L, 1.0);
        assertEquals("1000", exact1000);

        // >= 10.0 branch
        String medRate = (String) rateMethod.invoke(null, 25L, 1.0);
        assertEquals("25.0", medRate);

        String exact10 = (String) rateMethod.invoke(null, 10L, 1.0);
        assertEquals("10.0", exact10);

        String ninePointNine = (String) rateMethod.invoke(null, 99L, 10.0);
        assertEquals("9.90", ninePointNine);

        // < 10.0 branch
        String lowRate = (String) rateMethod.invoke(null, 2L, 1.0);
        assertEquals("2.00", lowRate);

        String zeroRate = (String) rateMethod.invoke(null, 0L, 1.0);
        assertEquals("0.00", zeroRate);
    }

    @Test
    void ensureStartedHandlesSchedulerThrowAndResetsStarted() throws Exception {
        RTPServerAccessor originalAccessor = RTPRuntime.serverAccessor();
        try {
            RTPServerAccessor throwingAccessor = (RTPServerAccessor) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{RTPServerAccessor.class},
                    (proxy, method, args) -> {
                        if ("getScheduler".equals(method.getName())) {
                            throw new RuntimeException("Scheduler unavailable");
                        }
                        return null;
                    }
            );
            RTPRuntime.setServerAccessor(throwingAccessor);

            // Calling ensureStarted when scheduler throws should catch exception and reset STARTED to false
            assertDoesNotThrow(CfDiag::ensureStarted);

            Field startedField = CfDiag.class.getDeclaredField("STARTED");
            startedField.setAccessible(true);
            boolean started = ((AtomicBoolean) startedField.get(null)).get();
            assertFalse(started, "STARTED must be reset to false when scheduler fails");
        } finally {
            RTPRuntime.setServerAccessor(originalAccessor);
        }
    }

    @Test
    void dumpOnceEachCounterIndividuallyTriggersLogging() throws Exception {
        Method dumpOnce = CfDiag.class.getDeclaredMethod("dumpOnce");
        dumpOnce.setAccessible(true);

        java.util.concurrent.atomic.LongAdder[] adders = new java.util.concurrent.atomic.LongAdder[]{
                CfDiag.pregenAttemptStart, CfDiag.pregenAllOfDispatch, CfDiag.pregenAllOfTimeout,
                CfDiag.queueAllOfDispatch, CfDiag.queueAllOfTimeout, CfDiag.regionCacheTaskIssue,
                CfDiag.locationGenPregenEntry, CfDiag.locationGenQueueEntry, CfDiag.pregenReschedule,
                CfDiag.chunkSetPregenLive, CfDiag.chunkSetPregenVerified,
                CfDiag.chunkSetQueueLive, CfDiag.chunkSetQueueVd,
                CfDiag.chunkSetRegion, CfDiag.chunkSetRegionCache, CfDiag.chunkSetPipeline,
                CfDiag.chunkSetFabricGetChunk, CfDiag.fabricGetChunkAtAsync,
                CfDiag.fabricLoadLiveChunk,
                CfDiag.scanRunBatch, CfDiag.scanTestPos, CfDiag.scanRunFullLoad,
                CfDiag.regionExecute, CfDiag.regionDeficitDispatch
        };

        // For each counter, test that incrementing it alone produces non-zero delta and triggers dump
        for (java.util.concurrent.atomic.LongAdder adder : adders) {
            adder.add(1);
            assertDoesNotThrow(() -> dumpOnce.invoke(null));
        }

        // Verify that the logged message actually contains formatted delta rates
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor mockAccessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTPRuntime.serverAccessor();
        assertFalse(mockAccessor.logMessages.isEmpty());
        String lastLog = mockAccessor.logMessages.get(mockAccessor.logMessages.size() - 1);
        assertTrue(lastLog.contains("[RTP][CFDIAG] window="));

        // Now test with simulated elapsedSec = 2.0 (by advancing LAST_DUMP_NANOS 2 seconds ago)
        Field lastDumpField = CfDiag.class.getDeclaredField("LAST_DUMP_NANOS");
        lastDumpField.setAccessible(true);
        AtomicLong lastDump = (AtomicLong) lastDumpField.get(null);
        lastDump.set(System.nanoTime() - 2_000_000_000L);

        CfDiag.pregenAttemptStart.add(10);
        assertDoesNotThrow(() -> dumpOnce.invoke(null));

        String dumpLog = mockAccessor.logMessages.get(mockAccessor.logMessages.size() - 1);
        assertTrue(dumpLog.contains("pregen{attempt="));
    }

    /** Counters in the order their rates appear in the dump line, with the label preceding each. */
    private static Object[][] rateLayout() {
        return new Object[][]{
                {" locGen{pregen=", CfDiag.locationGenPregenEntry},
                {"/s queue=", CfDiag.locationGenQueueEntry},
                {"/s} regionCache=", CfDiag.regionCacheTaskIssue},
                {"/s pregen{attempt=", CfDiag.pregenAttemptStart},
                {"/s reschedule=", CfDiag.pregenReschedule},
                {"/s allOf=", CfDiag.pregenAllOfDispatch},
                {"/s timeout=", CfDiag.pregenAllOfTimeout},
                {"/s} queue{allOf=", CfDiag.queueAllOfDispatch},
                {"/s timeout=", CfDiag.queueAllOfTimeout},
                {"/s} chunkSet{pregenLive=", CfDiag.chunkSetPregenLive},
                {"/s pregenVer=", CfDiag.chunkSetPregenVerified},
                {"/s queueLive=", CfDiag.chunkSetQueueLive},
                {"/s queueVd=", CfDiag.chunkSetQueueVd},
                {"/s region=", CfDiag.chunkSetRegion},
                {"/s regionCache=", CfDiag.chunkSetRegionCache},
                {"/s pipeline=", CfDiag.chunkSetPipeline},
                {"/s fabricGCA=", CfDiag.chunkSetFabricGetChunk},
                {"/s} fabric{getChunkAtAsync=", CfDiag.fabricGetChunkAtAsync},
                {"/s loadLiveChunk=", CfDiag.fabricLoadLiveChunk},
                {"/s} scan{run=", CfDiag.scanRunBatch},
                {"/s testPos=", CfDiag.scanTestPos},
                {"/s fullLoad=", CfDiag.scanRunFullLoad},
                {"/s} region{execute=", CfDiag.regionExecute},
                {"/s deficitDispatch=", CfDiag.regionDeficitDispatch},
        };
    }

    private static String lastLog() {
        java.util.List<String> logs =
                ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTPRuntime.serverAccessor()).logMessages;
        return logs.isEmpty() ? "" : logs.get(logs.size() - 1);
    }

    @Test
    void dumpOnceReportsPerCounterDeltaRatesAndRunningTotals() throws Exception {
        Method dumpOnce = CfDiag.class.getDeclaredMethod("dumpOnce");
        dumpOnce.setAccessible(true);
        Field lastDumpField = CfDiag.class.getDeclaredField("LAST_DUMP_NANOS");
        lastDumpField.setAccessible(true);
        AtomicLong lastDump = (AtomicLong) lastDumpField.get(null);
        Object[][] layout = rateLayout();

        // Non-zero snapshots, so a delta computed as n + s instead of n - s is visible.
        for (Object[] row : layout) {
            ((java.util.concurrent.atomic.LongAdder) row[1]).add(7);
        }
        dumpOnce.invoke(null);

        // Counter k (1-based) gains (k - 0.5) * 10_000 over a 10_000 s window: rate k - 0.5 per
        // second. Half-integers keep the few microseconds of extra window off the format boundaries.
        final long windowSec = 10_000L;
        StringBuilder expectedRates = new StringBuilder();
        for (int i = 0; i < layout.length; i++) {
            double rate = i + 0.5;
            ((java.util.concurrent.atomic.LongAdder) layout[i][1]).add((long) (rate * windowSec));
            expectedRates.append(layout[i][0])
                    .append(rate < 10.0 ? String.format("%.2f", rate) : String.format("%.1f", rate));
        }
        expectedRates.append("/s} totals{");
        lastDump.set(System.nanoTime() - windowSec * 1_000_000_000L);
        dumpOnce.invoke(null);

        String log = lastLog();
        assertTrue(log.contains("[RTP][CFDIAG] window=10000."), log);
        int ratesStart = log.indexOf(" locGen{");
        int ratesEnd = log.indexOf("totals{") + "totals{".length();
        assertEquals(expectedRates.toString(), log.substring(ratesStart, ratesEnd));

        String expectedTotals = "totals{pregenAttempt=" + CfDiag.pregenAttemptStart.sum()
                + " pregenAllOf=" + CfDiag.pregenAllOfDispatch.sum()
                + " queueAllOf=" + CfDiag.queueAllOfDispatch.sum()
                + " regionCache=" + CfDiag.regionCacheTaskIssue.sum()
                + " chunkSetPregenVer=" + CfDiag.chunkSetPregenVerified.sum()
                + " chunkSetQueueVd=" + CfDiag.chunkSetQueueVd.sum()
                + " chunkSetFabricGCA=" + CfDiag.chunkSetFabricGetChunk.sum()
                + " fabricGetChunkAtAsync=" + CfDiag.fabricGetChunkAtAsync.sum()
                + " fabricLoadLiveChunk=" + CfDiag.fabricLoadLiveChunk.sum()
                + " scanTestPos=" + CfDiag.scanTestPos.sum()
                + " scanRunFullLoad=" + CfDiag.scanRunFullLoad.sum()
                + " regionExecute=" + CfDiag.regionExecute.sum()
                + " regionDeficitDispatch=" + CfDiag.regionDeficitDispatch.sum()
                + "}";
        assertTrue(log.endsWith(expectedTotals), log);
    }

    @Test
    void dumpOnceLogsWhenOnlyOneCounterMoved() throws Exception {
        Method dumpOnce = CfDiag.class.getDeclaredMethod("dumpOnce");
        dumpOnce.setAccessible(true);
        dumpOnce.invoke(null); // settle snapshots

        for (Object[] row : rateLayout()) {
            java.util.List<String> logs =
                    ((io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) RTPRuntime.serverAccessor()).logMessages;
            int before = logs.size();
            ((java.util.concurrent.atomic.LongAdder) row[1]).add(1);
            dumpOnce.invoke(null);
            assertEquals(before + 1, logs.size(), "a single moved counter must emit a dump: " + row[0]);
            assertTrue(lastLog().contains("[RTP][CFDIAG] window="));
        }
    }
}
