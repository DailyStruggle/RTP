# PROPOSAL - Admin-panel "quick start" prefab setups

**Status:** Design v3 (2026-05-20). D-005 approved by user (2026-05-20). Revised again after user feedback: the `--commit` flag is dropped (the project's command system has no flag parser), the `lite-jar` prefab is renamed to `lightweight` and gains an opposite-axis counterpart `fast-paced` (seven bundled prefabs, not six). Prefab overlays **shall not** touch `backlogCacheCap` (pro-only knob; lite-assembly default of `0` already disables L3 there, and the runtime prefab path must remain pro-vs-lite-agnostic).

**Effective issue (user, 2026-05-18 23:00):**
> design some prefab setups for the admin panel to "quick start"

**Clarifications (user, same session):**
> prefab is primarily performance and region settings, for example a "multi-world" prefab would create a region for each world but a "low performance" prefab would lower the cache sizes and tick allocations and increase period, or a folia prefab could massively reduce the period and decrease kept cache to reduce number of concurrent regions

> that's fine and all but I dont think I want to make this public facing because we currently only save default.yml but I want a hardcoded set of prefabs

**Decisions log (chronological):**
- **v1 -> v2 (2026-05-18, user-revised):** Drop the on-disk prefab YAML format (`rtp-plugin/src/main/resources/prefabs/*.yml`) and the `<datafolder>/prefabs/` admin-extensibility hook. Prefabs become a sealed, hardcoded Java registry in `rtp-core`. Apply flow, panel surface, bak/rollback, and audit-log semantics are unchanged - only the *source* of the overlay changes from "parsed YAML resource" to "Java-constant `Prefab` record". This also drops `PrefabLoader` and the `<datafolder>/prefabs/` discovery code path.
- **v2 -> v3 (2026-05-20, user-revised, D-005 approval):** Three deltas.
  1. **Drop the `--commit` flag.** The commands-api does not support `--flag` parsing (parameters are positional / named-with-`:` only). Replace with a **confirmation menu**: `/rtp admin prefab apply <name>` (or the panel row) opens a generated book/chat menu titled `Confirm prefab: <displayName>` listing the per-file diff plus a `Confirm` row and a `Cancel` row. `Confirm` dispatches `RunRtpCommand({"admin","prefab","confirm","<id>","<token>"})` where `<token>` is a short-lived (~60 s) per-caller nonce stored in a `ConcurrentHashMap<UUID, PendingPrefabApply>` on the `PrefabCommand` instance. `Cancel` dispatches `OpenAdminPanel` (or the equivalent close action in chat mode). No flags anywhere; pure subcommand verbs.
  2. **Rename `lite-jar` -> `lightweight`.** Same overlay (mirror of `rtp-plugin/src/lite/resources/regions/default.yml`); user-facing name was too implementation-bound. ADR-024 tie-in unchanged.
  3. **Add `fast-paced`** as the opposite-axis counterpart to `lightweight`. Where `lightweight` shrinks footprint (`cacheCap: 25`, `activeChunkCap: 6`), `fast-paced` enlarges it for snappier-feeling teleports at the cost of more chunk activity: `regions.default.cacheCap: 100`, `activeChunkCap: 20`, `performance.period: 10` (more frequent pulses), `performance.syncAllottedTime: 40`. Distinct from `high-performance` (which targets dedicated hardware / high concurrent player counts); `fast-paced` is about responsiveness on a *normal* server. Total bundled prefabs: **seven**.
  4. **No prefab overlay touches `backlogCacheCap`.** It is a pro-vs-lite assembly-time knob (the lite assembly hardcodes it to `0` at `rtp-plugin/src/lite/resources/regions/default.yml` to disable L3). Runtime prefabs must remain assembly-agnostic, so the prefabs that originally set `backlogCacheCap` (`low-performance`, `high-performance`, `folia-tuned`, the renamed `lightweight`, and the new `fast-paced`) all drop that key from their overlays. The `lightweight` prefab is now strictly a regions/default overlay of `cacheCap: 25` + `activeChunkCap: 6` (the *pro-portable* subset of the lite default); the `backlogCacheCap: 0` line stays in the lite assembly and is not duplicated at runtime.
- **v3 -> v3.1 (2026-05-20, user-revised):** There is no top-level `admin` subcommand today - the existing top-level verbs are `config|docs|info|menu|parameters|reload|scan|test`, and admins reach the admin panel by running `/rtp menu` and clicking the Admin row (the `OpenAdminPanel` action landed by `PROPOSAL-admin-panel.md` v2). Two options were considered: (a) re-root the prefab subtree as a top-level `/rtp prefab ...`, or (b) introduce a new top-level `admin` `TreeCommand` whose bare form dispatches `OpenAdminPanel` (i.e. opens the same admin panel as the menu click) and whose `prefab` child is the subtree this proposal already specifies. **Option (b) is chosen** (user-suggested): "unless of course you want to wire that command to open the menu". `/rtp admin` becomes a discoverable shortcut to the admin panel (book on Paper, chat-paginated elsewhere); `/rtp admin prefab list|apply|confirm|rollback` is the prefab subtree unchanged from v3. Permission for the bare `/rtp admin` form is `rtp.menu.admin` (the same gate the `OpenAdminPanel` dispatch uses); the prefab subtree keeps its own `rtp.admin.prefab` gate. No flag parser is introduced; both verbs remain pure positional.

**Final design summary (read this first):**
- A prefab is a curated **overlay** of `performance.yml` + `regions/default.yml` (and, for `multi-world`, synthesised additional `regions/<world>.yml` files) applied on top of the current on-disk values. It is **not** a wholesale file replacement.
- A new `Setup` section appears at the top of the admin panel (above `Configuration`) with one row per shipped prefab. Each row dispatches `RunRtpCommand({"admin","prefab","apply","<name>"})` and uses the existing destructive-action hover-warning pattern.
- Prefabs live in **`rtp-core` Java code only** as a sealed registry (`PrefabRegistry`) of `Prefab` record instances. There is no on-disk prefab file format, no `<datafolder>/prefabs/` discovery path, and no admin-authored prefab extensibility in v1. The set of shipped prefabs is fixed at compile time and changes only via a code edit + release.
- Seven bundled prefabs ship in v1: `survival-default`, `low-performance`, `high-performance`, `folia-tuned`, `lightweight`, `fast-paced`, `multi-world`.
- `apply` semantics: `/rtp admin prefab apply <name>` opens a **confirmation menu** showing the per-file diff plus `Confirm` / `Cancel` rows. `Confirm` dispatches `/rtp admin prefab confirm <name> <token>` (token = short-lived per-caller nonce); the confirm arm writes `<file>.yml.bak.<timestamp>` siblings, atomically replaces, then triggers a reload through the existing `/rtp reload` path. No `--commit` flag anywhere.

---

## 1. Current state

Admins quick-start a server today by:

1. Reading `docs/admin/CORE_CONFIG.md`, `PERFORMANCE.md`, and `REGIONS.md`.
2. Hand-editing `performance.yml`, `config.yml`, and one or more files under `regions/`.
3. Restarting or `/rtp reload`-ing and iterating on TPS/MSPT.

This is a high-friction loop. The lite jar already encodes one "preset" (a different `regions/default.yml` that omits `backlogCacheCap` to disable L3, plus selective omissions documented at `rtp-plugin/src/lite/resources/regions/default.yml`). That precedent shows the project already has a *latent* notion of "preset overlay" - it just happens at jar-assembly time, not at runtime.

A Folia operator and a Spigot operator currently start from the same baseline defaults and have to discover, independently, that on Folia the `period` knob should drop (regions are per-region-thread, so a 20-tick period across many regions oversubscribes) and `cacheCap` should shrink (so total chunk tickets stay bounded). Nothing in the shipped config surfaces that delta.

The admin panel (`AdminPanelBuilder`, per `PROPOSAL-admin-panel.md` v2) has sections `Configuration`, `Diagnostics`, `Lifecycle`, `Browse` and is the natural host for an *entry point* into curated quick-starts.

## 2. Goals

1. Ship a small set of curated **performance + region** configurations addressable by name from one click in the admin panel.
2. Make the underlying file format additive and admin-extensible: a prefab is a YAML overlay on disk, not Java code; new prefabs are added by dropping a file, not by recompiling.
3. Apply semantics are **explicit and reversible**: dry-run by default, sibling `.yml.bak` backup on commit, single `/rtp reload` to take effect, no live in-memory hot-swap of region state mid-teleport.
4. The lite jar's existing region-default override becomes one of the shipped prefabs (`lite-jar`) so the assembly variant and the runtime prefab system describe the same delta in one place. See [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md).
5. Zero new platform imports, zero chunk I/O, S-005 trivially preserved (apply path runs on the command dispatch thread; reload follows the existing async-safe reload contract).
6. Locale-mirrored row labels and hover text per REQ-RTP-F-013 (TSV pipeline).

## 3. Proposed design

### 3.1 Prefab in-code representation

A prefab is a Java `record` in `rtp-core`, instantiated once in a sealed registry. No on-disk file, no resource lookup, no admin-authored extensibility in v1.

```java
// rtp-core: io/github/dailystruggle/rtp/common/commands/prefab/Prefab.java
public record Prefab(
        String id,                                  // canonical id, e.g. "low-performance"
        String displayKey,                          // MessagesKeys entry
        String hoverKey,                            // MessagesKeys entry
        String description,                         // English fallback, locale-mirrored via TSV
        Map<String,Object> performanceOverlay,      // sparse: only the keys this prefab sets
        Map<String,Map<String,Object>> regionOverlays, // regionId -> sparse overlay
        boolean expandPerWorld                      // only true for the multi-world prefab
) {}

// rtp-core: io/github/dailystruggle/rtp/common/commands/prefab/PrefabRegistry.java
public final class PrefabRegistry {
    private static final List<Prefab> PREFABS = List.of(
        SurvivalDefault.INSTANCE,
        LowPerformance.INSTANCE,
        HighPerformance.INSTANCE,
        FoliaTuned.INSTANCE,
        Lightweight.INSTANCE,
        FastPaced.INSTANCE,
        MultiWorld.INSTANCE
    );
    public static List<Prefab> list() { return PREFABS; }
    public static Optional<Prefab> byId(String id) { ... }
}
```

Each prefab is its own small final class holding the immutable overlay maps as `Map.of(...)` constants. The maps are *sparse* in the same sense the v1 YAML proposal was: any key absent from the overlay leaves the current on-disk value untouched on apply.

Rules:

- **Sparse overlays only.** A prefab declares only the keys it changes; everything else stays whatever the admin had.
- **`regionOverlays` is keyed by region id**, mirroring `regions/<name>.yml` on disk. A prefab may target an existing region (overlay merge) or define a new one (created from scratch, e.g. `multi-world` synthesising one per world). New-region overlays must carry the complete required shape (shape, vert, world); `PrefabApplier` validates this and refuses to write an incomplete region file.
- **Scope cap.** v1 prefabs touch `performance.yml` + `regions/<id>.yml` only. The `Prefab` record deliberately has no `config`/`safety`/`worlds`/`economy` overlay slots; adding one is a code edit (new field on the record + new applier branch), not a config-format extension. This matches the user's framing ("primarily performance and region settings") and avoids the "reserved-but-unused YAML section" footgun.
- **Versioning:** the per-target-file `version:` lines on `performance.yml` / `regions/<id>.yml` are preserved unchanged on apply. The prefab itself carries no version; changing a prefab's overlay is a code change tracked by git.

### 3.2 Bundled prefabs

Seven ship in v1. Each is one small final class in `rtp-core` under `io/github/dailystruggle/rtp/common/commands/prefab/builtin/`.

| id | Intent | Headline overrides |
|---|---|---|
| `survival-default` | Identity overlay - documents the current shipped defaults. Useful as a "reset to defaults" target. | All defaults (empty `performanceOverlay`, `regionOverlays = { "default" -> on-jar defaults }`). |
| `low-performance` | Underpowered hosts, shared-CPU VPSes. | `performance.period: 60`, `syncAllottedTime: 20`, `asyncAllottedTime: 30`, `minTPS: 19.5`, `loginCacheEnabled: false`; `regions.default.cacheCap: 10`, `activeChunkCap: 4`. |
| `high-performance` | Dedicated hardware, large player counts. | `performance.period: 10`, `syncAllottedTime: 50`, `asyncAllottedTime: 50`, `loginCacheEnabled: true`, `postTeleportQueueing: true`; `regions.default.cacheCap: 200`, `activeChunkCap: 40`. |
| `folia-tuned` | Folia regional scheduler; many small regions instead of one big tick budget. | `performance.period: 5` (regions pulse faster per-thread), `syncAllottedTime: 10`, `asyncAllottedTime: 25`, `minTPS: 19.0`; `regions.default.cacheCap: 15`, `activeChunkCap: 4` (cap concurrent region-owned chunk tickets). |
| `lightweight` | Shrinks region cache + chunk-ticket footprint for small servers; pro-portable subset of `rtp-plugin/src/lite/resources/regions/default.yml`. The lite assembly continues to ship `backlogCacheCap: 0` at assembly time; the runtime prefab does not duplicate that. | `regions.default.cacheCap: 25`, `activeChunkCap: 6`. `performance` unchanged. |
| `fast-paced` | Snappier-feeling teleports on a normal server (opposite axis to `lightweight`). | `performance.period: 10`, `syncAllottedTime: 40`; `regions.default.cacheCap: 100`, `activeChunkCap: 20`. |
| `multi-world` | One region per world on the server. | At apply time, the loader enumerates `Bukkit.getWorlds()` (or platform equivalent through the existing `RTPServerAccessor`) and synthesizes `regions.<worldName>` entries cloning the current `regions/default.yml` but with `world: "<worldName>"`. Existing per-world regions are left untouched (the merge is idempotent). |

The `multi-world` prefab is the only one that performs *runtime enumeration*; it cannot be expressed as a static overlay alone. It sets `expandPerWorld = true` in its `Prefab` record; `PrefabApplier` then calls a small (~30-line) `MultiWorldExpander` that reads `RTPServerAccessor.getWorlds()` and synthesises per-world region overlays cloning `regions/default.yml` with `world: "<worldName>"`. No platform imports.

### 3.3 Apply flow

```
/rtp admin prefab list
    -> prints id + display name + description for every prefab in
       PrefabRegistry.list() (hardcoded set; no datafolder discovery)

/rtp admin prefab apply <name>
    -> dry-run by default. Computes the merged target tree per file,
       emits a per-file unified-style diff to the caller, exits without
       writing. Prints a footer:
         "to commit: /rtp admin prefab apply <name> --commit"

/rtp admin prefab apply <name> --commit
    -> 1. For every target file in <prefab>.appliesTo, copy the current
          file to <file>.yml.bak.<timestamp> (timestamp is millis since
          epoch, suffix avoids clobbering a prior bak).
       2. Compute the merged YAML in memory, preserving comments where
          the existing YAML library supports it (current loader uses
          SnakeYAML w/ comment retention disabled - acceptable trade
          for v1; a comment-preserving path is a follow-up).
       3. Atomic write: write to <file>.yml.tmp, fsync, rename to
          <file>.yml.
       4. Trigger /rtp reload through the existing pipeline (which
          already handles regions vs perf-only invalidation).
       5. Audit-log the apply with caller id, prefab name, list of
          touched files, and bak-file paths (S-004: no silent
          discards).
```

Rollback: `/rtp admin prefab rollback <name>` restores the most recent `<file>.yml.bak.<timestamp>` for every file in that prefab's `appliesTo`, then `/rtp reload`. v1 keeps the last-3 bak files per file, deleting older ones; an admin who wants permanent backups can copy them aside.

### 3.4 Admin panel surface

`AdminPanelBuilder` grows one new section, inserted **above** `Configuration` (matching the user's framing of "quick start" - it is the first thing a fresh admin should see):

```
Admin panel
Click an option below. Destructive actions show a warning hover.
-- Setup (quick start) --
Apply prefab: Survival default
Apply prefab: Low performance
Apply prefab: High performance
Apply prefab: Folia tuned
Apply prefab: Lightweight
Apply prefab: Fast-paced
Apply prefab: Multi-world
-- Configuration --
Config editor
-- Diagnostics --
...
```

Each prefab row:
- **Action:** `RunRtpCommand({"admin","prefab","apply","<id>"})`. The `apply` arm opens a confirmation menu (per-file diff + `Confirm` / `Cancel` rows). The `Confirm` row dispatches `RunRtpCommand({"admin","prefab","confirm","<id>","<token>"})` with a short-lived per-caller nonce. No flags anywhere; the two-step gate is two subcommand verbs.
- **Hover:** the `description` field from the prefab's record, plus a `"Opens a confirmation menu. Confirming will overwrite <files>; backups will be written as .bak.<timestamp>."` warning.
- **Permission:** `rtp.admin.prefab` (new). Hidden when missing. The redeem arm permission-checks again per S-004.

The section ships with exactly the seven bundled prefab rows; admins cannot add their own without a code change + recompile. Seven rows plus the other panel sections overflow one book page, so pagination via the existing `ChangePage` mechanism kicks in (see §7).

A new `RunRtpCommand` is **not** added; the existing variant accepts arbitrary command arrays.

### 3.5 Files / module touch list

| Module | File | Change |
|---|---|---|
| `rtp-core` | `commands/admin/AdminCmd.java` (new, ~30 lines) | Top-level `TreeCommand` registered under verb `admin`. Bare form dispatches `OpenAdminPanel` (same surface as clicking the Admin row from `/rtp menu`); child `prefab` is `PrefabCommand`. Permission `rtp.menu.admin` (mirrors the `OpenAdminPanel` gate). |
| `rtp-core` | `commands/prefab/Prefab.java` (new) | Immutable record carrying `id`, locale keys, sparse `performanceOverlay`, `regionOverlays`, `expandPerWorld`. |
| `rtp-core` | `commands/prefab/PrefabRegistry.java` (new) | Sealed list of the seven bundled prefabs. `list()` + `byId(String)`. No file I/O. |
| `rtp-core` | `commands/prefab/builtin/*.java` (seven new, ~20-40 lines each) | One final class per prefab (`SurvivalDefault`, `LowPerformance`, `HighPerformance`, `FoliaTuned`, `Lightweight`, `FastPaced`, `MultiWorld`) holding `Map.of(...)` overlay constants and a `Prefab INSTANCE`. |
| `rtp-core` | `commands/prefab/PrefabCommand.java` (new) | `TreeCommand` registered under `admin prefab`; subcommands `list`, `apply` (opens confirmation menu), `confirm <id> <token>` (writes), `rollback`. Permission `rtp.admin.prefab`. Maintains a `ConcurrentHashMap<UUID, PendingPrefabApply>` for the per-caller nonce (~60 s TTL). |
| `rtp-core` | `commands/prefab/PrefabApplier.java` (new) | Merge + atomic write + bak rotation. Pure function from `(currentTrees, prefab)` to `(newTrees, diffPayload)` so it is unit-testable without touching disk. |
| `rtp-core` | `commands/prefab/MultiWorldExpander.java` (new, ~30 lines) | The one dynamic hook. Reads `RTPServerAccessor.getWorlds()`, synthesises per-world region overlays for the `multi-world` prefab. |
| `rtp-core` | `commands/menu/AdminPanelBuilder.java` | Insert the `Setup` section above `Configuration`. One row per `PrefabRegistry.list()` entry. Suppressed when caller lacks `rtp.admin.prefab`. |
| `rtp-api` | `configuration/enums/MessagesKeys.java` | Add `menuAdminPanelSectionSetup` plus 14 prefab row/hover keys (seven prefabs x two) plus confirmation-menu title/hint/confirm/cancel keys. |
| `rtp-plugin` | `messages.yml` baseline + TSV pipeline | Locale keys per [Locale Config TSV Pipeline](../../../.junie/AGENTS.md#locale-config-tsv-pipeline-translate-before-regenerating). |
| `rtp-core` (test) | `commands/prefab/PrefabApplierTest.java` (new) | Per-prefab merge correctness, idempotency (apply twice == apply once), bak rotation, dry-run produces no writes, multi-world expander. |
| `rtp-core` (test) | `commands/menu/AdminPanelBuilderTest.java` | Setup-section ordering, per-row permission hiding, empty-prefab-list section suppression, prefab-row action payload. |
| `rtp-plugin` (test) | `LocaleParityTest` | Inherits new keys via the TSV pipeline; no per-test change. |
| `CHANGELOG.md` | unreleased heading | One bullet describing the net delta vs the last released tag, per *CHANGELOG Hygiene*. |
| `docs/admin/` | `PREFABS.md` (new) | Admin-facing doc: how to apply / confirm / rollback and the seven bundled prefabs with their exact deltas. v1 explicitly does not document a custom-prefab authoring path (no on-disk format ships). |

No platform-adapter changes. No `rtp-api` interface changes beyond the locale-key enum.

### 3.6 ADR / REQ references

- **ADR-024** ([rtp-lite assembly variant](../adr/ADR-024-rtp-lite-assembly-variant.md)) - the `lightweight` prefab subsumes the **pro-portable** subset of the lite override (`cacheCap`, `activeChunkCap`). `backlogCacheCap` stays as a lite-assembly-only knob and is **not** mirrored at runtime; the assembly variant continues to ship the trimmed `regions/default.yml`.
- **ADR-028** ([L3 backlog cache](../adr/ADR-028-l3-backlog-cache.md)) - prefab overlays intentionally do **not** touch `backlogCacheCap`; it remains a pro-vs-lite assembly-time knob. The `lightweight` prefab is the pro-portable subset of the lite-assembly default; the `backlogCacheCap: 0` line stays in `rtp-plugin/src/lite/resources/regions/default.yml`.
- **ADR-035 / ADR-045** - the admin panel is the host surface; this proposal is purely additive within their charters.
- **REQ-RTP-F-013** - all user-visible strings TSV-mirrored.
- **REQ-RTP-S-004** - apply and rollback both audit-log; no silent-discard paths.
- **S-005** - apply runs on the command thread, no chunk I/O. The triggered reload uses the existing reload contract which is already S-005-compliant.
- **D-005** (project rule) - this proposal is the gate. No code lands until the user approves an issue to implement it.
- **MULTI_SERVER_PLAN** - intentionally out of scope. A `proxy-backend` prefab can be added once the network-mode probe lands; the file format already accommodates it.

## 4. Risks and trade-offs

- **Overwriting hand-tuned configs.** The primary risk. Mitigations: (a) `apply` opens a confirmation menu (per-file diff + `Confirm` / `Cancel` rows); writing only happens on the `confirm` subcommand with a fresh per-caller nonce; (b) `.bak.<timestamp>` siblings on every confirm; (c) the confirmation menu shows a real per-file diff, not a summary; (d) the hover text on every panel row carries the warning. The panel click never auto-commits.
- **Comment loss on merge.** The current YAML loader does not round-trip comments. Mitigation: the bak file preserves the admin's original comments verbatim, and the docs surface (`PREFABS.md`) calls this out. A follow-up using a comment-preserving emitter is plausible but not in v1 scope.
- **No admin-authored prefabs in v1.** Hardcoding the registry is intentional (user-requested) but means a server-specific tuning bundle cannot be saved as a prefab without a code change. Mitigation: the admin can still hand-edit `performance.yml` / `regions/*.yml` as today; prefabs are a *quick-start* shortcut, not a config-management substitute. If admin-authored prefabs are wanted later, the `Prefab` record can be reused unchanged and a new optional loader added behind a feature flag.
- **Stale state after apply.** If players are mid-teleport when `confirm` runs, the in-flight pipelines complete against the pre-apply settings (region settings are snapshotted at pipeline creation per existing semantics). New `/rtp` invocations see the new settings after the reload completes. This is the same staleness contract `/rtp reload` already has; no new race.
- **Multi-world auto-expansion drift.** If an admin applies `multi-world` and then adds a new world later, the new world has no region. v1 documents this as a known limitation; a follow-up `multi-world` re-apply is the workaround.
- **Locale TSV churn.** ~19 new baseline keys (seven prefab row+hover pairs = 14, one section divider, plus confirmation-menu title/hint/confirm-row/cancel-row). Standard TSV pipeline pass handles seeding; identity-mapped first-pass is acceptable per `TRANSLATION_GUIDE.md` section 8.
- **Sealed-action expansion?** None. The panel rows use the existing `RunRtpCommand` variant; no new `MenuAction` is needed.
- **`survival-default` semantics drift.** It is an identity overlay against the current shipped defaults. If a default ever changes in `performance.yml` / `regions/default.yml`, the prefab file must be updated in the same commit. Mitigation: a `PrefabIdentityOverlayTest` asserts `survival-default` is the identity merge against the on-jar defaults; CI catches drift.

## 5. Open questions

1. **Confirmation flow (locked in v3):** panel click -> `/rtp admin prefab apply <id>` -> opens a confirmation menu (per-file diff + `Confirm` / `Cancel` rows). `Confirm` dispatches `/rtp admin prefab confirm <id> <token>` where `<token>` is a ~60 s per-caller nonce. No `--commit` flag (the commands-api has no flag parser). `MenuAction.Confirm` is not introduced; the menu is rendered through the existing curated-page-builder + `RunRtpCommand` rows.
2. **Per-prefab section ordering (locked in v3):** grouped order: `survival-default`, `low-performance`, `high-performance`, `folia-tuned`, `lightweight`, `fast-paced`, `multi-world`.
3. **Rollback retention:** keep the last 3 baks per file, or every bak ever? Default: last 3, with a `prefab.bakRetention` knob in `performance.yml` for admins who want more.
4. **`survival-default` semantics:** as a hardcoded identity overlay it must stay in sync with the shipped defaults in `performance.yml` / `regions/default.yml`. Default: ship a `PrefabIdentityOverlayTest` that diffs `SurvivalDefault.INSTANCE` against the on-jar defaults and fails CI on drift. Acceptable, or do we drop `survival-default` entirely as redundant (an admin who wants defaults can just delete their files)? Default: keep it with the drift guard.

## 6. Implementation order (eight steps, only after user approval per D-005)

1. `Prefab` record + `PrefabRegistry` + the seven `builtin/*.java` classes. `docs/admin/PREFABS.md` documents the shipped seven and the exact knobs each one touches.
2. `PrefabApplier` (pure-function merge + diff), with unit tests against `PrefabRegistry.list()`.
3. `MultiWorldExpander` + its unit test using a fake `RTPServerAccessor`.
4. `PrefabCommand` (`list` / `apply` (opens confirmation menu) / `confirm <id> <token>` / `rollback`); audit-log on every path; nonce map with TTL eviction.
5. `AdminPanelBuilder` Setup section + `AdminPanelBuilderTest` rows.
6. `MessagesKeys` + baseline `messages.yml` adds; run the [Locale Config TSV Pipeline](../../../.junie/AGENTS.md#locale-config-tsv-pipeline-translate-before-regenerating).
7. `CHANGELOG.md` bullet (absolute-terms framing per *CHANGELOG Hygiene*).
8. `.\gradlew :rtp-plugin:test --tests "*LocaleParityTest*"` then `.\gradlew build` (mandatory *Final Full Build*).

Each step independently verifiable. Steps 1-3 are pure and testable without any platform module; step 5 is the only behavior-visible change for existing admins; step 4 is the only new command surface.

## 7. Visual sketch (book-page layout, admin panel post-change)

```
Admin panel
Click an option below. Destructive actions show a warning hover.
-- Setup (quick start) --
Survival default
Low performance
High performance
Folia tuned
Lightweight
Fast-paced
Multi-world
-- Configuration --
Config editor
-- Diagnostics --
Server info
Full diagnostics
Memory tracker snapshot
Scan control
-- Lifecycle --
Reload
-- Browse --
Browse all commands
Back
```

20 visible lines including title + hint + dividers + back. Exceeds the ~14-line book cap, so the panel paginates via the existing `ChangePage` mechanism: page 1 ends after `Fast-paced`, page 2 starts at `Multi-world`. No new pagination machinery is required - `BookMenuRenderer` already handles overflow.

---

## 8. Out of scope (deferred)

- A `claim-friendly` prefab that flips safety knobs (waits for `EXTERNAL_HOOKS.md` work).
- A `proxy-backend` prefab (waits for `MULTI_SERVER_PLAN.md` Phase 1+ network-mode probe).
- A comment-preserving YAML emitter (the on-disk bak file preserves the admin's original comments; the new file uses defaults' comments).
- An in-game prefab *editor* (this proposal is "apply curated prefabs", not "compose a new prefab in chat").
- Cross-platform prefab differentiation (e.g. `folia-tuned` only appearing on Folia). v1 shows all bundled prefabs everywhere and lets the admin pick; a per-platform `@prefab.platforms: [folia]` gate is a small follow-up if the panel becomes cluttered.
