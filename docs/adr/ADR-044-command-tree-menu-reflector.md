# ADR-044 — `menu-api` Module for Generalized Interactive Menus

**Status:** Proposed
**Date:** 2026-05-15
**Target release:** `3.0.0-beta.3` (surface + reflector already shipped under `rtp-api`/`rtp-core`); module extraction lands once Stage 5 of `CHECKLIST-generalized-menu.md` completes.
**Supersedes (in part):** the prior "Command-Tree Menu Reflector" draft of this ADR (which placed `CommandTreeMenuBuilder` permanently in `rtp-core`).

## Context

[ADR-035](ADR-035-interactive-menus-book-first.md) (Accepted, 2026-05-15 amendment) defines the platform-neutral menu primitive: `MenuModel` / `MenuPage` / `MenuLine` / `MenuFragment` / `MenuAction` plus `MenuRenderer` / `MenuTokenRegistry` / `MenuConsumerProfile` / `MenuOpenRequest` / `YamlCommentLookup`. The first concrete implementation has already shipped under the staged checklist at [`docs/dev/scratch/CHECKLIST-generalized-menu.md`](../dev/scratch/CHECKLIST-generalized-menu.md):

- **Stage 1** — sealed surface placed in `rtp-api/.../menu/` (10 types, no platform imports).
- **Stage 2** — `LocalMenuTokenRegistry`, `MenuRedeemSubcommand`, `CommandTreeMenuBuilder`, `ConfigMenuConsumerProfile` in `rtp-core/.../commands/menu/`.
- **Stage 3** — `/rtp menu` registration in `rtp-plugin`, `messages.yml` chrome keys, traceability rows.
- **Stage 4** — `BookMenuRenderer` in `rtp-paper-common`, `menu.renderer: [book]` config, ordered-preference renderer selection.
- **Stage 5** (in flight) — Spigot per-version renderers, Fabric `ChatMenuRenderer`, paginated chat renderer on Paper/Folia/Spigot, region-picker / `/rtpadmin` wizard consumers.

Two structural facts about the resulting subsystem now match the criteria that justified pulling `effects-api` and `commands-api` (and, per [ADR-046](ADR-046-maps-api-module.md), `maps-api`) into top-level sibling modules:

