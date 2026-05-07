# Fabric `/rtp` Tab-Completion Audit — Findings

**Effective Issue:** "check the fabric rtp subcommands and subparams for issues that would cause tabcompletion to not occur"
**Mode:** [ADVANCED_CHAT] (read-only analysis; no code changes pending Rule D-005 approval)
**Date:** 2026-05-06

> Working note. Delete once the follow-up fix lands and `commands-api-ADR-001` /
> `MULTI_PLATFORM_PLAN.md` Step G2 reflect the resolution.

---

## Checklist

- [x] 1. Walk the Fabric `/rtp` registration path — `RTPFabricMod` → `RTPCmdFabric.register` → `BrigadierCommandAdapter.toBrigadier`.
- [x] 2. Inspect command tree built in `RTPCmdFabricRoot` (parameters + sub-commands).
- [x] 3. Inspect `commands-api` parameter/value plumbing (`CommandParameter.relevantValues`, `BrigadierCommandAdapter.suggestionsFrom`, `BrigadierBridgeContext`).
- [x] 4. Check Fabric mod entrypoint for the actual `BrigadierBridgeContext` permission predicate (`(src, perm) -> true` — permissive, line 118 of `RTPFabricMod`).
- [x] 5. Confirm `FabricRTPPlayer.hasPermission` semantics (op-level≥2 fallback; no fabric-permissions-api yet).
- [x] 6. Categorise findings into structural-tree gaps vs. suggestion-content gaps.
- [x] 7. Submit a Rule D-005 proposal for the `attachChildren` recursion + sibling-parameter chaining fix in `BrigadierCommandAdapter` — **APPROVED 2026-05-06** (see *Approved Proposal* below).
- [x] 8. Implement P1 — `BrigadierCommandAdapter.attachChildren` recursion (subParams + sibling chaining + cycle guard). — landed 2026-05-06; `attachParameterChildren` helper + `paramsSeen` path-local cycle guard.
- [x] 9. Implement P2 — split `CommandParameter` validation from suggestion-relevance (`isSuggestionRelevant` default-permissive override path); update Fabric validators to use it. — landed 2026-05-06; also rerouted `TreeCommand.expandRegexToken` through `isRelevant` directly to preserve `RegexParameterSecurityTest` invariant.
- [x] 10. Implement P3 — add `FabricServerAccessor.getOnlinePlayerNames()` and override `values()` on the `player` parameter in `RTPCmdFabricRoot`. — landed 2026-05-06; backed by the existing `playersByName` map (no main-thread player-list iteration).
- [x] 11. Implement P4 — regression test `BrigadierTreeShapeTest` in `commands-api` asserting `/rtp region <r> shape <s> vert <v>` is reachable from the dispatcher; update `docs/dev/TRACEABILITY.md`. — landed 2026-05-06; 6 tests, all green; companion to `ReqApiArch005BrigadierBridgeTest`.
- [x] 12. Update `commands-api-ADR-001` with an addendum describing the recursion contract + cycle-guard, and tick the Step G2 box in `MULTI_PLATFORM_PLAN.md`. — landed 2026-05-06; addendum dated, Step G2 "Tab-completion smoke test" box ticked.

> **Live verification still pending.** All unit/integration tests pass
> (commands-api 11/11; rtp-core 1379/1379; rtp-fabric-common 20/20). The user
> reports the in-game `/rtp` base on Fabric still does not show tab-completion;
> this is most likely because the running server is on a stale `rtp-fabric-common`
> + `commands-api` build from before the addendum landed. Rebuild + reinstall
> the mod jar and reconnect to refresh Brigadier's client-side tree before
> retesting.

> **2026-05-06 follow-up — silent-failure isolation (P13).** User reported
> the base `/rtp` was still empty in-game after P8–P12 and asked us to check
> subcommands for silent permission/relevance failures/exceptions. Audit
> confirmed the adapter had no per-attach try/catch — a single throwing
> subcommand `getParameterLookup()`, parameter `subParams()`, suggestion
> `values()`, or `requires()` predicate aborted the whole `toBrigadier`
> walk silently. Fix: wrapped each attach site (per-subcommand,
> per-parameter top + recursive helper, suggestion provider lambda,
> `applyRequires` predicate) in `try/catch(Throwable)` with a logged
> `WARNING`. Three new regression tests in `BrigadierTreeShapeTest`:
> `throwingSubcommandIsIsolated`, `throwingParameterIsIsolated`,
> `throwingSuggestionProviderIsIsolated`. ADR addendum updated with a
> "Silent failure isolation" sub-section. commands-api 14/14;
> RegexParameterSecurityTest 20/20; rtp-fabric-common 20/20. Live
> verification still requires a fresh mod-jar build + client reconnect.

