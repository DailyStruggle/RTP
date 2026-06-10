package io.github.dailystruggle.rtp.common.commands.version;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit coverage for the descriptive {@code /rtp version} (alias {@code about})
 * subcommand. Verifies its metadata and that it emits static plugin/server
 * identity strings (and, unlike {@code /rtp info}, no realtime telemetry).
 */
public class VersionCmdTest {

    @TempDir
    Path tempDir;

    private VersionCmd versionCmd;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new SelectionAPI();
        versionCmd = new VersionCmd(null);
    }

    @Test
    void name_returnsVersion() {
        assertEquals("version", versionCmd.name());
    }

    @Test
    void permission_returnsRtpVersion() {
        assertEquals("rtp.version", versionCmd.permission());
    }

    @Test
    void description_isNotEmpty() {
        assertNotNull(versionCmd.description());
        assertFalse(versionCmd.description().isEmpty());
    }

    @Test
    void aliasConstant_isAbout() {
        assertEquals("about", VersionCmd.ALIAS);
    }

    @Test
    void onCommand_withNextCommand_delegatesToNextCommand() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = versionCmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    @Test
    void onCommand_emitsPluginAndServerIdentity() {
        UUID senderId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(senderId, "player1", null);
        accessor.addPlayer(player);

        boolean result = versionCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
        String joined = String.join("\n", ((MockRTPCommandSender) player).sentMessages);
        assertTrue(joined.contains(accessor.getPluginVersion()), "should report plugin version");
        assertTrue(joined.contains(accessor.getPlatform()), "should report platform");
        assertTrue(joined.contains(accessor.getServerVersion()), "should report server version");
    }
}
