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
    The free jar runs on Paper, Spigot, Folia, Fabric, and NeoForge as-is - including
    single-jar cross-server `/rtp` through the bundled `proxy-direct` transport. LeafRTP-Pro
    adds the shaded database drivers (the SQL/Redis shared-state transports) and a
    parallelized Folia scheduler tuned for regionised threading; on the free build Folia
    runs on the Paper-optimized scheduler and still performs well. Optional soft dependencies
    (all auto-detected): Vault (economy charge), PlaceholderAPI, ProtocolLib.

## Where to start

- Read [Intended usage](intended-usage.md) first - it is the mental model the rest of the
  documentation assumes.
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
