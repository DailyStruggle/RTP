# ADR-041 — `/rtp config` Command and Save Mechanics: Implementation Strategy

**Status:** Proposed
**Date:** 2026-05-14
**Target release:** `3.0.0-beta.3`
**Companion documents:** [ADR-037](ADR-037-harden-rtp-config-commands.md) (decision / eight contracts), [`docs/dev/CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) (target-state normative spec).

## Context

[ADR-037](ADR-037-harden-rtp-config-commands.md) records the **decision** to harden the `/rtp config` command surface around eight contracts (validator, atomic `ConfigTransaction`, dry-run, uniform audit, configurable messages, scoped permissions, schema-checked reload, single parse/complete grammar). [`CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) records the **target behavior** as observable user-facing semantics and an exhaustive error matrix. Neither commits to a concrete class layout, package boundary, or migration order.

This ADR is the *how* alongside ADR-037's *what*. It commits to:

- The exact set of new classes and the package they live in.
- How existing classes (`SubConfigCmd`, `ViewSubConfigCmd`, `ReloadCmd`, `LanguageCmd`, `BukkitBaseRTPCmd`) are refactored to consume them.
- The order in which the eight contracts ship within beta.3, so each unit of work is independently mergeable and testable.
- The test classes named in ADR-037 mapped to file paths and to the spec sections they exercise.

No new contracts beyond ADR-037 are introduced here. No spec behavior beyond `CONFIG_COMMAND_SPEC.md` is added. If a tension arises during implementation, this ADR loses to the spec; the spec is the contract.

## Decision

### Module placement

All implementation lands in `rtp-core`. The existing command classes are already in `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/`; the hardening stays in that tree.

- `rtp-core/.../commands/config/internal/` — **new package**. Houses the hardening primitives. Internal-stable for beta.3; not promoted to `rtp-api` (deliberate per ADR-037 *Alternatives Considered*).
- `rtp-core/.../commands/config/` — existing package. `SubConfigCmd`, `ViewSubConfigCmd`, `ListAddParameter`, `ListRemoveParameter`, `LanguageCmd` are refactored in place to delegate into `internal/`.
- `rtp-core/.../commands/reload/` — existing package. `ReloadCmd` / `SubReloadCmd` are wired through `ConfigParameterValidator` before swap.
- `rtp-plugin/.../commands/BukkitBaseRTPCmd.java` — only the `msgInvalidCommand` / `msgBadParameter` path is touched; routes through the new `config.error.*` keys. No business logic moves here.
- `commands-api/` — no surface change. Brigadier bridge ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) carries the unchanged subcommand grammar; `--dry-run` is an ordinary trailing argument from its perspective.
- `rtp-api` — no surface change in beta.3, per ADR-037's deliberate decision.

### New classes (under `commands/config/internal/`)

| Class | Responsibility | Spec anchor |
|---|---|---|
| `ConfigReasonCode` (enum) | Closed set of failure codes from the spec's §5.2 table. Each constant carries its `messages.yml` key (`config.error.<lowercase>`) and a default English fallback. | §5.2 |
| `ConfigValidationFailure` | Immutable record: `parameterPath`, `attemptedValue`, `reasonCode`, optional `detail`. Thrown / returned from validators; never `null`, never `return false`. | §3.3, §5.1 |
| `ConfigParameterValidator` | Strategy interface: `validate(parameterPath, attemptedValue, snapshot) → Optional<ConfigValidationFailure>`. Implementations per parameter type (numeric range, enum, type, region-name, world-name, shape-specific). Composite invariants implement a separate `CompositeInvariant` interface invoked after per-parameter validators pass. | §5.1 |
| `ConfigParameterGrammar` | Single source of truth for parse + tab-complete. One `GrammarNode` per parameter position; both `onCommand` and `onTabComplete` walk the same tree. | §9, §2 |
| `ConfigTransaction` | Owns: snapshot map (`FactoryValue` → captured value), mutation list, target file. Methods: `addMutation`, `validateAll`, `apply`, `persistAtomically`, `rollback`, `audit`. Single-file at the command surface (spec §2.2). | §3.4–§3.7 |
| `AtomicConfigWriter` | The temp+fsync+rename helper. Method `write(targetFile, serializedContent)`: writes `<target>.tmp` in same directory, fsyncs, renames over `<target>`, best-effort parent-dir fsync. Throws `IOException` only; never swallows. Also exposes `cleanupStaleTempFiles(directory)` for the startup hook. | §4.2, §4.3 |
| `ConfigAuditRecord` | Immutable record carrying every field of spec §6.1. | §6.1 |
| `ConfigAuditFormatter` | Pure function `format(ConfigAuditRecord) → String`. Deterministic; consumed by tests via `SendMessage.addInterceptor`. | §6.2 |
| `ConfigCommandExecutor` | Orchestrates the seven-stage lifecycle of spec §3. Owned by `SubConfigCmd`; one instance per invocation. Reduces `SubConfigCmd#onCommand` to "parse → executor.run → render result." | §3 |

