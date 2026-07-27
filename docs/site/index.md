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
reroll, without affecting the random distribution. See [Why](why.md) for the source
algorithm.

## Supported platforms

Paper, Folia, Spigot, Fabric, and NeoForge (Minecraft 1.20.x / 1.21.x / 26.x). Java 21+
is required. Legacy Forge is not native - run the Spigot/Paper jar under Arclight /
Mohist.

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

- Read [Intended usage](intended-usage.md) first - it is the mental model the rest of the
  documentation assumes.
- [Commands](https://github.com/DailyStruggle/RTP/wiki/Commands), the interactive
  [Menu](https://github.com/DailyStruggle/RTP/wiki/Menu), and
  [Permissions](https://github.com/DailyStruggle/RTP/wiki/Permissions)
- [Core Configuration](https://github.com/DailyStruggle/RTP/wiki/Core-Configuration) and
  the [Typical Configuration Order](https://github.com/DailyStruggle/RTP/wiki/Typical-Configuration-Order)
- [Regions](https://github.com/DailyStruggle/RTP/wiki/Regions),
  [Shapes](https://github.com/DailyStruggle/RTP/wiki/Shapes), and
  [Biome Controls](https://github.com/DailyStruggle/RTP/wiki/Biome-Controls)
- [What NOT to do!](what-not-to-do.md)

## Beyond the basics

Recent versions also add a multi-server / proxy "network mode" (cross-server `/rtp` via
Velocity with SQL/Redis shared state), runtime metrics, Vault economy, a lifecycle
[Effects](https://github.com/DailyStruggle/RTP/wiki/Effects) engine, and a cross-platform
addon [API](https://github.com/DailyStruggle/RTP/wiki/API).
