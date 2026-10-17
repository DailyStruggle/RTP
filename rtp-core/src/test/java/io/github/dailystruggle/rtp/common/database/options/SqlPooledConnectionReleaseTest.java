package io.github.dailystruggle.rtp.common.database.options;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.pool.MiniConnectionPool;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Pooled backends (MySQL/PostgreSQL) recycle a connection only when its proxy is closed.
 * {@code flush()} and {@code processQueries()} must return every borrowed handle on all exit
 * paths, or the pool drains and persistence stops.
 */
@DisplayName("REQ-RTP-DB-OPT-002: pooled SQL drains return every borrowed connection")
class SqlPooledConnectionReleaseTest {

    private static final int POOL_SIZE = 2;
    private static final int CYCLES = 20;

    @TempDir
    Path tempDir;

    private MiniConnectionPool pool;
    private PooledAccessor accessor;

    private static final class PooledAccessor extends AbstractSQLDatabaseAccessor {
        private final MiniConnectionPool pool;

        PooledAccessor(MiniConnectionPool pool) {
            this.pool = pool;
        }

        @Override
        public String name() {
            return "pooled-h2";
        }

        @Override
        public Connection getConnection() throws SQLException {
            return pool.getConnection();
        }

        @Override
        public void startup() {}

        @Override
        protected String getInsertStatement() {
            return "INSERT INTO rtp_teleport_data "
                    + "(senderName, senderId, time, delay, selectedX, selectedY, selectedZ, "
                    + "selectedWorldName, selectedWorldId, originalX, originalY, originalZ, "
                    + "originalWorldName, originalWorldId, region, cost, attempts) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public Optional<Map<String, Object>> read(Connection connection, String tableName,
                                                  Map.Entry<String, Object> lookup) {
            return Optional.empty();
        }

        @Override
        public void write(Connection connection, String tableName, Map<TableObj, TableObj> keyValuePairs) {}
    }

    @BeforeEach
    void setUp() throws SQLException {
        RTPTestSetup.install(tempDir.toFile());
        String url = "jdbc:h2:mem:pooledrelease_" + UUID.randomUUID().toString().replace("-", "")
                + ";DB_CLOSE_DELAY=-1";
        pool = new MiniConnectionPool(url, null, null, POOL_SIZE);
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE rtp_teleport_data (senderName TEXT, senderId VARCHAR(36), "
                    + "time BIGINT, delay BIGINT, selectedX INT, selectedY INT, selectedZ INT, "
                    + "selectedWorldName TEXT, selectedWorldId VARCHAR(36), originalX INT, "
                    + "originalY INT, originalZ INT, originalWorldName TEXT, originalWorldId VARCHAR(36), "
                    + "region TEXT, cost DOUBLE, attempts INT)");
            st.execute("CREATE TABLE rtp_cached_locations (UUID VARCHAR(255) PRIMARY KEY, world TEXT, "
                    + "x INT, y INT, z INT, attempts INT, region TEXT, player_uuid VARCHAR(36), "
                    + "timestamp BIGINT, seed BIGINT)");
        }
        accessor = new PooledAccessor(pool);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
        RTP.serverAccessor = null;
    }

    @Test
    @DisplayName("repeated non-empty flushes reuse the pool instead of exhausting it")
    void flushReturnsConnection() throws Exception {
        for (int i = 0; i < CYCLES; i++) {
            accessor.cacheValue(teleport(i));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), accessor::flush,
                    "flush #" + i + " blocked waiting for a pooled connection");
        }
        assertEquals(CYCLES, countTeleportRows());
        assertPoolFullyAvailable();
    }

    @Test
    @DisplayName("processQueries releases its connection on the stop-flag early return")
    void processQueriesReturnsConnectionWhenStopped() throws Exception {
        accessor.stop.set(true);
        for (int i = 0; i < CYCLES; i++) {
            accessor.removeCachedLocation("loc-" + i);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> accessor.processQueries(Long.MAX_VALUE),
                    "processQueries #" + i + " blocked waiting for a pooled connection");
        }
        assertPoolFullyAvailable();
    }

    @Test
    @DisplayName("processQueries releases its connection after a normal drain")
    void processQueriesReturnsConnectionAfterDrain() throws Exception {
        for (int i = 0; i < CYCLES; i++) {
            accessor.removeCachedLocation("loc-" + i);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> accessor.processQueries(Long.MAX_VALUE),
                    "processQueries #" + i + " blocked waiting for a pooled connection");
        }
        assertPoolFullyAvailable();
    }

    private TeleportData teleport(int i) {
        TeleportData data = new TeleportData();
        data.time = 1000L + i;
        data.selectedCoords = new RTPCoords("world", i, 64, i);
        data.originalCoords = new RTPCoords("world", 0, 64, 0);
        io.github.dailystruggle.rtp.common.selection.region.Region region =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
        region.name = "default";
        data.targetRegion = region;
        return data;
    }

    private int countTeleportRows() throws SQLException {
        try (Connection c = pool.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM rtp_teleport_data")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Every slot must be checkout-able at once; a leaked handle makes this wait out the pool timeout. */
    private void assertPoolFullyAvailable() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            Connection[] held = new Connection[POOL_SIZE];
            try {
                for (int i = 0; i < POOL_SIZE; i++) {
                    held[i] = pool.getConnection();
                    assertNotNull(held[i]);
                }
            } finally {
                for (Connection c : held) {
                    if (c != null) c.close();
                }
            }
        }, "pool slots were leaked");
    }
}