### Refactor of existing classes

- **`SubConfigCmd`** — the 245-line `onCommand` body collapses to: parse via `ConfigParameterGrammar` → build `ConfigTransaction` → invoke `ConfigCommandExecutor` → render the resulting `ConfigAuditRecord` to the caller. `onTabComplete` calls the same `ConfigParameterGrammar` (parity test enforces). The world-aware vertical clamping noted in `docs/admin/COMMANDS.md` (nether `maxY=128`, vert→LINEAR, etc.) moves into a `CompositeInvariant` so it is applied uniformly on both `/rtp config` writes and `/rtp reload` paths (spec §8.2).
- **`ViewSubConfigCmd`** — becomes the renderer for spec §2.4's interactive view. Reads from the current parser snapshot, produces a sequence of `ChatComponent`-style entries with hover (when comments are available; see Appendix A12) and click-suggest. Emits a single `INFO` audit record with empty `mutations`.
- **`ListAddParameter` / `ListRemoveParameter`** — collapsed into the grammar's list-operator nodes; the standalone classes either become thin grammar adapters or are deleted, decided during implementation.
- **`ConfigCmd`** — gains tab-complete delegation to the registered `SubConfigCmd` set; no semantic change.
- **`LanguageCmd`** — hardened to the spec's lifecycle (§3) **plus** a `reInitializeAllParsers()` step in §3.7 that replaces the standard targeted-reload, per spec §4.4 / the user's clarification. Permission node: `rtp.config.set.language` (additive, falls back to `rtp.config.set`).
- **`ReloadCmd` / `SubReloadCmd`** — interpose `ConfigParameterValidator.validateAll(parsedConfig)` between "read from disk" and "swap into `Configs`." On failure: abort the swap, restore the previous map, emit one `WARNING` audit record (`outcome = REJECTED`), surface to caller.
- **`BukkitBaseRTPCmd#msgInvalidCommand` / `msgBadParameter`** — resolve via `messages.yml → config.error.<reasonCode>` with the same `Level.WARNING` REQ-RTP-F-013 enforcement on missing keys. The existing `RTP.log` audit emission for REQ-RTP-S-004 stays; the new audit record subsumes it.

### Startup hook (Appendix A11 closure)

On plugin enable, after `Configs.reload` builds the initial parser map and before any command is dispatchable, `AtomicConfigWriter.cleanupStaleTempFiles` is invoked once per registered config directory (`plugins/RTP/`, `plugins/RTP/regions/`, `plugins/RTP/worlds/`, `plugins/RTP/safety/`). Each stray `<name>.yml.tmp` is deleted; one `INFO` log line per deletion (`reasonCode = STALE_TEMP_FILE`). No retry on failure to delete (a locked `.tmp` likely means a parallel plugin instance is mid-write; logged but tolerated).

### Concurrency model

- Writes execute on the caller thread (or, on Folia, dispatched through `RTP.scheduler.runTaskAsynchronously` per platform-adapter convention). No background batcher. Per-file write lock is a `ReentrantLock` held by `ConfigTransaction` for the duration of §3.4–§3.7; lock acquisition is `tryLock(0)` so a contended `/rtp config` returns `RELOAD_IN_PROGRESS` rather than blocking.
- `/rtp reload` acquires the same per-file write lock (or a global lock for `/rtp reload` without arguments). A reload in progress causes incoming writes to fast-reject (spec §4.6 / §5.2 `RELOAD_IN_PROGRESS`); a write in progress causes a concurrent reload to await the write's completion (single deferral, no stacking).
- `ConfigParameterValidator` instances are pure and stateless; no locking required.
- `AtomicConfigWriter` is stateless; safe to use from any thread.

### Test scaffolding

ADR-037 names the test classes. This ADR pins their paths and the spec sections they exercise:

