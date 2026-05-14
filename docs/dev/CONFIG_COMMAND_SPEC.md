# `/rtp config` Command and Config Save Mechanics — Specification

**Status:** Target-state normative specification. Implementation tracked by [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md) (decision) and [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md) (implementation strategy).
**Audience:** Contributors modifying anything under `rtp-core/.../commands/config/`, `rtp-core/.../commands/reload/`, the YAML save path, or downstream consumers (`/rtpadmin` wizards per ADR-038, menu redeems per ADR-035).
**Companion docs:** [`docs/architecture/09-configuration-load-and-reload.md`](../architecture/09-configuration-load-and-reload.md) (read path), [`docs/architecture/11-configuration-write-and-persist.md`](../architecture/11-configuration-write-and-persist.md) (write path).

> **Scope note.** This document specifies the **target** behavior of `/rtp config` and the YAML save mechanics after the ADR-037 / ADR-041 hardening ships (planned for `3.0.0-beta.3`, alongside proxy support and other beta.3 work). Where the current pre-hardening implementation diverges, the gap is enumerated in *Appendix A: Current Gaps vs. Spec*. The spec is the contract the implementation is held against; the appendix shrinks to zero as contracts ship.

---

## 1. Scope and Non-Goals

### 1.1 In scope

- The user-facing grammar, behavior, and exit semantics of `/rtp config <file> …` and its `--dry-run` variant.
- The on-disk save mechanics for every YAML file under `plugins/RTP/` (write-to-temp → fsync → atomic rename, per-file scope, ordering vs. in-flight reload).
- Validation, audit, permission, and reload semantics that the command shall satisfy.
- Tab-complete contract (single grammar with the parser).
- Error model: `reasonCode` enumeration, message resolution path, audit-record schema.

### 1.2 Out of scope

- `/rtpadmin` wizard flow design — see [ADR-038](../adr/ADR-038-rtpadmin-setup-wizards.md). The wizard is a *consumer* of this spec.
- Interactive menu / book redeems — see [ADR-035](../adr/ADR-035-interactive-menus-book-first.md). Menu redeems that dispatch a config command inherit this spec unchanged.
- The on-disk YAML *shape* (key names, nested structure). Unchanged from the pre-hardening implementation.
- Read-path / reload internals — see architecture diagram 09.
- Cross-server / proxy fan-out of config changes — see [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md). Each backend persists locally; cross-backend propagation is a separate axis.
- Translation of new `messages.yml` keys — handled at the project's normal translation cadence ([`TRANSLATION_GUIDE.md`](TRANSLATION_GUIDE.md)).

### 1.3 Cross-references

- Decision: [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md) — the eight contracts. This spec is the long-form, normative restatement of those contracts as observable behavior.
- Implementation strategy: [ADR-041](../adr/ADR-041-config-command-and-save-implementation.md) — concrete class layout, package, test scaffolding.
- Requirements: [`REQUIREMENTS.md`](REQUIREMENTS.md) — REQ-RTP-S-004 (no silently discarded failures), REQ-RTP-S-007 / REQ-RTP-F-013 (configurable failure messages), REQ-RTP-S-006 (require-by-contract API entry points).
- Shape invariants: [ADR-034](../adr/ADR-034-memory-shape-catalog.md) — plug into the validator chain.
- Persistence assumption baseline: [ADR-002](../adr/ADR-002-h2-sqlite-over-flat-file-cache.md) (atomic-rename / fsync assumption already in force for the DB layer).
- YAML I/O substrate: [ADR-025](../adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md).

---

## 2. Command Grammar

The grammar is **flat** (no nested operator precedence), **single-file** per invocation, and **derived from the parameter registry** — never hand-rolled per command. The same grammar object drives `onCommand` and `onTabComplete` (contract §9).

### 2.1 Top-level forms

```
/rtp config <file> <key>:<value> [<key>:<value> …] [--dry-run]
/rtp config <file> <list-key> add:<value> [add:<value> …] [remove:<value> …] [--dry-run]
/rtp config <file> view <key> [<key> …]
/rtp config <file> view
```

