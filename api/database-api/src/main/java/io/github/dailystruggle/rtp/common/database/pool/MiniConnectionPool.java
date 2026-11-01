package io.github.dailystruggle.rtp.common.database.pool;

import io.github.dailystruggle.database.api.CloseableDataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * In-house, zero-dependency lightweight JDBC connection pool ({@code leafrtp-ADR-079}). Replaces
 * external pool dependencies (HikariCP) with a bounded, thread-safe {@link CloseableDataSource}
 * suitable for asynchronous batch flush tasks, proxy network heartbeats, and cross-shard state
 * stores.
 *
 * @governedBy ADR-113 (Java 17 Bytecode Baseline)
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @governedBy leafrtp-ADR-079 (In-House SQL Connection Pool and Retirement of HikariCP)
 * @trace S-004
 * @trace S-005
 */
public class MiniConnectionPool implements CloseableDataSource {
  private static final int DEFAULT_MAX_POOL_SIZE = 8;
  private static final int VALIDATION_TIMEOUT_SECONDS = 2;
  private static final long CHECKOUT_TIMEOUT_MS = 5000L;

  /**
   * Optional hook invoked once on each newly created physical {@link Connection} (for example, to
   * apply SQLite {@code WAL} and {@code busy_timeout} pragmas).
   */
  @FunctionalInterface
  public interface ConnectionInitializer {
    void initialize(Connection connection) throws SQLException;
  }

  private final String jdbcUrl;
  private final Properties properties;
  private final int maxPoolSize;
  private final ConnectionInitializer initializer;
  private final BlockingQueue<Connection> pool;
  private final AtomicInteger allocatedCount = new AtomicInteger(0);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public MiniConnectionPool(String jdbcUrl, String username, String password) {
    this(jdbcUrl, username, password, DEFAULT_MAX_POOL_SIZE);
  }

  public MiniConnectionPool(String jdbcUrl, String username, String password, int maxPoolSize) {
    this(jdbcUrl, buildProperties(username, password), maxPoolSize, null);
  }

  public MiniConnectionPool(String jdbcUrl, Properties properties, int maxPoolSize) {
    this(jdbcUrl, properties, maxPoolSize, null);
  }

  public MiniConnectionPool(
      String jdbcUrl,
      Properties properties,
      int maxPoolSize,
      ConnectionInitializer initializer) {
    this.jdbcUrl = jdbcUrl;
    this.maxPoolSize = Math.max(1, maxPoolSize);
    this.pool = new ArrayBlockingQueue<>(this.maxPoolSize);
    this.properties = new Properties();
    if (properties != null) {
      this.properties.putAll(properties);
    }
    this.initializer = initializer;
  }

  private static Properties buildProperties(String username, String password) {
    Properties props = new Properties();
    if (username != null && !username.isEmpty()) {
      props.setProperty("user", username);
    }
    if (password != null && !password.isEmpty()) {
      props.setProperty("password", password);
    }
    return props;
  }

