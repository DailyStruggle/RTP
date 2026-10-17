# rtp-fabric-ADR-015 - MC 26.3 Fabric adapter

- **Status:** Accepted (2026-09-24).
- **Scope:** `rtp-fabric` (new `rtp-fabric-v26_3_R1` submodule), `settings.gradle`, `rtp-plugin/build.gradle` (bytecode merge wiring), and `rtp-plugin/.../fabric/RTPFabricMod.java` (version dispatch). Does not touch `rtp-core`, `rtp-api`, or any Bukkit-family adapter.
- **Related:** [rtp-fabric-ADR-001](rtp-fabric-ADR-001-multiversion-submodule-layout.md) (multiversion submodule layout + runtime version dispatch), [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md) (obf/unobf common split that the 26.x deobf adapters consume), [rtp-fabric-ADR-014](rtp-fabric-ADR-014-mc-26-2-prerelease-early-adapter.md) (MC 26.2 adapter pattern).

## Context

Minecraft 26.3 is released. Like 26.1 and 26.2, 26.3 ships fully deobfuscated and runs on Java 25.

In `RTPFabricMod.java`, runtime adapter resolution selects an adapter strictly by prefix match against the running MC version string (`RTPFabricMod#adapterFqnFor`). Because `26.3` was not previously mapped, running on MC 26.3 threw an `IllegalStateException` per S-006 (fail loud, never silently no-op).

## Decision

Add an `rtp-fabric-v26_3_R1` submodule and register it for runtime dispatch on the `26.3` line.

1. **New submodule `rtp-fabric/rtp-fabric-v26_3_R1/`.** Matches the unobfuscated Loom 1.15 and JDK-25 toolchain established in `v26_1_R1` and `v26_2_R1`. Package `io.github.dailystruggle.rtp.fabric.v26_3_R1`; adapter classes named `V26_3_R1Fabric*`.
2. **`settings.gradle`.** Include `rtp-fabric:rtp-fabric-v26_3_R1` under the existing `!excludeJdk25` gate and map `projectDir`.
3. **`rtp-plugin/build.gradle` bytecode merge.** Dedicated `fabric263Bytecode` configuration merged into both Pro and Lite shaded jars post-`remapJar` so Mojang names are preserved.
4. **`RTPFabricMod#adapterFqnFor`.** Add a `mcVersion.startsWith("26.3")` branch returning `io.github.dailystruggle.rtp.fabric.v26_3_R1.V26_3_R1FabricVersionAdapter`.

## Consequences

### Positive
- Full runtime compatibility when running on Minecraft 26.3 under Fabric.
- Preserves the per-line version isolation pattern (ADR-001/ADR-014), ensuring any 26.3 specific API drift does not impact 26.1 or 26.2 carriers.
- Build hosts without JDK 25 continue to build Java 21 modules normally via `-PexcludeJdk25`.