- `<file>` shall be the basename (without `.yml`) of a config file that the running plugin has registered. The set of accepted values is exactly the set of `ConfigParser` and `MultiConfigParser` instances live in `Configs` at parse time, plus, for multi-config groups, the form `<group> <subfile>` (e.g. `regions nether`, `worlds world_nether`).
- `<key>` is the parameter name as declared in the file's enum registry (e.g. `ConfigKeys`, `PerformanceKeys`, `RegionKeys`, …). Case-insensitive on input; canonical case is preserved in audit and on disk.
- `<value>` is a single whitespace-free token. Values containing spaces are not supported by the command surface in the beta.3 hardening; admins requiring whitespace shall edit YAML directly and `/rtp reload`. This is a deliberate limitation of this release; relaxing it is out of scope for ADR-037.
- `<list-key>` is a `<key>` whose parameter type is `List<T>`. Scalar-key invocations with `add:` / `remove:` operators shall be rejected with `reasonCode = WRONG_OPERATOR_FOR_TYPE`.
- `--dry-run` is the literal token configured by `commands.config.dryRunFlag` (default `--dry-run`, REQ-RTP-F-013). Position-independent within the tail; the parser scans for it once before validation.

### 2.2 One file per invocation

A single `/rtp config` invocation shall mutate **exactly one** target file (`<file>` or `<group> <subfile>` pair). Multi-file batching is not exposed at the command surface; admins requiring atomic cross-file changes shall sequence invocations and accept per-file atomicity. The implementation's internal transaction primitive (per [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md) contract 2) is single-file at the command surface and remains an internal API; any future cross-file-batch consumer requires its own ADR.

### 2.3 Multiple mutations per invocation

Within the one targeted file, an invocation may carry **multiple `<key>:<value>` pairs** and/or **multiple `add:` / `remove:` operators** against the same `<list-key>`. All such mutations form one transaction:

- All-or-nothing: if any mutation fails validation, none are persisted.
- Order-preserving: `add:` operators are appended in argument order; `remove:` operators are applied after all `add:` operators in argument order.
- Idempotent within an invocation: a duplicate `add:` of an existing list element is a no-op (not a failure). A `remove:` of a non-member is a no-op (not a failure).

### 2.4 `view` sub-form

`view` is a **per-`SubConfigCmd` sub-sub-command**, modeled on `help` rather than a flat option. Forms:

```
/rtp config <file> view              # list every key in the file with its current value
/rtp config <file> view <key>        # show one key's current value, type, declared bounds, and YAML comment
/rtp config <file> view <list-key>   # list-typed keys render their current members one per line
```

The view is **interactive** on platforms whose chat renderer supports it:

- Each rendered key **may** carry a **hover-text** tooltip showing the parameter's YAML comment from the on-disk file. Comment preservation through the temp+rename round-trip is **not guaranteed by the current YAML substrate** (see Appendix A row A12); when a comment is unavailable the tooltip degrades to the parameter's declared type + bounds. A future YAML-parser upgrade (or in-house replacement) is the precondition for full comment-preserving hover text; see [ADR-025](../adr/ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md) for the current substrate and Appendix A12 for the open gap.
- Each rendered value carries a **click-suggest** action that pre-fills the caller's chat with `/rtp config <file> <key>:` ready for the new value (or `/rtp config <file> <list-key> add:` / `remove:` for list-typed keys), so the admin's next keystroke is the new value.
- On consoles and platforms without interactive chat, the view degrades to plain text; the click-suggest and hover are simply absent. No audit-record shape change between modes.

`view` is read-only: it takes no `ConfigTransaction`, never opens a temp file, never enters §3.4–§3.6, and is unaffected by `RELOAD_IN_PROGRESS` (it reads whichever parser snapshot is current at the moment it serializes its output). Failure modes are limited to `NO_PERMISSION`, `UNKNOWN_FILE`, and `UNKNOWN_KEY`.

### 2.5 Reserved tokens

