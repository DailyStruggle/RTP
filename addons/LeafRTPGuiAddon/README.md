# LeafRTPGuiAddon (DonutSMP-style destination picker)

A polished, drop-in **destination picker** for RTP, structured the same way the plugin
itself is - a platform-neutral core plus thin per-platform renderers. Out of the box it
renders a "DonutSMP-style" clickable menu so a casual admin gets the flashy UI the moment
the jar is dropped in, while every line of safety/validation stays inside the RTP engine.

## Module layout (mirrors the project platform model)

| Module | Platform | Responsibility |
|--------|----------|----------------|
| `rtp-gui-common` | none (pure engine) | The menu *model*, config, the render seam, and the teleport submit. No `org.bukkit.*`. |
| `rtp-gui-bukkit` | Bukkit/Paper/Folia | Chest-inventory renderer + click listener. |
| `rtp-gui-fabric` | Fabric (MC 26.x) | Server-side container (chest) screen renderer. |
| `rtp-gui-neoforge` | NeoForge (MC 26.x) | Server-side container (chest) screen renderer. |
| `rtp-gui` | none (assembly) | The single distributable: shades `rtp-gui-common` + every platform renderer and carries all three loader descriptors. |

The split exists because only one thing is genuinely platform-specific: *drawing* the menu.
Everything else - listing the player's destinations, decorating them with live status,
config, the teleport submit, and result feedback - is identical on every platform and lives
in `rtp-gui-common`, built on the same engine abstractions the reference `LeafRTPCountdownAddon`
uses (`RTP.scheduler`, `RTP.serverAccessor`, and `ConfigParser`).

### How the pieces connect

- `rtp-gui-common` ships an `RTPAddon` (`RTPGuiCommonAddon`, discoverable via
  `META-INF/services`). It registers the `guimenu.yml` `ConfigParser` and binds the bare
  `/rtp` root action (ADR-056) to open the menu via whatever `MenuRenderer` is installed.
- A platform module registers its renderer(s) into the `GuiRenderers` registry under a
  **style key** (`chest`, `book`, ...) and (on Bukkit) registers the common addon
  programmatically. The `menuStyle` value in `guimenu.yml` selects which registered style
  a bare `/rtp` opens, so an admin can swap the GUI (chest <-> book) without a code change;
  an unknown/unset style falls back to any available renderer, and with none registered a
  bare `/rtp` cleanly defers to RTP's classic teleport - it never silently no-ops.
- A new renderer is just a `MenuRenderer` whose `key()` returns its style; register it with
  `GuiRenderers.register(...)` and set `menuStyle` to that key. This is the extension point
  for shipping a book GUI alongside the chest GUI.
- `MenuModel.build(...)` reads the player's allowed targets + status once; the renderer
  consumes that immutable model and never re-derives anything.

> **Single multi-loader jar.** Like RTP itself, the addon ships as **one jar**
> (`rtp-gui`) that bundles `rtp-gui-common` plus every platform renderer and carries all
> three loader descriptors at the jar root: `plugin.yml` (Bukkit), `fabric.mod.json`
> (Fabric), and `META-INF/neoforge.mods.toml` (NeoForge). Each loader reads only its own
> descriptor and starts the matching entry point, which registers **that platform's**
> `MenuRenderer` (and the common `RTPAddon`) at runtime - the same "register the
> implementation for the current platform" model RTP uses. Renderers are also published
> via `META-INF/services` (SPI), so the common addon's `ServiceLoader` pass binds the
> present platform's renderer even where its entry point did not run; a renderer whose
> platform classes are absent simply fails to instantiate and is skipped.
>
> The Fabric / NeoForge carriers target MC 26.x (Mojmap, JDK 25) and build on the same
> Loom-unobf / ModDevGradle toolchains as RTP's own 26.x carriers; drop them on a
> JDK-21-only or network-constrained host with `-PexcludeJdk25` (and the whole NeoForge
> set with `-PexcludeNeoforge`).

## What it does

Type `/rtp` (or `/rtp gui`) to open the menu. Each slot is one destination the player is
allowed to use (default RTP, configured regions, per-world targets), decorated with the
player's live status (ready / on-cooldown / cost / unavailable). Clicking a slot submits a
teleport. A tile at the bottom shows server health (TPS / MSPT / player count).

## Configuration (`guimenu.yml`)

Config is registered with RTP's own config system, so the file is created on first boot in
the RTP data folder, reloads on `/rtp reload`, and never needs manual pasting. The defaults
produce the DonutSMP-style layout; every key is optional and falls back to that default.
Keys cover the renderer style (`menuStyle`: e.g. `chest` or `book`), the title, row count,
filler material, the per-target-kind and per-availability icon material *names*, the
dashboard toggle, and the user-facing messages. Material values are platform-neutral names;
each renderer maps them to its own item type.

### Region biome-map icons (`regionIconStyle`)

