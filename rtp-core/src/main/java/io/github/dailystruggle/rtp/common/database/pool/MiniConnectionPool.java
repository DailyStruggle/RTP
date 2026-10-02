package io.github.dailystruggle.rtp.common.database.pool;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
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
import javax.sql.DataSource;

/**
 * In-house, zero-dependency lightweight connection pool (ADR-101).
 * Replaces external pool dependencies (HikariCP) with a bounded, thread-safe
 * pool suitable for RTP's asynchronous batch flush tasks and proxy network heartbeats.
 */
public class MiniConnectionPool implements DataSource, AutoCloseable {
  private static final int DEFAULT_MAX_POOL_SIZE = 8;
  private static final int VALIDATION_TIMEOUT_SECONDS = 2;
  private static final long CHECKOUT_TIMEOUT_MS = 5000L;

  private final String jdbcUrl;
  private final Properties properties;
  private final int maxPoolSize;
  private final BlockingQueue<Connection> pool;
  private final AtomicInteger allocatedCount = new AtomicInteger(0);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public MiniConnectionPool(String jdbcUrl, String username, String password) {
    this(jdbcUrl, username, password, DEFAULT_MAX_POOL_SIZE);
  }

  public MiniConnectionPool(String jdbcUrl, String username, String password, int maxPoolSize) {
    this.jdbcUrl = jdbcUrl;
    this.maxPoolSize = Math.max(1, maxPoolSize);
    this.pool = new ArrayBlockingQueue<>(this.maxPoolSize);
    this.properties = new Properties();
    if (username != null && !username.isEmpty()) {
      this.properties.setProperty("user", username);
    }
    if (password != null && !password.isEmpty()) {
      this.properties.setProperty("password", password);
    }
  }

  public MiniConnectionPool(String jdbcUrl, Properties properties, int maxPoolSize) {
    this.jdbcUrl = jdbcUrl;
    this.maxPoolSize = Math.max(1, maxPoolSize);
    this.pool = new ArrayBlockingQueue<>(this.maxPoolSize);
    this.properties = new Properties();
    if (properties != null) {
      this.properties.putAll(properties);
    }
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
        } catch (SQLException e) {
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

    throw new SQLException("Timed out waiting for an available connection in MiniConnectionPool (max=" + maxPoolSize + ")");
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }

  protected Connection createPhysicalConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl, properties);
  }

  private boolean isConnectionHealthy(Connection connection) {
    try {
      return connection != null && !connection.isClosed() && connection.isValid(VALIDATION_TIMEOUT_SECONDS);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private Connection wrap(Connection physical) {
    PooledConnectionHandler handler = new PooledConnectionHandler(physical);
    return (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[]{Connection.class},
        handler
    );
  }

  private void recycle(Connection physical) {
    if (closed.get()) {
      silentlyClose(physical);
      allocatedCount.decrementAndGet();
      return;
    }

    if (isConnectionHealthy(physical)) {
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

  public boolean isClosed() {
    return closed.get();
  }

  public int getActiveCount() {
    return allocatedCount.get() - pool.size();
  }

  public int getIdleCount() {
    return pool.size();
  }

  @Override
  public PrintWriter getLogWriter() {
    return null;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
  }

  @Override
  public void setLoginTimeout(int seconds) {
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

      return method.invoke(physical, args);
    }
  }
}