The literal tokens `add:`, `remove:`, `view`, and the configured `dryRunFlag` are reserved at the grammar level. A `<key>` that collides with a reserved token (none currently do; this is forward-defensive) shall be rejected with `reasonCode = RESERVED_TOKEN_COLLISION` at parse time.

---

## 3. Lifecycle of a Single Invocation

Every mutating invocation proceeds through a fixed seven-stage lifecycle. No stage may be reordered, skipped, or short-circuited; failure at any stage routes to *Audit + Reject* (§3.7) without leaving in-memory or on-disk state mutated.

### 3.1 Parse

The raw argument list is tokenized against the grammar (§2). Output: a `ParsedInvocation { targetFile, mutations[], dryRun }`. Parse-time failures (`UNKNOWN_FILE`, `WRONG_OPERATOR_FOR_TYPE`, `RESERVED_TOKEN_COLLISION`, `MALFORMED_ARGUMENT`) are reported with the offending token's index for tab-complete attribution.

### 3.2 Authorize

The caller's permissions are checked against the most-specific node implied by `targetFile` (§7). All mutations in one invocation target one file, so authorization is a single check, not per-mutation. Failure → `NO_PERMISSION`.

### 3.3 Validate

Each mutation is run through `ConfigParameterValidator` (§5). Validators are pure functions over `(parameterPath, attemptedValue, currentInMemoryState)`. No mutation has been applied yet. First failure short-circuits the stage; subsequent mutations are not validated (their state in the audit record is `NOT_REACHED`).

### 3.4 Snapshot

A snapshot of every `FactoryValue` the mutations will touch is captured into the transaction. The snapshot is the rollback source-of-truth (§3.7) and the `oldValue` source for the audit record (§6).

### 3.5 Apply (in-memory)

Mutations are applied to the in-memory parser state in argument order. Re-validation against schema invariants that depend on multiple keys (e.g. `Polygon.expand == false`, `Rectangle` half-extent positivity per [ADR-034](../adr/ADR-034-memory-shape-catalog.md)) runs after all mutations are applied but before any disk write. Failure here triggers immediate rollback from the snapshot.

### 3.6 Persist (if not dry-run)

The targeted file is written via the atomic save mechanics of §4. On any I/O failure (temp-file write, fsync, rename), the snapshot is restored to the in-memory parser and the temp file is deleted; the invocation fails with `reasonCode = PERSIST_IO`.

### 3.7 Audit + Reload (or Audit + Reject)

- **Success (live).** A targeted reload of the affected parser is dispatched (per architecture diagram 09). One `INFO` audit record is emitted (§6).
- **Success (dry-run).** No reload. The would-be diff is rendered to the caller via `messages.yml → config.dryRun.*` and an `INFO` audit record is emitted with `dryRun = true`.
- **Failure (any stage).** Rollback is complete (no in-memory mutation survives, no temp file survives), one `WARNING` audit record is emitted with the failing `reasonCode`, and the configurable failure message (§5.2) is delivered to the caller.

> **S-004 (no silently discarded failures).** Every exit path of this lifecycle emits exactly one audit record. Catch-and-swallow, silent `return false`, and `null` returns are prohibited.

---

## 4. Save Mechanics

### 4.1 One file per write

Each successful invocation writes **exactly one** YAML file. The write is independent of any other file's state. There is no cross-file fsync barrier; the per-file atomic rename is the unit of durability.

### 4.2 Atomic rename pattern

The write proceeds:

1. Serialize the post-mutation parser state to a sibling temp file `<target>.tmp` in the same directory as `<target>`. Same-directory siblings guarantee the rename is intra-filesystem (atomic-rename precondition on POSIX and NTFS).
2. `fsync` the temp file's contents.
3. `rename(<target>.tmp, <target>)` — atomic replace.
4. Best-effort `fsync` of the parent directory (POSIX); no-op on NTFS where directory metadata is journaled.

A crash between steps 1 and 3 leaves `<target>` untouched and a stray `<target>.tmp` on disk; startup shall log and delete `*.tmp` siblings of registered config files (`reasonCode = STALE_TEMP_FILE`, `Level.INFO`).

### 4.3 Filesystem assumptions

