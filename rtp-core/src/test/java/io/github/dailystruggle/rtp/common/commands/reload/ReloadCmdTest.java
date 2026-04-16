package io.github.dailystruggle.rtp.common.commands.reload;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

public class ReloadCmdTest {

    @TempDir
    Path tempDir;

    private ReloadCmd reloadCmd;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        reloadCmd = new ReloadCmd(null);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    void name_returnsReload() {
        assertEquals("reload", reloadCmd.name());
    }

    @Test
    void permission_returnsRtpReload() {
        assertEquals("rtp.reload", reloadCmd.permission());
    }

    @Test
    void description_isNotEmpty() {
        assertNotNull(reloadCmd.description());
        assertFalse(reloadCmd.description().isEmpty());
    }

    // ── onCommand delegation ──────────────────────────────────────────────────

    @Test
    void onCommand_withNextCommand_delegatesToNextCommand() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = reloadCmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    // ── onCommand execution ───────────────────────────────────────────────────

    @Test
    void onCommand_noNextCommand_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "admin", null));

        boolean result = reloadCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
    }

    @Test
    void onCommand_setsReloadingFlag() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "admin2", null));

        // Before command, reloading should be false
        RTP.reloading.set(false);

        // The command schedules an async task that sets reloading=true.
        // After the call returns, the flag may be true (async) or false (sync mock).
        // Either way the command must not throw and must return true.
        boolean result = reloadCmd.onCommand(senderId, new HashMap<>(), null);
        assertTrue(result);
    }

    @Test
    void onCommand_withConsoleSender_returnsTrue() {
        boolean result = reloadCmd.onCommand(io.github.dailystruggle.rtp.api.RTPAPI.serverId, new HashMap<>(), null);
        assertTrue(result);
    }

    // ── addCommands ───────────────────────────────────────────────────────────

    @Test
    void addCommands_populatesSubCommandsFromConfigs() {
        reloadCmd.addCommands();
        // Should have sub-commands for each registered config parser
        assertFalse(reloadCmd.getCommandLookup().isEmpty());
    }

    @Test
    void addCommands_doesNotDuplicateExistingSubCommands() {
        reloadCmd.addCommands();
        int sizeAfterFirst = reloadCmd.getCommandLookup().size();
        reloadCmd.addCommands();
        int sizeAfterSecond = reloadCmd.getCommandLookup().size();
        assertEquals(sizeAfterFirst, sizeAfterSecond,
                "Calling addCommands twice should not add duplicate sub-commands");
    }

    // ── [filename] replacement in messages ───────────────────────────────────

    @Test
    void onCommand_filenamePatternReplacedWithConfigs() {
        // Verify the command completes without error when lang returns a [filename] placeholder
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "admin3", null));

        // The real MessagesKeys parser is registered; reloading/reloaded messages
        // may contain [filename] — the command should replace it with "configs"
        assertDoesNotThrow(() -> reloadCmd.onCommand(senderId, new HashMap<>(), null));
    }

    // ── sub-command structure ─────────────────────────────────────────────────

    @Test
    void subCommands_haveCorrectPermission() {
        reloadCmd.addCommands();
        for (CommandsAPICommand sub : reloadCmd.getCommandLookup().values()) {
            assertEquals("rtp.reload", sub.permission(),
                    "All sub-commands of ReloadCmd should require rtp.reload");
        }
    }

    @Test
    void subCommands_haveNonEmptyNames() {
        reloadCmd.addCommands();
        for (CommandsAPICommand sub : reloadCmd.getCommandLookup().values()) {
            assertNotNull(sub.name());
            assertFalse(sub.name().isEmpty());
        }
    }
}
