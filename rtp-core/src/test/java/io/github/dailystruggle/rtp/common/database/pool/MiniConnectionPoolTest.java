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
        stmt.execute("CREATE TABLE IF NOT EXISTS test_tab (id INT PRIMARY KEY)");
      }
    }

    assertEquals(0, pool.getActiveCount());
    assertEquals(1, pool.getIdleCount());

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

  @Test
  void testDataSourceInterfaceMethods() throws SQLException {
    assertNull(pool.getLogWriter());
    pool.setLogWriter(null);
    assertEquals(0, pool.getLoginTimeout());
    pool.setLoginTimeout(10);
    assertThrows(java.sql.SQLFeatureNotSupportedException.class, pool::getParentLogger);

    assertTrue(pool.isWrapperFor(MiniConnectionPool.class));
    assertTrue(pool.isWrapperFor(javax.sql.DataSource.class));
    assertSame(pool, pool.unwrap(MiniConnectionPool.class));
    assertThrows(SQLException.class, () -> pool.unwrap(String.class));
  }

  @Test
  void testProxyEqualsHashCodeToStringAndClosedCalls() throws SQLException {
    try (Connection conn = pool.getConnection()) {
      assertEquals(conn, conn);
      assertNotEquals(conn, "string");
      assertNotEquals(0, conn.hashCode());
      assertNotNull(conn.toString());
      assertFalse(conn.isClosed());

      conn.close();
      assertTrue(conn.isClosed());
      assertThrows(SQLException.class, () -> conn.createStatement());

      conn.close();
    }
  }

  @Test
  void testConstructorsAndProperties() throws SQLException {
    java.util.Properties props = new java.util.Properties();
    props.setProperty("user", "sa");
    props.setProperty("password", "");

    try (MiniConnectionPool p1 = new MiniConnectionPool("jdbc:h2:mem:p1;DB_CLOSE_DELAY=-1", "sa", "");
         MiniConnectionPool p2 = new MiniConnectionPool("jdbc:h2:mem:p2;DB_CLOSE_DELAY=-1", props, 2)) {
      try (Connection c1 = p1.getConnection();
           Connection c2 = p2.getConnection()) {
        assertNotNull(c1);
        assertNotNull(c2);
      }
    }
  }
}
