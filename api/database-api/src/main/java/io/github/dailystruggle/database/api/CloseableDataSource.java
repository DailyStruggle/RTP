package io.github.dailystruggle.database.api;

import javax.sql.DataSource;

/**
 * A JDBC {@link DataSource} that also implements {@link AutoCloseable} without checked exceptions,
 * enabling unified lifecycle management across pooled ({@code MySQL}, {@code MariaDB}, {@code
 * PostgreSQL}) and single-writer ({@code SQLite}, {@code H2}) SQL stores.
 *
 * @governedBy ADR-113 (Java 17 Bytecode Baseline)
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 * @governedBy leafrtp-ADR-079 (In-House SQL Connection Pool and Retirement of HikariCP)
 */
public interface CloseableDataSource extends DataSource, AutoCloseable {

  /** Closes all idle physical connections and marks this data source closed. */
  @Override
  void close();

  /**
   * Returns {@code true} when {@link #close()} has been invoked on this data source.
   *
   * @return {@code true} if closed
   */
  boolean isClosed();

  /**
   * Returns the number of connections currently checked out from the pool.
   *
   * @return active checked-out connection count
   */
  default int getActiveCount() {
    return 0;
  }

  /**
   * Returns the number of idle connections currently available in the pool queue.
   *
   * @return idle connection count
   */
  default int getIdleCount() {
    return 0;
  }
}
