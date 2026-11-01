# LeafRTP: Minecraft Random Teleport (`/rtp` & `/wild`) Plugin and Mod

**LeafRTP is a Minecraft random teleport (`/rtp` and `/wild`) plugin and mod** for Paper,
Spigot, Folia, Fabric, NeoForge, and Velocity. Drop the single jar into `plugins/` or
`mods/` and the default circular region works out of the box - players run `/rtp` or
`/wild` to teleport to a safe random location without lagging the server.

Server owners can adjust region borders visually on a browser map (`/rtp editor`), run the
60-second in-game setup wizard (`/rtp admin setup`), or migrate existing settings in one
command (`/rtp config import`) from BetterRTP, JustRTP, or EzRTP:

- **Zero main-thread chunk stalls:** checks terrain from region files on disk (`.mca` and
  `.linear`) and keeps a pre-warmed queue of safe destinations ready before anyone types
  `/rtp`
- **Easy region setup:** configure simple `CIRCLE`, `SQUARE`, `RECTANGLE`, `ELLIPSE`, or
  `POLYGON` regions using block or kilometer radii (e.g. `radius: 5000b` or `10km`)
- **Even player spacing:** spreads players across the region without clumping at the center
  or repeating known-bad ocean/lava chunks (see [How it works](how-it-works.md) and
  [Why](why.md) for the internal engine design)
- **Built-in gameplay & claim support:** wilderness portals, party scatter, arrival
  schematics, Vault economy, and 18+ land-claim integrations

## Supported platforms

Paper and Spigot 1.18+, Folia 1.21.11+, Fabric 1.20.1 and 1.21+, and NeoForge 1.21.1+, up to
Minecraft 26.3, plus Velocity for cross-server `/rtp`. Java 17+ is required (Minecraft
itself needs Java 21 from 1.20.5 and Java 25 on 26.x). Bug reports are accepted for 1.21.11
through the latest release; older versions are best effort, except Folia, which is
experimental and not supported below 1.21.11 ([support matrix](../dev/SUPPORT_MATRIX.md)).
Legacy Forge is not native - run the Spigot/Paper jar under Arclight / Mohist.

## Install

One jar covers every platform - there is no per-platform download.

1. Download the jar ([Modrinth](https://modrinth.com/plugin/leafrtp) for the free download,
   or [BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/) for paid priority support).
2. Bukkit-family servers (Paper, Spigot, Folia and their forks, Arclight, Mohist, Velocity): drop
   the jar in `plugins/`. Fabric / NeoForge: drop it in `mods/`. Start the server.
3. Run `/rtp` (or `/wild`). Configuration generates on first run under `plugins/RTP/` on
   the Bukkit family, or `config/rtp/` on Fabric / NeoForge. Use `/rtp editor` or
   `/rtp admin setup` whenever you want to customize borders or presets.

!!! note
    Both the free Modrinth download and the paid BuiltByBit listing ship the exact same MIT-licensed
    code, features, Folia scheduler, and cross-server transports (`proxy-direct`, SQL, and Redis) -
    nothing in the plugin is locked. Optional soft dependencies (all auto-detected): Vault
    (economy charge), PlaceholderAPI, ProtocolLib.

## Where to start

- [Quick start](../admin/QUICK_START.md) and [Server administrators guide](../FOR_SERVER_ADMINS.md)
- [Web Workspace & Editor (`/rtp editor`)](../admin/WEB_EDITOR_GUIDE.md) and
  [Migration guide (`/rtp config import`)](../admin/MIGRATION.md)
- [Intended usage](intended-usage.md) - the mental model the rest of the
  documentation assumes
- [Commands](../admin/COMMANDS.md), the interactive menu, and permissions
- [Core Configuration](../admin/configuration/CORE_CONFIG.md) and the
  [Configuration overview](../admin/configuration/CONFIGURATION.md)
- [Regions](../admin/configuration/REGIONS.md),
  [Schematics / shapes](../admin/configuration/SCHEMATICS.md), and
  [Safety](../admin/configuration/SAFETY.md)
- [What NOT to do!](what-not-to-do.md)

## Beyond the basics

Recent versions also add a multi-server / proxy "network mode" (cross-server `/rtp` via
Velocity), runtime metrics, Vault economy, a lifecycle effects engine, and a
cross-platform addon [API](../FOR_ADDON_DEVELOPERS.md).
