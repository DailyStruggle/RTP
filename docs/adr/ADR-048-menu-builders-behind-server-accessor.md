# ADR-048 - Move menu page builders into `rtp-api` behind `RTPServerAccessor`

- **Status:** Proposed (2026-05-23)
- **Scope:** `rtp-api` (`menu/`, `server/RTPServerAccessor`), `rtp-core` (`common/commands/menu/`), `rtp-bukkit-common`, `rtp-fabric-common` (+ `-common-unobf`), `rtp-plugin` (Paper + Fabric command roots).
- **Supersedes:** none. Amends the platform-shaped wiring contract assumed by [ADR-035](ADR-035-interactive-menus-book-first.md), [ADR-044](ADR-044-command-tree-menu-reflector.md), [ADR-045](ADR-045-rtp-docs-menu-consumer.md), [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md).
- **Related:** [ADR-035](ADR-035-interactive-menus-book-first.md) (menu rollout - Paper book renderer + Fabric chat renderer), [ADR-044](ADR-044-command-tree-menu-reflector.md) (`MenuModel` reflector), [rtp-fabric-ADR-011](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md) (Fabric effective-permission resolver, feeds the accessor), [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md) (Fabric chat-first renderer; ADR-012 will be amended to consume the accessor introduced here).

## Context

The interactive-menu rollout is split across two layers today:

- **Platform-neutral SPI** in `rtp-api/.../menu/`: `MenuModel`, `MenuPage`, `MenuLine`, `MenuFragment`, `MenuAction`, `MenuRenderer`, `MenuTokenRegistry`, `MenuConsumerProfile`, `MenuOpenRequest`, `YamlCommentLookup`. These are pure value types and SPIs and are correctly factored - third-party-renderer-friendly, no platform imports.
- **Page builders** in `rtp-core/.../common/commands/menu/`: `FrontPageBuilder`, `AdminPanelBuilder`, `CommandTreeMenuBuilder`, `MultiConfigMenuBuilder`, `PrefabConfirmationMenuBuilder`. These build `MenuModel` instances from the live command tree, configuration files, and per-player state.

The builders are *nominally* platform-neutral (they live in `rtp-core`, which forbids `org.bukkit.*` imports) but in practice they couple to platform-shaped data through ad-hoc seams:

1. **Permission visibility filter.** Every builder filters command-tree rows by a `Predicate<String>` permission probe. Today the probe is constructed inline at each command-root call site (Paper: `RTPCmdBukkit` lines 213 / 239 / 246 / 259 / 303; Fabric per [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md) plan). The construction logic differs per platform (Paper delegates to `Player#hasPermission`; Fabric routes through the three-tier chain from [rtp-fabric-ADR-011](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md)).
2. **Effective-permission enumeration.** `AdminPanelBuilder` and the param-picker page need the granted-node set to decide what config rows / param values to expose. Available on `RTPPlayer#getEffectivePermissions()` today, but each builder reaches for the player via `RTP.serverAccessor.getPlayer(uuid)` ad-hoc and assumes the result is non-empty - a Fabric pre-[rtp-fabric-ADR-011](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md) regression that the ADR-011 resolver fixed but did not lift to a stable accessor surface.
3. **Player locale.** Builders embed strings through `LangParser` / `messages.yml`. The lookup is platform-neutral, but the *fallback chain* (client locale -> world default -> server default) is not - Paper reads `Player#getLocale()`, Fabric resolves through the carrier split.
4. **Region / world descriptor.** `FrontPageBuilder` shows a "you are here" line. On a single-server backend this reads `Player#getWorld()`; on a network-mode backend (ADR-036) the answer is proxy-routed. Each builder currently reaches for whatever it can find.

The result is that *each* platform's command root re-wires the same seven things inline: build a `LocalMenuTokenRegistry`, construct a `menuPermissionProbe`, attach `MenuRedeemSubcommand`, instantiate each builder, and connect them to the renderer. Adding a new platform (the Fabric work in [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md), or any future Forge / Sponge port) means re-implementing that wiring from scratch. The five builders themselves are mostly platform-neutral, but the platform-shaped seams keep them from being directly reusable.

The user-raised question - "can we extract this into a `menu-api` module and use the `RTPServerAccessor` / `effects-api` model?" - distils to: where do the platform-shaped seams belong, and is a new Gradle module warranted? This ADR answers both.