| Test class (path: `rtp-core/src/test/java/...commands/config/`) | Exercises | Spec section |
|---|---|---|
| `ReqRtpS004ConfigCommandAuditTest` | Every failure path emits exactly one audit record with the right `outcome` and `reasonCode`. | §6 + §10 |
| `ConfigTransactionAtomicRollbackTest` | A simulated `PERSIST_IO` failure leaves in-memory state unchanged and removes the temp file. | §3.6, §4.2 |
| `ConfigDryRunDiffTest` | `--dry-run` produces the diff and never touches disk; live mode does both. | §3.7 |
| `ReloadCmdSchemaValidationTest` | An offline-edited shape-invariant violation (Polygon `expand=true`) is caught at reload time, not at sample time. | §8 |
| `ConfigPermissionScopeTest` | Holder of `rtp.config.set.regions` may mutate `regions/*.yml` but not `performance.yml`; umbrella holder may mutate everything. | §7 |
| `ConfigParameterGrammarParseCompleteParityTest` | Every tab-complete suggestion at every grammar position round-trips through the parser. | §9 |
| `ReqRtpF013ConfigMessageCoverageTest` | Every `ConfigReasonCode` has a `messages.yml → config.error.<code>` entry, and vice versa. | §5.3 |
| `AtomicConfigWriterRenameAtomicityTest` | A crash simulated between fsync and rename leaves `<target>` unchanged; subsequent startup cleans `<target>.tmp`. | §4.2, §A11 closure |

Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)) gains one row per test, keyed to REQ-RTP-S-004 / REQ-RTP-S-007 / REQ-RTP-F-013 / REQ-RTP-S-006 as appropriate.

### Migration sequencing (within beta.3)

The work ships as ordered, independently-reviewable units. Each unit lands its own commit / PR; later units assume earlier ones are in.

1. **Codes + messages.** Land `ConfigReasonCode` enum and the corresponding `messages.yml → config.error.*` keys (with English defaults). `ReqRtpF013ConfigMessageCoverageTest` enforces the bijection. No behavior change yet; the codes are unused.
2. **Validators (scalar).** Land `ConfigParameterValidator` interface and the easy implementations: numeric range, type, enum. Wire them into `SubConfigCmd#onCommand` as an *additive* pre-check that emits a `WARNING` audit record but does **not** block the legacy path. This is the "audit-first" intermediate state: real failures are now visible without changing externally-observed behavior.
3. **Atomic writer + startup cleanup.** Land `AtomicConfigWriter` and the startup `cleanupStaleTempFiles` hook. Swap the YAML save path in `ConfigParser#saveToFile` (or its equivalent) to route through the writer. `AtomicConfigWriterRenameAtomicityTest` enforces correctness. Closes spec gaps A9 + A11.
4. **Transaction + audit.** Land `ConfigTransaction`, `ConfigAuditRecord`, `ConfigAuditFormatter`, `ConfigCommandExecutor`. Refactor `SubConfigCmd#onCommand` to delegate to `ConfigCommandExecutor`. The legacy per-type branches collapse. `ReqRtpS004ConfigCommandAuditTest` and `ConfigTransactionAtomicRollbackTest` go green.
5. **Dry-run.** Add the `--dry-run` token to `ConfigParameterGrammar` (still embryonic at this point) and the executor; `ConfigDryRunDiffTest` goes green.
6. **Scoped permissions.** Add `rtp.config.set.<section>` resolution in `ConfigCommandExecutor#authorize`; the umbrella `rtp.config.set` remains valid. `ConfigPermissionScopeTest` goes green.
7. **Grammar unification.** Replace the hand-written `onTabComplete` branches with `ConfigParameterGrammar` walks. `ConfigParameterGrammarParseCompleteParityTest` goes green. Closes spec gap A8.
8. **Schema-checked reload.** Wire `ConfigParameterValidator.validateAll` into `ReloadCmd` / `SubReloadCmd`. `ReloadCmdSchemaValidationTest` goes green. Closes spec gap A7.
9. **`LanguageCmd` hardening.** Apply the same lifecycle to `LanguageCmd` with the `reInitializeAllParsers` step in §3.7. Closes spec gap A10.
10. **Composite invariants.** Register `Polygon.expand=false`, `Polygon.vertices` validity, `Rectangle` / `Ellipse` extent positivity (ADR-034) as composite invariants. Run on every write and every reload. Closes the last piece of contract 7.

