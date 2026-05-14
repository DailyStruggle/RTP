# ADR-038 — `/rtpadmin` Setup Wizards (Book-Menu Driven, Config-Transaction Backed)

**Status:** Proposed
**Date:** 2026-05-13
**Target release:** `3.0.0-beta.4`

## Context

RTP's configuration surface is wide: regions, shapes (rectangle / ellipse / polygon per [ADR-034](ADR-034-memory-shape-catalog.md)), biome and material safety lists, performance knobs (cache caps, pipeline bounds, backlog per [ADR-028](ADR-028-l3-backlog-cache.md)), login reserve ([ADR-023](ADR-023-login-reserve-cache.md)), lite-jar variant ([ADR-024](ADR-024-rtp-lite-assembly-variant.md)), external-hook toggles ([ADR-026](ADR-026-external-hook-api-surface.md)), and — pending implementation of [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) — network-mode settings. The admin onboarding experience is `messages.yml` plus `/rtp config view` plus reading `docs/admin/`. New admins routinely:

- Pick parameter values that pass per-parameter validation but violate cross-parameter invariants (e.g. polygon with `expand=true`, region `centerRadius` greater than `radius`, sample biome lists that exclude every biome in the configured world).
- Set values, forget to `/rtp reload`, and conclude the change "didn't work."
- Stumble through region creation by editing YAML directly and reloading after each typo.
- Have no discoverable surface for the rarer knobs (per-shape params, anvil pre-filter, lite-jar feature toggles).

The two primitives required to fix this — an interactive UI surface free of inventory-desync exploits, and a hardened, transactional, auditable config-mutation surface — are landing in beta.4 as [ADR-035](ADR-035-interactive-menus-book-first.md) (book-first menus) and [ADR-037](ADR-037-harden-rtp-config-commands.md) (config command hardening). This ADR composes them into `/rtpadmin` setup wizards: a finite set of guided, page-driven flows that walk an admin through validated, reversible, audited multi-parameter setup tasks, with the wizard itself contributing **no** new validation, persistence, rollback, audit, or message-rendering logic.

This ADR is intentionally narrow: the wizards are a *driver* over ADR-035 and ADR-037. If a feature does not belong in the menu primitive or the config command primitive, it does not belong in this ADR either.

## Decision

Add a `/rtpadmin wizard <flow>` command surface in `rtp-core` that renders book-based menus (ADR-035) whose `ClickEvent.runCommand` redeems mutate config via `ConfigTransaction` (ADR-037). Ship a fixed initial catalog of flows in beta.4; admit further flows in later releases without ADR churn so long as they obey the contracts below.

### Module placement (Architecture Boundaries)

- **`rtp-core`** — wizard flow definitions, page-flow state machine, `WizardSession` registry, and the `/rtpadmin wizard` subcommand tree. No platform imports. The state machine is a pure function over `(WizardSession, ClickToken) → (NextPage, ConfigTransaction?)`.
- **Adapter layer (`rtp-paper`/`rtp-folia`/`rtp-spigot`/`rtp-fabric`)** — none. Rendering is delegated entirely to ADR-035's `MenuRenderer`; the wizard never constructs a `Book` or `Component` directly.
- **`rtp-api`** — no surface change. Wizard flows are not extensible by addons in beta.4 (deferred; see *What this ADR is not*).
- **`commands-api`** — no surface change. `/rtpadmin wizard <flow>` is an ordinary subcommand routed through the Brigadier bridge ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)).

### The seven contracts

1. **Composition only, no duplication.** The wizard layer contributes page flow, page rendering models, and session state. It does **not** re-implement validation, persistence, rollback, audit emission, message resolution, permission checks, or grammar parsing. Every such concern is delegated to ADR-037's `ConfigParameterValidator` / `ConfigTransaction` / `ConfigAuditRecord` / `ConfigParameterGrammar` and to ADR-035's `MenuRenderer` / token registry. A reviewer who finds the wizard layer parsing a parameter value or writing YAML directly should reject the change.

2. **One `ConfigTransaction` per wizard run.** A wizard session opens exactly one `ConfigTransaction` at page 1 and either `commit()`s it on the final page or `rollback()`s it on cancel / timeout / disconnect. Per-page mutations are appended to that transaction; the wizard's "back" affordance pops the last appended mutation (not the entire transaction). The single-transaction guarantee is what makes "step 4 of 6 fails, undo the first 3" cheap.

