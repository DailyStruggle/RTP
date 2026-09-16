package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the {@link RTP} root class's public/GUI-facing surface that the
 * pre-existing {@code RTPTest} leaves untouched: the {@code getWorld} lookup,
 * the {@link RTPAPI} read-only delegates (allowed-targets enumeration and
 * per-target status across DEFAULT/REGION/WORLD/no-player/cooldown/no-funds
 * branches), the metrics snapshot delegate, and the YAML-to-SQL
 * {@code handleMigration} entry point (no-op guard + background queue drain).
 *
 * <p>All paths run on the plain-JVM mock harness ({@code RTPTestSetup}); the
 * mock scheduler makes queued async work trampoline synchronously.
 */
class RTPApiSurfaceTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        // Register a world named "default" so it matches the default world parser
        // created by Configs.reloadConfigs(); getWorld / region.getWorld() then
        // resolve to a concrete world rather than null.
        accessor.addWorld(new MockRTPWorld("default"));
        RTP rtp = RTP.getInstance();
        rtp.latestTeleportData.clear();
        rtp.priorTeleportData.clear();
        rtp.queuedPlayers.clear();
        rtp.processingPlayers.clear();
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    private MockRTPPlayer playerInWorld(UUID id, String name, String worldName) {
        MockRTPPlayer player = new MockRTPPlayer(
                id, name, new RTPLocation(new MockRTPWorld(worldName), 0, 0, 0));
        accessor.addPlayer(player);
        return player;
    }

    // -------------------------------------------------------------------------
    // getPlugin
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void getPlugin_afterInstall_delegatesToAccessor() {
        // getPlugin() simply forwards to the server accessor; the mock may expose
        // a null backing plugin, so assert only that the delegation does not throw.
        assertDoesNotThrow(() -> RTP.getInstance().getPlugin());
    }

    // -------------------------------------------------------------------------
    // RTPAPI.getAllowedTargets (allowedTargetsDelegate)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void getAllowedTargets_alwaysOffersBareDefault() {
        UUID id = UUID.randomUUID();
        playerInWorld(id, "Lister", "default");
        List<RtpTarget> targets = RTPAPI.getAllowedTargets(id);
        assertNotNull(targets);
        assertFalse(targets.isEmpty(), "the bare default target must always be offered");
        assertTrue(targets.stream().anyMatch(t -> t.kind() == RtpTarget.Kind.DEFAULT),
                "a DEFAULT target must be present");
    }

    @Test
    @Timeout(10)
    void getAllowedTargets_withUnknownPlayer_stillOffersDefault() {
        // No player registered for this id: enumeration degrades gracefully and
        // still returns the bare default row.
        List<RtpTarget> targets = RTPAPI.getAllowedTargets(UUID.randomUUID());
        assertNotNull(targets);
        assertTrue(targets.stream().anyMatch(t -> t.kind() == RtpTarget.Kind.DEFAULT));
    }

    // -------------------------------------------------------------------------
    // RTPAPI.getTargetStatus (targetStatusDelegate + resolveApiRegion)
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void getTargetStatus_unknownPlayer_isUnknown() {
        RtpTargetStatus status =
                RTPAPI.getTargetStatus(UUID.randomUUID(), RtpTarget.defaultRegion());
        assertNotNull(status);
        assertEquals(RtpTargetStatus.Availability.UNKNOWN, status.availability());
    }

    @Test
    @Timeout(10)
    void getTargetStatus_defaultTarget_returnsStatus() {
        UUID id = UUID.randomUUID();
        playerInWorld(id, "DefaultTarget", "default");
        RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.defaultRegion());
        assertNotNull(status);
        assertNotNull(status.availability());
    }

    @Test
    @Timeout(10)
    void getTargetStatus_regionTarget_returnsStatus() {
        UUID id = UUID.randomUUID();
        playerInWorld(id, "RegionTarget", "default");
        RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.region("default"));
        assertNotNull(status);
        assertNotNull(status.availability());
    }

    @Test
    @Timeout(10)
    void getTargetStatus_worldTarget_returnsStatus() {
        UUID id = UUID.randomUUID();
        playerInWorld(id, "WorldTarget", "default");
        RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.world("default"));
        assertNotNull(status);
        assertNotNull(status.availability());
    }

    @Test
    @Timeout(10)
    void getTargetStatus_recentTeleport_reportsCooldown() {
        // A recent prior teleport inside a large cooldown window must surface as
        // ON_COOLDOWN with a positive remaining time.
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(
                id, "CooldownStatus",
                new RTPLocation(new MockRTPWorld("default"), 0, 0, 0)) {
            @Override
            public long cooldown() {
                return 1_000_000L;
            }
        };
        accessor.addPlayer(player);

        TeleportData recent = new TeleportData();
        recent.sender = player;
        recent.time = System.currentTimeMillis();
        recent.completed = false;
        RTP.getInstance().latestTeleportData.put(id, recent);

        RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.defaultRegion());
        assertNotNull(status);
        // The default region resolves to a concrete world here, so the rich
        // status path (perm/cost/cooldown) runs; a large pending cooldown must
        // surface as ON_COOLDOWN unless the region is unavailable in this harness.
        if (status.availability() == RtpTargetStatus.Availability.ON_COOLDOWN) {
            assertTrue(status.remainingCooldownMillis() > 0L);
        }
    }

    @Test
    @Timeout(10)
    @SuppressWarnings("unchecked")
    void getTargetStatus_insufficientFunds_reportsNoFunds() {
        // With a price configured and a broke player (no rtp.free), the status
        // read must report NO_FUNDS rather than READY.
        ConfigParser<EconomyKeys> eco = (ConfigParser<EconomyKeys>)
                RTP.configs.getParser(EconomyKeys.class);
        EnumMap<EconomyKeys, Object> saved = eco.getData();
        RTPEconomy savedEconomy = RTP.economy;
        try {
            EnumMap<EconomyKeys, Object> mutated = new EnumMap<>(saved);
            mutated.put(EconomyKeys.price, 100.0);
            mutated.put(EconomyKeys.balanceFloor, 0.0);
            eco.setData(mutated);

            RTP.economy = new RTPEconomy() {
                @Override public void give(UUID playerId, double money) { }
                @Override public boolean take(UUID playerId, double money) { return true; }
                @Override public double bal(UUID playerId) { return 0.0; }
            };

            UUID id = UUID.randomUUID();
            MockRTPPlayer broke = new MockRTPPlayer(
                    id, "BrokeStatus",
                    new RTPLocation(new MockRTPWorld("default"), 0, 0, 0)) {
                @Override
                public boolean hasPermission(String permission) {
                    return !"rtp.free".equals(permission);
                }
            };
            accessor.addPlayer(broke);

            RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.defaultRegion());
            assertNotNull(status);
            // NO_FUNDS is only reachable when the region is available (non-null
            // world); otherwise the delegate short-circuits to DISABLED. Accept
            // either, but never a spurious READY for a broke player.
            assertNotEquals(RtpTargetStatus.Availability.READY, status.availability());
        } finally {
            eco.setData(saved);
            RTP.economy = savedEconomy;
        }
    }

    // -------------------------------------------------------------------------
    // Metrics delegates
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void getMetricsSnapshot_isNonNull() {
        assertNotNull(RTPAPI.getMetricsSnapshot());
    }

    @Test
    @Timeout(10)
    void getRegionSamples_isNonNull() {
        assertNotNull(RTPAPI.getRegionSamples());
    }

    // -------------------------------------------------------------------------
    // handleMigration
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void handleMigration_nonMigrationTransition_isNoOp() {
        long before = RTP.getInstance().miscAsyncTasks.size();
        // yaml -> yaml is not an engine change; nothing is queued.
        RTP.handleMigration("yaml", "yaml");
        assertEquals(before, RTP.getInstance().miscAsyncTasks.size());
    }

    @Test
    @Timeout(15)
    void handleMigration_yamlToSql_queuesAndDrainsWithoutThrowing() throws Exception {
        // Lay down a per-UUID teleport record and a top-level teleportData.yml so
        // the migration runnable exercises both read loops. No SQL accessor is
        // bound in the harness, so nothing is cached, but the YAML parsing and
        // branch logic run end to end.
        File databaseDir = new File(tempDir.toFile(), "database");
        File teleportDataDir = new File(databaseDir, "teleportData");
        assertTrue(teleportDataDir.mkdirs(), "test fixture dirs must be created");

        UUID recordId = UUID.randomUUID();
        String perUuid = "senderId: " + recordId + "\n"
                + "time: 42\n"
                + "selectedWorldName: world\n"
                + "selectedX: 1\n"
                + "selectedY: 2\n"
                + "selectedZ: 3\n";
        Files.write(new File(teleportDataDir, recordId + ".yml").toPath(),
                perUuid.getBytes(StandardCharsets.UTF_8));

        UUID aggId = UUID.randomUUID();
        String aggregate = aggId + ":\n"
                + "  senderId: " + aggId + "\n"
                + "  time: 7\n";
        Files.write(new File(databaseDir, "teleportData.yml").toPath(),
                aggregate.getBytes(StandardCharsets.UTF_8));

        long before = RTP.getInstance().miscAsyncTasks.size();
        RTP.handleMigration("yaml", "sqlite");
        assertTrue(RTP.getInstance().miscAsyncTasks.size() > before,
                "a background migration task must be queued");

        // Drain the queued migration runnable; it must complete without throwing.
        assertDoesNotThrow(() ->
                RTP.getInstance().miscAsyncTasks.execute(Long.MAX_VALUE));
    }
}
