# ADR-002 — H2/SQLite for Spatial Memory Persistence Over Flat-File Cache

**Status:** Accepted
**Date:** 2026-04-15

## Context

The `MemoryShape` system must persist its knowledge of known-invalid spatial sectors (bad-sector index ranges) across server restarts so that the plugin does not redundantly re-validate locations it has already determined to be unsafe. This requires a durable, queryable store for the sorted set of excluded integer intervals per region.

At the time this decision was made, the developer was not yet deeply familiar with the full landscape of embedded database options. The choice was made pragmatically: use a well-supported embedded SQL database rather than designing a custom flat-file format.

## Decision

Use an embedded relational database (H2 or SQLite, configurable) as the persistence layer for spatial memory data, rather than a hand-rolled flat-file cache.

Both H2 and SQLite are zero-configuration embedded databases that ship as a single JAR/native library, require no external server process, and provide ACID guarantees. The plugin exposes a configurable `database` setting so server operators can choose between them (or connect to an external MySQL instance for multi-server setups).

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Custom flat-file (e.g., serialised Java objects or CSV of index ranges) | Requires designing, implementing, and maintaining a custom serialisation format, including versioning and migration logic. No query capability; full file parse required for any lookup. High risk of corruption on unclean shutdown. |
| Java `Properties` / YAML file per region | Human-readable but not suited to storing large sets of integer intervals efficiently; no atomic write guarantees; no indexing. |
| In-memory only (no persistence) | Loses all spatial memory on restart, forcing full re-validation of the region on every server start — directly contradicts the fault-tolerance and restart-resilience requirements (`REQ-CORE-NF-003`). |
| External database only (MySQL/MariaDB) | Requires operators to provision and maintain a separate database server; unacceptable as a default for single-server deployments. |

## Consequences

- **Positive:**
  - ACID guarantees prevent corruption of spatial memory data on unclean shutdown.
  - SQL query interface allows efficient range lookups and updates without loading the entire dataset into memory.
  - Configurable backend (H2, SQLite, MySQL) lets operators choose the appropriate store for their deployment scale.
  - No custom serialisation format to maintain or migrate.

- **Negative / Trade-offs:**
  - Adds a runtime dependency (H2 or SQLite JDBC driver) that must be bundled or provided.
  - SQL schema migrations are required when the data model changes between plugin versions (see `MIGRATION.md`).
  - H2 and SQLite have different SQL dialects in edge cases; the abstraction layer must handle both.

## References

- Implementing classes: `MemoryShape`, database integration layer (`rtp-core`)
- Design reference: [`DESIGN.md` §4 — Persistent State and Fault Tolerance](../DESIGN.md)
- Requirements: `REQ-CORE-NF-003` (restart resilience), `REQ-CORE-F-005` (stateful memory tracking)
- Upgrade notes: [`MIGRATION.md`](../MIGRATION.md)