3. **Page state is server-authoritative.** The book's pages are a rendering of `WizardSession.currentPage`, not a source of truth. Click tokens carry only `(sessionId, pageId, choiceId)`; the actual mutation payload is resolved server-side from the session. This is the same security boundary ADR-035 already imposes for menu redeems and is restated here because a wizard accumulates more state per session than a single-shot menu.

4. **Preview before commit (composes ADR-037 contract 3).** The penultimate page of every flow renders the full pending diff via `ConfigTransaction#preview()` (the `--dry-run` primitive). Admin sees `path: oldValue → newValue` for every mutation about to land, then clicks "Apply" or "Cancel." No flow commits without an explicit final confirm click. Configurable via `messages.yml → wizard.preview.*` and `wizard.confirm.*` (REQ-RTP-F-013).

5. **Resumable across disconnect within a TTL.** `WizardSession` is keyed by `(playerUuid, flowId)` and persists in-memory with a TTL (default 10 minutes, config `commands.wizard.sessionTtlSeconds`). A reconnecting admin running `/rtpadmin wizard <flow>` resumes at the last completed page rather than restarting. On TTL expiry the underlying `ConfigTransaction` is rolled back and the session evicted. Sessions are **not** persisted to disk in beta.4 (no cross-restart resume); rationale in *Alternatives*.

6. **Audit through ADR-037's stream.** Each page commit emits the standard `ConfigAuditRecord` via `RTP.log` (ADR-037 contract 4) augmented with `wizardFlowId` and `wizardPageId`. Wizard-level events (`start`, `cancel`, `timeout`, `commit`, `rollback`) emit one additional record per event through the same formatter. No separate wizard log file (same reasoning as ADR-037's audit-file alternative rejection).

7. **Cross-server posture matches ADR-036 reservation tokens.** When ADR-036's network mode is active, wizards run **origin-server-only**: the book is opened on the backend the admin is currently on, mutations land in that backend's local config, and replication to other backends happens through whatever config-sync mechanism ADR-036 / a follow-up establishes — the wizard does not invent its own cross-server config replication. In beta.4 this means `/rtpadmin wizard` is functionally single-server even when the proxy is online, with a configurable `wizard.crossServerNotice` rendered when network mode is active. A future ADR may layer cross-server semantics on top.

### Initial flow catalog (beta.4)

Exactly four flows ship in beta.4. Each is implemented as a `WizardFlow` enum entry registering its page sequence and the validator subset it touches. No other flows ship until a follow-up adds them.

| `flowId` | Purpose | Pages | Notes |
|----------|---------|-------|-------|
| `region.create` | Create a new region: name, world, shape (rectangle/ellipse/polygon), shape params, biome/material safety lists, optional per-player visibility. | 6 | Polygon path enforces ADR-034's `expand=false` invariant at validator time, not at sample time. |
| `region.edit` | Edit an existing region: pick region → page through its parameter sections. | 3 + N (N = number of parameter sections touched) | Identical validator surface as `region.create`. |
| `performance.tune` | Walk an admin through cache caps (kept / unkept / backlog / login), pipeline bounds, anvil pre-filter toggle. | 4 | The single most-asked support topic; flow exists to short-circuit the "tune this for my server size" question. |
| `firstrun` | First-time setup: pick default world, create one region, set core safety lists, set messages locale. Auto-suggested on first plugin start when no regions exist. | 5 | Composes the other flows' validators; does not call them directly (avoids re-entrancy on a single `WizardSession`). |