## Decision

Adopt the **accessor-extension** path. No new Gradle module.

### 1. Fold the menu-platform seams into `RTPServerAccessor`

Add four default methods to `RTPServerAccessor` (in `rtp-api/.../server/RTPServerAccessor.java`). They have safe defaults so the existing Bukkit and Fabric implementations compile without immediate changes; platforms override progressively.

```java
// In RTPServerAccessor

/**
 * Returns a permission probe scoped to the given player. The probe answers
 * {@code hasPermission(node)} for menu-visibility filtering. Implementations
 * should respect the same three-tier resolution as
 * {@link RTPPlayer#hasPermission(String)}.
 *
 * @param player the player UUID; the probe is bound to this player's view
 * @return a non-null predicate; default delegates to
 *         {@code getPlayer(player).hasPermission(node)} and falls back to
 *         {@code n -> false} if the player is offline / unresolved
 */
default Predicate<String> menuPermissionProbe(UUID player) { ... }

/**
 * Returns the set of effective permissions granted to {@code player} for
 * the menu-relevant {@code rtp.*} namespaces (effects, on-event, numeric
 * tails where enumerable). Delegates to
 * {@link RTPPlayer#getEffectivePermissions()} by default.
 *
 * @param player the player UUID
 * @return an immutable snapshot; empty if unresolved
 */
default Set<String> menuEffectivePermissions(UUID player) { ... }

/**
 * Returns the BCP-47 locale tag the menu should render in for the given
 * player (e.g. {@code "en_us"}, {@code "es_es"}). Default returns the
 * server's configured default locale.
 *
 * @param player the player UUID; may be {@link RTPPlayer#consoleUuid()}
 *               for console-issued menu commands
 * @return a non-null locale tag
 */
default String menuLocale(UUID player) { ... }

/**
 * Returns a short descriptor of where the player currently is, for the
 * "you are here" line in {@code FrontPageBuilder}. The descriptor is
 * intentionally string-shaped (not a {@code World} reference) so that
 * network-mode backends (ADR-036) can answer with a proxy-routed server
 * id or region name.
 *
 * @param player the player UUID
 * @return a non-null short descriptor; default is the empty string,
 *         which causes the "you are here" line to be omitted
 */
default String menuRegionDescriptor(UUID player) { ... }
```

These methods live on `RTPServerAccessor` (not a new sibling accessor) because:

- The five builders already depend on `RTPServerAccessor` transitively through `RTP.serverAccessor`. Adding methods on the existing accessor avoids a second injection point.
- Each method is the *menu-shaped projection* of an existing accessor / `RTPPlayer` capability. They are not a new subsystem, they are convenience views.
- `effects-api` has a separate `EffectFactory` because effects are third-party-registerable. Menu builders are not third-party-registerable (per [ADR-044](ADR-044-command-tree-menu-reflector.md) the model reflects the command tree, not user-registered pages), so the symmetry does not apply.

