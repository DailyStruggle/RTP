# ADR-037 — Harden RTP Config Commands (Prerequisite for `/rtpadmin` Setup Wizards)

**Status:** Accepted
**Date:** 2026-05-13
**Accepted on:** 2026-05-14
**Target release:** `3.0.0-beta.3` (retargeted from `3.0.0-beta.4` on acceptance; this hardening ships alongside proxy support and other beta.3 work)
**Companion documents:** [`docs/dev/CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) (target-state normative spec), [ADR-041](ADR-041-config-command-and-save-implementation.md) (implementation strategy: class layout, package, migration sequencing).

## Context

The `/rtpadmin` setup-wizards work (a follow-up ADR scoped to beta.4) will compose on top of the existing RTP **config commands** — primarily `ConfigCmd` → `SubConfigCmd` → `ViewSubConfigCmd` (under `rtp-core/.../commands/config/`), `ReloadCmd` / `SubReloadCmd`, and `ScanResetCmd`. A wizard is by definition a *driver* over those commands: it walks the admin through a series of validated, reversible parameter mutations, narrates failures, and leaves the on-disk YAML in a state that survives a reload and a restart. The wizard cannot do any of that if the underlying commands are themselves unreliable.

The current config command surface has accumulated behaviors that are tolerable when an experienced admin uses them directly but become liabilities the moment a wizard drives them programmatically:

- **Heterogeneous validation.** `SubConfigCmd#onCommand` (rtp-core, 245 lines) inlines parse-and-coerce logic per parameter type with mixed early-return / `return false` / silent-fail branches; failures do not consistently flow through `RTP.log` or through configurable messages, which puts the surface at risk against S-004 (no silently discarded failures) and REQ-RTP-S-007 (configurable "invalid command" messages).
- **Non-atomic writes.** Config mutations are applied to in-memory `FactoryValue` state and serialized lazily; a process crash mid-mutation or an exception between "validate" and "persist" leaves the on-disk YAML and the runtime state out of sync. A wizard that batches N changes needs all-or-nothing semantics.
- **No dry-run / preview.** There is no way to ask "what would this command do?" without doing it. Wizards need a non-mutating preview phase to render the next page of the menu.
- **Coarse permission grain.** Reload, set, and reset are governed by a small number of permission nodes; a wizard targeting a specific region or world would benefit from the ability to delegate a scoped subset (e.g. "edit regions/<name>.*") without granting full `rtp.config.*`.
- **Audit trail is partial.** Successful sets are not consistently logged; failed sets are sometimes logged, sometimes silently rejected. The wizard's "what did the admin just change?" view, and the project's S-004 auditing contract, both depend on a uniform record.
- **Reload semantics are best-effort.** `ReloadCmd` re-reads config but does not validate the *resulting* state against schema invariants (e.g. shape-specific param coherence per ADR-034). A wizard that calls reload at the end of its run needs to surface schema violations introduced earlier in the wizard, not at the next teleport attempt.
- **No transactional boundary for the wizard.** A wizard that fails on step 4 of 6 must be able to roll back steps 1–3 cleanly. Today the only available rollback is "ask the admin to re-set each parameter by hand."
- **Tab-complete and parser drift.** `onTabComplete` and `onCommand` parse the argument grammar independently; a wizard that builds command lines from completion data occasionally produces lines that complete but do not execute. This is tolerable for humans, fatal for a wizard's "next suggested command" affordance.

The interactive-menu primitive ([ADR-035](ADR-035-interactive-menus-book-first.md)) further raises the stakes: menu clicks dispatch through the command pipeline, so any unreliability in the config commands surfaces as flaky menu actions. Hardening the config commands is therefore a prerequisite for **both** ADR-035's eventual `/rtpadmin` consumer and the standalone wizard ADR.

This ADR scopes that hardening. The wizard itself is **not** designed here; a follow-up ADR will design `/rtpadmin` setup wizards on top of the contracts established below.

## Decision

Harden the config command surface against the requirements a wizard (and ADR-035 menu redeems) will place on it, in a single beta.3 change. The hardening is organized around eight contracts; every config command (existing and future) must satisfy all eight. No new wizard code lands in this ADR. The concrete class layout, package, and migration sequencing that delivers the contracts is documented in [ADR-041](ADR-041-config-command-and-save-implementation.md); the observable user-facing semantics are documented in [`CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md). Where the spec and this ADR appear to disagree, the spec wins; this ADR is the decision, the spec is the contract.

### Module placement (Architecture Boundaries)

- **`rtp-core`** — all hardening lands here. The config commands already live in `rtp-core/.../commands/config/` and `commands/reload/`; the changes are internal to those packages plus a small new `commands/config/internal/` package for the transaction / preview / audit primitives. No platform imports introduced.
- **`commands-api`** — no surface change. The Brigadier bridge ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) carries the new arguments unchanged because they remain ordinary subcommand parameters.
- **`rtp-api`** — no surface change in this ADR. The wizard ADR will decide whether any of these primitives are promoted to the public API surface.
- **Platform adapters** — unaffected. Bukkit-family `BukkitBaseRTPCmd` already routes failures through `RTP.log(Level.WARNING, …)` per the project's existing REQ-RTP-S-004 auditing pattern; the hardening makes more of the surface emit through that same path.

### The eight contracts

1. **Validated input, uniformly.** Every config command path performs full schema validation **before** any state mutation. Validation is centralized in a `ConfigParameterValidator` (per parameter type: numeric range, enum, list-of-enum, region-name, world-name, shape param, etc.). Validation failures throw a typed `ConfigValidationFailure(parameterPath, attemptedValue, reason)`; no `return false`, no `null`, no silent branch. The failure carries a stable `reasonCode` (e.g. `OUT_OF_RANGE`, `UNKNOWN_REGION`, `WRONG_TYPE`, `IMMUTABLE_AT_RUNTIME`, `SCHEMA_INVARIANT`) suitable for keying a `messages.yml` entry.

2. **Atomic writes (transactional `applyAndPersist`).** Each config command runs inside a `ConfigTransaction`. The transaction owns:
   - A snapshot of every `FactoryValue` it intends to touch (captured before mutation).
   - The ordered list of mutations.
   - A `commit()` that (a) re-validates the post-mutation in-memory state against the schema, (b) writes the affected YAML files atomically (write to `<file>.tmp`, `fsync`, rename — the standard atomic-rename pattern), (c) on any failure restores the snapshots and discards the temp files.
   - A `rollback()` callable from any failure path, including exceptions in user-supplied schema invariants.

   Single-command invocations open and close a transaction implicitly; a wizard or batch caller can open one explicitly via the internal API and append multiple mutations before `commit()`. The transaction is the rollback primitive the wizard ADR will compose on; this ADR does **not** expose it on `rtp-api` yet.

3. **Dry-run / preview.** Every config-mutating command accepts a trailing `--dry-run` flag (config key `commands.config.dryRunFlag` for the literal token, default `--dry-run`, REQ-RTP-F-013). With the flag set the command runs validation and computes the would-be diff (`{ path, oldValue, newValue }[]`) but never enters `commit()`. The diff is returned through the same success / failure path the wizard will read; for direct admin use it is rendered via `messages.yml → config.dryRun.*` strings.

4. **Uniform audit log.** Every config command — successful or failed, dry-run or live — emits one `RTP.log(Level.INFO, …)` record on success and `RTP.log(Level.WARNING, …)` record on failure, with a stable structured payload: `actor`, `command`, `parameterPath`, `oldValue`, `newValue`, `reasonCode` (failure only), `transactionId`, `dryRun`. The payload is rendered through a single formatter so log scraping (and the `rtp test full` audit pass per the existing test surface) sees a consistent shape. Satisfies S-004 explicitly; the wizard later reuses the same records for its "recent changes" view.

5. **Configurable failure messages (REQ-RTP-F-013 / REQ-RTP-S-007).** Every `reasonCode` maps to a key under `messages.yml → config.error.<reasonCode>`. The legacy "invalid command" / "bad parameter" hardcoded fallbacks in `BukkitBaseRTPCmd#msgInvalidCommand` / `msgBadParameter` are removed in favor of resolving through the same map. Missing keys log a `WARNING` (per the existing REQ-RTP-F-013 enforcement pattern) but the command still completes with a safe default message rather than `null`.

6. **Scoped permissions.** The permission grammar is extended (additively, no breaks) to admit a parameter-path suffix: `rtp.config.set.<topLevelSection>` (e.g. `rtp.config.set.regions`, `rtp.config.set.performance`). The existing `rtp.config.set` continues to grant all. `SubConfigCmd#permission()` resolves the most-specific node that matches the parameter path; a wizard or addon can mint a scoped permission for a region admin without granting full config access. Permission rejection routes through the same audit + configurable-message path as validation failure (`reasonCode = NO_PERMISSION`).

7. **Schema-checked reload.** `ReloadCmd` runs the parsed-but-not-applied config through the same `ConfigParameterValidator` chain before swapping it in. Any failure aborts the reload with the previous state intact and emits the standard audit record. Today's "reload then discover at next teleport" failure mode is eliminated. Shape-specific invariants from [ADR-034](ADR-034-memory-shape-catalog.md) (e.g. `Polygon` rejects `expand=true`, polygon vertex validation, `Rectangle` half-extent positivity) are part of the validator chain.

8. **Single grammar for parse and complete.** `onCommand` and `onTabComplete` consume the same `ConfigParameterGrammar` definition per parameter, so any completion the player sees is a parseable command. The grammar is derived from the parameter's `FactoryValue` type and any associated `enum class` / range bounds; no hand-written tab-complete branches diverge from the parser. This contract is what makes the wizard's "next suggested click" affordance reliable.

### Concrete affected classes (informational; final shape decided during implementation per the existing module conventions)

- `rtp-core/.../commands/config/SubConfigCmd.java` — refactored to delegate validate/preview/commit to `ConfigTransaction`; the 245-line `onCommand` body collapses to grammar parse + validator dispatch + transaction handling.
- `rtp-core/.../commands/config/ViewSubConfigCmd.java` — gains the structured-audit emission for views (read-only; `Level.INFO`, no transaction).
- `rtp-core/.../commands/config/internal/` (new package): `ConfigTransaction`, `ConfigParameterValidator`, `ConfigParameterGrammar`, `ConfigAuditRecord`, `ConfigValidationFailure`.
- `rtp-core/.../commands/reload/ReloadCmd.java` / `SubReloadCmd.java` — wired through the validator before swap.
- `rtp-plugin/.../commands/BukkitBaseRTPCmd.java` — `msgInvalidCommand` / `msgBadParameter` route through the new `config.error.*` keys; the existing REQ-RTP-S-004 `RTP.log` audit emission remains.
- `messages.yml` — new `config.error.<reasonCode>` and `config.dryRun.*` keys; legacy hardcoded fallbacks removed.
- Tests: `ReqRtpS004ConfigCommandAuditTest`, `ConfigTransactionAtomicRollbackTest`, `ConfigDryRunDiffTest`, `ReloadCmdSchemaValidationTest`, `ConfigPermissionScopeTest`, `ConfigParameterGrammarParseCompleteParityTest`. Traceability rows ([TRACEABILITY.md](../dev/TRACEABILITY.md)) added for each.

### What this ADR is **not**

- Not a `/rtpadmin` wizard design. The wizard is a separate ADR that will consume `ConfigTransaction`, `ConfigParameterGrammar`, and the audit stream defined here.
- Not a config schema redesign. The on-disk YAML shape is unchanged; only the command surface that mutates it is hardened.
- Not a permission-system rewrite. The scoping in contract (6) is additive and falls back to existing nodes when a scoped node is absent.
- Not a `messages.yml` translation effort. New keys are added under `config.error.*` and `config.dryRun.*`; the translation expansion ([CHANGELOG](../../CHANGELOG.md) under beta.2) continues at its own cadence.
- Not a public-API change. `rtp-api` is untouched in beta.3. Promotion of any of these primitives to `rtp-api` for addon use is a wizard-ADR-or-later decision.

### Cross-references to existing rules

- **S-004** (no silently discarded teleport failures) — generalized to "no silently discarded *command* failures" by uniform audit emission. The existing teleport-pipeline guarantee is unchanged.
- **S-005** (no chunk loading on the main thread) — preserved trivially: config commands do not touch the chunk path. Schema validators are pure functions over in-memory state.
- **S-006** (require-by-contract API entry points) — `ConfigTransaction#commit()` called before core load throws `IllegalStateException` rather than silently no-opping.
- **S-007 / REQ-RTP-F-013** — fully satisfied by contracts (5) and the legacy-fallback removal.
- **D-005** (propose before implementation) — this ADR is the proposal; implementation waits on explicit acceptance.
- **ADR-026** (external hook API surface) — if a third-party plugin observes config mutations via reflection today, the audit stream gives them a documentable hook surface; whether to expose it lives in the wizard ADR.
- **ADR-034** (memory shape catalog) — shape-specific invariants (Polygon `expand=false`, vertex validity, Rectangle / Ellipse extents) plug into the new `ConfigParameterValidator` chain rather than being re-validated at sample time.
- **ADR-035** (interactive menus, book-first) — menu redeems dispatch through the command pipeline; any `MenuAction.RunRtpCommand` that maps to a config mutation inherits all eight contracts. The wizard ADR consumes both.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Skip hardening and build the wizard directly on the existing commands | The wizard would have to reproduce validation, rollback, audit, and grammar plumbing in its own layer to compensate for the surface's gaps. That duplicates logic across two surfaces, leaves direct-admin command use unhardened, and guarantees drift between "what the wizard accepts" and "what the command accepts." Hardening once at the command layer benefits both audiences. |
| Harden inside the wizard layer only (validator + transaction in `commands/wizard/`, not in `commands/config/`) | Same drift problem in reverse. Direct admin `/rtp config set …` invocations remain unhardened, S-004 / S-007 violations persist on the non-wizard path, and ADR-035 menu redeems pointing at config commands inherit the unhardened surface. |
| Expose the new primitives (`ConfigTransaction`, `ConfigParameterValidator`) on `rtp-api` in beta.3 | Premature per [ADR-011](ADR-011-rtp-api-separate-module.md): the interface needs an in-tree consumer (the wizard ADR) to exercise it before it stabilizes. An early SPI commits us to a shape we have not yet pressure-tested. |
| Implement transactions as "best-effort: log on rollback failure but proceed" | Defeats the all-or-nothing semantic the wizard requires. If `commit()` partial-fails and we proceed, the on-disk YAML and runtime state can diverge silently — the exact failure mode this ADR exists to remove. |
| Replace `messages.yml` keys with hardcoded English fallbacks "for the wizard's clarity" | Direct violation of REQ-RTP-F-013. The wizard is not exempt from the configurability rule; the wizard's renderer is responsible for substituting its own templates around the same configurable strings. |
| Use the existing teleport-pipeline `FailTypes` enum for config reason codes | Conflates teleport-stage failures (chunk null, biome mismatch, claim deny) with command-stage failures (out-of-range parameter, unknown region name). The two stream into different audit consumers and different `messages.yml` sections; sharing the enum produces ambiguous traceability rows. |
| Permission scoping by world rather than by config section | Misaligned with the on-disk YAML shape: regions, biomes, performance, safety, and messages are sections; worlds are values within those sections. Per-section scoping maps 1:1 onto file paths and is the lowest-friction extension of the existing permission nodes. |
| One-shot reload validation (no transaction) | Acceptable for reload-only, but the wizard requires the same primitive for multi-mutation flows. Implementing the transaction once and reusing it for reload (contract 7) is cheaper than maintaining two near-duplicate validation paths. |
| Audit via a new file (`config-audit.log`) instead of `RTP.log` | Splits the audit surface. The existing S-004 contract already routes the project's audit stream through `RTP.log` and `SendMessage` interceptors (per `AGENTS.md → Logging & Feedback`); adding a parallel file fragments tooling (`rtp test full`, downstream log scrapers, the planned metrics axis [METRICS_PLAN.md](../dev/METRICS_PLAN.md)). Structured payload through `RTP.log` is the existing pattern. |
| Defer schema-checked reload (contract 7) to a follow-up | Reload is the single most likely first thing a wizard calls after a batch commit; an unvalidated reload undermines the transactional guarantee at the moment the wizard surrenders control back to the admin. Cheap to include in the same change. |

## Consequences

- **Positive:**
  - Direct `/rtp config …` admin use becomes substantially more predictable: every failure is named, configurable, and audited; every successful mutation is durable and reversible at the transaction level.
  - The wizard ADR can be small. It composes `ConfigTransaction` + `ConfigParameterGrammar` + the audit stream + ADR-035's menu primitive and adds only the page-flow / state-machine logic. None of validation, persistence, rollback, audit, or message rendering belongs in the wizard layer.
  - ADR-035 menu redeems that target config commands inherit the hardened surface for free; cross-server menu redeems against config commands (ADR-035 *Cross-server menus*) get the same atomicity story end-to-end (atomic CAS on the menu token, atomic commit on the config transaction).
  - REQ-RTP-F-013 coverage of the config command surface goes from partial to total; one fewer place where a hardcoded English string can slip in.
  - The shape-specific invariants introduced in [ADR-034](ADR-034-memory-shape-catalog.md) (and any future ADR-NNN that defines a new schema constraint) have a single registration point — the validator chain — rather than being checked at sample time and surprising the admin hours later.
  - `rtp test full` gains a clean assertion surface (audit-record shape, transaction commit/rollback pairing) that does not depend on string-matching log output.

- **Negative / Trade-offs:**
  - One concentrated refactor of `SubConfigCmd` (currently 435 lines, with the 245-line `onCommand` body). The risk is regression in obscure parameter paths; mitigated by the parser/completer-parity test (`ConfigParameterGrammarParseCompleteParityTest`) and the existing config-set test surface (`TestConfigSetCmd`-driven coverage).
  - The atomic-rename pattern depends on the YAML files living on a filesystem that honors rename atomicity (every supported deployment target — POSIX servers, Windows NTFS — does, but exotic networked filesystems may not). Documented in the implementation note; not a new risk versus the project's existing H2/SQLite persistence story ([ADR-002](ADR-002-h2-sqlite-over-flat-file-cache.md)) which makes the same assumption.
  - `messages.yml` grows by roughly one entry per `reasonCode` plus the dry-run keys; translation footprint increases proportionally. Acceptable given the REQ-RTP-F-013 mandate.
  - Permission grain extension is additive but introduces a small documentation burden on the admin side (`docs/admin/` will need a permissions table update during implementation; not part of this ADR).
  - The audit log is more verbose. Every successful config view emits an `INFO` record; high-frequency scripted `rtp config view` use will produce log volume. Mitigated by the existing log-level config — admins who do not want command-level audit can raise the threshold; the project's audit story still routes through `WARNING` for failures.
  - Once the wizard ADR lands and consumes these primitives, removing or renaming any of them becomes a breaking change for the wizard (even though they are not on `rtp-api`). The wizard ADR will record its own assumptions; this ADR records that the primitives are deliberately internal-stable for beta.3.

## Migration / Rollout

- Beta.3 ships the eight contracts and the supporting tests; the `/rtpadmin` setup wizard does **not** ship in beta.3 unless a separate wizard ADR is accepted and implemented in the same window. The contracts are independently valuable for direct admin use. Migration sequencing within beta.3 is fixed by [ADR-041](ADR-041-config-command-and-save-implementation.md) *Migration sequencing*.
- New config keys: `commands.config.dryRunFlag` (default `--dry-run`); new `messages.yml → config.error.<reasonCode>` and `messages.yml → config.dryRun.*` sections. Defaults preserve existing English text where present; new `reasonCode` entries get reasonable English defaults that downstream translators expand at the usual cadence.
- No breaking changes to existing command grammar. `--dry-run` is additive; the existing forms continue to commit-on-success. Existing permission nodes continue to grant all; scoped nodes are additive.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add rows for `ReqRtpS004ConfigCommandAuditTest`, `ConfigTransactionAtomicRollbackTest`, `ConfigDryRunDiffTest`, `ReloadCmdSchemaValidationTest`, `ConfigPermissionScopeTest`, `ConfigParameterGrammarParseCompleteParityTest`. The first ties to REQ-RTP-S-004; the dry-run, message, and permission tests tie to REQ-RTP-F-013 / REQ-RTP-S-007; the schema-validation reload test ties to the relevant shape-invariant rows under ADR-034.
- Changelog: no entry until implementation lands, per the CHANGELOG hygiene rule in `AGENTS.md`.

## References

- [ADR-011](ADR-011-rtp-api-separate-module.md) — `rtp-api` as a separately published addon interface. Governs the deliberate decision to keep the new primitives off `rtp-api` in beta.3.
- [ADR-041](ADR-041-config-command-and-save-implementation.md) — Implementation strategy for the eight contracts. Class layout, package boundaries, and the ordered beta.3 migration sequence.
- [`CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) — Target-state normative spec. Observable user-facing semantics, error matrix, save-mechanics contract.
- [ADR-020](ADR-020-language-bootstrap-and-locale-aware-configparser.md) — Locale-aware ConfigParser. The hardening composes on top of the existing parser locale story; no changes to bootstrap.
- [ADR-026](ADR-026-external-hook-api-surface.md) — External hook API surface. The audit stream is a candidate future hook; deferred.
- [ADR-034](ADR-034-memory-shape-catalog.md) — Memory shape catalog. Shape-specific schema invariants plug into the new validator chain.
- [ADR-035](ADR-035-interactive-menus-book-first.md) — Interactive menus (book-first). Menu redeems that target config commands inherit all eight contracts; the eventual `/rtpadmin` wizard composes this ADR's primitives with ADR-035's menu primitive.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge. Carries the unchanged subcommand grammar; the `--dry-run` flag is a normal trailing argument from its perspective.
- [REQUIREMENTS.md section 3](../dev/REQUIREMENTS.md) — Prohibitions. S-004, S-005, S-006, S-007 all referenced above.
- [TRACEABILITY.md](../dev/TRACEABILITY.md) — REQ-* → class → test mapping; new rows enumerated in *Migration / Rollout*.
- Code surveyed: `rtp-core/.../commands/config/{ConfigCmd,SubConfigCmd,ViewSubConfigCmd}.java`, `rtp-core/.../commands/reload/{ReloadCmd,SubReloadCmd}.java`, `rtp-plugin/.../commands/BukkitBaseRTPCmd.java`.
