package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
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
 * <p>Verifies sub-INFO log buffering prior to config loading and subsequent flush/replay through
 * the {@code RTP.log} / {@code RTP.flushPendingLogs} delegates and the config-backed gate that
 * {@code RTP} installs into {@link RTPRuntime} (ADR-115 seam 4).
 */
class BootstrapLogBufferTest {

    private RTPServerAccessor previousAccessor;
    private ServerAccessor previousCommonAccessor;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() throws Exception {
        previousAccessor = RTPRuntime.serverAccessor();
        previousCommonAccessor = ServerAccessors.get();
        File tempDir = Files.createTempDirectory("rtp-boot-log-buffer-test").toFile();
        tempDir.deleteOnExit();
        accessor = new MockRTPServerAccessor(tempDir);
        RTPRuntime.setServerAccessor(accessor);
        // Ensure the buffer is armed and empty for every test.
        resetBufferState();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Drain anything we left buffered so other tests aren't affected.
        RTP.flushPendingLogs();
        RTPRuntime.setServerAccessor(previousAccessor);
        ServerAccessors.set(previousCommonAccessor);
        ServerAccessors.setLogSink(RTPRuntime::log);
        resetBufferState();
    }

    @Test
    @DisplayName("REQ-API-NF-002: runtime registration installs the common server bridge")
    void runtimeRegistrationInstallsCommonServerBridge() {
        assertSame(accessor, ServerAccessors.get());
    }

    @Test
    @DisplayName("Sub-INFO records emitted before configs are buffered, not forwarded")
    void subInfoBuffered_whenConfigsAbsent() {
        // Configs unavailable: nullify so loggingParserUnavailable() is true.
        ActiveConfigs.set(null);

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
        ActiveConfigs.set(null);

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
        ActiveConfigs.set(null);

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
        ActiveConfigs.set(null);

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

    @Test
    @DisplayName("ServerAccessors.log and LanguageBootstrap.resolve buffer sub-INFO records before flush and replay on flush")
    void serverAccessorsAndLanguageBootstrapBufferSubInfo_andReplayOnFlush() {
        ActiveConfigs.set(null);

        ServerAccessors.log(Level.FINE, "yaml-api-fine-1");
        String locale = LanguageBootstrap.resolve(accessor.getPluginDirectory());
        assertEquals(LanguageBootstrap.DEFAULT_LOCALE, locale);

        assertTrue(accessor.logMessages.isEmpty(),
                "Sub-INFO records from ServerAccessors.log and LanguageBootstrap must be buffered before flush. Got: "
                        + accessor.logMessages);

        RTP.flushPendingLogs();

        synchronized (accessor.logMessages) {
            List<String> msgs = accessor.logMessages;
            assertTrue(msgs.size() >= 2,
                    "Buffered ServerAccessors and LanguageBootstrap records must be replayed on flush. Got: " + msgs);
            assertTrue(msgs.get(0).contains("yaml-api-fine-1"), msgs.toString());
            assertTrue(msgs.stream().anyMatch(m -> m.contains("LanguageBootstrap")), msgs.toString());
        }
    }

    /** Resets the static buffer/gate so each test sees a fresh bootstrap window. */
    private static void resetBufferState() throws Exception {
        Field gate = RTPRuntime.class.getDeclaredField("logsBuffered");
        gate.setAccessible(true);
        gate.setBoolean(null, true);

        Field queue = RTPRuntime.class.getDeclaredField("pendingLogs");
        queue.setAccessible(true);
        ((java.util.Queue<?>) queue.get(null)).clear();
    }
}
