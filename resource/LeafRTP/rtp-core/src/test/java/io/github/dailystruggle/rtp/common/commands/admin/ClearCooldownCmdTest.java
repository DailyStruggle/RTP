package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@code /rtp clear cooldown} ({@link ClearCooldownCmd}).
 * Exercises self, global (console), and named player targeting modes.
 */
class ClearCooldownCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private final ClearCooldownCmd cmd = new ClearCooldownCmd(null);

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        TeleportSessions.current().latestTeleportData.clear();
        TeleportSessions.current().priorTeleportData.clear();
    }

    private static Map<String, List<String>> noArgs() {
        return new HashMap<>();
    }

    @Test
    @DisplayName("no player arg + player caller clears only the caller's cooldown")
    void self_clearsCallerOnly() {
        UUID caller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        TeleportSessions.current().latestTeleportData.put(caller, new TeleportData());
        TeleportSessions.current().latestTeleportData.put(other, new TeleportData());

        cmd.onCommand(caller, noArgs(), null);

        assertFalse(TeleportSessions.current().latestTeleportData.containsKey(caller),
                "caller's cooldown must be cleared");
        assertTrue(TeleportSessions.current().latestTeleportData.containsKey(other),
                "a non-targeted player's cooldown must be left intact");
    }

    @Test
    @DisplayName("no player arg + console caller clears every player's cooldown")
    void global_clearsAll() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TeleportSessions.current().latestTeleportData.put(a, new TeleportData());
        TeleportSessions.current().priorTeleportData.put(b, new TeleportData());

        cmd.onCommand(RTPAPI.serverId, noArgs(), null);

        assertTrue(TeleportSessions.current().latestTeleportData.isEmpty(),
                "all latest teleport data must be cleared globally");
        assertTrue(TeleportSessions.current().priorTeleportData.isEmpty(),
                "all prior teleport data must be cleared globally");
    }

    @Test
    @DisplayName("player=a,b clears just the named players, comma-separated")
    void named_clearsListedPlayers() {
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(aId, "alice", null));
        accessor.addPlayer(new MockRTPPlayer(bId, "bob", null));
        accessor.addPlayer(new MockRTPPlayer(cId, "carol", null));
        TeleportSessions.current().latestTeleportData.put(aId, new TeleportData());
        TeleportSessions.current().latestTeleportData.put(bId, new TeleportData());
        TeleportSessions.current().latestTeleportData.put(cId, new TeleportData());

        Map<String, List<String>> args = new HashMap<>();
        args.put("player", Collections.singletonList("alice,bob"));
        cmd.onCommand(RTPAPI.serverId, args, null);

        assertFalse(TeleportSessions.current().latestTeleportData.containsKey(aId), "alice cleared");
        assertFalse(TeleportSessions.current().latestTeleportData.containsKey(bId), "bob cleared");
        assertTrue(TeleportSessions.current().latestTeleportData.containsKey(cId),
                "carol was not listed and must remain on cooldown");
    }
}