If a fifth or sixth method joins the list (e.g. `boolean isViewingMenu(UUID)` for [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md)'s chat-prompt callback, or `void runOnPlayerThread(UUID, Runnable)` for Folia region-affinity actions), this ADR is amended to split the menu surface into a sibling `RTPMenuAccessor` retrieved via `RTPServerAccessor#menuAccessor()`. The split threshold is documented as **>=5 menu-specific methods on `RTPServerAccessor`**; today's four sit below it.

### 2. Move the five builders into `rtp-api/.../menu/builders/`

Relocate (not rewrite):

| From (`rtp-core/.../common/commands/menu/`) | To (`rtp-api/.../menu/builders/`) |
|---|---|
| `FrontPageBuilder` | `FrontPageBuilder` |
| `AdminPanelBuilder` | `AdminPanelBuilder` |
| `CommandTreeMenuBuilder` | `CommandTreeMenuBuilder` |
| `MultiConfigMenuBuilder` | `MultiConfigMenuBuilder` |
| `PrefabConfirmationMenuBuilder` | `PrefabConfirmationMenuBuilder` |

Constructor signatures change to take the platform seams from the accessor rather than reaching for `RTP.serverAccessor` ad-hoc:

```java
public FrontPageBuilder(MenuPlatformView view, MenuTokenRegistry tokens) { ... }
```

where `MenuPlatformView` is a tiny record assembled from the accessor at call time:

```java
public record MenuPlatformView(
    Predicate<String> hasPermission,
    Set<String> effectivePermissions,
    String locale,
    String regionDescriptor) {

    public static MenuPlatformView of(RTPServerAccessor accessor, UUID player) {
        return new MenuPlatformView(
            accessor.menuPermissionProbe(player),
            accessor.menuEffectivePermissions(player),
            accessor.menuLocale(player),
            accessor.menuRegionDescriptor(player));
    }
}
```

The `MenuPlatformView` lives in `rtp-api/.../menu/`. It is a *snapshot* per build call, not a long-lived reference - this keeps builders pure (same input -> same `MenuModel`) and avoids cross-thread races on permission / locale changes mid-render.

Why `rtp-api` rather than leaving in `rtp-core`: `rtp-api` is the only module both `rtp-core` and platform adapters depend on. Builders in `rtp-api` can be invoked from the platform-side command roots without a `rtp-core` round-trip, and the `MenuPlatformView` record sits next to `MenuModel` / `MenuPage` where it naturally belongs.

### 3. Collapse the per-platform command-root wiring

After the move, `RTPCmdBukkit` and `RTPCmdFabricRoot` shrink to:

```java
// Once at boot
LocalMenuTokenRegistry tokens = new LocalMenuTokenRegistry();
MenuRendererRegistry.register("book", new BookMenuRenderer(...));     // Paper
// or new ChatMenuRenderer(...) on Fabric per rtp-fabric-ADR-012
attachSubcommand(new MenuRedeemSubcommand(tokens));

// Per /rtp invocation
MenuPlatformView view = MenuPlatformView.of(RTP.serverAccessor, callerUuid);
MenuModel model = new FrontPageBuilder(view, tokens).build();
RTP.serverAccessor.openMenu(callerUuid, model);  // existing dispatch path
```

The seven inline construction sites in `RTPCmdBukkit` (token registry + permission probe + redeem subcommand + four builder call sites) collapse to one accessor lookup per build call. The same pattern applies to Fabric, which lets [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md)'s `RTPCmdFabricRoot` wiring section be drastically simplified - the ADR will be amended to delete its `menuPermissionProbe` sub-item (now subsumed) and reference this ADR for the builder wiring.

### 4. Platform adapter changes

| Platform | Adapter delta |
|---|---|
| `BukkitServerAccessor` (rtp-bukkit-common) | Override the four default methods. `menuPermissionProbe(uuid)` -> `node -> Bukkit.getPlayer(uuid).hasPermission(node)`. `menuEffectivePermissions(uuid)` -> walks `Player#getEffectivePermissions()`. `menuLocale(uuid)` -> `Player#getLocale()`. `menuRegionDescriptor(uuid)` -> `Player#getWorld().getName()` (or empty string when network mode is active and the player is on a different backend). |
| `FabricServerAccessor` (rtp-fabric-common + -common-unobf) | Override the four default methods. `menuPermissionProbe(uuid)` and `menuEffectivePermissions(uuid)` delegate to `FabricEffectivePermissionsResolver` from [rtp-fabric-ADR-011](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md). `menuLocale(uuid)` reads `ServerPlayer#clientInformation().language()` through the carrier split. `menuRegionDescriptor(uuid)` reads `ServerPlayer#serverLevel().dimension().location().toString()`. |
| Folia | Inherits `BukkitServerAccessor`. No delta in this ADR; a follow-up may add `runOnPlayerThread(UUID, Runnable)` for region-affinity click actions, but the four methods above are platform-thread-agnostic. |
| `rtp-proxy-*` | Out of scope. The proxy SPI in `rtp-proxy-common` does not implement `RTPServerAccessor` and does not render menus directly - menus on proxy-routed players are still rendered backend-side per the 2026-05-15 ADR-035 amendment. |

### 5. Migration mechanics

The move is mechanical but non-trivial because the builders are referenced from `RTPCmdBukkit`, `MenuRedeemSubcommand`, and (planned) `RTPCmdFabricRoot`. Sequence:

1. **Land the accessor methods first** (default implementations + Bukkit / Fabric overrides). No behavioural change yet - existing call sites still work because the inline `menuPermissionProbe` constructions are byte-equivalent to the new defaults.
2. **Add `MenuPlatformView`** as a record in `rtp-api/.../menu/`.
3. **Copy** the five builders into `rtp-api/.../menu/builders/` with the new constructor signature, alongside the existing copies in `rtp-core`. Both work for one cycle.
4. **Switch call sites** in `RTPCmdBukkit` to the new builders + `MenuPlatformView.of(...)`.
5. **Delete** the old builders from `rtp-core`. ArchUnit guard ([MULTI_PLATFORM_PLAN.md Step H](../dev/MULTI_PLATFORM_PLAN.md)) is unchanged - the new package is in `rtp-api`, which is under neither `bukkit/` nor `fabric/`.
6. **Wire Fabric** in `RTPCmdFabricRoot` per the amended [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md).

Steps 1-2 are independently committable; steps 3-6 land together to avoid split-brain builders.

## Consequences

### Positive

- **Each new platform adds one accessor subclass + four overrides** to gain full menu support. Forge / Sponge / hypothetical Mod Loader X get the menu surface for free as long as they ship a `RTPServerAccessor`.
- **`RTPCmdBukkit` and `RTPCmdFabricRoot` shrink substantially**: seven inline constructions per root collapse to one accessor lookup per build call.
- **Cross-cutting menu concerns funnel through one surface.** Any future "show 'you are here' as proxy-routed server id" change is a single override in the network-mode-aware accessor variant, not five builders.
- **Aligns with the existing `RTPServerAccessor` convention.** No new accessor pattern for contributors to learn.
- **No new Gradle module.** Build times, publish coordinates, and ArchUnit guards unchanged.
- **Retroactively cleans Paper's `menuPermissionProbe` ad-hoc field** (the implicit pattern that [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md) inherits).

### Negative

- **`rtp-api` grows.** Five builder classes (~600-1200 LOC each in their current form) move from `rtp-core` to `rtp-api`. This is acceptable because they are leaf consumers of `rtp-api` types and have no dependency on `rtp-core` business logic that would force a circular dependency - confirmed by inspection (the builders consume `MenuModel`, `MenuTokenRegistry`, `LangParser`, `YamlCommentLookup`, all of which are in `rtp-api`). If a circular dep is uncovered during step 3 of the migration, the offending dep is pulled up to `rtp-api` (`LangParser` is the most likely candidate; it is already a viable `rtp-api` resident).
- **`RTPServerAccessor` interface widens by four methods.** Each has a safe default, so out-of-tree implementers (addons that ship a stub accessor for tests) are not broken. The widening is documented in CHANGELOG under `[3.0.0-beta.4]` when the implementation lands.
- **The four default implementations need careful documentation.** Default `menuPermissionProbe` returning `n -> false` for offline players is a subtle behavioural choice (no rows visible vs all rows visible). The chosen default (no rows visible) is conservative; documented in the method Javadoc.

### Limitations

1. **`MenuPlatformView` is a snapshot.** Permission or locale changes during a long-rendering page do not propagate. Acceptable because pages render in milliseconds; documented as a Javadoc note on the record.
2. **No third-party builder registration.** This ADR keeps builders project-internal. Per [ADR-044](ADR-044-command-tree-menu-reflector.md) the model reflects the command tree, not user-registered content, so third-party builders are out of scope. If that requirement materializes, the migration target (`rtp-api/.../menu/builders/`) is already in a publishable module.
3. **No coverage for proxy-side menu rendering.** Proxy JVMs (`rtp-proxy-velocity`, `rtp-proxy-bungee`) do not host `RTPServerAccessor`. Menus on proxy-routed players continue to render on the destination backend per ADR-035's 2026-05-15 amendment. A future "proxy-rendered menu" ADR would need its own seam.
4. **`menuRegionDescriptor` is intentionally a `String`.** Avoids leaking a `World` reference into `rtp-api`. Callers that want richer structure can parse the descriptor or call a future typed method.

## Implementation plan (post-acceptance)

The implementation is a separate change after this ADR is accepted. Sketch:

1. Add four `default` methods to `RTPServerAccessor`. Add `MenuPlatformView` record under `rtp-api/.../menu/`.
2. Override the four methods in `BukkitServerAccessor` and `FabricServerAccessor` (+ `-common-unobf` variant). Wire Fabric overrides to the existing `FabricEffectivePermissionsResolver`.
3. Move the five builders from `rtp-core/.../common/commands/menu/` to `rtp-api/.../menu/builders/`. Update constructors to take `MenuPlatformView` + `MenuTokenRegistry`.
4. Update `RTPCmdBukkit` call sites to `MenuPlatformView.of(...)` + new builder package.
5. Amend [rtp-fabric-ADR-012](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md): delete the `menuPermissionProbe` sub-item, simplify the `RTPCmdFabricRoot` wiring section to reference ADR-048, keep the chat-renderer decision intact.
6. New `ServerAccessorMenuSurfaceTest` in `rtp-api/src/test/`: pins the four-method contract against a mock accessor; asserts `MenuPlatformView.of` snapshots correctly; asserts default `menuPermissionProbe` is conservative for unresolved players.
7. New `BukkitMenuPlatformViewParityTest` and `FabricMenuPlatformViewParityTest`: each builds a known `MenuModel` against a real platform accessor and asserts the rendered page set matches an oracle (covers the regression where Fabric's permission probe was always-true pre-[rtp-fabric-ADR-011](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md)).
8. CHANGELOG entry under `[3.0.0-beta.4] - Unreleased`: "Menu page builders moved from `rtp-core` to `rtp-api`; platform-shaped seams (permission probe, effective permissions, locale, region descriptor) lifted to `RTPServerAccessor` default methods. Out-of-tree accessor implementations gain the new surface via defaults; explicit overrides recommended for production platforms."
9. `TRACEABILITY.md` rows added if the new tests target a REQ-* (likely a new `REQ-RTP-MENU-NNN` for "menu visibility filter shall use the platform's effective-permission view"; needs a REQ wording pass first).

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| **New `menu-api` Gradle module** (the user's original framing) | No third-party menu registration on the roadmap; the `effects-api` symmetry is superficial (effects are third-party-registerable, menus are not per ADR-044). Gradle module overhead (publish coordinates, ArchUnit updates, transitive-dep wiring on five other modules) without an API-surface win. Can be split out later if requirements change; the migration target (`rtp-api/.../menu/builders/`) is already publishable. |
| **Separate `RTPMenuAccessor` sibling, retrieved via `RTPServerAccessor#menuAccessor()`** | Premature with only four menu-shaped methods. Adds an indirection (`accessor.menuAccessor().permissionProbe(uuid)`) that buys no clarity at this size. Documented threshold for revisiting: >=5 menu-specific methods on `RTPServerAccessor`. |
| **Keep builders in `rtp-core`, pass `RTPServerAccessor` directly** | Builders would still reach into the accessor for four different concerns each, scattering the call sites and forcing every platform to implement the four methods even if they are not menu-rendering yet. The `MenuPlatformView` snapshot record is the right intermediate object. |
| **Pass a `RTPPlayer` to builders instead of `MenuPlatformView`** | `RTPPlayer` is too coarse - locale and region descriptor are not on it today, and adding them re-creates the same widening problem one level deeper. `MenuPlatformView` is a menu-shaped projection, not a player abstraction. |
| **Refactor `RTPServerAccessor` into a `ServiceLocator` keyed by capability** | Out of scope and far heavier than required. The four methods do not warrant a service-locator pattern; the existing default-method convention on the interface is sufficient. |

## References

- [ADR-035 - Interactive Menus via Written Book](ADR-035-interactive-menus-book-first.md)
- [ADR-044 - Command-Tree Menu Reflector](ADR-044-command-tree-menu-reflector.md)
- [ADR-045 - `rtp-docs` Menu Consumer](ADR-045-rtp-docs-menu-consumer.md)
- [rtp-fabric-ADR-011 - Effective-permission enumeration on Fabric](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-011-effective-permissions-enumeration.md)
- [rtp-fabric-ADR-012 - Menu renderer parity on Fabric](../../platforms/rtp-fabric/docs/adr/rtp-fabric-ADR-012-menu-renderer-parity.md)
- [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md) (Step I gating context)
- [`RTPServerAccessor.java`](../../rtp-api/src/main/java/io/github/dailystruggle/rtp/api/server/RTPServerAccessor.java) (the interface being extended)
