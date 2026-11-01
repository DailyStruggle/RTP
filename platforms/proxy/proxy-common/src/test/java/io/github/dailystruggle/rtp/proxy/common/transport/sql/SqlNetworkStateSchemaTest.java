package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SqlNetworkStateSchemaTest {

    @Test
    void bootstrap_idempotent() throws SQLException {
        String url = "jdbc:h2:mem:schema_test_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection conn = DriverManager.getConnection(url)) {
            // First run creates tables and columns
            assertDoesNotThrow(() -> SqlNetworkStateSchema.bootstrap(conn));
            // Second run idempotently passes without error
            assertDoesNotThrow(() -> SqlNetworkStateSchema.bootstrap(conn));
        }
    }
}