  @Override
  public Connection getConnection() throws SQLException {
    if (closed.get()) {
      throw new SQLException("MiniConnectionPool is closed");
    }

    long deadline = System.currentTimeMillis() + CHECKOUT_TIMEOUT_MS;

    while (System.currentTimeMillis() < deadline) {
      // 1. Try to reuse an existing idle connection
      Connection raw = pool.poll();
      if (raw != null) {
        if (isConnectionHealthy(raw)) {
          return wrap(raw);
        }
        // Dead connection: discard it and decrement count
        silentlyClose(raw);
        allocatedCount.decrementAndGet();
      }

      // 2. Can we allocate a new physical connection?
      int current = allocatedCount.get();
      if (current < maxPoolSize && allocatedCount.compareAndSet(current, current + 1)) {
        try {
          Connection fresh = createPhysicalConnection();
          return wrap(fresh);
        } catch (SQLException | RuntimeException e) {
          allocatedCount.decrementAndGet();
          throw e;
        }
      }

      // 3. Pool is exhausted: wait briefly for an idle connection to be returned
      try {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining > 0) {
          raw = pool.poll(Math.min(remaining, 500L), TimeUnit.MILLISECONDS);
          if (raw != null) {
            if (isConnectionHealthy(raw)) {
              return wrap(raw);
            }
            silentlyClose(raw);
            allocatedCount.decrementAndGet();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SQLException("Interrupted while waiting for a database connection", e);
      }
    }

    throw new SQLException(
        "Timed out waiting for an available connection in MiniConnectionPool (max="
            + maxPoolSize
            + ")");
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }

  protected Connection createPhysicalConnection() throws SQLException {
    Connection conn =
        properties.isEmpty()
            ? DriverManager.getConnection(jdbcUrl)
            : DriverManager.getConnection(jdbcUrl, properties);
    if (initializer != null) {
      try {
        initializer.initialize(conn);
      } catch (SQLException | RuntimeException e) {
        silentlyClose(conn);
        throw e;
      }
    }
    return conn;
  }

  private boolean isConnectionHealthy(Connection connection) {
    if (connection == null) {
      return false;
    }
    try {
      if (connection.isClosed()) {
        return false;
      }
      try {
        return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
      } catch (SQLFeatureNotSupportedException | AbstractMethodError unsupported) {
        return true;
      }
    } catch (Throwable ignored) {
      return false;
    }
  }

  private Connection wrap(Connection physical) {
    PooledConnectionHandler handler = new PooledConnectionHandler(physical);
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  private void recycle(Connection physical) {
    if (closed.get()) {
      silentlyClose(physical);
      allocatedCount.decrementAndGet();
      return;
    }

    if (isConnectionHealthy(physical)) {
      try {
        if (!physical.getAutoCommit()) {
          physical.setAutoCommit(true);
        }
      } catch (Throwable ignored) {
        silentlyClose(physical);
        allocatedCount.decrementAndGet();
        return;
      }
      if (!pool.offer(physical)) {
        // Pool queue full, close excess connection
        silentlyClose(physical);
        allocatedCount.decrementAndGet();
      }
    } else {
      silentlyClose(physical);
      allocatedCount.decrementAndGet();
    }
  }

  private void silentlyClose(Connection connection) {
    if (connection != null) {
      try {
        connection.close();
      } catch (Throwable ignored) {
        // Suppress on shutdown / eviction
      }
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      Connection conn;
      while ((conn = pool.poll()) != null) {
        silentlyClose(conn);
        allocatedCount.decrementAndGet();
      }
    }
  }

  @Override
  public boolean isClosed() {
    return closed.get();
  }

  @Override
  public int getActiveCount() {
    return allocatedCount.get() - pool.size();
  }

  @Override
  public int getIdleCount() {
    return pool.size();
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    // MiniConnectionPool does not direct its internal connection logging to a custom PrintWriter
  }

  @Override
  public void setLoginTimeout(int seconds) {
    // Login timeout configuration is handled directly via JDBC connection properties / DriverManager
  }

  @Override
  public int getLoginTimeout() {
    return 0;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("getParentLogger not supported");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  private final class PooledConnectionHandler implements InvocationHandler {
    private final Connection physical;
    private final AtomicBoolean handleClosed = new AtomicBoolean(false);

    PooledConnectionHandler(Connection physical) {
      this.physical = physical;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String methodName = method.getName();

      if ("close".equals(methodName)) {
        if (handleClosed.compareAndSet(false, true)) {
          recycle(physical);
        }
        return null;
      }

      if ("isClosed".equals(methodName)) {
        return handleClosed.get() || physical.isClosed();
      }

      if (handleClosed.get()) {
        throw new SQLException("Connection is closed");
      }

      if ("equals".equals(methodName)) {
        return proxy == args[0];
      }

      if ("hashCode".equals(methodName)) {
        return System.identityHashCode(proxy);
      }

      if ("toString".equals(methodName)) {
        return "PooledConnection[url=" + jdbcUrl + ", physical=" + physical + "]";
      }

      try {
        return method.invoke(physical, args);
      } catch (InvocationTargetException ite) {
        throw ite.getTargetException();
      }
    }
  }
}
