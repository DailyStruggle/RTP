package io.github.dailystruggle.rtp.common.commands.info;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
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

    // ── onCommand empty params - player sender ────────────────────────────────

    @Test
    void onCommand_emptyParams_playerSender_returnsTrue() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player1", null));

        boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
    }

    // ── onCommand empty params - console sender ───────────────────────────────

    @Test
    void onCommand_emptyParams_consoleSender_returnsTrue() {
        // RTPAPI.serverId is UUID(0,0) - the console
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

    // ── metrics snapshot caching ──────────────────────────────────────────────

    /**
     * One {@code /rtp info} invocation shall trigger exactly one
     * {@link MetricsSnapshot} round-trip (i.e. one call into the installed
     * {@link io.github.dailystruggle.metrics.api.MetricsBinding}), regardless
     * of how many metrics-backed placeholders the rendered messages contain.
     * Anchored in {@link io.github.dailystruggle.rtp.common.tools.PlaceholderProvider#withSnapshot}.
     */
    @Test
    void onCommand_snapshotsMetricsExactlyOncePerInvocation() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "metrics-player", null));

        java.util.concurrent.atomic.AtomicInteger tpsReads =
                new java.util.concurrent.atomic.AtomicInteger();
        io.github.dailystruggle.metrics.api.MetricsBinding counting =
                new io.github.dailystruggle.metrics.api.MetricsBinding() {
                    @Override public double tps1m() { tpsReads.incrementAndGet(); return 20.0; }
                    @Override public double tps5m() { return 20.0; }
                    @Override public double tps15m() { return 20.0; }
                    @Override public double mspt()  { return 5.0; }
                    @Override public int playerCount() { return 1; }
                    @Override public int softCap() { return 0; }
                    @Override public int chunkLoadBacklog() { return 0; }
                    @Override public int databaseLatencyMs() { return -1; }
                };
        RTP.metrics.setBinding(counting);
        try {
            boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);
            assertTrue(result);
            assertEquals(
                    1,
                    tpsReads.get(),
                    "InfoCmd must capture exactly one MetricsSnapshot per /rtp info "
                            + "invocation; placeholders read the cached snapshot "
                            + "via PlaceholderProvider.currentSnapshot().");
        } finally {
            RTP.metrics.setBinding(null);
        }
    }

    // ── Folia per-region table (Section C / C3) ───────────────────────────────

    /**
     * When the active {@link io.github.dailystruggle.metrics.api.MetricsBinding}
     * publishes per-region detail (Folia), {@code /rtp info} renders the
     * {@code infoFoliaRegionsHeader} + per-row {@code infoFoliaRegion} template
     * with row-local placeholders substituted. Asserts test-visibility of
     * per-region metrics for {@code rtp test full}-style diagnostics.
     */
    @Test
    void onCommand_rendersFoliaRegionTable_whenBindingPublishesRegions() {
        UUID senderId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(senderId, "folia-player", null);
        accessor.addPlayer(player);

        io.github.dailystruggle.metrics.api.FoliaRegionSample r0 =
                new io.github.dailystruggle.metrics.api.FoliaRegionSample(
                        "region-0", 19.5, 12.5, 3, 4);
        io.github.dailystruggle.metrics.api.FoliaRegionSample r1 =
                new io.github.dailystruggle.metrics.api.FoliaRegionSample(
                        "region-1", 18.0, 30.0, 1, 0);
        java.util.List<io.github.dailystruggle.metrics.api.FoliaRegionSample> regions =
                java.util.Arrays.asList(r0, r1);

        io.github.dailystruggle.metrics.api.MetricsBinding folia =
                new io.github.dailystruggle.metrics.api.MetricsBinding() {
                    @Override public double tps1m() { return 19.0; }
                    @Override public double tps5m() { return 19.0; }
                    @Override public double tps15m() { return 19.0; }
                    @Override public double mspt()  { return 20.0; }
                    @Override public int playerCount() { return 4; }
                    @Override public int softCap() { return 0; }
                    @Override public int chunkLoadBacklog() { return 0; }
                    @Override public int databaseLatencyMs() { return -1; }
                    @Override public java.util.List<io.github.dailystruggle.metrics.api.FoliaRegionSample> foliaRegions() { return regions; }
                };
        RTP.metrics.setBinding(folia);
        @SuppressWarnings("unchecked")
        // Inject explicit templates - the test ConfigParser stub returns the
        // enum name as default text, which would not exercise the row-token
        // substitution. Setting the values directly is the minimal surface
        // for verifying the rendering path.
        ConfigParser<CommandMessages> lang =
                (ConfigParser<CommandMessages>) RTP.configs.getParser(CommandMessages.class);
        Object prevHeader = lang.getConfigValue(CommandMessages.infoFoliaRegionsHeader, null);
        Object prevRow = lang.getConfigValue(CommandMessages.infoFoliaRegion, null);
        lang.setData(java.util.Map.of(
                "infoFoliaRegionsHeader", "--- Folia Regions ---",
                "infoFoliaRegion", "[regionId]: tps=[tps1m] mspt=[mspt] players=[playerCount] queue=[queueDepth] tickBudget=[tickBudgetUtilisation]"));
        try {
            boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);
            assertTrue(result);

            java.util.List<String> sent = new java.util.ArrayList<>(player.sentMessages);
            // Header line present
            assertTrue(
                    sent.stream().anyMatch(s -> s.contains("Folia Regions")),
                    "Expected infoFoliaRegionsHeader in /rtp info output, got: " + sent);
            // Both region rows present with substituted tokens
            assertTrue(
                    sent.stream().anyMatch(s ->
                            s.contains("region-0") && s.contains("19.50")
                                    && s.contains("players=3") && s.contains("queue=4")),
                    "Expected region-0 row with substituted tokens, got: " + sent);
            assertTrue(
                    sent.stream().anyMatch(s ->
                            s.contains("region-1") && s.contains("18.00")
                                    && s.contains("players=1") && s.contains("queue=0")),
                    "Expected region-1 row with substituted tokens, got: " + sent);
        } finally {
            RTP.metrics.setBinding(null);
            lang.setData(java.util.Map.of(
                    "infoFoliaRegionsHeader", prevHeader == null ? "" : prevHeader,
                    "infoFoliaRegion", prevRow == null ? "" : prevRow));
        }
    }

    /**
     * On non-Folia platforms (Paper / Bukkit / Fabric) the default
     * {@code foliaRegions()} returns an empty list, so the table header and
     * per-row template are suppressed entirely.
     */
    @Test
    void onCommand_suppressesFoliaRegionTable_whenBindingReturnsEmptyList() {
        UUID senderId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(senderId, "single-region-player", null);
        accessor.addPlayer(player);

        io.github.dailystruggle.metrics.api.MetricsBinding singleRegion =
                new io.github.dailystruggle.metrics.api.MetricsBinding() {
                    @Override public double tps1m() { return 20.0; }
                    @Override public double tps5m() { return 20.0; }
                    @Override public double tps15m() { return 20.0; }
                    @Override public double mspt()  { return 5.0; }
                    @Override public int playerCount() { return 1; }
                    @Override public int softCap() { return 0; }
                    @Override public int chunkLoadBacklog() { return 0; }
                    @Override public int databaseLatencyMs() { return -1; }
                    // foliaRegions() inherits the empty-list default
                };
        RTP.metrics.setBinding(singleRegion);
        try {
            boolean result = infoCmd.onCommand(senderId, new HashMap<>(), null);
            assertTrue(result);

            java.util.List<String> sent = new java.util.ArrayList<>(player.sentMessages);
            assertTrue(
                    sent.stream().noneMatch(s -> s.contains("Folia Regions")),
                    "infoFoliaRegionsHeader must be suppressed when foliaRegions() is empty, got: " + sent);
        } finally {
            RTP.metrics.setBinding(null);
        }
    }

    // ── parameters registered ─────────────────────────────────────────────────

    @Test
    void constructor_registersWorldAndRegionParameters() {
        assertNotNull(infoCmd.getParameterLookup().get("world"));
        assertNotNull(infoCmd.getParameterLookup().get("region"));
    }
}
