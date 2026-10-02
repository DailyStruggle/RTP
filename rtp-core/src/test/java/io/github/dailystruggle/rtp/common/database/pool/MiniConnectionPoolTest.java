package io.github.dailystruggle.rtp.common.database.pool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-101: MiniConnectionPool Unit Tests")
class MiniConnectionPoolTest {

  private MiniConnectionPool pool;

  @BeforeEach
  void setUp() {
    // In-memory H2 database url for testing pool mechanics
    pool = new MiniConnectionPool("jdbc:h2:mem:testpool;DB_CLOSE_DELAY=-1", "sa", "", 3);
  }

  @AfterEach
  void tearDown() {
    if (pool != null) {
      pool.close();
    }
  }

  @Test
  void testBorrowAndReturnConnection() throws SQLException {
    assertEquals(0, pool.getActiveCount());
    assertEquals(0, pool.getIdleCount());

    try (Connection conn = pool.getConnection()) {
      assertNotNull(conn);
      assertFalse(conn.isClosed());
      assertEquals(1, pool.getActiveCount());

      try (Statement stmt = conn.createStatement()) {
        stmt.execute("CREATE TABLE test_tab (id INT PRIMARY KEY)");
      }
    }

    // After close(), connection should be returned to pool
    assertEquals(0, pool.getActiveCount());
    assertEquals(1, pool.getIdleCount());

    // Borrow again, should reuse the idle connection
    try (Connection conn2 = pool.getConnection()) {
      assertNotNull(conn2);
      assertFalse(conn2.isClosed());
      assertEquals(1, pool.getActiveCount());
      assertEquals(0, pool.getIdleCount());
    }

    assertEquals(0, pool.getActiveCount());
    assertEquals(1, pool.getIdleCount());
  }

  @Test
  void testPoolExhaustionAndTimeout() throws SQLException {
    Connection c1 = pool.getConnection();
    Connection c2 = pool.getConnection();
    Connection c3 = pool.getConnection();

    assertEquals(3, pool.getActiveCount());

    // 4th connection should time out since maxPoolSize is 3
    long start = System.currentTimeMillis();
    SQLException ex = assertThrows(SQLException.class, () -> {
      pool.getConnection();
    });
    assertTrue(ex.getMessage().contains("Timed out waiting for an available connection"));

    c1.close();
    c2.close();
    c3.close();

    assertEquals(3, pool.getIdleCount());
  }

  @Test
  void testPoolCloseEvictsConnections() throws SQLException {
    Connection c = pool.getConnection();
    c.close();
    assertEquals(1, pool.getIdleCount());

    pool.close();
    assertTrue(pool.isClosed());

    assertThrows(SQLException.class, () -> pool.getConnection());
  }
}
