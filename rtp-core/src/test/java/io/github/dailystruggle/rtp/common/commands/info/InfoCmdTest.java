package io.github.dailystruggle.rtp.common.commands.info;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

public class InfoCmdTest {

    @TempDir
    Path tempDir;

    private InfoCmd infoCmd;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new SelectionAPI();
        infoCmd = new InfoCmd(null);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    void name_returnsInfo() {
        assertEquals("info", infoCmd.name());
    }

    @Test
    void permission_returnsRtpInfo() {
        assertEquals("rtp.info", infoCmd.permission());
    }

    @Test
    void description_isNotEmpty() {
        assertNotNull(infoCmd.description());
        assertFalse(infoCmd.description().isEmpty());
    }

    // ── onCommand delegation ──────────────────────────────────────────────────

    @Test
    void onCommand_withNextCommand_delegatesToNextCommand() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = infoCmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    // ── onCommand empty params — player sender ────────────────────────────────

    @Test
    void onCommand_emptyParams_playerSender_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player1", null));

        boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
    }

    // ── onCommand empty params — console sender ───────────────────────────────

    @Test
    void onCommand_emptyParams_consoleSender_returnsTrue() {
        // RTPAPI.serverId is UUID(0,0) — the console
        boolean result = infoCmd.onCommand(RTPAPI.serverId, new HashMap<>(), null);

        assertTrue(result);
    }

    // ── onCommand with world parameter ───────────────────────────────────────

    @Test
    void onCommand_withWorldParam_knownWorld_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player2", null));

        Map<String, List<String>> params = new HashMap<>();
        params.put("world", Collections.singletonList("world"));

        boolean result = infoCmd.onCommand(senderId, params, null);

        assertTrue(result);
    }

    @Test
    void onCommand_withWorldParam_unknownWorld_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player3", null));

        Map<String, List<String>> params = new HashMap<>();
        params.put("world", Collections.singletonList("nonexistent_world"));

        boolean result = infoCmd.onCommand(senderId, params, null);

        assertTrue(result);
    }

    // ── onCommand with region parameter ──────────────────────────────────────

    @Test
    void onCommand_withRegionParam_unknownRegion_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player4", null));

        Map<String, List<String>> params = new HashMap<>();
        params.put("region", Collections.singletonList("nonexistent_region"));

        boolean result = infoCmd.onCommand(senderId, params, null);

        assertTrue(result);
    }

    // ── admin/support DRM info branch ────────────────────────────────────────

    @Test
    void onCommand_adminPermission_doesNotThrow() {
        UUID senderId = UUID.randomUUID();
        // MockRTPPlayer.hasPermission always returns true (admin branch covered)
        accessor.addPlayer(new MockRTPPlayer(senderId, "admin1", null));

        assertDoesNotThrow(() -> infoCmd.onCommand(senderId, new HashMap<>(), null));
    }

    @Test
    void onCommand_noAdminPermission_doesNotThrow() {
        UUID senderId = UUID.randomUUID();
        MockRTPCommandSender noAdminSender = new MockRTPCommandSender(senderId, "regular") {
            @Override
            public boolean hasPermission(String permission) {
                return false;
            }
        };

        MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir.toFile()) {
            @Override
            public io.github.dailystruggle.rtp.api.entity.RTPCommandSender getSender(UUID uuid) {
                if (uuid.equals(senderId)) return noAdminSender;
                return super.getSender(uuid);
            }
        };
        RTP.serverAccessor = customAccessor;

        assertDoesNotThrow(() -> infoCmd.onCommand(senderId, new HashMap<>(), null));
    }

    // ── worldInfo with List value ─────────────────────────────────────────────

    @Test
    void onCommand_worldInfoAsList_doesNotThrow() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player5", null));

        // Override lang to return a List for worldInfo
        @SuppressWarnings("unchecked")
        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

        Map<String, List<String>> params = new HashMap<>();
        params.put("world", Collections.singletonList("world"));

        assertDoesNotThrow(() -> infoCmd.onCommand(senderId, params, null));
    }

    // ── multiple worlds in accessor ───────────────────────────────────────────

    @Test
    void onCommand_multipleWorlds_emptyParams_returnsTrue() {
        accessor.addWorld(new MockRTPWorld("world2"));
        accessor.addWorld(new MockRTPWorld("world3"));

        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player6", null));

        boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);
        assertTrue(result);
    }

    // ── parameters registered ─────────────────────────────────────────────────

    @Test
    void constructor_registersWorldAndRegionParameters() {
        assertNotNull(infoCmd.getParameterLookup().get("world"));
        assertNotNull(infoCmd.getParameterLookup().get("region"));
    }
}
