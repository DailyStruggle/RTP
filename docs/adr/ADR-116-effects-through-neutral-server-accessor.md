# ADR-116 - Effects Through a Neutral Server Accessor, Not Per-Platform Initializers

**Status:** Proposed (targeted after 3.3.0; amends effects-api-ADR-003 and effects-api-ADR-006 on acceptance)
**Date:** 2026-10-18
**Linear:** RTP-70

## Context

Effect classes in `effects-api/common/effects/` (`TitleEffect`, `GlideEffect`, `CommandEffect`, `DeathEffect`, `DropInventoryEffect`, `DropExpEffect`) are platform-neutral. Each platform still registers them from its own `*EffectsInitializer.registerAll()` list of `EffectFactory.addEffect(...)` calls, and no check compares the lists. They have drifted. Registrations in 3.3.0:

| Effect | Bukkit / Paper / Folia | Fabric (obf carriers) | Fabric 26.x (unobf) | NeoForge |
|---|---|---|---|---|
| `GLIDE` | yes | yes | no | no |
| `TITLE` | no | yes | yes | yes (separate `NeoForgeTitleEffect`) |
| `DROP_INVENTORY` / `DROP_EXP` | yes | yes | yes | no |
| `FIREWORK` / `NOTE` | yes | no | no | no |

Every platform's handle can run the missing effects: all handles implement `setGliding`, and Bukkit and both Fabric handles implement `sendTitle`. The gaps are registration lines that were never copied over. `NeoForgeHandles.sendTitle`, `playNote` and `spawnFirework` have empty bodies that point to "version adapters" no class provides.

Effects reach the platform through their own seam: `HandleRegistry` / `PlayerHandle` / `ValueCoercer`. That seam sits beside `RTPServerAccessor` and repeats it, so each platform implements two seams. effects-api-ADR-003's single `EffectRuntime` chokepoint was never built.

Effects used per-platform initializers because effects-api could not depend on the accessor: the accessor once lived in `rtp-core`, and `rtp-core` depends on effects-api. The accessor now lives in `rtp-api`. effects-api is not RTP-specific, though, and `api/effects-api/build.gradle` dropped its `rtp-api` dependency on purpose so the module stays standalone. Depending on `rtp-api` would undo that.

## Decision

1. **A neutral platform-seam module.** The server accessor contract (`RTPServerAccessor` and the player, world and location types it needs) moves out of `rtp-api` into a new module with no `io.github.dailystruggle.rtp` dependency. `rtp-api` depends on it and re-exports it, so addon source stays compatible. effects-api depends on this module and on nothing RTP-specific.
2. **Platform code implements effect primitives on the accessor.** The accessor gains the primitives effects need: title, glide start/stop, sound / particle / potion by string key, note, firework, inventory and experience drop, kill, and command dispatch. Each platform implements them once in its existing accessor. `HandleRegistry`, `PlayerHandle` and the effect registration in the per-platform initializers are removed. Registry lookups (`Sound`, `Particle`, `PotionEffectType`, Minecraft registries) stay platform-side behind string keys, which also replaces the `ValueCoercer` binding.
3. **One registration.** effects-api registers every built-in effect once. A platform that cannot run a primitive reports that explicitly (logged once per primitive, visible from `/effectsapi`). An empty method body is not allowed.
4. **effects-api carries no platform code.** `effectsapi/bukkit` (`BukkitHandles`, Bukkit listeners, `/effectsapi` Bukkit commands) moves to the Bukkit carrier, and `spigot-api` leaves `effects-api/build.gradle`. The Fabric and NeoForge effect classes stay in their carriers as accessor implementations.
5. **Mechanical guards.** A parity test asserts every platform accessor exposes the full built-in effect set or declares each unsupported primitive. An architecture test asserts effects-api imports no `org.bukkit.*`, `net.minecraft.*` or `io.github.dailystruggle.rtp.*` type.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep per-platform initializers; add missing lines | Fixes 3.3.0's gaps but not the cause. The next effect drifts the same way. Acceptable only as a 3.3.x patch. |
| Shared `registerCommonEffects()` over the existing `HandleRegistry` seam | Ends the registration drift but keeps two platform seams per platform. NeoForge's no-op handles show the second seam is where gaps hide. |
| effects-api depends on `rtp-api` directly | No cycle, but it makes a general-purpose module RTP-specific and undoes the deliberate decoupling in `api/effects-api/build.gradle`. |
| Keep `RTPServerAccessor` in `rtp-api` and copy it into effects-api | Two copies of one contract drift like the initializers did. |

## Consequences

- **Positive:** One platform seam per platform. An effect added to effects-api works on every platform that implements its primitives, and unsupported primitives fail visibly. effects-api becomes reusable outside RTP without platform jars on its compile classpath.
- **Negative / Trade-offs:** New Gradle module and a cross-module move of the accessor contract, with japicmp review of `rtp-api`. Every platform adapter grows effect primitives, and NeoForge needs real `sendTitle`, `playNote` and `spawnFirework`. `effectsapi.bukkit.*` and `effectsapi.common.spi.HandleRegistry` disappear from the effects-api artifact, so out-of-tree addons that use them need migration notes in the CHANGELOG. `RTPAPI.serverAccessor` and `RTP.serverAccessor` must be set together on every platform (or merged) before effects read the accessor.

## References

- Linear: RTP-70.
- `api/effects-api/src/main/java/io/github/dailystruggle/effectsapi/bukkit/BukkitEffectsInitializer.java` (`registerAll`)
- `api/effects-api/effects-api-fabric-unobf/src/main/java/io/github/dailystruggle/effectsapi/fabric_unobf/FabricEffectsInitializer.java`
- `platforms/rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/effects/FabricEffectsInitializer.java`
- `platforms/rtp-neoforge/rtp-neoforge-common/src/main/java/io/github/dailystruggle/rtp/neoforge/effects/local/NeoForgeEffectsInitializer.java`
- `api/effects-api/src/main/java/io/github/dailystruggle/effectsapi/common/spi/HandleRegistry.java`
- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/server/RTPServerAccessor.java`
- [effects-api-ADR-003](../../api/effects-api/docs/adr/effects-api-ADR-003-platform-split-bukkit-fabric.md), [effects-api-ADR-006](../../api/effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md), [ADR-048](ADR-048-menu-builders-behind-server-accessor.md), [ADR-113](ADR-113-java-17-baseline-and-26x-support-boundary.md)
- Operator-facing platform table: [`EVENTS_AND_EFFECTS.md`](../admin/configuration/EVENTS_AND_EFFECTS.md)
