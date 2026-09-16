package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-behaviour coverage for {@link PlayerTargetedClearCmd} exercised through
 * the concrete {@link ClearInvulnCmd} verb: unknown-player skipping, mixed
 * known/unknown targeting, whitespace-only entries, and {@code nextCommand}
 * delegation.
 */
class PlayerTargetedClearCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private final ClearInvulnCmd cmd = new ClearInvulnCmd(null);

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.getInstance().invulnerablePlayers.clear();
    }

    private static Map<String, List<String>> players(String csv) {
        Map<String, List<String>> args = new HashMap<>();
        args.put("player", Collections.singletonList(csv));
        return args;
    }

    @Test
    @DisplayName("unknown player name is skipped and leaves existing state intact")
    void unknownPlayer_skippedNoClear() {
        UUID known = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(known, "alice", null));
        RTP rtp = RTP.getInstance();
        rtp.invulnerablePlayers.put(known, 1L);

        boolean handled = cmd.onCommand(RTPAPI.serverId, players("ghost"), null);

        assertTrue(handled, "command still reports handled for an unknown target");
        assertTrue(rtp.invulnerablePlayers.containsKey(known),
                "unrelated player's marker must survive an unknown-target request");
    }

    @Test
    @DisplayName("mixed known/unknown list clears only the resolvable players")
    void mixedList_clearsKnownOnly() {
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(aId, "alice", null));
        accessor.addPlayer(new MockRTPPlayer(bId, "bob", null));
        RTP rtp = RTP.getInstance();
        rtp.invulnerablePlayers.put(aId, 1L);
        rtp.invulnerablePlayers.put(bId, 2L);

        cmd.onCommand(RTPAPI.serverId, players("alice,ghost"), null);

        assertFalse(rtp.invulnerablePlayers.containsKey(aId), "known target alice cleared");
        assertTrue(rtp.invulnerablePlayers.containsKey(bId),
                "bob not listed and must keep his marker");
    }

    @Test
    @DisplayName("blank/whitespace-only entries are ignored, falling back to console-global clear")
    void blankEntries_treatedAsGlobalFromConsole() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        RTP rtp = RTP.getInstance();
        rtp.invulnerablePlayers.put(a, 1L);
        rtp.invulnerablePlayers.put(b, 2L);

        cmd.onCommand(RTPAPI.serverId, players("  , "), null);

        assertTrue(rtp.invulnerablePlayers.isEmpty(),
                "blank player list from console clears all markers");
    }

    @Test
    @DisplayName("nextCommand delegation short-circuits the clear")
    void delegatesToNextCommand() {
        UUID caller = UUID.randomUUID();
        RTP rtp = RTP.getInstance();
        rtp.invulnerablePlayers.put(caller, 1L);

        AtomicBoolean delegated = new AtomicBoolean(false);
        CommandsAPICommand next = new ClearInvulnCmd(null) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                                     CommandsAPICommand nextCommand) {
                delegated.set(true);
                return true;
            }
        };

        cmd.onCommand(caller, new HashMap<>(), next);

        assertTrue(delegated.get(), "nextCommand must be invoked");
        assertTrue(rtp.invulnerablePlayers.containsKey(caller),
                "delegation short-circuits before clearing the caller's marker");
    }
}
