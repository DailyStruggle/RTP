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

## Performance & Throughput

### Paper 26.1 scan throughput parity with Spigot/Folia (2026-04-21)

Paper 26.1 with the non-blocking `LocationGenerator` state machine (ADR-015 post-refactor) achieves roughly **300 cps effective scan throughput**, on parity with the Spigot/Folia Anvil-based scan path. This confirms that ADR-016 §1.1 (the adapter-internal `[RTP] Anvil gate skipped reason=chunk-already-loaded` gate firing on essentially every candidate on Paper chunk-system-v2) is **not** a performance regression relative to the pure-Anvil path — Paper's live-chunk `getBiome` on an already-loaded chunk is cheap enough to close the gap.

Important caveat: the ~300 cps figure is a **batched / parallel effective throughput** (candidates-per-wall-second aggregated across the `scan` command's parallel `allOf` chunk-load batches). It is **not** a per-chunk read latency and must not be divided out as `1/N` to reason about per-stage cost — dividing underestimates true per-chunk latency by roughly the scan batch's parallelism factor.

For a genuine per-chunk latency number, use the serial `--samples N` harness in the `rtp test/async-chunk-load` sub-command (reports p50/p95/p99 over N serial `getChunkAt` probes along a non-spawn spiral). That harness deliberately walks one candidate at a time and is the correct signal to compare between Paper chunk-system versions and/or before/after ADR-015 / ADR-016 changes.

---

## Localization

### Locale-switch migration in `ConfigParser` (2026-04-26)

When `language.yml` is edited from `en` → another locale and the server reloads, `ConfigParser.detectAndPreserveLocaleMismatch` re-extracts `lang/<locale>/<file>.yml` from the JAR over the on-disk file (after backing it up to `<name>.old<n>`) and re-applies user-customized scalar values keyed by enum so they survive under the new locale's key names. Three pitfalls the regression suite (`ConfigParserLocaleSwitchTest`) guards against:

- **Stale `fileDatabase` cache.** After re-extraction, evict `cachedLookup` / `cachedLookupLastModified` for the file or the next read returns the previously-loaded English `YamlFile` and every lookup via the new locale's key names returns `null` (silent blank lines, e.g. the original `rtp info` Spanish report).
- **Identity mappings carry no locale signal.** A `<file>.lang.yml` line where left == right (`infoTickets: infoTickets`) must not count as evidence the on-disk file is already in the active locale. The detector explicitly excludes identity entries from `activeLocaleHits`; otherwise files like `messages.yml` that happen to retain English-named identity keys short-circuit migration falsely.
- **No-rename locale ⇒ no migration.** Files like `integrations.yml` that have no localized JAR resource get a seeded identity-only `language_mapping` from `loadLangFile`. Treating them as "foreign" causes an infinite re-backup loop on every reload. The detector short-circuits when **every** mapping entry is identity (i.e. the active locale renames zero keys for this file).

When adding a new translatable file or locale, see `TRANSLATION_GUIDE.md`. The `LocaleResourceParityTest` enforces the on-disk shape contract that this migration depends on.

---

## Build & Environment

### Gradle daemon / Java version mismatch

The Gradle daemon caches the JVM it was started with. If the active JDK changes between sessions (e.g., Java 17 → Java 25), Gradle logs a daemon-context mismatch and starts a new daemon. This is normal; do **not** kill or restart the daemon manually.
