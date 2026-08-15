# LeafRTP

A random-teleportation plugin built to fill operator needs still unmet by the more
popular RTP plugins:

- performance management (no synchronous chunk loads on the main thread)
- instant response times via a pre-warmed location cache
- multiple regions per world
- placement learning (spatial memory), improving time-stability as the plugin learns
- unique placements, so players more easily find new places with fewer teleports and
  less exploration
- optionally grow regions to add new area as space is used up
- cache locations ahead of time on a timer
- queue teleporting players by command input times

A custom selection algorithm lets the plugin step over learned placements rather than
reroll, without affecting the random distribution. See [Why](site/why.md) for the source
algorithm.

## Supported platforms

**Backend servers** (where the plugin/mod jar runs and teleports happen): Paper, Folia,
Spigot, Fabric, and NeoForge (Minecraft 1.20.x / 1.21.x / 26.x). Java 21+ is required.
Legacy Forge is not native - run the Spigot/Paper jar under Arclight / Mohist.

**Proxy** (a different role, not another backend): the same jar also runs on a **Velocity**
proxy, where it acts as the router/transport for cross-server "network mode" rather than
teleporting players itself. Drop the jar in the proxy's `plugins/`, install RTP on each
backend as usual, and a bare `/rtp` on one server can send a player to a region on
another. The free build ships the `proxy-direct` transport: a lightweight TCP socket that
needs no Redis or SQL, so cross-server `/rtp` works with just the jar on the proxy and each
backend. (Redis and SQL shared-state transports are not bundled in the free jar - they are
LeafRTP-Pro extras.) See [Proxy mode](admin/proxies/INDEX.md).

## Install

One jar covers every platform - there is no per-platform download.

1. Download the jar ([Modrinth](https://modrinth.com/plugin/leafrtp) for the free build,
   or [BuiltByBit](https://builtbybit.com/resources/leafrtp-pro.105418/) for Pro).
2. Bukkit-family servers (Paper, Spigot, Folia and their forks, Arclight, Mohist): drop
   the jar in `plugins/`. Fabric / NeoForge: drop it in `mods/`. Restart the server.
3. Configuration generates on first run under `plugins/RTP/` on the Bukkit family, or
   `config/rtp/` on Fabric / NeoForge.

!!! note
    Folia, multi-server / proxy, and SQL/Redis shared state are LeafRTP-Pro features. The
    free jar runs on Paper / Spigot / Fabric / NeoForge as-is. Optional soft dependencies
    (all auto-detected): Vault (economy charge), PlaceholderAPI, ProtocolLib.

## Where to start

- Read [Intended usage](site/intended-usage.md) first - it is the mental model the rest
  of the documentation assumes.
- [Commands](admin/COMMANDS.md), the interactive menu, and permissions
- [Core Configuration](admin/configuration/CORE_CONFIG.md) and the
  [Configuration overview](admin/configuration/CONFIGURATION.md)
- [Regions](admin/configuration/REGIONS.md),
  [Schematics / shapes](admin/configuration/SCHEMATICS.md), and
  [Safety](admin/configuration/SAFETY.md)
- [What NOT to do!](site/what-not-to-do.md)

## Beyond the basics

Recent versions also add a multi-server / proxy "network mode" (cross-server `/rtp` via
Velocity with SQL/Redis shared state), runtime metrics, Vault economy, a lifecycle
effects engine, and a cross-platform addon
[API](FOR_ADDON_DEVELOPERS.md).