Set `regionIconStyle: biome-map` to draw each named-region row's icon as the region's live
biome render (the same chart `/rtp visualization biomes` produces) instead of the static
`iconRegion` material. This reuses RTP's bundled `maps-api` `MapView` path
(maps-api-ADR-002, agreed-upon path): the Bukkit renderer allocates **one shared map id per
region** (viewer-independent), repaints it lazily behind a 60s staleness window via
`renderEphemeral`, and stamps a `FILLED_MAP` into the slot. It adds no new `maps-api`
delivery method and no off-main raw-packet path. The work runs on the main thread during the
menu open; if the map subsystem is unavailable, the region has no scanned biome data yet, or
the renderer cannot draw it, the row falls back to the `iconRegion` material automatically.
The default is `material` (the static icon), so this is strictly opt-in.

## The security boundary (unchanged)

- **RTP owns safety and validation.** Permission gating, cooldown/cost resolution, and the
  S-001..S-007 prohibitions all live behind the API. The only mutating call the addon makes
  is `teleport(...)`, which re-validates everything server-side and always completes with a
  result (never a silent no-op, per REQ-RTP-S-004).
- **The addon owns presentation and click handling.** Layout, icons, and the
  open/close/click lifecycle are the addon's responsibility. `DestinationPickerListener`
  cancels every click on the read-only chest GUI (anti-dupe) and identifies "our" inventory
  by its custom `InventoryHolder`, never by a spoofable title.

| Need | API call |
|------|----------|
| List the player's allowed destinations (permission-gated) | `RTPAPI.getAllowedTargets(UUID)` |
| Decorate each icon (availability / cooldown / cost) | `RTPAPI.getTargetStatus(UUID, RtpTarget)` |
| Trigger the teleport on click | `RTPAPI.teleport(UUID, RtpTarget)` |
| Server-health dashboard tile | `RTPAPI.getMetricsSnapshot()` |

## Files

`rtp-gui-common`:
- `RTPGuiCommonAddon` - `RTPAddon` entry: registers config + binds bare `/rtp`.
- `GuiMenuKeys` / `guimenu.yml` / `GuiMenuConfig` - the config surface.
- `MenuModel` / `MenuEntry` - the platform-neutral menu contents.
- `MenuRenderer` / `GuiRenderers` - the render seam + its registry.
- `MenuActions` - teleport submit + result feedback (via `RTP.scheduler` / `serverAccessor`).

`rtp-gui-bukkit`:
- `RTPGuiBukkitPlugin` - Bukkit entry: registers the `chest` renderer, registers the common addon.
- `BukkitMenuRenderer` - opens the chest on the main thread.
- `DestinationPickerGui` - maps the neutral model to a chest `Inventory`; the `InventoryHolder`.
- `DestinationPickerListener` - cancels clicks and delegates to `MenuActions`.
- `RegionBiomeMapIcons` - per-region shared map-id + TTL cache that renders a region's biome
  chart to a `FILLED_MAP` icon via the bundled `maps-api` `MapView` path (opt-in via
  `regionIconStyle: biome-map`; falls back to the material icon).

`rtp-gui-fabric` (MC 26.x, Mojmap):
- `RTPGuiFabricInitializer` - Fabric `ModInitializer`: captures the server, registers the
  `chest` renderer and the common addon at `SERVER_STARTED`.
- `FabricMenuRenderer` - opens the container screen for the `ServerPlayer` on the server thread.
- `DestinationPickerMenu` - a read-only `ChestMenu` whose top-row clicks submit via `MenuActions`.

`rtp-gui-neoforge` (MC 26.x, Mojmap):
- `RTPGuiNeoForgeMod` - NeoForge `@Mod`: captures the server, registers the `chest` renderer
  and the common addon on `ServerStartedEvent`.
- `NeoForgeMenuRenderer` / `DestinationPickerMenu` - the NeoForge counterparts of the Fabric pair.

## Planned: "Virtual Rift" warmup effect (ROADMAP Tier 2)

A separate companion addon, `addons/rtp-effects-rift`, is planned as a **demo of the
RTP/EffectsAPI extension surface**: a registrable warmup effect that, during the teleport
delay, sends *client-side only* fake block-change packets around the player so the terrain
appears to "dissolve" into a rift, then restores the real blocks on teleport/cancel. It
makes **no** physical world edits, no physics, and no chunk loads on a region thread.

Status: **not implemented yet** - documented here so the GUI addon (a pure menu + config
showcase) and the rift effect (a pure effects-API showcase) can be demonstrated together.
Intended shape when built:

- Module `addons/rtp-effects-rift`, registered via RTP/EffectsAPI as an additional effect.
- Bukkit/Paper/Folia first (per-player packets via the platform scheduler / entity scheduler);
  ProtocolLib as a soft-depend, cataloged in `docs/dev/EXTERNAL_HOOKS.md` (ADR-026).
- Snapshot/restore bookkeeping unit-tested with the packet send mocked.

## Cross-platform note

Fabric/NeoForge have no Bukkit inventory API, so their renderers open a **server-side
container screen** (`ChestMenu`) instead - the same destination picker, drawn with the
native Minecraft menu system. The `rtp-gui-common` model is platform-neutral, so all three
renderers consume the identical `MenuModel` and route clicks through the same `MenuActions`
submit path. The `chest` style key is shared across platforms, so `menuStyle: chest` selects
the right renderer everywhere.
