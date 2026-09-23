package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecorderProxyTest {

    @Test
    @DisplayName("recorder proxy forwards state, counts, and snapshots to underlying recorder")
    void testProxyDelegation() throws Exception {
        Path tempCsv = Files.createTempFile("stresstest-test-", ".csv");
        tempCsv.toFile().deleteOnExit();

        MetricsRecorder target = new MetricsRecorder(tempCsv);

        sun.misc.Unsafe unsafe;
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (sun.misc.Unsafe) f.get(null);
        } catch (Throwable t) {
            return;
        }

        StressTestRTPPlugin plugin = (StressTestRTPPlugin) unsafe.allocateInstance(StressTestRTPPlugin.class);

        java.lang.reflect.Field recField = StressTestRTPPlugin.class.getDeclaredField("recorder");
        recField.setAccessible(true);
        recField.set(plugin, target);

        java.lang.reflect.Constructor<?> ctor = Class.forName(
                "io.github.dailystruggle.helpers.stresstestrtp.StressTestRTPPlugin$RecorderProxy")
                .getDeclaredConstructor(StressTestRTPPlugin.class);
        ctor.setAccessible(true);
        MetricsRecorder proxy = (MetricsRecorder) ctor.newInstance(plugin);

        proxy.setRecording(false);
        assertFalse(target.isRecording());
        assertFalse(proxy.isRecording());

        proxy.setRecording(true);
        assertTrue(target.isRecording());
        assertTrue(proxy.isRecording());

        MetricsRecorder.Attempt a = new MetricsRecorder.Attempt(
                UUID.randomUUID(), "player1", "world", "rtp",
                System.currentTimeMillis(), 0, 0, 20.0, 10.0, 500);

        proxy.onDispatch(a);
        assertEquals(1, proxy.totalAttempts());
        assertEquals(1, proxy.inFlightCount());
        assertEquals(1, target.totalAttempts());
        assertEquals(1, target.inFlightCount());

        proxy.onComplete(a, true, "", 100, 200);
        assertEquals(0, proxy.inFlightCount());
        assertEquals(1, proxy.successCount());
        assertEquals(1, target.successCount());

        assertEquals(1, proxy.latenciesSnapshot(true).size());
        assertEquals(1, proxy.latenciesSnapshot(true, "rtp").size());
        assertEquals(0, proxy.latenciesSnapshot(true, "ezrtp").size());

        assertTrue(proxy.observedTargetLabels().contains("rtp"));
        assertTrue(proxy.coldStartLatencyMs() >= 0);
        assertTrue(proxy.coldStartLatencyMs("rtp") >= 0);
    }
}
