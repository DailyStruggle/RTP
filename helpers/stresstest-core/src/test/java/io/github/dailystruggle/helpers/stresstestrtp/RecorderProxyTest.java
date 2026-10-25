package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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

        StressHost plugin = (StressHost) Proxy.newProxyInstance(
                StressHost.class.getClassLoader(),
                new Class<?>[]{StressHost.class},
                (p, m, a) -> "recorder".equals(m.getName()) ? target : null);

        MetricsRecorder proxy = new StressCoreRuntime.RecorderProxy(plugin);

        proxy.setRecording(false);
        assertFalse(target.isRecording());
        assertFalse(proxy.isRecording());

        proxy.setRecording(true);
        assertTrue(target.isRecording());
        assertTrue(proxy.isRecording());

        Attempt a = new Attempt(
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
