# ADR-057 — Platform-agnostic addon SPI

**Status:** Accepted
**Date:** 2026-05-30

## Context

RTP's reference addons (`RTP_ExampleAddon`, `RTP_GuiAddon`) were authored as Bukkit
plugins: each `extends org.bukkit.plugin.java.JavaPlugin`, ships a `plugin.yml` with a
hard `depend: RTP`, and is discovered and enabled by the Bukkit plugin manager. The
Bukkit-family platforms (Bukkit/Paper/Folia) inherit this path for free, but Fabric and
the proxy JVMs (Velocity/BungeeCord) have no Bukkit plugin loader, so an addon jar never
loads there.

This is inconsistent with the project's architecture: `rtp-core` and `rtp-api` are
platform-agnostic, and almost everything the example addons actually do is already
platform-neutral: config registration via `RTP.configs.putParser(...)`, safety
predicates via `RTPAPI.hooks().verifiers().register(...)`, reload hooks via
`Configs.onReload(...)`, and the teleport/query delegates on `RTPAPI`. Only two
touch-points tied the addons to Bukkit:

1. **Loading** via `JavaPlugin` + `plugin.yml`.
2. **Events** via `org.bukkit.event.Listener` consuming the Bukkit-only
   `io.github.dailystruggle.rtp.bukkit.events.PostTeleportEvent`.

## Decision

Introduce a platform-agnostic addon SPI and discovery mechanism:

- **`io.github.dailystruggle.rtp.api.addon.RTPAddon`** (in `rtp-api`): a lifecycle
  interface with `onLoad()`, `onUnload()`, and `name()`. Implementations declare a public
  no-arg constructor and register themselves in
  `META-INF/services/io.github.dailystruggle.rtp.api.addon.RTPAddon`.
- **`io.github.dailystruggle.rtp.common.addon.AddonRegistry`** (in `rtp-core`, exposed as
  `RTP.addons`): discovers addons via `java.util.ServiceLoader` and/or accepts programmatic
  `register(RTPAddon)` calls from a platform adapter. `loadAll()` invokes `onLoad()` once per
  addon after core initialisation has settled (scheduled as a startup task so `RTPAPI`
  delegates installed by the platform adapter are visible); `unloadAll()` invokes `onUnload()`
  during `RTP.stop()`. Per-addon failures are isolated so one bad addon cannot abort
  startup/shutdown of its peers.
- **Events**: addons observe teleport lifecycle through the existing platform-agnostic
  `TeleportPipelineTask.teleportPostActions` (and sibling `setup`/`load`/`cleanup` lists,
  plus `RTPTeleportCancel.postActions`) rather than the Bukkit `PostTeleportEvent`. These
  lists are already consumed cross-platform by the Bukkit and Fabric effects handlers, so the
  Bukkit event was a redundant, platform-locked mirror.

The reference `RTP_ExampleAddon` is ported to implement `RTPAddon` with zero `org.bukkit.*`
imports; its `plugin.yml` and Bukkit `Listener` are removed and its build trimmed to
`rtp-api` + `rtp-core`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep `JavaPlugin` loading, add per-platform loaders | Duplicates loader logic per platform and keeps `org.bukkit.*` in addons; defeats the platform-agnostic goal. |
| A bespoke RTP event bus in `rtp-api` | Unnecessary: `TeleportPipelineTask` already exposes platform-agnostic post-action runnable lists fired on every platform. |
| Wrap the runnable lists behind an `RTPAPI.hooks().lifecycle()` facade now | Deferred as optional polish; not required to ship the platform-neutral loader and can be added without breaking the SPI. |

## Consequences

- **Positive:** Addons run on every RTP platform (Bukkit/Paper/Folia, Fabric, and proxy JVMs
  where the used API surface permits) with no Bukkit dependency. Discovery is pure JDK
  (`ServiceLoader`). The SPI mirrors the existing `RTPAPI` delegate/facade and ADR-026 hook
  patterns. Lifecycle is uniform and failure-isolated.
- **Negative / Trade-offs:** Existing third-party addons authored against `JavaPlugin` are not
  auto-migrated; a Bukkit back-compat shim (a thin `JavaPlugin` that forwards to
  `RTP.addons.register(...)`) can be provided per platform if needed. Addons touching
  `RTP.configs`/world state remain backend-only; proxy JVMs only support `RTPAPI`
  query/teleport-level addons (documented on the SPI).

## References

- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/addon/RTPAddon.java`
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/addon/AddonRegistry.java`
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java` (`RTP.addons`, startup load, `stop()` unload)
- `addons/RTP_ExampleAddon/` (ported reference addon + `META-INF/services` entry)
- `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/addon/AddonRegistryTest.java`
- ADR-026 (external hook API surface), `docs/dev/EXTERNAL_HOOKS.md`
