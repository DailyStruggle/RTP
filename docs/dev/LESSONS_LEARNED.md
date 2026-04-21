# RTP Lessons Learned

Dated engineering notes, reproduction pitfalls, and non-obvious behaviors discovered during development. These are **not normative requirements** (see `REQUIREMENTS.md` for those) and **not architectural decisions** (see `docs/adr/` for those). They exist to save the next contributor from repeating a debugging session.

Each entry is dated so it can be pruned or superseded over time. When an entry becomes obsolete (e.g., the underlying bug has been fixed and is regression-guarded by a test), delete it and reference the guarding test in `TRACEABILITY.md` instead.

---

## Database & Persistence

### `DatabaseAccessor` persistence tests must exercise the full public API (2026-04-18)

When writing tests for `DatabaseAccessor` persistence, **always** exercise the full public surface:

    saveCachedLocation(...) → flushDirtyCache() → processQueries(Long.MAX_VALUE) → loadCachedLocations(...)

Tests that call `write(conn, table, prebuiltColumns)` or `delete(conn, ...)` directly with hand-built `TableObj` maps will miss bugs in:

- `cacheValue`'s primary-key inference,
- `flushDirtyCache`'s composite-key parsing, and
- `processQueries`' queue-drain gating.

Two silent bugs (column-map wrapping in `saveCachedLocation` and an incomplete early-exit in `processQueries` that stranded `deleteQueue`) slipped past the entire H2 / MySQL / PostgreSQL / SQLite test suite for exactly this reason. See `CachedLocationRoundTripTest` for the correct round-trip pattern.

### Shutdown flush pipeline (2026-04-18)

`RTP.stop()` must explicitly call `databaseAccessor.processQueries(Long.MAX_VALUE)` **after** `flushDirtyCache()` and **before** `stop.set(true)`.

- `flushDirtyCache` only moves entries from `dirtyCache` into `writeQueue` (via `setValue().thenAccept(...)`, which runs inline because `getTable` returns a completed future); it does **not** write to disk.
- The actual disk write happens in `processQueries`, scheduled every 60 ticks in production — but on server stop there is no "next tick", so any entry that arrived between the last periodic drain and shutdown is lost.
- `processQueries` bails immediately if `stop.get()` is true, so the drain must happen **before** the stop flag is set.

Symptom if missed: the kept-location cache appears to save (no warnings) but is always empty after restart.

### Shared database connections (2026-04-19)

`SQLiteDatabaseAccessor` and `H2DatabaseAccessor` use a single shared `Connection`. To avoid `SQLException: database connection closed` during concurrent operations (e.g., async `flush` vs. `processQueries`):

- **Do not** use try-with-resources on `getConnection()`.
- **Do not** call `close()` in their `disconnect()` implementation.
- Use `connect()` / `disconnect()` as soft references and call the explicit `DatabaseAccessor.close()` **only** during server shutdown in `RTP.stop()`.

---

## Command Pipeline

### `TreeCommand` error reporting

`TreeCommand` distinguishes between invalid commands (subcommand not found) and bad parameters (formatted as `key:val`):

- Arguments **without** a `:` delimiter must be subcommands; if no match is found, report via `msgInvalidCommand`.
- Delimited arguments (`key:val`) with unknown keys or rejected values report via `msgBadParameter`.

### `RTPCmd` delegation

`RTPCmd` (the root command) delegates all argument parsing to `TreeCommand.onCommand`. It must **not** contain manual loops for positional parameter detection — that causes double-dispatch and ignores error states from the library.

### Command feedback auditing (REQ-RTP-S-004)

All platform-specific command handlers (e.g., `BukkitBaseRTPCmd`) must call `RTP.log(Level.WARNING, msg)` for **both** `invalidCommand` and `badArg` to ensure visibility in `rtp test full`.

### Testing async command feedback

When testing command feedback in `rtp-core` (e.g., `InvalidCommandTest`), use `Thread.sleep` or await the `CompletableFuture` returned by `onCommand` to ensure feedback has arrived before asserting.

---

## Test Infrastructure

### Harmless `rtp-core` test warnings

Every `rtp-core` test run emits `SLF4J: No SLF4J providers were found` and Java-agent loading warnings. Ignore them — they do not indicate test failures.

### `run_test` suppresses stdout

The `run_test` tool summary suppresses test `System.out.println`; `[DEBUG_LOG]` lines do **not** appear in its result text. To read them, inspect `rtp-core/build/test-results/test/TEST-<fqcn>.xml` (the `<system-out>` CDATA block):

    Select-String -Path "rtp-core\build\test-results\test\TEST-<fqcn>.xml" -Pattern "DEBUG_LOG"

### Interpreting `rtp test full`

The `commands-live` portion of the full test suite intentionally dispatches malformed commands (see `LiveCommandDispatcherTestJob.malformedInputs()`). These **must** produce `Level.WARNING` logs to satisfy REQ-RTP-S-004; warnings there are evidence of compliance, not failures.

---

## Build & Environment

### Gradle daemon / Java version mismatch

The Gradle daemon caches the JVM it was started with. If the active JDK changes between sessions (e.g., Java 17 → Java 25), Gradle logs a daemon-context mismatch and starts a new daemon. This is normal; do **not** kill or restart the daemon manually.