Each unit's PR description shall link the spec section(s) it implements and the test class(es) it adds or makes green.

### Out of scope for this ADR

- `LanguageCmd`'s detailed re-init algorithm — recorded as a single bullet here ("§3.7 calls `reInitializeAllParsers`"). If the re-init turns out to be non-trivial (locale switching mid-flight, in-flight teleports holding old locale-aware messages), a separate ADR covers it.
- YAML-comment preservation through the write-back round-trip (spec Appendix A12). Closing A12 requires either upgrading the YAML substrate (extension of [ADR-025](ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md)) or replacing it with an in-house parser. Separate ADR; tracked but not addressed in beta.3.
- Wizard concerns. The wizard ADR ([ADR-038](ADR-038-rtpadmin-setup-wizards.md)) consumes these primitives in a later release; this ADR does not commit any of them to a public-API surface.
- Cross-server propagation of config changes ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)). Each backend persists locally; cross-backend fan-out is independent work.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Put validators on the `FactoryValue` itself rather than in a separate `ConfigParameterValidator` interface | Couples validation logic to the data carrier, leaks platform-agnostic invariants (shape coherence, region-name validity) into a class whose primary job is value transport. Also makes composite invariants (cross-parameter) awkward — they have no natural single owner. External validators are testable in isolation and let composite invariants live as first-class entities. |
| Make `ConfigTransaction` a decorator over `Configs` rather than its own type | The decorator path turns every read on `Configs` during a transaction into a snapshot-aware lookup, which is the wrong cost model — reads vastly outnumber writes. A separate transaction object owns the snapshot, leaves the read path untouched, and confines transactional complexity to the write path. |
| Skip `ConfigParameterGrammar` and write parser/completer parity tests instead | Parity tests can only enforce parity for cases the test author thought to enumerate; the grammar enforces it by construction. The parity test still ships as a regression guard, but it is checking a single source of truth, not two hand-aligned implementations. |
| Land all eight contracts in one commit | The diff would be unreviewable (`SubConfigCmd` alone is 435 lines; full hardening touches it + `ViewSubConfigCmd` + `ListAdd/Remove` + `ReloadCmd` + `LanguageCmd` + `BukkitBaseRTPCmd` + `messages.yml` + 8 test classes). The sequencing in *Migration sequencing* above produces an audit-first intermediate state at step 2 that is itself an improvement; each later step is an additive guarantee. |
| Use a global write lock instead of per-file | Simpler to reason about, but it makes concurrent writes to unrelated files (`/rtp config performance maxAttempts:20` and `/rtp config regions nether maxY:128`) serialize when they shouldn't. The per-file lock keeps the contention surface small; it costs a `ConcurrentHashMap<Path, ReentrantLock>` lookup per write. |
| Background save queue with a shutdown drain | Adds a category of failure mode the spec explicitly designs out (shutdown-flush ordering, per §4.6 and `LESSONS_LEARNED.md`). Writes-on-caller-thread are bounded — the atomic-rename pattern's hot path is two syscalls plus a fsync — and the spec's "exactly one record per invocation" rule is much easier to enforce without a queue. |
| `ConfigAuditRecord` as a JSON-serializing record (vs. formatter-rendered string) | JSON is appealing for downstream log scrapers but binds the audit format to a serialization library and turns every audit emission into an allocation that is only needed if a consumer is attached. The single-formatter / interceptor pattern (`SendMessage.addInterceptor`) is the project's existing convention and keeps the hot path allocation-free for the default no-scraper case. A JSON renderer can be a separate interceptor when a scraper exists. |
| Promote the primitives to `rtp-api` in beta.3 | Same reason as ADR-037: no in-tree consumer has exercised the interface yet. Premature SPI commits us to a shape that hasn't been pressure-tested. The wizard ADR will be the consumer that justifies promotion (or doesn't). |
| Land `LanguageCmd` hardening before the rest | `LanguageCmd`'s re-init step depends on `ConfigTransaction` and the schema-checked reload (step 8). Sequencing it after step 8 means each step's contracts are stable when the language path consumes them. |

## Consequences

### Positive