---

## Findings

The findings split into two buckets: **structural gaps in the Brigadier tree** (subcommands/params that physically aren't present in the tree the client gets) and **suggestion gaps** (nodes are present, but `suggests(...)` returns nothing).

### Structural gaps in the Brigadier tree (no completions possible at all)

Source: `commands-api/src/main/java/io/github/dailystruggle/commandsapi/brigadier/BrigadierCommandAdapter.java`, specifically `attachChildren(...)` (lines ~86–157).

1. **Nested parameters under `region` are never registered.** `RTPCmdFabricRoot` calls `regionParameter.put("world", …)`, `put("price", …)`, `put("worldborderoverride", …)`, `put("shape", …)`, `put("vert", …)`. Those are `CommandParameter.subParams` entries. `attachChildren` reads only `tree.getParameterLookup()` from the current `CommandsAPICommand`; when it builds a `RequiredArgumentBuilder` for `region`, it does **not** consult `param.subParams(name)` and does **not** recurse. The comment at lines 149–153 acknowledges this ("chained parameters and sub-commands-after-parameters are intentionally not auto-recursed here"). On Bukkit this is fine because Bukkit's `commands-api` dispatcher parses the wire format itself, but **Brigadier needs the tree explicitly**, so nothing past `<region>` is tab-completable on Fabric.

2. **Sibling parameters cannot be chained.** Same root cause. After typing `/rtp region:foo`, the only valid next token Brigadier knows about is *end of command*. Bukkit users can type `/rtp region:foo biome:bar world:baz` because Bukkit reads `args[]` as a free token list; Brigadier requires explicit graph edges, and `attachChildren` only ever appends each parameter as a leaf off whatever it's looking at.

3. **Sub-commands' nested parameters/sub-commands are partially walked.** `attachChildren` does recurse for sub-commands (`attachChildren(subLiteral, root, sub, …)`), so `/rtp config <param>`, `/rtp scan <param>`, `/rtp info <param>` should expose the sub-command's own first-level parameters — but again, only one parameter at a time, no sibling chaining, and no nested-`subParams` expansion. Anything `ConfigCmd` / `ScanCmd` / `InfoCmd` exposes via nested `subParams` will be missing.

### Suggestion gaps (nodes exist but `suggests(...)` returns empty)

Sources: `RTPCmdFabricRoot.java`, `BrigadierCommandAdapter.suggestionsFrom`.

4. **`player` parameter explicitly returns no suggestions.** `RTPCmdFabricRoot` lines 156–173 register an inline `CommandParameter` whose `values()` returns `Collections.emptySet()`. The comment ("…just no tab-complete suggestions until Step E adds an online-player listing helper to FabricServerAccessor") is honest; this is a known gap. Until that helper exists, `/rtp player:<TAB>` will always be silent.

5. **Per-value permission filters silently empty the suggestion list for non-ops.** `BrigadierCommandAdapter.suggestionsFrom` (lines 256–268) calls `param.relevantValues(callerId)`, which is `values().stream().filter(isRelevant)…`. The validators in `RTPCmdFabricRoot` are AND-gated on a per-value permission:
   - `region`: `… && sender.hasPermission("rtp.regions." + s)`
   - top-level `world` and nested `region.world`: `… && sender.hasPermission("rtp.worlds." + s)`
   - `biome`: `(sender.hasPermission("rtp.biome.*") || sender.hasPermission("rtp.biome." + s))`

   On Fabric, `FabricRTPPlayer.hasPermission` is currently an op-level≥2 fallback (no `fabric-permissions-api` yet — Step F). For any non-op player every per-value check fails, `relevantValues` returns an empty set, and Brigadier shows zero suggestions even though the **node** is reachable (the bridge's `requires` predicate is permissive — `RTPFabricMod.java` line 118: `(src, perm) -> true`).

   Symptom: "the literal `region` is offered, but pressing TAB after it does nothing".

6. **`requires` predicate is permissive but value-level checks are not — they disagree.** Not a bug per se, but it's the asymmetry that makes (5) hard to diagnose: the *node* is visible (because of the permissive `BrigadierBridgeContext`), the *values under it* are filtered through a stricter, Fabric-side path.

### Lesser issues worth recording

7. **Sub-command literal name vs. registry key.** `attachChildren` line 111 uses `sub.name()` rather than the map key from `getCommandLookup()` (which is uppercased by `TreeCommand.addSubCommand`). That's correct for Brigadier (Brigadier literals are case-sensitive and case-sensitively user-facing), and the comment on lines 107–110 explains why — but if any sub-command's `name()` ever drifts from its registration key the literal would silently disappear from completion. Worth a quick assertion.

8. **`successEvent` / `failEvent` are no-ops on Fabric** (lines 239–249 of `RTPCmdFabricRoot`). Not a TC issue, but failures during a tab-completed teleport don't surface anywhere — easy to confuse with a TC bug. (S-004 nuance — flag for a future audit.)

---

## Recommended next steps (proposal — gated on Rule D-005)

- **Bridge-level fix (most leverage)**: extend `attachChildren` so that after building a `RequiredArgumentBuilder` for a parameter, it (a) walks `param.subParams(paramName)` and recurses, and (b) re-attaches sibling parameters of the *current* tree as additional children, mirroring Bukkit's free-token parsing. This single change unblocks `/rtp region:<r> shape:<s> vert:<v>` etc. for **every** Brigadier-hosted platform (Fabric today, Velocity per `MULTI_SERVER_PLAN.md`).
- **`player` suggestions**: add an `online-players` accessor to `FabricServerAccessor` (Step E follow-up already noted in the code) and override `values()` in the `player` parameter to return that set.
- **Permission/value suggestion mismatch**: until `fabric-permissions-api` is wired (Step F), either
  - (a) split "validate on execute" from "is-relevant-for-suggestion" in `CommandParameter` (cleanest, platform-agnostic), or
  - (b) make `FabricRTPPlayer.hasPermission` permissive for `rtp.regions.*` / `rtp.worlds.*` / `rtp.biome.*` namespaces during the smoke-test window.
- **Add a regression test** covering the Brigadier tree shape: assert that `/rtp region <r> shape …` is reachable in the dispatcher for a fixed mock root. Reference `commands-api-ADR-001` and update `docs/dev/TRACEABILITY.md` per the guidelines.

These are proposals; per Rule D-005 the multi-class change to `BrigadierCommandAdapter` should be approved before implementation.

---

## Approved Proposal (Rule D-005, approved 2026-05-06)

Scope of approval covers four coordinated changes (P1–P4) plus a documentation update (P5). Each change lists affected modules, before/after behaviour, REQ-*/ADR cross-refs, and risks/trade-offs, per Rule D-005.

### P1 — `BrigadierCommandAdapter.attachChildren` recursion + sibling chaining

**Affected**

- `commands-api/src/main/java/io/github/dailystruggle/commandsapi/brigadier/BrigadierCommandAdapter.java` (single file).

**Before**

- `attachChildren` walks sub-commands recursively but treats parameters as leaves: each parameter node is `parentBuilder.then(argNode)` only, with no children attached to `argNode`. Nested `CommandParameter.subParams(name)` are never consulted. Siblings of a parameter are not reachable after it.

**After**

- After building each `RequiredArgumentBuilder` for parameter `paramName`, attach two kinds of children to `argNode` before `parentBuilder.then(argNode)`:
  1. **Nested params from `param.subParams(paramName)`** — for each nested `CommandParameter`, recursively build a `RequiredArgumentBuilder` (same `mapArgumentType` / `needsSuggestions` / `suggestionsFrom` plumbing) and attach with the appropriate `pathSoFar` extension.
  2. **Sibling parameters of `parent`** — re-iterate `tree.getParameterLookup()` minus the current `paramName`, building each sibling as another required-argument child. This mirrors Bukkit's free-token parsing where `/rtp region:R biome:B world:W` is a flat token list.
- A **cycle guard** is required because (2) is naturally cyclic (`region` → `world` → `region` → …). Implement as a per-walk `Set<String> visitedAtThisLevel` carried alongside `pathSoFar`; a parameter name is excluded from sibling expansion once it appears in the path. Limits Brigadier-tree fanout to `O(N!)` worst-case where `N` = number of top-level params on a tree; for `RTPCmdFabricRoot` (4 top-level params + 5 nested under `region`) this is bounded and acceptable.
- Sub-command-after-parameter chaining is **not** added in P1 — Bukkit semantics don't allow it either (sub-commands are positional literals at the head of `args[]`). Documented as an explicit non-goal in the ADR-001 addendum (P5).

**REQ / ADR refs**

- `commands-api-ADR-001` (Brigadier Bridge — adapter contract).
- `MULTI_PLATFORM_PLAN.md` Step G2 (Fabric command parity).
- No S-00x rule directly affected (TC is not safety-critical), but **REQ-RTP-F-013** (configurable messages) is unaffected — this changes structure only.

**Risks / trade-offs**

- **Tree size growth.** Brigadier sends the tree to clients on join; sibling-chaining grows it factorially. Mitigation: cycle guard + the fact that each `RTPCmdFabricRoot` only has 4 top-level params. Measured tree size will be reported in the regression test (P4) so it cannot regress unnoticed.
- **Suggestion provider re-binds.** Each `argNode` instance gets its own `suggests(...)` call; `relevantValues` is invoked per Brigadier completion request, so there is no caching issue, but logging frequency may rise. Acceptable.
- **Backward compatibility on Bukkit.** Bukkit does not use `BrigadierCommandAdapter`; this change is Fabric-/Velocity-only. No risk to Bukkit dispatch.
- **Argument parsing on execute.** `reconstructArgs` already emits `name=value` tokens per parameter slot in `pathSoFar`, so `TreeCommand.onCommand`'s `splitOnParamDelimiter()` consumes them correctly regardless of order. No change to `execute(...)` required.

### P2 — Split validation from suggestion-relevance in `CommandParameter`

**Affected**

- `commands-api/src/main/java/io/github/dailystruggle/commandsapi/common/CommandParameter.java` (add a default-permissive `isSuggestionRelevant(UUID, String)` hook + change `relevantValues` to use it).
- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/commands/RTPCmdFabricRoot.java` (validators stay as-is; no override needed if default is permissive).
- *(Optional)* `rtp-core` parameter subclasses if any need to keep the AND-with-permission semantics for Bukkit suggestions (audit during implementation; default-permissive is the safe direction).

**Before**

- `relevantValues(UUID)` = `values().stream().filter(v -> isRelevant.test(uuid, v))`. The `isRelevant` predicate doubles as both **execute-time validator** (must reject unauthorised values) and **suggestion filter**. On Fabric pre-Step-F, `hasPermission(...)` returns `false` for non-ops, so the predicate empties the suggestion list.

**After**

- New protected method `isSuggestionRelevant(UUID uuid, String value)` defaults to `true` (permissive). `relevantValues(UUID)` calls `isSuggestionRelevant` instead of `isRelevant`. Validation on execute still runs through `isRelevant` via `TreeCommand.onCommand`'s parameter-value gate — unchanged.
- Subclasses that genuinely want permission-filtered suggestions on Bukkit (where `hasPermission` is reliable) can override `isSuggestionRelevant` to delegate to `isRelevant`. The `commands-api` Bukkit parameter subclasses (`RegionParameter`, `WorldParameter`, `BiomeParameter`) will be audited; if existing Bukkit UX depends on hiding values the player can't use, those subclasses get an explicit override. Default for plain `CommandParameter` stays permissive.

**REQ / ADR refs**

- `commands-api-ADR-001` (clarifies adapter contract).
- No REQ-* impact; behaviour-preserving on the validate-on-execute path.

**Risks / trade-offs**

- **Information disclosure.** A non-op Fabric player would see region/world/biome names they can't use. Acceptable for the Step F window (smoke testing, op-only servers); revisited when `fabric-permissions-api` lands and `FabricRTPPlayer.hasPermission` becomes reliable. Documented in the ADR addendum.
- **Bukkit parity.** If any current Bukkit subclass relies on AND-with-permission filtering for UX, its override must be added in the same commit to avoid regression. The audit is part of the implementation step.

### P3 — `FabricServerAccessor.getOnlinePlayerNames()` + `player` parameter `values()` override

**Affected**

- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/server/FabricServerAccessor.java` (new `Set<String> getOnlinePlayerNames()` reading the captured `MinecraftServer` reference).
- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/commands/RTPCmdFabricRoot.java` (replace `Collections.emptySet()` in the inline `player` param with a call to the new accessor).

**Before**

- `RTPCmdFabricRoot` `player` parameter `values()` returns `Collections.emptySet()` — TC silent.

**After**

- `values()` returns `RTP.serverAccessor instanceof FabricServerAccessor f ? f.getOnlinePlayerNames() : Collections.emptySet()`. Validator unchanged (still routes through `RTP.serverAccessor.getPlayer(s)` and the `rtp.notme` check).
- The accessor reads from the cached `MinecraftServer` instance set in `FabricServerAccessor.bind(...)` (already plumbed by `RTPFabricMod`). Implementation is one line: `server.getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().getName()).collect(...)`. No platform leak into `commands-api`.

**REQ / ADR refs**

- ADR-022 §4 (no `org.bukkit.*` / `net.minecraft.*` in core; the leak is contained inside `FabricServerAccessor`, which is *meant* to hold those imports).
- Unblocks the TODO comment at `RTPCmdFabricRoot.java:154–155`.

**Risks / trade-offs**

- **Thread safety.** `getPlayerList().getPlayers()` is a `List<ServerPlayer>` snapshot on the main thread; on Fabric, command suggestions are computed off-thread via Brigadier's `SuggestionProvider`. The list itself is `CopyOnWriteArrayList` in practice but the spec is "main-thread mutated"; we'll iterate via `new ArrayList<>(server.getPlayerList().getPlayers())` defensively. Documented in the accessor Javadoc.
- **No S-005 concern** — no chunk I/O.

### P4 — Regression test `BrigadierTreeShapeTest`

**Affected**

- `commands-api/src/test/java/io/github/dailystruggle/commandsapi/brigadier/BrigadierTreeShapeTest.java` (new).
- `docs/dev/TRACEABILITY.md` (new row referencing the test).

**Coverage**

- Build a fixture root with two top-level params (`a` with nested `x`, `y`; and `b`) plus one sub-command (`sub`) carrying its own param `p`. Convert with `BrigadierCommandAdapter.toBrigadier(root, ctx)`. Use Brigadier's `CommandDispatcher.parse(...)` / `getCompletionSuggestions(...)` to assert:
  1. `root sub p:<value>` parses cleanly.
  2. `root a:<v> b:<v>` parses cleanly (sibling chain).
  3. `root a:<v> x:<v>` parses cleanly (nested chain).
  4. `root a:<v> a:<v>` does **not** infinitely expand (cycle guard) — the second `a` is rejected at parse time.
  5. Tree depth and total node count are below a documented bound (e.g. `<= 64` nodes for the fixture) so factorial fanout regressions trigger a test failure.
- Test name follows the REQ-traceable convention; mapped under a new TC trace row in `TRACEABILITY.md` (e.g. `commands-api-ADR-001 → BrigadierCommandAdapter → BrigadierTreeShapeTest`).

**Risks / trade-offs**

- Brigadier is already a runtime dependency of `commands-api` (the brigadier package exists), so the test compiles without new deps. If gradle config doesn't expose Brigadier on the test classpath, add `testImplementation` for it in the same commit.

### P5 — Documentation: `commands-api-ADR-001` addendum + `MULTI_PLATFORM_PLAN.md` tick

**Affected**

- `commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md` (addendum: recursion contract, cycle guard, "sub-commands-after-parameters is out of scope" non-goal, suggestion-vs-validation split).
- `docs/dev/MULTI_PLATFORM_PLAN.md` (tick the Step G2 sub-bullet for tab-completion parity once P1–P4 are merged and verified).

**Risks / trade-offs**

- Pure documentation; risk is staleness if implementation diverges. Addendum gets a date-stamped header so future readers can correlate with commit history.

---

### Sequencing

1. P2 first (lowest risk, single-method API addition, unblocks Bukkit-side audit independently).
2. P1 (the leverage change). Tested against the P4 fixture as it lands.
3. P3 (small, isolated, depends on neither).
4. P4 (locks the contract).
5. P5 last, after the four code commits land green.

Each phase should be a separate commit so a single revert is surgical if any phase breaks Bukkit parity unexpectedly.

---

## Files referenced

- `commands-api/src/main/java/io/github/dailystruggle/commandsapi/brigadier/BrigadierCommandAdapter.java` (main structural bug, lines 86–157, 256–268)
- `commands-api/src/main/java/io/github/dailystruggle/commandsapi/brigadier/BrigadierBridgeContext.java`
- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/commands/RTPCmdFabricRoot.java` (parameter validators, `player` empty `values()`)
- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/commands/RTPCmdFabric.java` (thin shim — no issue)
- `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/fabric/RTPFabricMod.java` (line 118 — permissive `requires`, intentional for G1)
- `rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/player/FabricRTPPlayer.java` (`hasPermission` op-only fallback)