The pattern requires that `rename` over an existing file is atomic on the deployment filesystem. This holds for all officially supported targets (POSIX local filesystems, Windows NTFS). Networked or exotic filesystems (NFS without `rename` atomicity, some FUSE drivers, certain container-overlay configurations) are not supported for in-place config edits; admins on such filesystems shall edit YAML offline. This matches the existing assumption baseline already in force for [ADR-002](../adr/ADR-002-h2-sqlite-over-flat-file-cache.md)'s H2/SQLite persistence.

### 4.4 Per-file scope (which files are writable)

Writable via `/rtp config`:

- `config.yml`, `performance.yml`, `economy.yml`, `logging.yml`
- Every file under `safety/` (currently `safety/safety.yml` and any future siblings)
- Every file under `regions/` (multi-config; addressed as `regions <subfile>`)
- Every file under `worlds/` (multi-config; addressed as `worlds <subfile>`)
- `messages.yml` (subject to the same lifecycle; key existence checked against the active `MessagesKeys` registry)

Not writable via the **general** `/rtp config <file> <key>:<value>` path:

- `language.yml` — read by `LanguageBootstrap` before any `ConfigParser` exists ([ADR-020](../adr/ADR-020-language-bootstrap-and-locale-aware-configparser.md)). A live mutation requires re-initializing **every** parser (because the locale-aware `ConfigParser` resolves keys against the active locale at construction), which is a heavier operation than the per-file lifecycle of §3. Therefore `language.yml` is **not** exposed as a generic `SubConfigCmd` target; instead, a dedicated path (the existing `LanguageCmd` family under `commands/config/`, hardened to the same audit + permission + atomic-write contracts as this spec) handles locale changes by running the lifecycle of §3 **and then** triggering a full `Configs.reload`-equivalent re-init in §3.7 of its own invocation. Attempts to address `language` through the generic `/rtp config language …` path shall be **redirected** to the dedicated `LanguageCmd` (not failed) when the grammar allows; if the syntax is ambiguous, the command shall fail with `reasonCode = USE_DEDICATED_COMMAND` and the configurable message shall name the right command.
- Any non-registered `.yml` file under `plugins/RTP/` (third-party drop-ins, addon configs not exposed through `Configs`). Tab-complete enumerates only registered files; explicit invocation against an unregistered file fails with `UNKNOWN_FILE`.

### 4.5 Tab-complete enumeration

Tab-complete for `<file>` shall list every registered `ConfigParser` and `MultiConfigParser` name as it appears in `Configs`. For multi-config groups, tab-completing `<file>` returns the group name (e.g. `regions`); a second tab on the next position returns the live set of `<subfile>` names discovered on disk at completion time. No cross-server data appears in this completion — cross-server data sources (see [`MULTI_SERVER_PLAN.md`](MULTI_SERVER_PLAN.md)) supply tab-complete only for cross-server *region names* under other subcommands (e.g. a future `/rtp <region>` resolver), never for the local config file enumeration.

### 4.6 Ordering vs. in-flight reload

A `/rtp config` write and a `/rtp reload` are mutually serialized:

- A reload in progress (i.e. between the in-memory swap and the post-swap region rebuild of architecture diagram 09) shall reject incoming writes with `reasonCode = RELOAD_IN_PROGRESS`. The reject is fast (no temp file is opened).
- A write in progress (i.e. between §3.4 *Snapshot* and §3.7 *Reload*) shall block a concurrently-issued reload by deferring it until the write's reload-affected step completes. The deferral is a single short await on the per-file write lock; reloads do not stack.
- In-flight teleports holding a pre-write parser snapshot are unaffected — they see the old values until they finish, exactly as documented in architecture diagram 09. This is the same invariant the read path already provides.

> **No background save queue.** Writes are dispatched on the command's caller thread (or its scheduled platform handoff for Folia per the platform adapter's `RTP.scheduler` runAsync), never queued behind unrelated work. There is no risk of a config save being lost on shutdown because none are pending: every successful invocation has already completed §4.2 before the audit record is emitted.

---

## 5. Validation Model and `reasonCode` Catalog

### 5.1 Validator chain