- One concentrated refactor of `SubConfigCmd` (the 245-line `onCommand`) replaces a class of subtle bugs (silent fail, partial writes, audit gaps) with one composable lifecycle. The same lifecycle services `/rtp reload`, `/rtp config view`, `LanguageCmd`, and any future config-mutating command.
- The migration sequencing produces an *audit-first* intermediate state at step 2 that is independently useful: real failures become visible without changing externally-observed behavior. Steps 3–10 each tighten one specific guarantee.
- The startup `cleanupStaleTempFiles` hook closes the only durability hole the atomic-rename pattern can leave (crash between temp-write and rename). The spec is fully self-recovering from crashes.
- The wizard ADR ([ADR-038](ADR-038-rtpadmin-setup-wizards.md)) can be small. It composes `ConfigTransaction` + `ConfigParameterGrammar` + the audit stream + ADR-035's menu primitive. None of validation, persistence, rollback, audit, or message rendering belongs in the wizard layer.
- `rtp test full` gains a clean assertion surface (audit-record shape, transaction commit/rollback pairing) that does not depend on string-matching log output.

### Negative / Trade-offs

- One concentrated refactor of `SubConfigCmd`. Risk is regression in obscure parameter paths; mitigated by `ConfigParameterGrammarParseCompleteParityTest` and the existing `TestConfigSetCmd`-driven coverage.
- The atomic-rename pattern depends on the filesystem honoring `rename` atomicity (every supported deployment target does, exotic networked filesystems may not). Documented in the spec; matches the existing assumption baseline for [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md).
- `messages.yml` grows by one entry per `reasonCode` plus the dry-run keys. Acceptable given REQ-RTP-F-013.
- Once beta.3 ships and the wizard ADR consumes the primitives, removing or renaming any of them becomes a breaking change for the wizard (even though they are not on `rtp-api`). The wizard ADR will record its own assumptions; this ADR records that the primitives are deliberately internal-stable for beta.3.
- The audit log is more verbose. Every successful set emits an `INFO` record; high-frequency scripted use will produce log volume. Mitigated by the existing log-level config.
- Spec Appendix A12 (YAML comment preservation) is **not** closed in beta.3. The `view` hover-text feature degrades to declared type + bounds where comments are unavailable; the rest of the spec is fully achievable on the current YAML substrate.

## Migration / Rollout

- Beta.3 ships steps 1–10 of *Migration sequencing*. The `/rtpadmin` setup wizard does **not** ship in beta.3 unless a separate wizard ADR is accepted and implemented in the same window. The contracts are independently valuable for direct admin use.
- New config keys: `commands.config.dryRunFlag` (default `--dry-run`); new `messages.yml → config.error.<reasonCode>` and `messages.yml → config.dryRun.*` sections.
- No breaking changes to existing command grammar. `--dry-run` is additive; existing forms continue to commit-on-success. Existing permission nodes continue to grant all; scoped nodes are additive.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add rows for every test class in *Test scaffolding*.
- CHANGELOG: no entry until implementation lands, per the CHANGELOG hygiene rule in `AGENTS.md`. Each migration-sequencing step gets its own changelog bullet under `[3.0.0-beta.3] — Unreleased` as it merges.

## References

- [ADR-037](ADR-037-harden-rtp-config-commands.md) — decision (the eight contracts). This ADR is the *how* to ADR-037's *what*.
- [`docs/dev/CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) — target-state normative spec. If any class layout in this ADR conflicts with the spec, the spec wins.
- [ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md) — H2/SQLite persistence. Same filesystem-assumption baseline as the atomic-rename pattern here.
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) — Locale-aware parser. `LanguageCmd` re-init in spec §4.4 composes on top of this.
- [ADR-025](ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md) — Internal SnakeYAML wrapper. Current YAML substrate; comment-preservation gap tracked at spec Appendix A12.
- [ADR-034](ADR-034-memory-shape-catalog.md) — Memory shape catalog. Composite invariants (Polygon `expand=false`, Rectangle / Ellipse extents) plug into `ConfigParameterValidator`.
- [ADR-035](ADR-035-interactive-menus-book-first.md) — Interactive menus. Menu redeems that dispatch a config command inherit all of this ADR's primitives for free.
- [ADR-038](ADR-038-rtpadmin-setup-wizards.md) — `/rtpadmin` setup wizards. The future consumer that justifies (or doesn't) promotion of any of these primitives to `rtp-api`.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge. Carries the unchanged subcommand grammar.
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) — REQ-* → class → test mapping.
- [`docs/architecture/09-configuration-load-and-reload.md`](../architecture/09-configuration-load-and-reload.md) — read path (companion).
- [`docs/architecture/11-configuration-write-and-persist.md`](../architecture/11-configuration-write-and-persist.md) — write path (companion).