1. **The menu surface is a platform-neutral SPI consumed by every platform adapter and by future addons.** It is not RTP-domain logic. `MenuModel`/`MenuAction`/`MenuRenderer`/`MenuTokenRegistry` do not reference any `rtp-api` domain type (`RTPLocation`, `Region`, `RTP`, hook registries). Conversely, every platform adapter must ship at least one renderer (`BookMenuRenderer` on Paper/Folia, version-branched on Spigot, `ChatMenuRenderer` on Fabric), and Stage 5.5 anticipates addon-authored consumers.
2. **The Fabric path will require an obf/unobf carrier split.** Once Stage 5.2 lands `ChatMenuRenderer` on Fabric, the same Mojmap-unobf carrier shape that `effects-api-fabric-unobf` ([effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md)) and `maps-api-fabric-unobf` ([ADR-046](ADR-046-maps-api-module.md)) use — driven by [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — applies. Hosting that carrier inside `rtp-api` is structurally wrong: `rtp-api` explicitly avoids platform carriers.

A third fact about the dependency graph was clarified by the user on 2026-05-15:

3. **`rtp-api` is permitted to depend on sibling `*-api` modules.** This removes the historical objection (that pulling a `commands-api` dependency into `rtp-api` would leak the Brigadier surface to addons) and makes a `menu-api → commands-api` direct dependency expressible without forcing the menu code to live in `rtp-core`. The menu reflector is intrinsically coupled to `commands-api` (it walks `TreeCommand#getCommandLookup()` / `getParameterLookup()` and filters by `CommandsAPICommand#permission()`); the dependency edge belongs at the module boundary, not hidden inside `rtp-core`.

The remaining decision points — applicability semantics (hide vs. grey), hover-text resolution, per-consumer hooks, the live-tree SSOT property, mint-time vs. redeem-time hover — were settled by the prior draft of this ADR and the ADR-035 amendment. This revision does not relitigate them; it relocates the implementation.

## Decision

A new top-level module **`menu-api/`** (sibling to `effects-api/`, `commands-api/`, and the proposed `maps-api/`) shall host the generalized menu subsystem. The module is structured in three layers, mirroring [ADR-046](ADR-046-maps-api-module.md):

1. **Layer 1 — Surface** (`menu-api/.../menuapi/`): the immutable sealed surface currently sitting in `rtp-api/.../menu/` — `MenuAction` (sealed: `RunRtpCommand`, `ChangePage`, `SuggestInput`, `OpenExternalUrl`), `MenuFragment`, `MenuLine`, `MenuPage`, `MenuModel`, `MenuRenderer`, `MenuTokenRegistry`, `MenuConsumerProfile`, `MenuOpenRequest`, `YamlCommentLookup`. No platform imports. Depends only on `commands-api` (for the `CommandsAPICommand` / `CommandParameter` / `TreeCommand` types referenced by `MenuConsumerProfile` and the reflector contract).
2. **Layer 2 — Reflector + token registry** (`menu-api/.../menuapi/build/` and `.../menuapi/token/`): `CommandTreeMenuBuilder` (pure function over `(callerId, permissionPredicate, TreeCommand, pageIndex, MenuConsumerProfile) → MenuModel`) and `LocalMenuTokenRegistry` (in-memory CAS-consume implementation of `MenuTokenRegistry`). Both currently live in `rtp-core/.../commands/menu/` and move to `menu-api` unchanged.
3. **Layer 3 — Bindings**: renderer implementations. Bukkit-family renderers (`BookMenuRenderer`, paginated `ChatMenuRenderer`) stay in `rtp-paper/rtp-paper-common/` and Spigot per-version branches because they import Adventure / BungeeCord-Chat. Fabric renderers dispatch through a **`menu-api-fabric-unobf/`** Mojmap-built carrier paired with NM-typed obf carriers under `rtp-fabric/rtp-fabric-common/.../menu/`, following the dispatch contract in [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md) and [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md). The `menu-api-fabric-unobf/` module is created **only when Stage 5.2 confirms an NM-typed surface is unavoidable**; pure-Adventure renderers do not justify it.

### Module dependency layering

```
menu-api    commands-api    maps-api    effects-api
    \           |              |            /
     \          |              |           /
      +--------- rtp-api ---------+
                    |
                rtp-core
                    |
        rtp-{spigot,paper,folia,fabric}
                    |
                rtp-plugin / addons
```

- **`menu-api`** depends on `commands-api` (compile, `api` scope). Nothing else. No `rtp-api`, no `rtp-core`, no platform, no YAML library — `YamlCommentLookup` is a plain `(String key) → String` functional interface.
- **`rtp-api`** adds a compile dependency on `menu-api` (`api` scope, so consumers pick it up transitively). The existing direct dependency on `commands-api` stays, for IDE navigation clarity, even though it is now also reachable transitively.
- **`rtp-core`** retains the consumer-side glue that binds `YamlCommentLookup` to the in-house YAML substrate (`ConfigMenuConsumerProfile` and the `MenuRedeemSubcommand` wiring against the live `/rtp` root). These classes do not move; they continue to import `menu-api` types.
- **Platform renderers** (`BookMenuRenderer`, `ChatMenuRenderer`, future Fabric carriers) move to the platform modules they already would have lived in had `menu-api` existed at Stage 4 — Stage 4's `BookMenuRenderer` location in `rtp-paper-common` is already correct and does not relocate.

A small policy generalization, also satisfied by [ADR-046](ADR-046-maps-api-module.md): **any `*-api` sibling module may depend on any other `*-api` sibling module; none of them may depend on `rtp-api`, `rtp-core`, or any platform module.** This keeps the sibling tier a strict DAG and prevents `commands-api` (or future `*-api` siblings) from ever transitively pulling in `rtp-core`. Future `*-api` extractions cite this paragraph until it is promoted to its own project-wide policy ADR.

### Reflection algorithm (unchanged from the prior draft)

Given `(callerId: UUID, permissionCheckMethod: Predicate<String>, node: CommandsAPICommand, pageIndex: int, profile: MenuConsumerProfile)`:

1. **Applicability filter.** If `permissionCheckMethod.test(node.permission()) == false`, return `MenuModel.empty(localized("menu.empty"))`.
2. **Title.** `MenuModel.title = localized("menu.title", node.name())`.
3. **Children pass.** Iterate `getCommandLookup().entrySet()` in declaration order. Skip when the child's permission fails. Emit one `MenuFragment` per visible child with `text = child.name()`, `hover = child.description() ?? firstLine(child.help(...))`, `action = RunRtpCommand("menu", pathPrefix..., child.name())`.
4. **Parameters pass.** Iterate `getParameterLookup().entrySet()`. Skip when a parameter-level permission fails. Emit `text = paramName + ":"`, `hover = resolveParameterHover(node, paramName, parameter)`, `action = SuggestInput(profile.suggestPrefix(node, paramName))`.
5. **Empty page.** If neither pass emits a fragment, return a single `localized("menu.empty")` line with no action.
6. **Pagination.** The renderer paginates; the builder returns the logical page for `pageIndex` per ADR-035 + Stage 5.3.a's `MenuOpenRequest` plumbing.

The builder is **pure** with respect to its inputs against the live tree; the live-tree SSOT property from the ADR-035 amendment is preserved.

### Applicability semantics

Inaccessible nodes are **hidden**, not greyed out. Unchanged from the prior draft and from Stage 2's implementation. Rationale, alternatives, and the "hide-vs-disabled may be revisited per-consumer" caveat carry over.

### Hover text resolution

`resolveParameterHover(node, paramName, parameter)` returns the first non-empty of:

1. The consumer's `YamlCommentLookup.getComment(...)` (the `/rtp config` consumer dispatches `RtpYamlSection#getComment(canonicalPath(paramName))`, preserved by [ADR-042](ADR-042-yaml-comment-preservation-block-only.md)).
2. The localized `menu.hoverFallbackType` / `menu.hoverFallbackBounds` composition over `parameter.getClass().getSimpleName()` and exposed bounds metadata.
3. `null` — renderers omit the `HoverEvent` rather than draw an empty tooltip.

Hover is built at **mint time**, dispatch is **live**. Unchanged from the prior draft.

### Per-consumer hooks

The `MenuConsumerProfile` interface in `menu-api` (currently `rtp-api/.../menu/MenuConsumerProfile.java`) carries `suggestPrefix` + `commentLookup` + `defaultProfile()` and an optional `includeParameter` hook. `ConfigMenuConsumerProfile` (in `rtp-core`) binds the comment lookup to the live `Configs` registry and produces the `/rtp config <file> <key>:` prefix from `CONFIG_COMMAND_SPEC §2.4`. Future consumers (region picker, `/rtpadmin` wizards, addon-authored menus) ship their own profiles; no SPI extension is required.

### Requirements

Four new requirements introduced (to be authored in `docs/dev/REQUIREMENTS.md` during the module extraction stage — see *Migration / Rollout*):

| REQ | Statement (target wording) |
|-----|-----------------------------|
| `REQ-RTP-MENU-001` | A `MenuRenderer` shall throw `IllegalStateException` (S-006) when invoked before RTP core is loaded or with an offline viewer UUID for which no online resolver is available. |
| `REQ-RTP-MENU-002` | A `MenuTokenRegistry` implementation shall atomically consume a token (CAS or equivalent) so concurrent redeems for the same token observe exactly one success. |
| `REQ-RTP-MENU-003` | A `MenuRedeemSubcommand` shall reject a non-`RunRtpCommand` action with `messages.yml → menuInvalid` and a `WARNING` log entry (S-004 / S-007 / REQ-RTP-F-013). |
| `REQ-RTP-MENU-004` | A `CommandTreeMenuBuilder` shall hide any child or parameter for which the caller's permission predicate returns `false`. |

The existing `messages.yml` chrome keys (`menuInvalid`, `menuExpired`, `menuUnknownPlayer`, `menuHoverFallbackType`, `menuHoverFallbackBounds`) remain in `rtp-plugin`'s `messages.yml`; REQ-RTP-F-013 traceability already covers them.

### Safety inheritance

All four S-00x prohibitions inherited by the menu subsystem (S-004 via `MenuRedeemSubcommand` logging, S-005 via mint-time-only command-tree walk + redeem dispatching through the existing async pipeline, S-006 via `IllegalStateException` on premature use, S-007 via `messages.yml`-sourced chrome) are unchanged from the prior draft. The module move does not alter any safety boundary; it only relocates the classes.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep `CommandTreeMenuBuilder` permanently in `rtp-core` (the prior draft of this ADR) | Forces a hidden `commands-api ↔ rtp-core` reflector edge instead of the explicit `menu-api → commands-api` module boundary; obstructs the imminent Fabric carrier work (Stage 5.2) which has no natural home in `rtp-core`; addons that want to render their own menus would have to depend on all of `rtp-api` to reach the surface types. |
| Move only the surface (current `rtp-api/.../menu/` contents) and leave the reflector in `rtp-core` | Splits a single conceptual subsystem across two modules with no behavioral justification. The reflector is a pure function over `commands-api` types; it has no `rtp-api` dependency to anchor it. |
| Hand-authored layout file per consumer (`menus/config.yml` etc.) | Already rejected by the ADR-035 amendment: introduces a second SSOT; drifts whenever the command tree changes. Carried forward from the prior draft. |
| A parallel `MenuRegistry` consumers populate at startup | Same SSOT problem in a different package. Carried forward from the prior draft. |
| Snapshot the command tree at builder construction | Stale across runtime tree updates from the network-state layer. Carried forward from the prior draft. |
| Fold `menu-api` into `commands-api` | `commands-api` is a Brigadier bridge over typed command nodes; menu rendering is a presentation concern with its own per-platform carriers (Adventure book, BungeeCord-Chat, Fabric NM-typed packets). The two share neither a delivery path nor a per-viewer cadence. The same reasoning the `maps-api` ADR uses to keep maps out of `commands-api` applies here. |
| Fold `menu-api` into `effects-api` | `effects-api` dispatches discrete sensory events (sounds, particles) with no token registry, no command-tree reflection, and no Adventure `ClickEvent` plumbing. The Fabric obf/unobf carrier shape is the only thing the two share. |
| Grey-out (disabled fragment) inaccessible nodes | Forces a redeem-time predicate that duplicates the mint-time predicate. Carried forward from the prior draft; user direction (2026-05-15) confirms hide. |
| Resolve hover at redeem time | Adds DB / config I/O to the redeem hot path. Carried forward from the prior draft. |
| Use `commands-api` `onTabComplete` output as the menu source | Loses (child vs. parameter), (permission), (description) signal. Carried forward from the prior draft. |
| Expose the builder as `rtp-api` SPI to addons in this ADR | Premature; the SPI is now naturally `menu-api`-scoped. Addons will depend on `menu-api` directly once the module is extracted. |

## Consequences

- **Positive:**
  - One reflector + one token registry serve every present and future menu consumer (`/rtp config`, region picker, `/rtpadmin` wizards, addon-authored menus). Per-consumer work is a `MenuConsumerProfile` (≤ 30 lines).
  - Runtime command-tree updates (network-state changes, addon registration, reload) propagate automatically: the next menu open re-reflects the live tree (live-tree SSOT preserved).
  - `menu-api` and `commands-api` share an explicit module boundary; the Brigadier coupling is visible in the dependency graph rather than buried in `rtp-core`.
  - The Fabric `ChatMenuRenderer` (Stage 5.2) has a natural home (`menu-api-fabric-unobf/`) without dragging Fabric carriers into `rtp-api`.
  - Addons that want to author menus depend on `menu-api` alone, not the entire RTP-domain API.
  - Future `*-api` modules cite the *sibling DAG* policy in §*Module dependency layering* instead of re-deriving it.

- **Negative / Trade-offs:**
  - Module count grows by one (plus optionally `menu-api-fabric-unobf/`). Mitigated: the addition follows the established `effects-api` / `commands-api` / `maps-api` shape; no new build pattern is invented.
  - The migration must rewrite imports across `rtp-core`, `rtp-plugin`, `rtp-paper-common`, and tests. Bounded scope: roughly the file set currently under `rtp-api/.../menu/` (10 files) plus `rtp-core/.../commands/menu/` (4 files) plus `rtp-paper-common/.../menu/` (1 file) and their test mirrors.
  - ADR cross-references must be reconciled. ADR-035 was committed-modified before this revision; it remains the menu-surface ADR and is updated by a one-line back-reference rather than renumbered or moved. ADR-044's own slot in the global sequence is retained (the module-extraction ADR replaces the reflector-only draft); no `menu-api-ADR-NNN` re-sequencing is required for these two documents.
  - Stale hover text between mint and redeem under fast config edits. Carried forward from the prior draft.

## Migration / Rollout

This ADR ratifies the **module-extraction** step, not the initial surface ship. The implementation lives in `rtp-api`/`rtp-core` as Stages 1–4 of [`CHECKLIST-generalized-menu.md`](../dev/scratch/CHECKLIST-generalized-menu.md). The checklist's amended **Stage 6** captures the extraction work itself, gated on:

1. **Stage 5.3 (paginated chat renderers on Paper/Folia/Spigot) complete** — minimizes mid-stage import churn.
2. **Stage 5.2 go/no-go for the Fabric obf/unobf carrier confirmed** — drives whether `menu-api-fabric-unobf/` is created in the same extraction or deferred.
3. **D-005 proposal accepted** — module additions cross module boundaries by definition.

When triggered, Stage 6 performs the following in a single dedicated change (no behavioral edits mixed in):

- Create `menu-api/` with `src/main/java/io/github/dailystruggle/rtp/menuapi/...` and `src/test/java/...`. Add `menu-api/build.gradle` mirroring `commands-api/build.gradle`'s structure (no platform deps, `api` scope on `commands-api`).
- Move the 10 files currently under `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/menu/` into `menu-api/src/main/java/io/github/dailystruggle/rtp/menuapi/`. Move their test mirror.
- Move `LocalMenuTokenRegistry.java` and `CommandTreeMenuBuilder.java` from `rtp-core/.../commands/menu/` into `menu-api/.../menuapi/build/` and `.../menuapi/token/`. Leave `MenuRedeemSubcommand.java` and `ConfigMenuConsumerProfile.java` in `rtp-core` (they bind to the live `/rtp` root and the YAML substrate, both `rtp-core` concerns).
- Rewrite imports across `rtp-api`, `rtp-core`, `rtp-plugin`, `rtp-paper-common`, and tests.
- Add `menu-api` to `settings.gradle`; add `compileOnly`/`api` dependency on `menu-api` in `rtp-api/build.gradle` (and consequently transitively to all RTP consumers); add explicit `menu-api` dependency in `rtp-core/build.gradle` and `rtp-paper/rtp-paper-common/build.gradle` for IDE clarity.
- Add `REQ-RTP-MENU-001..004` rows to `docs/dev/REQUIREMENTS.md` and matching `TRACEABILITY.md` rows pointing at `MenuStageTwoTest` / `BookMenuRendererTest` (and any new Stage 5 renderer tests).
- Add a row to `docs/adr/README.md`'s *Subproject ADRs* table if `menu-api/docs/adr/` is created (the menu-api ADR sequence starts at `menu-api-ADR-001` only when the **first** menu-api-scoped decision after extraction is recorded; this ADR keeps its global `ADR-044` slot).
- Add `CHANGELOG.md` bullet under `[3.0.0-beta.3] - Unreleased ### Changed`: `menu surface moved to new \`menu-api\` sibling module; no API-level renames`.
- Run `.\gradlew build` (full multi-module) and cite the headline in the submit summary.

The user-visible delta from `v3.0.0-beta.1` is the new module path; no class or method renames cross the boundary. Existing addons that depended on `io.github.dailystruggle.rtp.api.menu.*` will require a recompile against the new `io.github.dailystruggle.rtp.menuapi.*` package — listed under `CHANGELOG.md` "Breaking (compile-only) Changes" for the same release.

## References

- [ADR-011](ADR-011-rtp-api-separate-module.md) — `rtp-api` layering; the 2026-05-15 clarification that sibling `*-api` modules may depend on each other is recorded here.
- [ADR-035](ADR-035-interactive-menus-book-first.md) — Interactive menus via written book. Defines the model, registry, and click-handling contract. This ADR is the module-placement decision for ADR-035's subjects.
- [ADR-042](ADR-042-yaml-comment-preservation-block-only.md) — Block-comment preservation. Source of truth for parameter hover text.
- [ADR-046](ADR-046-maps-api-module.md) — `maps-api` module. Structural precedent for the three-layer SPI shape and the sibling-DAG dependency policy used here.
- [effects-api-ADR-006](../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md) — obf/unobf carrier dispatch contract reused for Fabric `ChatMenuRenderer`.
- [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — Fabric carrier split rationale.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge; `/rtp menu` registration path.
- [CONFIG_COMMAND_SPEC §2.4](../dev/CONFIG_COMMAND_SPEC.md) — `view` sub-form; the `/rtp config` consumer mirrors its hover / click-suggest contract.
- [REQUIREMENTS.md §3](../dev/REQUIREMENTS.md) — Prohibitions; the menu subsystem inherits S-004, S-005, S-006, S-007.
- [TRACEABILITY.md](../dev/TRACEABILITY.md) — REQ-RTP-F-013 row covers menu chrome strings; REQ-RTP-MENU-001..004 rows added during Stage 6.
- [`docs/dev/scratch/CHECKLIST-generalized-menu.md`](../dev/scratch/CHECKLIST-generalized-menu.md) — multi-session implementation plan, including the Stage 6 module-extraction sub-plan ratified by this ADR.
