# RTP_GuiAddon (reference inventory-GUI addon)

A minimal, copy-pasteable example of a **chest-inventory destination picker** for RTP,
built **only** on the stable `rtp-api` GUI-author surface. It is the worked example
for `docs/dev/scratch/PROPOSAL-gui-author-spi.md`.

## What it does

Type `/rtpgui` to open a chest menu. Each slot is one destination the player is allowed
to use (default RTP, configured regions, per-world targets), decorated with the player's
live status. Clicking a slot submits a teleport intent. A single tile at the bottom shows
server health (TPS / MSPT / player count).

## Why it only links `rtp-api`

This addon deliberately does **not** depend on `rtp-core`. It proves a third-party GUI can
be fully functional through the public, semver-stable surface alone:

| Need | API call |
|------|----------|
| List the player's allowed destinations (permission-gated) | `RTPAPI.getAllowedTargets(UUID)` |
| Decorate each icon (availability / cooldown / cost) | `RTPAPI.getTargetStatus(UUID, RtpTarget)` |
| Trigger the teleport on click | `RTPAPI.teleport(UUID, RtpTarget)` |
| Server-health dashboard tile | `RTPAPI.getMetricsSnapshot()` |

## Getting the region list and the world list

A GUI author has two distinct enumeration needs - the destinations a *player* may use, and
the raw worlds/regions configured on the server. They come from two different surfaces:

### Region list (player-facing, permission-gated) - use this for a picker

`RTPAPI.getAllowedTargets(UUID player)` returns the destinations the player is actually
allowed to use, with the same permission gates `/rtp` applies already resolved. The list
always starts with `RtpTarget.defaultRegion()` (a bare `/rtp`), followed by every named
region the player passes the permission check for. Render it verbatim - each entry is a
ready-to-use `RtpTarget` you can hand straight to `teleport(...)`.

```java
for (RtpTarget target : RTPAPI.getAllowedTargets(player.getUniqueId())) {
    switch (target.kind()) {
        case DEFAULT -> // bare /rtp, target.name() is null
        case REGION  -> String regionName = target.name(); // a configured region
        case WORLD   -> String worldName  = target.name(); // a per-world target
    }
}
```

This is the right call for a destination picker because it is permission-filtered: you will
never show a region the player cannot use. It is also read-only - it never teleports.

### World list (server-wide, NOT permission-filtered) - use this for admin/world views

If you need every loaded world regardless of who is looking (e.g. an admin panel, or a
"pick any world" view), read it from the server accessor:

```java
import io.github.dailystruggle.rtp.api.world.RTPWorld;

List<RTPWorld<?>> worlds = RTPAPI.serverAccessor.getRTPWorlds(); // never null, may be empty
RTPWorld<?> overworld = RTPAPI.serverAccessor.getRTPWorld("world"); // by name, or null
```

Turn any world into a teleport target with `RtpTarget.world(worldName)` or
`RtpTarget.world(RTPWorld)`.

> **Caveat:** `getRTPWorlds()` is **not** permission-gated and lists *every* loaded world,
> including ones RTP is not configured for or the player cannot use. If you build buttons
> from it, decorate each with `RTPAPI.getTargetStatus(UUID, RtpTarget)` so disabled / no-
> permission worlds render greyed out, and rely on `teleport(...)` to reject anything
> invalid server-side. For a plain player picker, prefer `getAllowedTargets(...)`, which has
> already done this filtering for you.

> **Pre-init:** both `getAllowedTargets(...)` and the `serverAccessor` calls require
> `rtp-core` to have finished loading. Declare `RTP` as a hard `depend` in your `plugin.yml`
> (this addon does) so the API is ready before your code runs; otherwise the API methods
> throw `IllegalStateException` per REQ-RTP-S-006.

## The security boundary (the whole point)

- **RTP owns safety and validation.** Permission gating, cooldown/cost resolution, and the
  S-001..S-007 prohibitions all live behind the API calls. The only mutating call this
  addon can make is `teleport(...)`, which re-validates everything server-side and always
  completes with a result (never a silent no-op, per REQ-RTP-S-004).
- **The GUI author owns presentation and click handling.** Inventory layout, icons, and the
  open/close/click lifecycle are this addon's responsibility. The classic inventory footguns
  (click-spam, item dupe, drag-into-menu) live here, and a bug in them cannot bypass RTP's
  safety. See `DestinationPickerListener` for the standard anti-dupe guard (cancel every
  click on a read-only chest GUI) and the custom `InventoryHolder` pattern used to identify
  "our" inventory without matching on a spoofable title.

## Files

- `RTPGuiAddon` - plugin entry point; registers the `/rtpgui` command and the click listener.
- `DestinationPickerGui` - builds the chest inventory; the `InventoryHolder` that carries the
  slot-to-target mapping.
- `DestinationPickerListener` - handles clicks and submits the teleport intent.

## Cross-platform note

This is **Bukkit-family only**. Fabric has no Bukkit inventory API, so a chest renderer does
not port to Fabric; a Fabric equivalent would need a server-side screen handler. Player-facing
pickers also work well as books (RTP's native menu framework) - the inventory form is shown
here purely as an example of consuming the GUI-author surface.
