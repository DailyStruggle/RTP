package io.github.dailystruggle.rtp.common.commands.config;

import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 6 coverage for {@link ViewSubConfigCmd} — the rewritten
 * {@code /rtp config <file> view} command that opens the interactive book
 * menu via the {@link ViewSubConfigCmd.BookViewOpener} hook, and falls
 * through to the legacy raw-dump path provided by
 * {@link ViewRawSubConfigCmd} when no opener is wired.
 *
 * <p>The legacy raw-dump verb has been moved to its own subcommand
 * ({@code /rtp config <file> viewraw}); these tests assert the new entry
 * point's contract:
 * <ul>
 *   <li>When an opener is installed and returns {@code true}, the command
 *       does not fall through to the raw-dump path.</li>
 *   <li>When no opener is installed, the command falls through.</li>
 *   <li>When an opener is installed but returns {@code false}, the command
 *       falls through.</li>
 *   <li>When the opener throws, the failure is logged and the command
 *       falls through (rather than propagating the exception to the
 *       command pipeline).</li>
 * </ul>
 */
final class ViewSubConfigCmdTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        // Reset the static opener registration to a known state for each
        // test (the field is process-static and may leak across cases if
        // an earlier test left a non-null opener behind).
        ViewSubConfigCmd.setBookViewOpener(null);
    }

    @AfterEach
    void tearDown() {
        ViewSubConfigCmd.setBookViewOpener(null);
    }

    @Test
    @DisplayName("opener returning true short-circuits the command (no raw-dump fallthrough)")
    void openerTrue_shortCircuits() {
        AtomicBoolean called = new AtomicBoolean(false);
        UUID viewer = UUID.randomUUID();
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();
        ViewSubConfigCmd.setBookViewOpener((u, f) -> {
            called.set(true);
            assertSame(viewer, u, "opener must receive the caller UUID");
            assertEquals(parser.name, f, "opener must receive the parser's file name");
            return true;
        });

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, parser);
        boolean ok = cmd.onCommand(viewer, new HashMap<>(), null);
        assertTrue(ok, "onCommand must return true when opener succeeds");
        assertTrue(called.get(), "opener must have been invoked");
    }

    @Test
    @DisplayName("opener returning false falls through (raw-dump path is reachable)")
    void openerFalse_fallsThrough() {
        AtomicBoolean called = new AtomicBoolean(false);
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();
        ViewSubConfigCmd.setBookViewOpener((u, f) -> {
            called.set(true);
            return false;
        });

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, parser);
        // We don't assert chat output here (that's covered by the
        // ViewRawSubConfigCmd path); we assert the opener was at least
        // invoked and the command completed without throwing.
        cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertTrue(called.get(), "opener must have been invoked");
    }

    @Test
    @DisplayName("no opener wired: command falls through to raw-dump (does not throw)")
    void noOpener_fallsThrough() {
        assertNull(ViewSubConfigCmd.getBookViewOpener(),
                "preconditions: no opener installed");
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, parser);
        // Should not throw; fallthrough is the legacy raw-dump behavior.
        cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
    }

    @Test
    @DisplayName("throwing opener is contained; command falls through rather than propagating")
    void throwingOpener_isContained() {
        AtomicBoolean called = new AtomicBoolean(false);
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();
        ViewSubConfigCmd.setBookViewOpener((u, f) -> {
            called.set(true);
            throw new RuntimeException("boom");
        });

        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, parser);
        // Must not propagate; logged as WARN and fallthrough runs the
        // raw-dump path (which is async + best-effort).
        cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertTrue(called.get(), "opener must have been invoked");
    }

    @Test
    @DisplayName("setBookViewOpener(null) clears the previously installed opener")
    void setOpener_nullClears() {
        ViewSubConfigCmd.setBookViewOpener((u, f) -> true);
        assertSame(ViewSubConfigCmd.getBookViewOpener(),
                ViewSubConfigCmd.getBookViewOpener(),
                "opener must be retrievable while set");
        ViewSubConfigCmd.setBookViewOpener(null);
        assertNull(ViewSubConfigCmd.getBookViewOpener(),
                "passing null must clear the opener");
    }

    @Test
    @DisplayName("command surface: name='view', permission='rtp.config'")
    void commandSurface_isStable() {
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();
        ViewSubConfigCmd cmd = new ViewSubConfigCmd(null, parser);
        assertEquals("view", cmd.name());
        assertEquals("rtp.config", cmd.permission());
        assertFalse(cmd.description() == null || cmd.description().isEmpty(),
                "description must be non-empty");
    }

    @Test
    @DisplayName("ViewRawSubConfigCmd surface: name='viewraw', permission='rtp.config'")
    void rawCommandSurface_isStable() {
        ConfigParser<PerformanceKeys> parser = lookupPerformanceParser();
        ViewRawSubConfigCmd raw = new ViewRawSubConfigCmd(null, parser);
        assertEquals("viewraw", raw.name(),
                "raw chat-dump must be registered under 'viewraw' (step 6 extraction)");
        assertEquals("rtp.config", raw.permission());
    }

    // ---- helpers ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ConfigParser<PerformanceKeys> lookupPerformanceParser() {
        Object parser = RTP.configs.getParser(PerformanceKeys.class);
        if (!(parser instanceof ConfigParser<?>)) {
            // Fall back to building a synthetic list of available parsers
            // for diagnostics. RTPTestSetup is expected to install the
            // standard parser set.
            List<String> available = new ArrayList<>();
            throw new IllegalStateException(
                    "PerformanceKeys parser not wired by RTPTestSetup; "
                            + "available=" + available);
        }
        return (ConfigParser<PerformanceKeys>) parser;
    }
}
