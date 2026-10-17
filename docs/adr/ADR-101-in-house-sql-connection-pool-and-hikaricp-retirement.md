# ADR-101 — In-House SQL Connection Pool and Retirement of HikariCP

**Status:** Accepted
**Date:** 2026-10-02

## Context

In pursuit of architectural independence, minimal third-party attack surface, and deterministic runtime stability, RTP strives to avoid heavy external runtime dependencies. As demonstrated by recurring supply-chain vulnerabilities in the broader software ecosystem (e.g. CVE-2022-1471 in SnakeYAML, uncredited transitive vulnerabilities in image parsers), shading complex external frameworks into plugin assemblies introduces substantial supply-chain and maintenance risks.

Currently, `rtp-core` declares `implementation 'com.zaxxer:HikariCP:5.1.0'`. HikariCP is utilized in only two classes:
1. `MySQLDatabaseAccessor.java`
2. `PostgreSQLDatabaseAccessor.java`

Meanwhile, `SQLiteDatabaseAccessor` and `H2DatabaseAccessor` operate entirely without HikariCP via standard JDBC `DriverManager` connections.

An audit of RTP's actual SQL workload reveals:
- **Low Concurrency:** Database operations are confined to asynchronous batch log flushing (`writeQueue`), startup/refill queue state reads/writes, and periodic multi-server proxy heartbeat polling (`SqlNetworkStateBinding`). Peak concurrent connection demand is strictly bounded (2–5 connections).
- **Narrow API Contract:** RTP uses only `dataSource.getConnection()` and `dataSource.close()`. Complex features of HikariCP (bytecode manipulation, JMX metrics, thread-dump leak detection, SLF4J logging frameworks) are entirely unused and add unnecessary surface area (~160 KB shaded bytecode plus internal reflection).

## Decision

RTP shall retire HikariCP and replace it with an in-house, zero-dependency lightweight connection pool (`MiniConnectionPool`) located in `io.github.dailystruggle.rtp.common.database.pool`.

1. **Architecture of `MiniConnectionPool`:**
   - Implements `javax.sql.DataSource` and `AutoCloseable`.
   - Thread-safe resource tracking using JDK primitives: `ArrayBlockingQueue<Connection>` for idle connections and an `AtomicInteger` for active connection count.
   - Bounded pool sizing: default minimum 1 idle connection, maximum 4–8 connections.
   - Dynamic Proxy wrapper around `java.sql.Connection` intercepting `.close()` to return the physical connection to the queue rather than closing the underlying socket.
   - Liveness validation: calls `connection.isValid(int timeoutSeconds)` prior to checkout; automatically discards and replaces broken or stale connections.
2. **Migration:**
   - Update `MySQLDatabaseAccessor` and `PostgreSQLDatabaseAccessor` to instantiate `MiniConnectionPool` with the target JDBC URL, credentials, and tuning properties.
   - Maintain `AbstractSQLDatabaseAccessor.asDataSource()` compatibility so `SqlNetworkStateBinding` in network mode continues functioning seamlessly.
   - Remove `implementation 'com.zaxxer:HikariCP:5.1.0'` from `rtp-core/build.gradle`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Keep HikariCP 5.1.0** | Retains ~160 KB of shaded bytecode, potential classloader conflicts, and external dependency lag without using 95% of HikariCP's capabilities. |
| **Use Commons DBCP2 or Tomcat JDBC** | Even larger dependency footprint and older architectural constraints. |
| **No Connection Pool (Open-and-Close per Query)** | Opening TCP and TLS sockets to remote MySQL/PostgreSQL instances on every batch write introduces intolerable network latency and socket exhaustion. |
| **Single Shared Connection (like SQLite)** | MySQL/PostgreSQL connections are stateful and not thread-safe for concurrent interleaved operations across asynchronous flush tasks and network heartbeat loops. |

## Consequences

- **Positive:**
  - Complete elimination of HikariCP from the runtime dependency graph.
  - Reduced shaded JAR size and reduced compile/test resolution time.
  - Complete sovereignty over connection lifecycle, timeouts, and error handling.
  - Zero SLF4J or bytecode-generation baggage.
- **Negative / Trade-offs:**
  - In-house maintenance of basic connection pooling logic (stale socket eviction, timeout handling, connection wrapping). Given the simple requirements (~120 lines of Java), this cost is minimal and amortized immediately.

## References

- `io.github.dailystruggle.rtp.common.database.options.MySQLDatabaseAccessor`
- `io.github.dailystruggle.rtp.common.database.options.PostgreSQLDatabaseAccessor`
- `io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor`
- [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md) — Technical Parity and Dependency Trimming
- `docs/dev/ENTERPRISE_READINESS.md` item 18 (SQL Testcontainers validation)
