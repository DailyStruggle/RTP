# rtp-fabric-ADR-011 - Effective-permission enumeration on Fabric (LuckPerms-Fabric primary, registry-probe fallback)

- **Status:** Accepted (2026-05-22; revised same day to broaden scope beyond `rtp.effect.*` per consumer audit; implemented same day)
- **Scope:** `rtp-fabric` (`rtp-fabric-common`, `rtp-fabric-common-unobf`, `rtp-fabric-v26_1_R1`) and `FabricServerAccessor`. No `rtp-core` / `rtp-api` changes.
- **Supersedes / refines:** none. Closes the last remaining Step F sub-item in [`docs/dev/MULTI_PLATFORM_PLAN.md`](../../../docs/dev/MULTI_PLATFORM_PLAN.md).
- **Related:** [effects-api-ADR-003](../../../effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md) (platform split that exposed the empty-set stub on Fabric), [effects-api-ADR-005](../../../effects-api/docs/adr/effects-api-ADR-005-effects-yml-config-and-translations.md) (`buildEffects(prefix, Collection<String>)` seam), [effects-api-ADR-006](../../../effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md), `rtp-fabric-ADR-002` (Fabric platform in scope), `rtp-fabric-ADR-007` (Mojmap name decoupling), [ADR-023](../../../docs/adr/ADR-023-login-reserve-cache.md) (join-time `ParsePermissions.hasPerm("rtp.onevent.", ...)`), [ADR-043](../../../docs/adr/ADR-043-personal-queue-permission-semantics.md) (`rtp.personalqueue` as op-only direct check, intentionally bypassing `getEffectivePermissions()`), `CHANGELOG.md` entry "On-event teleports granted to operators by default (Paper + LuckPerms)" which is the precedent for routing `rtp.onevent.*` through `getEffectivePermissions()` rather than `hasPermission()`.

## Context

`RTPCommandSender#getEffectivePermissions()` (declared in `rtp-api`) returns the set of `node` strings the sender has effectively been *granted* (Bukkit semantics: every `PermissionAttachmentInfo` whose `getValue() == true`, lowercased). The first draft of this ADR scoped the enumeration problem to the effects subsystem only. **A consumer audit performed during ADR review widened the scope.** The actual consumer surface is:

| Consumer | Prefix scanned | Namespace shape | Behaviour on Fabric today (empty set) |
|---|---|---|---|
| `EffectFactory.buildEffects(prefix, union)` via `FabricEffectsHandler.java:159` | `rtp.effect.<id>[.params]` | **Closed** - registry-backed by `EffectFactory.registeredNames()` | Effects subsystem inert: permission-granted effects (e.g. donor-rank `rtp.effect.particle.heart`) never fire. |
| `ParsePermissions.hasPerm(sender, "rtp.onevent.", "firstjoin"/"join")` via `FabricOnEventTeleports.java:90-91` | `rtp.onevent.<event>` | **Closed** - 6 events declared in `plugin.yml` (`join`, `firstjoin`, `respawn`, `changeworld`, `move`, `teleport`) | **On-event teleports broken on Fabric**: a non-op player granted `rtp.onevent.firstjoin` via a permissions mod gets no RTP on first join. This is the most user-visible regression. |
| `ParsePermissions.getInt(sender, "rtp.delay.")` via `PlaceholderProvider.java:100` and platform `RTPCommandSender#getDelay()` | `rtp.delay.<n>` | **Open-ended integer tail** (no upper bound declared anywhere) | Per-player delay overrides via permissions are silently ignored on Fabric; admins must use `config.yml` defaults. |
| `ParsePermissions.getInt(sender, "rtp.cooldown.")` via `PlaceholderProvider.java:133` and platform `RTPCommandSender#getCooldown()` | `rtp.cooldown.<n>` | **Open-ended integer tail** | Same as `rtp.delay.<n>`: cooldown overrides via perms are invisible on Fabric. |