Validation is centralized in `ConfigParameterValidator` (per [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md) contract 1). One validator instance per parameter type. The validator receives `(parameterPath, attemptedValue, inMemoryStateSnapshot)` and returns either `Ok` or `ConfigValidationFailure(parameterPath, attemptedValue, reasonCode, detail)`.

Validators are pure: no I/O, no chunk access, no scheduling. They run on the calling thread. Composite invariants (cross-parameter, e.g. `Polygon.expand` vs. `Polygon.vertices`) run as a second pass after per-parameter validators pass, before §3.5 *Apply* commits the in-memory state.

### 5.2 `reasonCode` enumeration

Every failure mode is exactly one `reasonCode`. The set is closed; adding a new code requires updating this spec and adding the corresponding `messages.yml → config.error.<reasonCode>` key.

| `reasonCode` | Raised by | Meaning |
|---|---|---|
| `UNKNOWN_FILE` | Parse | `<file>` (or `<group> <subfile>`) does not name a registered config. |
| `UNKNOWN_KEY` | Parse | `<key>` is not declared in the target file's parameter registry. |
| `MALFORMED_ARGUMENT` | Parse | An argument did not match the `<key>:<value>` / `add:<value>` / `remove:<value>` shape. |
| `WRONG_OPERATOR_FOR_TYPE` | Parse | `add:` / `remove:` used against a non-list key, or `<key>:<value>` against a list key. |
| `RESERVED_TOKEN_COLLISION` | Parse | A `<key>` collides with a reserved grammar token. |
| `NO_PERMISSION` | Authorize | Caller lacks both the scoped node (`rtp.config.set.<section>`) and the umbrella node (`rtp.config.set`). |
| `OUT_OF_RANGE` | Validate | Numeric value outside the declared bounds. |
| `WRONG_TYPE` | Validate | Value did not parse to the declared type (e.g. non-integer for `int` key, non-enum for enum key). |
| `UNKNOWN_REGION` | Validate | A region-name-typed parameter references a region not in `permRegionLookup`. |
| `UNKNOWN_WORLD` | Validate | A world-name-typed parameter references a world neither loaded nor in `worlds/`. |
| `IMMUTABLE_AT_RUNTIME` | Validate | The parameter cannot be mutated at runtime (e.g. a key marked startup-only by its declaring registry). |
| `USE_DEDICATED_COMMAND` | Parse/Authorize | The target file requires its own command (e.g. `language.yml` routes through the dedicated `LanguageCmd`, not the generic `SubConfigCmd`). Message names the right command. |
| `SCHEMA_INVARIANT` | Validate (composite pass) | A multi-key invariant failed (e.g. `Polygon.expand=true`, mismatched `minY`/`maxY`). |
| `RELOAD_IN_PROGRESS` | Authorize/Apply | A reload is in progress; retry after it completes. |
| `PERSIST_IO` | Persist | Temp-file write, fsync, or rename failed; rollback completed. |
| `STALE_TEMP_FILE` | Startup (informational, not a command failure) | A `<target>.tmp` was found at startup and removed. |

### 5.3 Message resolution

Each `reasonCode` resolves to `messages.yml → config.error.<reasonCode>` (lowercased, e.g. `config.error.out_of_range`). The message template may reference `${parameterPath}`, `${attemptedValue}`, `${detail}`, and (where defined) `${expectedRange}` / `${expectedType}`. Missing key → `Level.WARNING` log per the existing REQ-RTP-F-013 enforcement pattern, plus a safe English fallback; never `null`, never the empty string. The legacy hardcoded fallbacks in `BukkitBaseRTPCmd#msgInvalidCommand` / `msgBadParameter` are removed in favor of this resolution path.

## 6. Audit Record Schema

