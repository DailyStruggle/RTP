package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-NET-002 gate: with no {@link NetworkStateBinding} installed
 * (the {@code network.enabled: false} shipping default), the
 * network-state member adjacent to {@link AbstractSQLDatabaseAccessor}
 * must be a pure plumbing slot. No threads spawned, no log lines, no
 * side effects on the accessor's behaviour.
 */
@DisplayName("REQ-RTP-NET-002 — network.enabled:false is a byte-identical no-op for the network-state member")
class ReqRtpNet002NetworkDisabledNoOpTest {

    /** Minimal in-memory accessor stub. Touches nothing the test does not need. */
    private static final class StubAccessor extends AbstractSQLDatabaseAccessor {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("not needed for this test");
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public void startup() { /* no-op */ }

        @Override
        protected String getInsertStatement() {
            return "";
        }

        @Override
        @NotNull
        public Connection connect() {
            throw new UnsupportedOperationException("not needed for this test");
        }

        @Override
        @NotNull
        public Optional<Map<String, Object>> read(
                Connection d, String tableName, Map.Entry<String, Object> lookup) {
            return Optional.empty();
        }

        @Override
        public void write(
                Connection d, String tableName, Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> keyValuePairs) {
            /* no-op for tests */
        }

        @Override
        public void delete(
                Connection d, String tableName, Map.Entry<String, Object> lookup) {
            /* no-op for tests */
        }

        @Override
        public void disconnect(Connection d) {
            /* no-op for tests */
        }
    }

    @Test
    @DisplayName("default binding is null")
    void default_bindingIsNull() {
        StubAccessor a = new StubAccessor();
        assertNull(a.getNetworkStateBinding(),
                "fresh accessor must default to a null network-state binding (REQ-RTP-NET-002 parity-when-disabled)");
    }

    @Test
    @DisplayName("constructing the accessor spawns no 'network'-named threads")
    void construction_spawnsNoNetworkThreads() {
        int before = countNetworkThreads();
        StubAccessor a = new StubAccessor();
        int after = countNetworkThreads();
        assertTrue(after <= before,
                "Construction must not spawn any network-mode threads when no binding is installed. "
                        + "Network thread count went from " + before + " to " + after + ".");
        // Touch a to keep it reachable through the measurement and silence "unused" warnings.
        assertNull(a.getNetworkStateBinding());
    }

    @Test
    @DisplayName("setter / getter round-trip; null clears the slot")
    void setter_isPlumbingOnly() {
        StubAccessor a = new StubAccessor();
        NetworkStateBinding binding = new NetworkStateBinding() {};
        a.setNetworkStateBinding(binding);
        assertSame(binding, a.getNetworkStateBinding(),
                "setter must install the exact instance passed in (no copy, no wrap)");
        a.setNetworkStateBinding(null);
        assertNull(a.getNetworkStateBinding(),
                "passing null must clear the slot back to the disabled-network default");
    }

    private static int countNetworkThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] arr = new Thread[Math.max(64, root.activeCount() * 2)];
        int n = root.enumerate(arr, true);
        int count = 0;
        for (int i = 0; i < n; i++) {
            Thread t = arr[i];
            if (t == null) continue;
            String name = t.getName();
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.contains("network") || lower.contains("redis") || lower.contains("lettuce")) {
                count++;
            }
        }
        return count;
    }
}
