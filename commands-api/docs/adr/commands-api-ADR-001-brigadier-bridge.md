# commands-api-ADR-001 — Brigadier Bridge via `commands-api` Adapter Layer

*(Renumbered from project-wide ADR-014 on 2026-05-05 when subproject ADRs were given per-directory numbering. Prior commits and historical references may still say "ADR-014".)*

**Status:** Accepted
**Date:** 2026-04-17

## Context

RTP's command tree is defined once in `commands-api` and executed on Bukkit-family platforms via a Bukkit command dispatcher. Fabric uses Minecraft's native Brigadier command system.

Duplicating the command structure in Brigadier terms across platforms forces any change to the command tree (new subcommand, renamed argument, permission change) to be applied in multiple places. This causes divergence. The `commands-api` module shall be the unified, platform-agnostic command framework and the single source of truth.

## Decision

A `BrigadierCommandAdapter` inside `commands-api` shall be provided to convert the `commands-api` tree into Brigadier nodes. Platform adapters (e.g., `rtp-fabric`) shall be thin registration shims that delegate to this adapter. The adapter shall carry a `compileOnly` dependency on Brigadier and shall not load on Bukkit platforms.

For implementation and code-level details, see [DESIGN.md — Brigadier Bridge](../../../docs/dev/DESIGN.md#brigadier-bridge-commands-api).

## Consequences

- **Positive:**
    - Single source of truth for the RTP command tree across all platforms.
    - Adding a new subcommand or changing permissions requires one edit in `commands-api`, automatically reflected on both Bukkit and Fabric.
    - `rtp-fabric`'s command registration code is reduced to a few lines.
    - Brigadier's client-side tab completion is available for free since the adapter produces correct argument nodes.

- **Negative / Trade-offs:**
    - `commands-api` carries a `compileOnly` dependency on Brigadier. This is acceptable because Brigadier is a stable, MIT-licensed library bundled with every Minecraft server; it does not add a runtime dependency for Bukkit users.
    - The adapter maps `commands-api` argument types (string, player, integer, etc.) to Brigadier argument types. This mapping is updated as `commands-api` evolves.
    - Brigadier's `CommandContext` and `commands-api`'s execution context are different types; the adapter bridges them without leaking Brigadier types into `commands-api` core interfaces.

## References

- `commands-api/src/main/` — command tree definition
- `rtp-fabric/src/main/java/.../fabric/commands/RTPCmdFabric.java` — registration shim
- [MULTI_PLATFORM_PLAN.md §Phase 3](../../../docs/dev/MULTI_PLATFORM_PLAN.md#phase-3-command-system-refinement)
- [ARCHITECTURE.md](../../../docs/dev/ARCHITECTURE.md) — dependency rule: `rtp-core` and `rtp-api` do not import platform-specific classes (ArchUnit-enforced)

---

## Addendum — 2026-05-06: Recursion contract, sibling chaining, and suggestion-relevance split

The original adapter walked sub-commands recursively but treated parameters as
leaves. On Brigadier (Fabric, planned Velocity) that is insufficient: Brigadier
needs an explicit graph edge for every reachable token, so anything past the
first parameter (`/rtp region <r> shape <s> vert <v>`, sibling parameters,
nested `CommandParameter.subParams`) was unreachable for tab-completion. The
Bukkit dispatcher is unaffected because it parses the wire format itself rather
than walking a node graph.

Source: `docs/dev/scratch/CHECKLIST-fabric-tabcompletion-audit.md` (delete on
merge).

### Recursion contract

After building each `RequiredArgumentBuilder` for parameter `paramName`,
`BrigadierCommandAdapter.attachChildren` shall additionally attach two kinds of
children to that argument node before returning it to the parent builder:

1. **Nested parameters** — every entry in
   `CommandParameter.subParams(paramName)` shall be attached as a further
   `RequiredArgumentBuilder` child of the current node, with the same
   suggestion / permission plumbing as a top-level parameter.
2. **Sibling parameters** — every other entry in the current `TreeCommand`'s
   `getParameterLookup()` shall be attached as a further child, mirroring
   Bukkit's free-token wire format (`/rtp region:R biome:B world:W` accepted in
   any order).

Both forms recurse through the shared `attachParameterChildren` helper.

### Cycle guard

Sibling-chaining is naturally cyclic (`region` → `world` → `region` → …). The
adapter carries a path-local `Set<String> paramsSeen` and excludes any
parameter whose name is already in the set. This bounds Brigadier-tree fanout
at `O(N!)` for `N` distinct parameter names on a single tree, which for the
production `RTPCmdFabricRoot` (4 top-level params + 5 nested under `region`)
stays well under the 64-node bound asserted by `BrigadierTreeShapeTest`.

### Non-goal: sub-commands after parameters

Sub-commands shall **not** be attached as children of an argument node.
Bukkit semantics treat sub-commands as positional literals at the head of
`args[]`, and the wire format reconstructed by `reconstructArgs` reflects that.
The adapter mirrors the Bukkit contract by attaching sub-commands only when
`paramsSeen.isEmpty()`. `BrigadierTreeShapeTest#subCommandNotReachableAfterParameter`
pins this.

### Suggestion-relevance split

`CommandParameter#isRelevant` doubles as both the execute-time validator and
the suggestion filter via `relevantValues(UUID)`. On Fabric pre-`fabric-permissions-api`
(`FabricRTPPlayer.hasPermission` falls back to op-level≥2), a non-op caller's
`isRelevant` returns `false` for every per-value permission check
(`rtp.regions.<r>`, `rtp.worlds.<w>`, `rtp.biome.<b>`), so suggestion lists go
empty even though the *node* is reachable through the permissive
`BrigadierBridgeContext`.

The addendum splits the two roles:

- A new protected hook `CommandParameter#isSuggestionRelevant(UUID, String)`,
  default-permissive (returns `true`), drives `relevantValues(UUID)`.
  Subclasses that want permission-filtered suggestions on Bukkit shall override
  it to delegate to `isRelevant`.
- Execute-time validation continues to flow through `isRelevant` via
  `TreeCommand.onCommand`'s parameter-value gate. This is an unchanged
  authorisation contract.
- `TreeCommand.expandRegexToken` shall filter through `isRelevant` directly
  (not `relevantValues`), preserving the security invariant pinned by
  `RegexParameterSecurityTest` (S-INJ-1 .. S-INJ-18). Mixing the suggestion
  hook into regex expansion would let a `reg:.*` token surface unauthorised
  values.

#### Information-disclosure trade-off

A non-op Fabric player will see region / world / biome names they cannot teleport
to. This is acceptable for the Step F window (smoke-testing, op-only servers)
and is revisited when `fabric-permissions-api` lands and `FabricRTPPlayer.hasPermission`
becomes reliable. Validation on execute is unaffected — typing an unauthorised
value still routes through `msgBadParameter`.

### Test coverage

- `commands-api/src/test/java/.../brigadier/BrigadierTreeShapeTest.java` —
  asserts: sub-command-with-param reachable; sibling chain reachable; nested
  `subParams` chain reachable; cycle guard rejects repeated parameter on any
  path; sub-command not attached after a parameter; total node count bounded.
- `rtp-core/src/test/java/.../commands/RegexParameterSecurityTest.java` —
  unchanged tests; still green after the `expandRegexToken` reroute through
  `isRelevant`.
- `commands-api/src/test/java/.../brigadier/ReqApiArch005BrigadierBridgeTest.java`
  — unchanged; verifies the Bukkit-parity wire-format reconstruction.

### Silent failure isolation (2026-05-06 follow-up)

Field report from a Fabric 1.21.11 op-only test server: even with the
recursion / sibling-chain / suggestion-relevance fixes above, the base
`/rtp` was rendering with no tab-completion in-game. Root cause: the
adapter walked the whole tree under a single try/catch-free traversal,
so any throw inside one subcommand's lookup, parameter setup, suggestion
provider, or `requires()` predicate aborted the entire `toBrigadier`
call — leaving the dispatcher with a partially-built (often empty) tree
and no log line to attribute the failure.

The adapter now isolates each "attach" site:

1. **Per-subcommand attach** — `attachChildren`'s sub-command loop wraps
   the body in `try/catch(Throwable)`. A throwing subcommand is logged
   at `WARNING` (with `parent` and `key`) and skipped; siblings + the
   base literal still register.
2. **Per-parameter attach** — same envelope around the top-level
   parameter loop in `attachChildren` and the recursive
   `attachParameterChildren` helper.
3. **Suggestion provider** — the lambda returned by `suggestionsFrom`
   wraps `param.relevantValues(callerId)` in `try/catch(Throwable)`.
   Brigadier silently swallows exceptions from suggestion futures, so
   without this guard a single throwing `values()` (e.g. NPE because
   `RTP.serverAccessor` is not yet bound on early init, or a Bukkit-only
   call sneaking into a platform-neutral override) deletes the
   suggestion list with no diagnostic. The catch logs at `WARNING` and
   returns the partial builder.
4. **`requires()` predicate** — `applyRequires` wraps the
   `BrigadierBridgeContext#permissionCheck` invocation in
   `try/catch(Throwable)`. A throwing predicate (e.g. a
   fabric-permissions-api lookup that NPEs on the integrated-server
   console source) now denies the node (matching Brigadier's
   "requires fail = node hidden" semantics) and logs, instead of
   propagating out of the dispatcher's tree-walk and corrupting later
   nodes.

The contract is: **a throw in any one subtree must not strip a sibling
subtree, must not strip the base literal, and must always leave a log
trail.** `BrigadierTreeShapeTest` adds three regressions:
`throwingSubcommandIsIsolated`, `throwingParameterIsIsolated`, and
`throwingSuggestionProviderIsIsolated`.

### Cross-references

- `docs/dev/MULTI_PLATFORM_PLAN.md` — Step G2 ticked when this addendum lands.
- `docs/dev/TRACEABILITY.md` — new row referencing
  `BrigadierTreeShapeTest`.
