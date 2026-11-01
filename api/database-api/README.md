# `database-api` — Zero-Dependency SQL Pooling, Dialect & Redis RESP SDK

Platform-neutral, zero-dependency Java 17 persistence and pub/sub substrate shared across `LeafRTP`, `rtp-proxy-common`, and `LeafSleep`.

## Capabilities

- **`CloseableDataSource` & `MiniConnectionPool` (`leafrtp-ADR-079`):** Bounded, lock-free/CAS JDBC `DataSource` and `AutoCloseable` connection pool with automatic liveness validation (`Connection#isValid`), `autoCommit` restoration on recycle, and connection initialization hooks (such as SQLite `WAL` pragmas).
- **`SqlDialect` & `SqlDataSourceFactory`:** Unified dialect detection (`SQLITE`, `MYSQL`, `MARIADB`, `POSTGRESQL`, `H2`), JDBC URL construction, driver availability probing, SQLite `WAL` + `busy_timeout` bootstrap, and portable UPSERT / monotonic high-water-mark SQL builders.
- **Zero-Dependency Redis RESP2 Client (`RespEndpoint`, `RespProtocol`, `RespConnection`, `RespPool`, `RespPubSub`):** Bounded-memory RESP2 wire codec, TLS (`rediss://`) + Redis 6 ACL socket client, connection pool, and dedicated pub/sub subscriber loop.
