# Building a Menu or GUI on `rtp-api`

A common reason to depend on an RTP plugin is to put a destination menu in front of players -
a clickable "where do you want to go?" GUI - and let the RTP engine do the actual teleport. RTP
supports that use case as a first-class, safe, platform-neutral API. You do not need to fork the
plugin, parse `/rtp` output, or reimplement safety checks. This page is the addon-developer guide
for building a menu or GUI on top of RTP.

> RTP already ships an interactive menu (book-first on Paper/Folia, chat fallback elsewhere). This
> page is for when you want to build your own GUI - a chest inventory, a custom book, a web panel -
> and have RTP supply the data and perform the teleport.

## Why this is a real API and not a hack

Legacy random-teleport plugins force integrators to either shell out to `/rtp` as the player
(losing all type-safety and result feedback) or to copy the plugin's coordinate logic into the GUI
(which re-runs unsafe synchronous rerolling on the main thread). RTP draws a hard line instead:

- RTP owns safety and validation. Permission gating, cooldowns, cost, and the S-001..S-007
  prohibitions (no unsafe block, no claim-protected land, no main-thread chunk I/O, no
  silently-discarded failures) all live behind the API. A GUI author cannot cause an unsafe
  teleport through this surface.
- Your addon owns presentation and click handling. Layout, icons, and the open/close/click
  lifecycle are yours.
- The only mutating call is `teleport(...)`, which re-validates everything server-side and always
  completes with a result - never a silent no-op (REQ-RTP-S-004).

The result: your GUI stays trivial, and it inherits RTP's asynchronous pre-generation queue,
Folia-native regional scheduling, and spatial memory for free.

## Happy path: a destination picker in four calls

Depend on `rtp-api` (the stable, semver-guaranteed surface) - not `rtp-core`. A full GUI needs only
four `RTPAPI` calls.

```java
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import java.util.List;
import java.util.UUID;

void openPicker(UUID playerId) {
    // 1. Ask RTP which destinations THIS player may use (permission gates already applied).
    List<RtpTarget> targets = RTPAPI.getAllowedTargets(playerId);

    for (RtpTarget target : targets) {
        // 2. Decorate each slot with the player's live status (cooldown / cost / availability).
        RtpTargetStatus status = RTPAPI.getTargetStatus(playerId, target);
        addSlot(target, status); // YOUR rendering: icon, lore, lock/greyed-out, price tag
    }
}

void onSlotClicked(UUID playerId, RtpTarget target) {
    // 3. Submit a validated teleport intent. RTP re-checks everything server-side.
    RTPAPI.teleport(playerId, target).thenAccept(result -> {
        if (result.isSuccess()) {
            // teleported; result.location() is the landing spot
        } else if (result.isQueued()) {
            // accepted onto the cross-server wait queue (network mode)
        } else {
            // result.reason() e.g. ON_COOLDOWN / NO_SAFE_LOCATION / NO_PERMISSION ...
            // result.message() is a human-readable explanation. Never silent (S-004).
        }
    });
}
```

That is the whole integration. The teleport future always completes with an `RTPResult`, so your
GUI can always give the player feedback.

> Every `RTPAPI` method throws `IllegalStateException` if called before `rtp-core` has finished
> loading (REQ-RTP-S-006) - it never returns `null` or silently no-ops. Call these from inside your
> addon's `onLoad()` (where core is guaranteed loaded) or in response to a player action, not from a
> static initializer.

## The four building blocks

### 1. `RTPAPI.getAllowedTargets(UUID)` - what to show

Returns an immutable `List<RtpTarget>` of the destinations the player is actually permitted to use,
with the same permission gates the `/rtp` command applies already resolved. The first element is
always `RtpTarget.defaultRegion()` (a bare `/rtp` is always offered), followed by any named
regions/worlds the player passes the permission check for. Render the list verbatim - one slot per
entry.

### 2. `RtpTarget` - addressing a destination

An immutable selector that hides all `rtp-core` region internals. Build one with a static factory:

| Factory | Resolves to |
|---|---|
| `RtpTarget.defaultRegion()` | the server default region (a bare `/rtp`) |
| `RtpTarget.region("spawnlands")` | a region by its configured name |
| `RtpTarget.world("world_nether")` | the region configured for a named world |
| `RtpTarget.network("backend-b", "wilds")` | a region on a peer backend (network mode); routed via the cross-server wait queue |

### 3. `RTPAPI.getTargetStatus(UUID, RtpTarget)` - how to decorate a slot

Returns a point-in-time `RtpTargetStatus` for decorating one button. It is read-only - it never
teleports or charges the player.

| Accessor | Use for |
|---|---|
| `availability()` | enum: `READY` / `ON_COOLDOWN` / `NO_PERMISSION` / `NO_FUNDS` / `DISABLED` / `UNKNOWN` |
| `isReady()` | convenience boolean for "clickable right now" |
| `remainingCooldownMillis()` | grey-out timer / countdown lore |
| `cost()` | price tag lore |
| `iconBlock()` / `environment()` / `label()` | optional display hints (may be `null`) |

> `UNKNOWN` is a real value - plan for it. The availability enum may grow over time, and a null
> delegate result degrades to `UNKNOWN` rather than throwing. Always have a sensible fallback
> rendering for an availability you do not recognise.

### 4. `RTPAPI.teleport(UUID, RtpTarget)` - the only mutating call

```java
CompletableFuture<RTPResult> teleport(UUID player, RtpTarget target);
boolean cancel(UUID player);            // cancel a pending/in-flight teleport
boolean isWarmingUp(UUID player);       // a teleport requested but not yet completed?
int     queueDepth(RTPWorld<?> world);  // pre-verified ready locations for a world
```