Every invocation — success or failure, live or dry-run — emits **exactly one** audit record. Records are written through `RTP.log` (never `Bukkit.getLogger()` or `System.out`, per the project's logging contract).

### 6.1 Record fields

| Field | Type | Always present | Meaning |
|---|---|---|---|
| `timestamp` | ISO-8601 UTC | yes | When the lifecycle entered §3.1 *Parse*. |
| `actor` | string | yes | The caller identity: `console`, `player:<uuid>` (and the player's name in a secondary field), or `addon:<addonName>` for `rtp-api`-driven invocations. |
| `command` | string | yes | The fully-resolved canonical command string the caller issued, with the configured `dryRunFlag` normalized but argument case and order preserved. |
| `targetFile` | string | yes | Resolved canonical file path relative to `plugins/RTP/` (e.g. `regions/nether.yml`). |
| `mutations` | array | yes (may be empty for `view`) | Per-mutation `{ parameterPath, oldValue, newValue, applied: bool, reasonCode? }`. `applied=false` with `reasonCode = NOT_REACHED` indicates a mutation that was queued but never validated because an earlier mutation failed. |
| `dryRun` | bool | yes | True when the invocation carried `--dry-run`. |
| `outcome` | enum | yes | `COMMITTED` \| `DRY_RUN_OK` \| `REJECTED` \| `ROLLED_BACK`. |
| `reasonCode` | string | only when `outcome ∈ {REJECTED, ROLLED_BACK}` | The closed-set code from §5.2. |
| `detail` | string | optional | Free-form human-oriented detail (e.g. the underlying `IOException` message for `PERSIST_IO`). Never used for control flow. |
| `durationMs` | integer | yes | Wall-clock time from §3.1 to record emission. |

Success records are emitted at `Level.INFO`; failure and rollback records at `Level.WARNING`. The `view` sub-form is `Level.INFO` with `outcome = DRY_RUN_OK` and an empty `mutations` array (its read-only payload, if any, is delivered to the caller, not the audit log, to avoid `messages.yml` content leaking into log files when an admin runs `view` against `messages.yml`).

### 6.2 Single formatter, single sink

All records flow through one `ConfigAuditFormatter`. Per the existing `SendMessage.addInterceptor(Consumer<String>)` pattern documented in `AGENTS.md → Logging & Feedback`, tests and the `rtp test full` audit pass shall consume the formatted line stream rather than scraping platform-specific logger output. The formatter's output is deterministic given the record (no clock-derived suffix beyond `timestamp`, no `Object#toString` leakage of identity hash codes). The single invocation produces exactly one record; rollback in §3.7 emits the same record (with `outcome = ROLLED_BACK` and the failing `reasonCode`) rather than a paired pre/post record, so no cross-record correlation key is needed.

---

## 7. Permission Grammar

### 7.1 Node hierarchy

Permissions are additive over the existing umbrella nodes:

| Node | Grants |
|---|---|
| `rtp.config` | Legacy alias retained for back-compat; equivalent to `rtp.config.view` + `rtp.config.set`. |
| `rtp.config.view` | The `view` sub-form (§2.4) against any file. |
| `rtp.config.set` | Any mutation against any file (umbrella). |
| `rtp.config.set.<section>` | Mutations against the named top-level section only. `<section>` is the basename of the target file or the multi-config group (e.g. `performance`, `economy`, `regions`, `worlds`, `safety`, `messages`, `config`, `logging`). |

`set` implies `view` for the same section: a holder of `rtp.config.set.regions` may run `/rtp config regions <subfile> view` without holding `rtp.config.view`.

### 7.2 Resolution

For a given invocation against `<targetFile>`:

1. Compute the section name (`section = basename of targetFile, stripped of any subfile suffix and `.yml`).
2. If the caller holds `rtp.config.set.<section>` → authorized.
3. Else if the caller holds the umbrella `rtp.config.set` → authorized.
4. Else if the invocation is a `view` and the caller holds `rtp.config.view` or `rtp.config` → authorized.
5. Else → `NO_PERMISSION`.

Per-region scoping (e.g. "edit only `regions/nether.yml`") is **not** part of the beta.3 hardening; the section is the smallest grain. A wizard that needs finer scoping shall propose its own ADR.

---

## 8. Schema-Checked Reload

`/rtp reload` (whether issued directly, dispatched by §3.7, or triggered by the dedicated `LanguageCmd`) runs the parsed-but-not-applied config through the same `ConfigParameterValidator` chain (§5) **before** swapping it in. The check runs against the same composite-invariant pass §3.5 uses, so a reload of a file that was edited offline (bypassing the command surface) is guarded by the same invariant set as a write through the command surface.

### 8.1 Failure semantics

A reload-time validation failure aborts the swap with the previous in-memory state intact. The audit record carries `outcome = REJECTED` and the failing `reasonCode`. The diagram-09 swap node is not entered; in-flight teleports continue to see the previous state. The caller is told which file and which key failed and is invited to re-edit and retry. No partial-swap state is observable.

### 8.2 Shape invariants

The shape-specific invariants from [ADR-034](../adr/ADR-034-memory-shape-catalog.md) (`Polygon` rejects `expand=true`, polygon vertex validity, `Rectangle` half-extent positivity, `Ellipse` axis positivity) are registered as composite invariants and run on every reload, not only on `/rtp config` writes. This eliminates the "reload, then discover at next teleport" failure mode that motivated contract 7 of [ADR-037](../adr/ADR-037-harden-rtp-config-commands.md).

---

## 9. Tab-Complete Contract (Parse/Complete Parity)

`onCommand` and `onTabComplete` consume the **same** `ConfigParameterGrammar` definition per parameter. There is exactly one source of truth for:

- The set of valid `<file>` tokens at position 1 (the `Configs` registry, §4.5).
- The set of valid `<subfile>` tokens at position 2 for multi-config groups (filesystem enumeration of the group directory).
- The set of valid `<key>` tokens at the next position (the file's parameter registry).
- The set of valid `<value>` completions for a typed key (enum members for enum-typed keys; `add:` / `remove:` literal completions plus current-list-members for `remove:` on `List` keys; numeric placeholder hints with the declared range for numeric keys).

Any completion the player sees is a **parseable** command. The test `ConfigParameterGrammarParseCompleteParityTest` (per ADR-037 *Migration / Rollout*) asserts this by, for every registered parameter, completing one step deeper than the previous tab and round-tripping the completion through the parser; any completion that fails to parse fails the test.

Tab-complete may filter by the caller's permissions (a holder of `rtp.config.set.regions` who tabs at position 1 shall see only `regions`, `worlds`-if-also-granted, etc.). This is a UX nicety and does not change the parser; it only suppresses suggestions the caller could not act on. It is **not** a substitute for the §7 authorization step in the lifecycle.

---

## 10. Error Matrix

The matrix below is exhaustive over the §3 lifecycle stages cross-producted with the §5 reasonCodes. Read it as: *for input class X, the expected outcome is Y, with audit `outcome` Z*. Cells marked "n/a" are unreachable by construction (e.g. `PERSIST_IO` cannot occur before §3.6).

| Input class | Stage that detects | `reasonCode` | Audit `outcome` | Caller sees |
|---|---|---|---|---|
| Unknown file / group | Parse | `UNKNOWN_FILE` | `REJECTED` | `config.error.unknown_file` |
| Unknown key | Parse | `UNKNOWN_KEY` | `REJECTED` | `config.error.unknown_key` |
| `add:` on scalar / `key:value` on list | Parse | `WRONG_OPERATOR_FOR_TYPE` | `REJECTED` | `config.error.wrong_operator_for_type` |
| Malformed token (no `:` separator, empty value, etc.) | Parse | `MALFORMED_ARGUMENT` | `REJECTED` | `config.error.malformed_argument` |
| Reserved-token key | Parse | `RESERVED_TOKEN_COLLISION` | `REJECTED` | `config.error.reserved_token_collision` |
| `language.yml` via generic path | Parse | `USE_DEDICATED_COMMAND` | `REJECTED` | `config.error.use_dedicated_command` (names `LanguageCmd`) |
| Insufficient permission | Authorize | `NO_PERMISSION` | `REJECTED` | `config.error.no_permission` |
| Reload in progress | Authorize | `RELOAD_IN_PROGRESS` | `REJECTED` | `config.error.reload_in_progress` |
| Numeric out of range | Validate | `OUT_OF_RANGE` | `REJECTED` | `config.error.out_of_range` |
| Type mismatch | Validate | `WRONG_TYPE` | `REJECTED` | `config.error.wrong_type` |
| Unknown region name | Validate | `UNKNOWN_REGION` | `REJECTED` | `config.error.unknown_region` |
| Unknown world name | Validate | `UNKNOWN_WORLD` | `REJECTED` | `config.error.unknown_world` |
| Startup-only key | Validate | `IMMUTABLE_AT_RUNTIME` | `REJECTED` | `config.error.immutable_at_runtime` |
| Composite-invariant failure (e.g. Polygon `expand=true`) | Validate (composite) | `SCHEMA_INVARIANT` | `ROLLED_BACK` | `config.error.schema_invariant` |
| Temp write / fsync / rename failure | Persist | `PERSIST_IO` | `ROLLED_BACK` | `config.error.persist_io` |
| All validations pass, dry-run | Audit | n/a | `DRY_RUN_OK` | `config.dryRun.diff` rendering |
| All validations pass, live | Audit | n/a | `COMMITTED` | `config.success.committed` |

Any future `reasonCode` shall extend the table and the `messages.yml` map together; a code without a matching message key (or vice versa) is a spec-conformance bug caught by `ReqRtpF013ConfigMessageCoverageTest`.

---

## Appendix A: Current Gaps vs. Spec

The pre-hardening implementation (`rtp-core/.../commands/config/{ConfigCmd,SubConfigCmd,ViewSubConfigCmd}.java` plus `commands/config/list/`, as of the spec-authoring date) diverges from this spec in the ways enumerated below. Each row is a candidate for one of the ADR-037 contracts and shall be closed (and the row removed) as the contract ships. The appendix is informative, not normative; the body above is the contract.

| # | Gap | Spec section | ADR-037 contract |
|---|---|---|---|
| A1 | `SubConfigCmd#onCommand` inlines per-type parse-and-coerce with mixed `return false` / silent-fail branches; no central `ConfigParameterValidator`. | §3.3, §5 | 1 |
| A2 | No `ConfigTransaction`; mutations are applied to in-memory state directly and serialized lazily; rollback is not available. | §3.4, §3.5, §3.7 | 2 |
| A3 | No `--dry-run` flag; no preview path. | §2.1, §3.7 | 3 |
| A4 | Audit records are partial: some successful sets are not logged; some failures are silently rejected. | §6 | 4 |
| A5 | `BukkitBaseRTPCmd#msgInvalidCommand` / `msgBadParameter` carry hardcoded English fallbacks. | §5.3 | 5 |
| A6 | Permission grain is `rtp.config` (umbrella) only; no scoped `rtp.config.set.<section>` nodes. | §7 | 6 |
| A7 | `ReloadCmd` re-reads config without running the validator chain against the resulting state; shape-invariant violations surface at sample time. | §8 | 7 |
| A8 | `onTabComplete` and `onCommand` parse the argument grammar independently; some completions do not round-trip. | §9 | 8 |
| A9 | Writes are not atomic-rename: the parser serializes directly to the target file. A crash mid-write can leave the file truncated. | §4.2, §4.6 | 2 (persist substep) |
| A10 | `language.yml` cannot be mutated at runtime through any command surface; locale changes require a server restart. | §4.4 | 2 (LanguageCmd hardening) |
| A11 | Startup does not log/clean stray `*.tmp` siblings of registered config files (no temp files exist today; this row is "starting state for the new contract," not a regression). | §4.2 | 2 |
| A12 | The YAML substrate does not guarantee that block / inline comments survive a write-back round-trip. The `view` hover-text feature (§2.4) depends on comment preservation; until the substrate is upgraded (or replaced with an in-house parser), hover-text falls back to declared type + bounds. Comment preservation is also valuable independently of `/rtp config` for admins who hand-edit YAML and reload. | §2.4, §4.2 | — (separate ADR; tracked here for visibility) |

When this appendix is empty, the implementation has caught up with the spec and ADR-037 may be marked **Implemented** independently of any subsequent revisions to this document.
