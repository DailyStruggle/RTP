package io.github.dailystruggle.rtp.common.commands.reload;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
public class SubReloadCmdTest {

    @TempDir
    Path tempDir;

    private SubReloadCmd<io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys> cmd;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        cmd = new SubReloadCmd<>(
                null,
                "performance",
                "rtp.reload",
                "reload only performance",
                io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
    }

    // ── Metadata accessors ────────────────────────────────────────────────────

    @Test
    void name_returnsConstructorValue() {
        assertEquals("performance", cmd.name());
    }

    @Test
    void permission_returnsConstructorValue() {
        assertEquals("rtp.reload", cmd.permission());
    }

    @Test
    void description_returnsConstructorValue() {
        assertEquals("reload only performance", cmd.description());
    }

    // ── subReload dispatch ────────────────────────────────────────────────────

    @Test
    void subReload_withNullFactoryValue_returnsFalse() {
        boolean result = cmd.subReload(UUID.randomUUID(), null);
        assertFalse(result, "subReload(null) should return false");
    }

    @Test
    void subReload_withUnknownFactoryValue_returnsFalse() {
        // A FactoryValue that is neither ConfigParser nor MultiConfigParser
        FactoryValue<?> unknown = mock(FactoryValue.class);
        boolean result = cmd.subReload(UUID.randomUUID(), unknown);
        assertFalse(result, "subReload with unknown FactoryValue type should return false");
    }

    @Test
    void subReload_withConfigParser_delegatesToSubReloadSingle() {
        // subReloadSingle requires RTP.configs to have a Enum<?> parser.
        // RTPTestSetup.install() registers all core parsers including MessagesKeys,
        // so this should succeed and return true.
        FactoryValue<?> parser = RTP.configs.getParser(
                io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys.class);
        assumeNotNull(parser);
        boolean result = cmd.subReload(UUID.randomUUID(), parser);
        assertTrue(result, "subReload with a real ConfigParser should return true");
    }

    // ── onCommand delegation ──────────────────────────────────────────────────

    @Test
    void onCommand_withNextCommand_delegatesToNextCommand() {
        io.github.dailystruggle.commandsapi.common.CommandsAPICommand next =
                mock(io.github.dailystruggle.commandsapi.common.CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = cmd.onCommand(UUID.randomUUID(), new java.util.HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private static void assumeNotNull(Object obj) {
        org.junit.jupiter.api.Assumptions.assumeTrue(obj != null,
                "Skipping test: required object is null (RTP.configs not fully initialised)");
    }
}