Out of scope for beta.4 and explicitly deferred: `network.setup` (waits on ADR-036 implementation), `hooks.configure` (waits on a hook-config surface broad enough to justify a wizard; today's `EXTERNAL_HOOKS.md` toggles are too few), `migration.fromOldYaml` (one-off and better served by a non-interactive importer).

### `/rtpadmin wizard` command grammar

```
/rtpadmin wizard list                       → render available flowIds to the admin
/rtpadmin wizard start <flowId>             → open a session, render page 1
/rtpadmin wizard cancel                     → rollback the current session
/rtpadmin wizard resume                     → re-render current page (if a session exists)
/rtpadmin wizard <internal-token>           → menu-redeem path (ADR-035 token format)
```

The `<internal-token>` form is what `ClickEvent.runCommand` issues; it is opaque, single-use, TTL-bound, and player-bound (ADR-035 contract). It is not documented for admin typing.

### Permissions

- `rtp.admin.wizard` — start any wizard.
- `rtp.admin.wizard.<flowId>` — start that specific flow. Permission resolution prefers most-specific (ADR-037 contract 6).
- Per-page mutations also require the underlying `rtp.config.set.<section>` node from ADR-037; the wizard does **not** elevate. An admin without `rtp.config.set.regions` cannot complete `region.create` even with `rtp.admin.wizard.region.create`, and the failing page renders the standard `NO_PERMISSION` `reasonCode` message.

### Concrete affected classes (informational; final shape decided during implementation)

- `rtp-core/.../commands/wizard/` (new package):
  - `WizardCmd`, `WizardStartCmd`, `WizardCancelCmd`, `WizardResumeCmd`, `WizardListCmd`.
  - `WizardSession`, `WizardSessionRegistry` (TTL-evicting `ConcurrentHashMap`).
  - `WizardFlow` (enum), `WizardPage`, `WizardChoice` (POJO render models).
  - `flows/RegionCreateFlow`, `flows/RegionEditFlow`, `flows/PerformanceTuneFlow`, `flows/FirstRunFlow`.
- `rtp-core/.../commands/RtpAdminCmd.java` (existing) — registers the `wizard` subtree.
- `messages.yml` — new `wizard.<flowId>.*`, `wizard.preview.*`, `wizard.confirm.*`, `wizard.timeout`, `wizard.cancel`, `wizard.crossServerNotice`, `wizard.resume` keys. All REQ-RTP-F-013.
- Tests: `WizardSessionTtlRollbackTest`, `WizardRegionCreatePolygonInvariantTest`, `WizardPreviewMatchesCommitDiffTest`, `WizardBackPopsLastMutationTest`, `WizardPermissionScopeRespectedTest`, `WizardCrossServerNoticeWhenNetworkModeActiveTest`. Traceability rows added per [TRACEABILITY.md](../dev/TRACEABILITY.md).

### What this ADR is **not**

- Not new validation, persistence, rollback, audit, or message logic — those are ADR-037.
- Not a new rendering primitive — that is ADR-035.
- Not an addon-extensible flow registry. Third-party flows are deferred; `rtp-api` is unchanged in beta.4.
- Not a cross-server config replicator. Cross-server posture in beta.4 is "origin-only with a notice" per contract 7.
- Not a replacement for `/rtp config view` / `/rtp config set` for power users. Direct config commands remain first-class.
- Not a CLI-mode (non-menu) wizard. The fallback if `MenuRenderer` cannot open a book is the existing direct command surface, not a chat-driven question/answer loop.
- Not a `messages.yml` translation effort. New keys ship with reasonable English defaults; translation expansion continues at its own cadence.

### Cross-references to existing rules

- **S-004** — wizard audit composes ADR-037 contract 4; no silent failure path.
- **S-005** — wizard never touches the chunk path. Polygon vertex validation and shape invariants are pure functions in the validator chain.
- **S-006** — `/rtpadmin wizard start` before core load throws `IllegalStateException` (inherited from the underlying command and `ConfigTransaction` contracts).
- **S-007 / REQ-RTP-F-013** — every wizard-rendered string resolves through `messages.yml`.
- **D-005** — this ADR is the proposal; implementation waits on explicit acceptance plus prior acceptance of ADR-035 and ADR-037.
- **ADR-034** — shape invariants are enforced by ADR-037's validator chain, which the wizard invokes; the wizard never re-implements them.
- **ADR-035** — sole rendering and click-redemption primitive.
- **ADR-036** — defines the network-mode posture that contract 7 references.
- **ADR-037** — sole config-mutation primitive.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Build the wizard before hardening config commands | Order-inverts ADR-037; the wizard would have to reproduce validation, rollback, audit, and grammar in its own layer. Explicitly identified as the rejected ordering in ADR-037's *Alternatives*. |
| Build the wizard before adding the menu primitive (ADR-035) | Leaves only chat or inventory as the UI surface. Chat menus reintroduce the chat-suppression problem the prior discussion already rejected; inventory menus reintroduce the desync-exploit class. ADR-035's book-first decision is the prerequisite. |
| Inventory-GUI wizard (chest-based pages) | Reopens the inventory-desync class of bugs (item duplication, click prediction, drag/shift-click edge cases) that ADR-035 was specifically authored to avoid. Also requires per-platform implementation (Fabric has no `InventoryClickEvent` analog), conflicting with the architecture-boundary rule. |
| Chat-only wizard (`tellraw` pages, no book) | ADR-035 already analyzed this path and made `tellraw` the fallback rather than the primary. A wizard run is the worst case for chat rendering (multi-page, persistent across other server chatter); using book pages eliminates the chat-suppression sub-problem entirely. |
| One `ConfigTransaction` per page rather than per session | Defeats "step 4 fails, undo steps 1–3." Each page would have to manually undo every prior page on failure, duplicating the rollback logic ADR-037 already provides at the transaction level. |
| Persist `WizardSession` to disk for cross-restart resume | Disproportionate complexity for the value: admins who restart mid-wizard are uncommon, the underlying `ConfigTransaction` would have to be serialized (including its snapshot of pre-mutation state), and recovery semantics across a config schema migration are an open problem. The in-memory TTL session with explicit rollback on eviction is the simpler, safer choice for beta.4; persistence can be added later without breaking the contracts. |
| Expose `WizardFlow` registration on `rtp-api` so addons can ship flows | Premature ([ADR-011](ADR-011-rtp-api-separate-module.md) reasoning, same as ADR-037's rationale for keeping its primitives internal). The in-tree flow catalog has to settle before the SPI shape can stabilize. |
| Skip the preview page; commit incrementally per page | Removes the explicit "Apply" confirmation, which is the single highest-value UX affordance for an admin walking through a complex mutation. Also forces the partial-commit problem back into the wizard layer. The cost of one extra page is trivial. |
| Cross-server wizard that mutates remote backends directly | Out of scope for ADR-036's beta.4 phase. Would require defining a cross-server `ConfigTransaction` protocol that ADR-036 does not yet specify. Contract 7's "origin-only with notice" is the conservative posture until ADR-036 graduates. |
| CLI-style fallback when book rendering is unavailable | The existing `/rtp config set` surface — hardened by ADR-037 — already is the CLI fallback. Inventing a second non-book wizard surface (chat-driven Q&A) duplicates flows in a degraded medium and is the same `tellraw`-wizard trap rejected above. |
| Per-flow audit log files (`wizard-region-create.log` etc.) | Splits the audit surface for no benefit. ADR-037's structured `RTP.log` stream with `wizardFlowId` / `wizardPageId` augmentations is filterable downstream. |
| Make the wizard the default surface for region creation; deprecate `/rtp config set regions.*` | Direct command access is required for scripting, CI-driven server provisioning, and power-user workflows. The wizard is an *additional* surface, never a replacement. |

## Consequences

- **Positive:**
  - First-time admin experience improves dramatically: a guided flow that names every parameter, validates as it goes, previews the full diff, and applies atomically.
  - The wizard layer is small: page render models, a TTL session map, four `WizardFlow` enum entries, and a subcommand tree. All hard problems (validation, rollback, audit, message resolution, rendering, token security) are solved upstream.
  - Polygon and other shape invariants ([ADR-034](ADR-034-memory-shape-catalog.md)) get a discoverable surface; admins stop hitting them as sample-time surprises.
  - The preview page (contract 4) gives admins a single screenshot-able artifact of "what is about to change," which is invaluable for support tickets and post-mortems.
  - Composes cleanly with ADR-035 cross-server menu redeems for future expansion; the origin-only posture in beta.4 is a deliberate floor, not a ceiling.
  - `rtp test full` gains assertion surface for wizard flow completeness (every page has a `messages.yml` entry; every flow's preview matches its commit diff) at no additional infrastructure cost.

- **Negative / Trade-offs:**
  - `messages.yml` grows by roughly one page-text-key per page per flow plus the common wizard keys. With four flows of ~5 pages each, on the order of 25–30 new keys. Acceptable given REQ-RTP-F-013.
  - Wizard rendering depends on a book-capable platform; Fabric ships ADR-035's chat fallback only ([ADR-035 *Migration / Rollout*](ADR-035-interactive-menus-book-first.md)), so the wizard experience on Fabric in beta.4 is degraded (chat pages, no `change_page` affordance). Documented in `messages.yml → wizard.fabricNotice` and in `docs/admin/`.
  - In-memory sessions mean a server restart mid-wizard loses progress and rolls back the in-flight transaction. Acceptable trade-off versus persistence complexity (see *Alternatives*).
  - Cross-server posture is restrictive in beta.4; admins managing a proxy network must still apply wizard changes per-backend or use the underlying direct commands plus their own replication. Recorded in `wizard.crossServerNotice`.
  - The four-flow initial catalog will draw "why isn't there a wizard for X?" support requests. Mitigated by the explicit deferral list and the unchanged direct command surface.
  - One additional ADR enters the beta.4 dependency graph (ADR-035 → ADR-037 → ADR-038); none can ship out of order. Coordinated rollout is required.

## Migration / Rollout

- Beta.4 ships the wizard surface, the four initial flows, the new `messages.yml` keys, and the `commands.wizard.*` config keys. The `/rtpadmin wizard` subcommand is gated behind `rtp.admin.wizard`; servers that do not grant the node see no behavioral change.
- New config keys: `commands.wizard.sessionTtlSeconds` (default `600`), `commands.wizard.firstRunAutoSuggest` (default `true`, controls whether `firstrun` is auto-offered when no regions exist), `commands.wizard.crossServerNoticeOnNetworkMode` (default `true`).
- New `messages.yml` sections: `wizard.<flowId>.*`, `wizard.preview.*`, `wizard.confirm.*`, `wizard.timeout`, `wizard.cancel`, `wizard.resume`, `wizard.crossServerNotice`, `wizard.fabricNotice`. Reasonable English defaults; downstream translation expands at the usual cadence.
- No breaking changes. Direct `/rtp config …` commands continue to work identically. Existing permission nodes are unchanged; the new `rtp.admin.wizard.*` nodes are additive.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add rows for `WizardSessionTtlRollbackTest`, `WizardRegionCreatePolygonInvariantTest`, `WizardPreviewMatchesCommitDiffTest`, `WizardBackPopsLastMutationTest`, `WizardPermissionScopeRespectedTest`, `WizardCrossServerNoticeWhenNetworkModeActiveTest`. The polygon-invariant test ties to the relevant ADR-034 row; the preview/commit-diff test ties to ADR-037's dry-run row; the permission test ties to ADR-037's scoped-permission row.
- Changelog: no entry until implementation lands, per the CHANGELOG hygiene rule in `AGENTS.md`.
- Order of operations within beta.4: ADR-035 implementation → ADR-037 implementation → ADR-038 implementation. None may merge before its predecessors.

## References

- [ADR-011](ADR-011-rtp-api-separate-module.md) — `rtp-api` as a separately published addon interface. Governs the deliberate decision to keep `WizardFlow` registration internal in beta.4.
- [ADR-023](ADR-023-login-reserve-cache.md), [ADR-024](ADR-024-rtp-lite-assembly-variant.md), [ADR-026](ADR-026-external-hook-api-surface.md), [ADR-028](ADR-028-l3-backlog-cache.md) — surfaces the `performance.tune` and (deferred) `hooks.configure` flows touch via the validator chain.
- [ADR-034](ADR-034-memory-shape-catalog.md) — shape-specific invariants enforced through ADR-037's validator chain.
- [ADR-035](ADR-035-interactive-menus-book-first.md) — sole rendering and click-redemption primitive.
- [ADR-036](ADR-036-network-mode-multi-server-multi-proxy.md) — defines the network-mode posture contract 7 references.
- [ADR-037](ADR-037-harden-rtp-config-commands.md) — sole config-mutation primitive; this ADR is its named follow-up consumer.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge. `/rtpadmin wizard` is an ordinary subcommand from its perspective.
- [REQUIREMENTS.md §3](../dev/REQUIREMENTS.md) — Prohibitions. S-004, S-005, S-006, S-007 referenced above.
- [TRACEABILITY.md](../dev/TRACEABILITY.md) — REQ-* → class → test mapping; new rows enumerated in *Migration / Rollout*.
