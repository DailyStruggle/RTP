# rtp-fabric-ADR-017 - MC 26.4 Fabric adapter

- **Status:** Accepted (2026-10-14).
- **Scope:** `rtp-fabric` (new `rtp-fabric-v26_4_R1` submodule), `settings.gradle`, `rtp-plugin/build.gradle` (bytecode merge wiring), and `rtp-plugin/.../fabric/RTPFabricMod.java` (version dispatch). Does not touch `rtp-core`, `rtp-api`, or any Bukkit-family adapter.
- **Related:** [rtp-fabric-ADR-001](rtp-fabric-ADR-001-multiversion-submodule-layout.md) (multiversion submodule layout + runtime version dispatch), [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md) (obf/unobf common split that the 26.x deobf adapters consume), [rtp-fabric-ADR-015](rtp-fabric-ADR-015-mc-26-3-fabric-adapter.md) (MC 26.3 adapter pattern), [rtp-fabric-ADR-016](rtp-fabric-ADR-016-kept-cache-non-entity-ticking.md) (kept-cache level 32 pinning).

## Context

Minecraft 26.4 is in active preparation and release pipeline. Like 26.1, 26.2, and 26.3, 26.4 ships fully deobfuscated and runs on Java 25.

In `RTPFabricMod.java`, runtime adapter resolution selects an adapter strictly by prefix match against the running MC version string (`RTPFabricMod#adapterFqnFor`). Because `26.4` was not previously mapped, running on MC 26.4 threw an `IllegalStateException` per S-006 (fail loud, never silently no-op).

## Decision

Add an `rtp-fabric-v26_4_R1` submodule and register it for runtime dispatch on the `26.4` line.

1. **New submodule `rtp-fabric/rtp-fabric-v26_4_R1/`.** Matches the unobfuscated Loom 1.15 and JDK-25 toolchain established in `v26_1_R1`, `v26_2_R1`, and `v26_3_R1`. Package `io.github.dailystruggle.rtp.fabric.v26_4_R1`; adapter classes named `V26_4_R1Fabric*`. Version pins: MC `26.4-snapshot-4`, loader `0.19.2`, fabric-api `0.163.0+26.4`. Re-verify the pin when 26.4 reaches final.
2. **`settings.gradle`.** Include `rtp-fabric:rtp-fabric-v26_4_R1` under the existing `!excludeJdk25` gate and map `projectDir`.
3. **`rtp-plugin/build.gradle` bytecode merge.** Dedicated `fabric264Bytecode` configuration merged into both Pro and Lite shaded jars post-`remapJar` so Mojang names are preserved.
4. **`RTPFabricMod#adapterFqnFor`.** Add a `mcVersion.startsWith("26.4")` branch returning `io.github.dailystruggle.rtp.fabric.v26_4_R1.V26_4_R1FabricVersionAdapter`.

## Consequences

### Positive
- Fabric servers on Minecraft 26.4 resolve a dedicated adapter instead of failing at startup. The carrier is compiled against `26.4-snapshot-4` and has not been booted; runtime compatibility is unverified until 26.4 ships final and passes the latest-release smoke.
- Preserves the per-line version isolation pattern (ADR-001/ADR-015), ensuring any 26.4 specific API drift does not impact 26.1, 26.2, or 26.3 carriers.
- Build hosts without JDK 25 continue to build Java 21 modules normally via `-PexcludeJdk25`.