The bullet that motivated routing on-event teleports through `getEffectivePermissions()` is documented in `CHANGELOG.md` under "On-event teleports granted to operators by default (Paper + LuckPerms)": LuckPerms (Paper) treats nodes whose `plugin.yml` `default` is `op` as auto-granted for ops, which made `hasPermission("rtp.onevent.firstjoin")` return TRUE for every op even when the admin intent was opt-in. The fix was to scan the *explicitly granted* set instead - precisely what `getEffectivePermissions()` returns on Bukkit. On Fabric, that fix's correctness depends on the platform actually populating the set.

On Bukkit/Paper/Folia, `Player#getEffectivePermissions()` is a server-provided enumeration API populated from `plugin.yml` defaults, op-implies-all, attachments, and any permissions plugin (LuckPerms, PermissionsEx, etc.) - all in O(1). On Fabric there is no equivalent. `fabric-permissions-api` (already a dependency, see Step F) is a **check-only** SPI: `Permissions.check(source, node, defaultLevel)` and `Permissions.getPermissionValue(uuid, node)`. The SPI's maintainers have explicitly scoped enumeration as out of contract (the API can be implemented by mods that genuinely have no enumerable node model - e.g. expression-based perms).

The *only* permissions foundation presently in use by serious Fabric admin servers that *does* expose an enumerable node model is **LuckPerms-Fabric** (`net.luckperms.api.model.user.User#getNodes()`, returning `Collection<Node>` with key strings, weights, and contexts). This is the same upstream LuckPerms that Bukkit/Paper installations use; the Fabric port keeps the same `LuckPermsProvider.get()` entry point. No other production-grade Fabric permissions mod currently exposes an analogous enumeration surface. Choosing a LuckPerms-first design is therefore not a vendor preference - it is the only available data source for the open-ended numeric tails (`rtp.delay.<n>`, `rtp.cooldown.<n>`) that registry-probing cannot solve.

## Options considered

### Option A - Static union of "default-true" + known-op + opportunistic perms-api check

Build the set from `FabricDefaultPermissions.DEFAULT_TRUE` + an op-implies-all expansion of `plugin.yml`-declared nodes + a probe of each `rtp.effect.*` node from `effects/*.yml`.

- Pro: zero perms-api calls for non-op players.
- Con: misses the entire reason `getEffectivePermissions()` is on the API - **picking up perms granted by a permissions plugin to non-op players** (donor ranks, mod-only privileges, opt-in cohorts).
- Con: cannot represent open-ended numeric tails at all.
- **Rejected.**

### Option B - Probe registered effect nodes via `hasPermission` (registry-driven, closed-namespace only)

Iterate `EffectFactory.registeredNames()` and probe each `rtp.effect.<id>` through the existing three-tier `hasPermission` chain.

- Pro: bounded, no new dependency, reuses existing infrastructure.
- Pro: extensible to other *closed* namespaces by adding their registries (`rtp.onevent.*` against a fixed 6-event list).
- **Con (fatal in revised scope): cannot enumerate `rtp.delay.<n>` / `rtp.cooldown.<n>`.** `n` is any integer; there is no bound to probe against without inventing an arbitrary ceiling.
- **Demoted to fallback** (see Decision).

### Option C - Defer until `fabric-permissions-api` adds an enumeration surface

Wait for upstream to add `Permissions.enumerate(source)`.

- Pro: zero code.
- Con: indefinite. The API maintainers have explicitly scoped enumeration as out of contract.
- Con: leaves on-event teleports broken indefinitely on Fabric.
- **Rejected.**

### Option D - Add `RTPCommandSender#getEffectivePermissions(String prefix)` to `rtp-api`

Replace the no-arg method with a prefix-scoped variant so each platform implements only the bounded case.

- Pro: clean.
- Con: breaks the public `rtp-api` contract (addons implementing `RTPCommandSender` would all break - a major-version bump trigger).
- Con: still does not solve the open-ended numeric tail problem; only narrows the closed-namespace probe.
- **Rejected** until a separate API-level ADR justifies the break.

### Option E - LuckPerms-Fabric soft-depend (selected)

Add `net.luckperms:api` as a `modCompileOnly` / `implementation` dependency on `rtp-fabric-common` (and carriers as needed). At `getEffectivePermissions()` call time, attempt `LuckPermsProvider.get().getUserManager().getUser(uuid)` reflectively or via direct API call guarded by `Class.forName("net.luckperms.api.LuckPermsProvider")`. If LuckPerms-Fabric is present and the user is loaded, return the union of:

1. `user.getNodes().stream().filter(Node::getValue).map(Node::getKey).map(String::toLowerCase).collect(...)` - the true effective set from LuckPerms.
2. `FabricDefaultPermissions.DEFAULT_TRUE` (constant nodes everyone gets, e.g. `rtp.see`).
3. The `rtp.effect.*` registry probe from Option B (catches nodes granted by a *non-LuckPerms* perms-api implementer if both happen to be installed - a rare but legal config).
4. For op players: an `op-implies-all` expansion limited to the closed-namespace registries (`rtp.effect.*`, `rtp.onevent.*`) only. **Op does not auto-grant `rtp.delay.<n>` / `rtp.cooldown.<n>`** because the integer tail is unbounded; admins must grant those explicitly.

If LuckPerms is **absent**, return:

1. `FabricDefaultPermissions.DEFAULT_TRUE`.
2. The closed-namespace registry probe (Option B's logic, broadened to `rtp.effect.*` and `rtp.onevent.*`).
3. Op-implies-all over the closed-namespace registries.

Pros:
- Solves the open-ended numeric tails (`rtp.delay.<n>`, `rtp.cooldown.<n>`) for LuckPerms users - which is "the only permissions foundation presently in use" per user direction.
- On-event teleports work correctly on Fabric for both op and non-op players, matching the Bukkit/Paper CHANGELOG fix.
- Effects subsystem works whether or not LuckPerms is present (registry probe still runs).
- Graceful degradation when LuckPerms is absent: only the closed-namespace cases work; numeric tails fall back to config defaults. This is documented and matches Option B exactly.
- No breaking change to `rtp-api` or `rtp-core`.

Cons:
- Adds a vendor dependency (`net.luckperms:api`). Mitigation: it is `api`-only (interfaces), Apache-2.0 licensed, already widely adopted by Bukkit admins, and is the *only* enumeration source available on Fabric today.
- Two code paths to maintain (LP-present / LP-absent). Mitigation: both paths share the closed-namespace union helper; only the leading step branches.
- LuckPerms-Fabric's `getUser(UUID)` is async (returns `CompletableFuture<User>`); the cached `User` is non-null only after `UserManager#loadUser` resolves. We use `getUser(uuid)` (the cached lookup) which returns `null` if not yet loaded - in that window we fall back to the closed-namespace path. For online players this window is sub-second post-login.

## Decision

Adopt **Option E**: LuckPerms-Fabric primary path with closed-namespace registry probe as fallback and defaults source.

### Effective set, formally

```
effective(player) =
    DEFAULT_TRUE                                     // always
  ∪ LP_NODES(player.uuid)                            // only if LuckPerms-Fabric present and User cached
  ∪ REGISTRY_PROBE(player, rtp.effect.*)            // always (catches non-LP perms-api implementers)
  ∪ REGISTRY_PROBE(player, rtp.onevent.*)           // always
  ∪ (player.isOp ? OP_CLOSED_NAMESPACE_GRANTS : ∅)  // op-implies-all for closed namespaces only
```

where:

- `DEFAULT_TRUE = FabricDefaultPermissions.DEFAULT_TRUE` (currently `{rtp.see, rtp.use}` per Step F's parity test).
- `LP_NODES(uuid)` = `LuckPermsProvider.get().getUserManager().getUser(uuid).getNodes().stream().filter(Node::getValue).map(Node::getKey).map(String::toLowerCase).collect(toSet())`, **including** numeric-tail nodes like `rtp.delay.300`. Empty if LP absent or user not cached.
- `REGISTRY_PROBE(player, prefix)` = `{ prefix + id : id in REGISTRY(prefix) ∧ player.hasPermission(prefix + id) }` where `REGISTRY("rtp.effect.")` = `EffectFactory.registeredNames()` and `REGISTRY("rtp.onevent.")` = the 6-event constant `{join, firstjoin, respawn, changeworld, move, teleport}` declared once as `FabricOnEventPermissions.EVENT_NAMES`.
- `OP_CLOSED_NAMESPACE_GRANTS` = `{ "rtp.effect." + id : id in EffectFactory.registeredNames() } ∪ { "rtp.onevent." + e : e in EVENT_NAMES }`. **Numeric-tail nodes are NOT auto-granted to ops** - that would be unbounded and would also let an op accidentally receive `rtp.delay.0` (zero delay) when the intent was `rtp.delay.300` only.

### Documented limitations

1. **Numeric-tail overrides require LuckPerms on Fabric.** `rtp.delay.<n>` / `rtp.cooldown.<n>` per-player permission overrides only work if LuckPerms-Fabric is installed. Without it, only the `config.yml` / `performance.yml` defaults apply. This matches user direction ("B is our best current state, and supports the only permissions foundation i am presently aware of for fabric") and matches the existing operational reality: admins who want per-player numeric overrides install LuckPerms.
2. **Other Fabric perms-api implementers (Cyan, Ledger, etc.) cover the closed-namespace cases only.** Their `Permissions.getPermissionValue(uuid, node)` returns are honoured via `REGISTRY_PROBE`, which is sufficient for `rtp.effect.*` and `rtp.onevent.*`. Numeric tails remain invisible.
3. **Op-implies-all is restricted to closed namespaces.** On Bukkit, ops effectively get every `rtp.*` node, including numeric tails (because `getEffectivePermissions()` enumerates them from the server's view of `plugin.yml` + attachments). On Fabric we cannot replicate that for numeric tails without inventing a probe ceiling. Documented as known-divergent behaviour: Fabric ops get the same on-event and effects grants as Bukkit ops, but to override `rtp.delay.<n>` / `rtp.cooldown.<n>` for themselves they must (a) install LuckPerms and grant the node explicitly, or (b) edit `config.yml`.
4. **LuckPerms `User` loading is async.** The first `getEffectivePermissions()` call within sub-second of login may miss `LP_NODES`. In practice every consumer (effects resolution, on-event teleports) runs after `ServerPlayConnectionEvents.JOIN` has fully completed, by which point LP has cached the user. Documented; not expected to fire.
5. **`rtp.personalqueue` intentionally bypasses this method** per [ADR-043](../../../docs/adr/ADR-043-personal-queue-permission-semantics.md) - it uses a direct `hasPermission` check. Not affected by this ADR.

## Implementation plan

1. **Dependency.** Add `net.luckperms:api:5.4` (or current) as `modCompileOnly` in `rtp-fabric/rtp-fabric-common/build.gradle` and `rtp-fabric/rtp-fabric-common-unobf/build.gradle`. **Do not** add to `rtp-core` or `rtp-api`. The dependency is API-only (LuckPerms ships the interface JAR separately from the impl); runtime resolution happens only if a LuckPerms-Fabric mod is loaded.
2. **`LuckPermsFabricEnumerator`** (new class in `rtp-fabric-common/.../player/`). Static facade:
   ```java
   static boolean isAvailable();              // guarded Class.forName + LuckPermsProvider.get()
   static Set<String> grantedNodes(UUID uuid); // returns LP_NODES or empty set
   ```
   `isAvailable()` is memoised after first call. `grantedNodes` swallows `IllegalStateException` (LP not loaded yet) and `null` cached User, returning empty set. Never throws.
3. **`FabricOnEventPermissions`** (new constants class). Holds the 6-event `EVENT_NAMES` list and an `OP_GRANTS` precomputed set. Single source of truth so `OnEventTeleports` and the enumerator agree.
4. **`FabricRTPPlayer.getEffectivePermissions()`** (replaces empty stub at line 218). Body matches the formal `effective(player)` definition above. Mirror to `FabricRTPPlayerUnobf` (line 235) and `V26_1_R1FabricRTPPlayer` (line 139). Bodies are identical because they depend only on `uuid()`, `isOp()`, and `hasPermission(node)` - all platform-specific abstractions already implemented.
5. **`FabricServerAccessor` console sender** (line 1968). Console returns the full op-grant set: `DEFAULT_TRUE ∪ OP_CLOSED_NAMESPACE_GRANTS`. Console does NOT get numeric tails (same reasoning as op players; if a server operator needs the console to use a specific delay, they configure it in `config.yml`).
6. **Tests.** Add `FabricEffectivePermissionsTest` under `rtp-fabric-common/src/test/.../player/` with four nested scenario groups:
   - **Group A (LP absent).** Stub `LuckPermsFabricEnumerator.isAvailable() → false`. Assert closed-namespace probe + defaults work; assert numeric tails are absent from the result.
   - **Group B (LP present, donor non-op).** Stub `LP_NODES` returning `{rtp.effect.particle.heart, rtp.onevent.firstjoin, rtp.delay.300}`. Assert all three are in the result; assert `rtp.effect.particle.heart` flows through to `EffectFactory.buildEffects`; assert `ParsePermissions.hasPerm(sender, "rtp.onevent.", "firstjoin")` returns TRUE; assert `ParsePermissions.getInt(sender, "rtp.delay.")` returns 300.
   - **Group C (op, no LP).** Stub `isOp() → true`, `LP absent`. Assert `OP_CLOSED_NAMESPACE_GRANTS` is in the result; assert numeric tails are absent (intentional divergence from Bukkit semantics, documented).
   - **Group D (handle == null).** Assert empty set, no NPE.
7. **Plan + traceability.** Tick Step F sub-item 3 in `MULTI_PLATFORM_PLAN.md`, update header from 4/5 → 5/5. Add `TRACEABILITY.md` row for the new test under candidate `REQ-RTP-F-???` (permission-driven event/effect dispatch on Fabric).
8. **CHANGELOG.** Under `[3.0.0-beta.2] - Unreleased` Fabric platform bullet: append "permission-granted on-event teleports and effect grants now fire on Fabric (LuckPerms-Fabric primary path; closed-namespace fallback for other perms-api implementers)". Bullet describes the net delta from `v3.0.0-beta.1` where Fabric returned empty from `getEffectivePermissions()`.
9. **Stale comments.** Remove the Step F "deferred" / "always-true predicate" comments at `RTPFabricMod.java:454`, `RTPCmdFabricRoot.java:70/84`, and the "0 effects fire" javadoc at `FabricEffectsHandlerUnobf.java:43` and `FabricEffectsHandler.java:45`.

## Risks / trade-offs

- **Vendor dependency on LuckPerms.** Soft, API-only, no runtime requirement. Mitigated by the registry-probe fallback for non-LP installs.
- **Permission-check fan-out for non-LP path.** `|EffectFactory.registeredNames()| + 6` `hasPermission` calls per `getEffectivePermissions()` call. Bounded; not on the teleport hot path (called from event listeners and effects resolution, both gated by player events, not per-tick).
- **No S-00x impact.** No chunk I/O, no main-thread blocking (perms lookups are in-memory for online players; LP `getUser` is a cached `User` lookup, not a network call).
- **No D-005 cross-module structural change.** Confined to `rtp-fabric-*` modules and the `FabricServerAccessor` accessor method.
- **LP version skew.** `net.luckperms:api:5.4` is forward-compatible across LP 5.x. If LP-Fabric ships an incompatible major bump, the guarded `Class.forName` + try-catch around `LuckPermsProvider.get()` keeps the rest of the system functional.

## Out of scope

- **Hoisting enumeration into a platform-neutral `RTPPlayer` default.** The Bukkit implementation reads a server-managed structure; only the Fabric path is probe + LP-API. A unified default would be a forced abstraction.
- **Forge / NeoForge enumeration.** Forge is not yet in scope per Multi-Platform Plan Phase 4. The same LP-primary / registry-fallback shape will apply.
- **Adding a new `rtp-api` method.** Considered as Option D and rejected; revisit only if a future closed namespace needs more than the union approach can express.
- **The stale ADR numbering referenced by `MULTI_PLATFORM_PLAN.md`** (`rtp-fabric-ADR-010-menu-renderer`, `-ADR-011-network-bootstrap`, `-ADR-012-maps-binding`). ADR-010 is already taken by typed-block-tag-snapshot; this ADR consumes 011. Plan renumbered to 012/013/014 in a prior session.
- **`rtp.personalqueue`** stays on direct `hasPermission` per ADR-043; not routed through `getEffectivePermissions()`.
