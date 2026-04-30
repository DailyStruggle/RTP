package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the bootstrap log buffer.
 *
 * <p>Before this fix, sub-INFO records emitted by {@link RTP#log(Level, String)}
 * during plugin enable / very early {@code reloadConfigs} bypassed the
 * {@code logging.yml#min_level} gate (the platform sink defaults to {@link Level#ALL}
 * when the parser isn't built yet) and rendered as INFO on the console. The fix
 * buffers FINE/FINER/FINEST/CONFIG records while {@link RTP#configs} or its
 * {@code LoggingKeys} parser is unavailable, then replays them through the live
 * sink when {@link RTP#flushPendingLogs()} runs (called from
 * {@code Configs#reloadConfigs()} after the atomic parser swap).
 */
class BootstrapLogBufferTest {

    private RTPServerAccessor previousAccessor;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() throws Exception {
        previousAccessor = RTP.serverAccessor;
        File tempDir = Files.createTempDirectory("rtp-boot-log-buffer-test").toFile();
        tempDir.deleteOnExit();
        accessor = new MockRTPServerAccessor(tempDir);
        RTP.serverAccessor = accessor;
        // Ensure the buffer is armed and empty for every test.
        resetBufferState();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Drain anything we left buffered so other tests aren't affected.
        RTP.flushPendingLogs();
        RTP.serverAccessor = previousAccessor;
        resetBufferState();
    }

    @Test
    @DisplayName("Sub-INFO records emitted before configs are buffered, not forwarded")
    void subInfoBuffered_whenConfigsAbsent() {
        // Configs unavailable: nullify so loggingParserUnavailable() is true.
        RTP.configs = null;

        RTP.log(Level.FINE, "fine-1");
        RTP.log(Level.FINER, "finer-1");
        RTP.log(Level.FINEST, "finest-1");
        RTP.log(Level.CONFIG, "config-1");

        assertTrue(accessor.logMessages.isEmpty(),
                "Sub-INFO records must be buffered, not forwarded to the live sink. Got: "
                        + accessor.logMessages);
    }

    @Test
    @DisplayName("INFO and above bypass the buffer even when configs are absent")
    void infoAndAboveBypassBuffer() {
        RTP.configs = null;

        RTP.log(Level.INFO, "info-1");
        RTP.log(Level.WARNING, "warn-1");
        RTP.log(Level.SEVERE, "severe-1");

        // All three must be forwarded immediately to the platform sink.
        synchronized (accessor.logMessages) {
            assertEquals(3, accessor.logMessages.size(),
                    "INFO+ records must always pass through. Got: " + accessor.logMessages);
            assertTrue(accessor.logMessages.get(0).contains("info-1"));
            assertTrue(accessor.logMessages.get(1).contains("warn-1"));
            assertTrue(accessor.logMessages.get(2).contains("severe-1"));
        }
    }

    @Test
    @DisplayName("flushPendingLogs replays buffered records to the live sink in order")
    void flushReplaysBufferedRecords() {
        RTP.configs = null;

        RTP.log(Level.FINE, "fine-A");
        RTP.log(Level.FINER, "finer-B");
        RTP.log(Level.FINEST, "finest-C");

        assertTrue(accessor.logMessages.isEmpty(),
                "Pre-flush sink must be empty.");

        RTP.flushPendingLogs();

        synchronized (accessor.logMessages) {
            List<String> msgs = accessor.logMessages;
            assertEquals(3, msgs.size(),
                    "All buffered records must be replayed. Got: " + msgs);
            assertTrue(msgs.get(0).contains("fine-A"), msgs.toString());
            assertTrue(msgs.get(1).contains("finer-B"), msgs.toString());
            assertTrue(msgs.get(2).contains("finest-C"), msgs.toString());
        }
    }

    @Test
    @DisplayName("After flush, subsequent sub-INFO records bypass the buffer")
    void afterFlushBypassesBuffer() {
        RTP.configs = null;

        RTP.log(Level.FINE, "before-flush");
        RTP.flushPendingLogs();
        accessor.logMessages.clear();

        RTP.log(Level.FINE, "after-flush");

        synchronized (accessor.logMessages) {
            assertEquals(1, accessor.logMessages.size(),
                    "Post-flush sub-INFO must pass through directly.");
            assertTrue(accessor.logMessages.get(0).contains("after-flush"));
        }
    }

    /** Resets the static buffer/gate so each test sees a fresh bootstrap window. */
    private static void resetBufferState() throws Exception {
        Field gate = RTP.class.getDeclaredField("logsBuffered");
        gate.setAccessible(true);
        gate.setBoolean(null, true);

        Field queue = RTP.class.getDeclaredField("pendingLogs");
        queue.setAccessible(true);
        ((java.util.Queue<?>) queue.get(null)).clear();
    }
}