Permission, cooldown, cost, and every S-00x safety check are enforced inside this call, regardless
of what `getAllowedTargets` / `getTargetStatus` returned. Treat those reads as display hints and
`teleport(...)` as the authority.

## Understanding `RTPResult`

The teleport future always completes with an `RTPResult` so you can always inform the player
(S-004 - no silent failures). `isSuccess()` and `isQueued()` are both non-failure outcomes (a queued
request will complete on a remote backend after transfer). On success `location()` is non-null; on
failure it is `null` and `message()` carries a human-readable explanation.

## Optional: a server-health dashboard tile

If your menu shows live server health, read a sampled snapshot - it is produced by the metrics
sampler, never computed on your thread, so it adds no main-thread cost and no chunk I/O:

```java
import io.github.dailystruggle.metrics.api.MetricsSnapshot;

MetricsSnapshot snap = RTPAPI.getMetricsSnapshot();   // TPS, MSPT, player count, heap, ...
// On Folia, per-region detail:
RTPAPI.getRegionSamples().forEach(sample -> /* region-scoped tile */);
```

Unsampled fields report `MetricsSnapshot.UNSAMPLED`; check before displaying.

## The reference addon: `LeafRTPGuiAddon`

[`addons/LeafRTPGuiAddon`](../../addons/LeafRTPGuiAddon/README.md) is a complete, drop-in
destination picker built only on the stable `rtp-api` surface above (it deliberately does not link
`rtp-core`, proving the public surface is sufficient for a full GUI). It is structured like the
plugin itself - a platform-neutral core plus thin per-platform renderers:

| Module | Platform | Responsibility |
|---|---|---|
| `rtp-gui-common` | none | the menu model, `guimenu.yml` config, the render seam, the teleport submit. No `org.bukkit.*`. |
| `rtp-gui-bukkit` | Bukkit/Paper/Folia | chest-inventory renderer + click listener |
| `rtp-gui-fabric` | Fabric (MC 26.x) | server-side container (`ChestMenu`) screen renderer |
| `rtp-gui-neoforge` | NeoForge (MC 26.x) | server-side container screen renderer |
| `rtp-gui` | none (assembly) | the single multi-loader distributable jar |

It demonstrates the security boundary precisely: one slot per `getAllowedTargets(UUID)` target, each
decorated via `getTargetStatus(UUID, RtpTarget)`, a health tile from `getMetricsSnapshot()`, and
click -> `teleport(UUID, RtpTarget)`. The Bukkit renderer cancels every click on the read-only chest
(anti-dupe) and identifies its own inventory by a custom `InventoryHolder`, never by a spoofable
title.

## Two ways to wire the open trigger

### Replace what a bare `/rtp` does (root action)

If you want a bare `/rtp` (no arguments) to open your GUI instead of teleporting, bind the
root-action hook. Sub-commands (`/rtp region=...`, `/rtp admin`, ...) are never affected:

```java
RTPAPI.hooks().rootAction().bind((uuid, feedback) -> {
    openMyMenu(uuid);
    return true;   // true = handled (classic teleport suppressed); false = defer to classic /rtp
});
```

This is the seam `LeafRTPGuiAddon` uses so a bare `/rtp` opens the picker. See
[EXTERNAL_HOOKS.md](EXTERNAL_HOOKS.md) for the full hook catalog.

### Your own command

Register your own `/rtpgui`-style command in your plugin/mod as usual and call `openMyMenu(...)` from
it - RTP imposes nothing here.

## Bring your own renderer (advanced)

RTP's internal menu is built on a platform-neutral SPI in `rtp-api` (`MenuModel`, `MenuPage`,
`MenuLine`, `MenuFragment`, `MenuAction`, `MenuOpenRequest`, and `MenuRenderer`). If you want RTP to
drive your renderer with its menu content (rather than building your own model), implement
`MenuRenderer`. Most integrators do not need this; the four `RTPAPI` calls above are simpler and
decouple you from the menu model's shape. Reach for `MenuRenderer` only when you specifically want to
re-skin RTP's own pages.

## Safety checklist for your menu addon

- Depend on `rtp-api` (`compileOnly` / `provided`), not `rtp-core`, unless you need an unstable
  introspection hook.
- Call `RTPAPI` methods only after core is loaded (inside `onLoad()` or in response to player
  actions) - they throw pre-load (S-006).
- Treat `getAllowedTargets` / `getTargetStatus` as display hints; `teleport(...)` is the authority.
- Always handle the `RTPResult` (including `UNKNOWN`/unrecognised reasons) so the player gets
  feedback (S-004).
- Do your own anti-dupe on click handling (cancel clicks on a read-only inventory; identify your
  inventory by holder, not title).
- Never load chunks or run blocking work on the main thread - the teleport is async and RTP handles
  it.

## See also

- [FOR_ADDON_DEVELOPERS.md](../FOR_ADDON_DEVELOPERS.md) - the addon-developer landing page.
- [EXTERNAL_HOOKS.md](EXTERNAL_HOOKS.md) - the root-action hook and the full behavior-modification catalog.
- [ADDON_CROSS_SERVER.md](ADDON_CROSS_SERVER.md) - offering remote (network-mode) destinations from an addon.
- [ADR-045](../adr/ADR-045-rtp-docs-menu-consumer.md), [ADR-048](../adr/ADR-048-menu-builders-behind-server-accessor.md) - the menu SPI design.
