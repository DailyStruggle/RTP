# rtp-fabric-ADR-014 - Early/experimental MC 26.2 pre-release Fabric adapter

- **Status:** Accepted (2026-05-31).
- **Scope:** `rtp-fabric` (new `rtp-fabric-v26_2_R1` submodule), `settings.gradle`, `rtp-plugin/build.gradle` (bytecode merge wiring), and `rtp-plugin/.../fabric/RTPFabricMod.java` (version dispatch). Does not touch `rtp-core`, `rtp-api`, or any Bukkit-family adapter.
- **Related:** [rtp-fabric-ADR-001](rtp-fabric-ADR-001-multiversion-submodule-layout.md) (multiversion submodule layout + runtime version dispatch), [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md) (obf/unobf common split that the 26.x deobf adapters consume), [rtp-fabric-ADR-002](rtp-fabric-ADR-002-platform-in-scope.md) (Fabric in scope), [effects-api-ADR-006](../../../../api/effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md).

## Context

Mojang publishes MC 26.2 as a pre-release line (the first artifact we target is `26.2-pre-2`, distributed as `fabric-server-mc.26.2-pre-2-loader.0.19.2-launcher.1.1.1.jar`, with Fabric API `0.150.1+26.2`). The 26.2 line, like 26.1, ships fully deobfuscated, so it uses the same Loom 1.15 unobfuscated regime and JDK-25 toolchain as the existing `rtp-fabric-v26_1_R1` submodule.

Preparing an adapter against the pre-release lets RTP have a working 26.2 build before 26.2 reaches final, rather than scrambling on release day. The cost is low because the per-version adapter surface is small (six classes) and the heavy lifting lives in the mapping-decoupled `rtp-fabric-common` / `rtp-fabric-common-unobf` carriers (rtp-fabric-ADR-007 / ADR-009).

Two facts shape the decision:

1. **Version-string dispatch.** `RTPFabricMod#adapterFqnFor` selects an adapter by the running MC version string, which RTP resolves via `FabricLoader.getModContainer("minecraft").getMetadata().getVersion().getFriendlyString()` (not `SharedConstants`, whose intermediary mapping drifts). For `26.2-pre-2` this friendly string begins with `26.2`, so a `startsWith("26.2")` prefix match catches both the pre-release builds and the eventual `26.2.x` finals.
2. **Pre-release volatility.** A pre-release is a moving target; the pinned `minecraftVersion` / `loaderVersion` / `fabricApiVersion` and the API the adapter binds to can change before 26.2 final.

## Decision

Add an EARLY/EXPERIMENTAL `rtp-fabric-v26_2_R1` submodule, cloned from `rtp-fabric-v26_1_R1`, and register it for runtime dispatch on the `26.2` line.

1. **New submodule `rtp-fabric/rtp-fabric-v26_2_R1/`.** Cloned from `v26_1_R1`: same Loom 1.15 unobf plugin, JDK-25 toolchain, no `mappings` line, plain `implementation` / `compileOnly`, and the `api project(':rtp-fabric:rtp-fabric-common-unobf')` carrier edge. Package `io.github.dailystruggle.rtp.fabric.v26_2_R1`; the six adapter classes are renamed `V26_2_R1Fabric*`. Version pins: MC `26.2-pre-2`, loader `0.19.2`, fabric-api `0.150.1+26.2`.
2. **`settings.gradle`.** Include `rtp-fabric:rtp-fabric-v26_2_R1` under the existing `!excludeJdk25` gate (the module needs JDK 25, same as `v26_1_R1`; JDK-21-only hosts such as JitPack exclude it).
3. **`rtp-plugin/build.gradle` bytecode merge.** The adapter is resolved at runtime by FQN only, so it must be merged into the shaded jar post-`remapJar` (Mojang names preserved, never run through Loom's intermediary remap), exactly like `v26_1_R1`. A dedicated `fabric262Bytecode` configuration carries the jar (its own configuration so the merge closure's `singleFile` resolution stays unambiguous), and a second `mergeFabricUnobfBytecodeIntoJar` pass merges it into both the Pro `remapJar` and the lite `remapLiteJar` outputs. The unobf carriers, already present from the first pass, are skipped by the closure's existing-entry check, so the second pass adds only the `v26_2_R1` adapter classes.
4. **`RTPFabricMod#adapterFqnFor`.** Add a `mcVersion.startsWith("26.2")` branch returning `io.github.dailystruggle.rtp.fabric.v26_2_R1.V26_2_R1FabricVersionAdapter`, and extend the unsupported-version message to list `26.2.x`.

## Consequences

### Positive

- RTP has a build that loads on MC 26.2 pre-releases ahead of the final release.
- Near-zero risk to existing platforms: the new module is JDK-25-gated, dispatched only on a `26.2` runtime, and merged via an isolated configuration. No change to `rtp-core` / `rtp-api` / Bukkit-family code.
- The clone reuses the mapping-decoupled SPI, so when 26.2 final lands the expected delta is a version re-pin plus any adapter-body fixes for API changes, not a new module.

### Negative / Limitations

- **Pre-release pin is volatile.** The pinned MC / loader / fabric-api versions and the dispatch matcher must be re-verified when 26.2 reaches final. This is called out inline in the new `build.gradle`, in `settings.gradle`, and in the `adapterFqnFor` comment.
- **Cloned bodies track `v26_1_R1`.** Any 26.2 API break (chunk-ticket / `DistanceManager` shape is the historical breakage point; see rtp-fabric-ADR-004 / ADR-006) must be ported into the `v26_2_R1` adapter; until verified on a live 26.2 runtime, the adapter is best treated as experimental.
- **JDK 25 required to build.** Contributors without JDK 25 see only this submodule (and the other 26.x carriers) fail toolchain provisioning; the rest of the build remains on JDK 21.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Wait for 26.2 final before adding an adapter | Defeats the purpose of preparing early; release-day scramble. The clone cost is low enough to justify tracking the pre-release. |
| Extend `v26_1_R1` to also match `26.2` | The two lines may diverge in chunk-ticket / world API; a shared module would force conditional code and risk the working 26.1 path. Separate submodules mirror the existing per-line split. |
| Add `v26_2_R1` to the existing `fabric26Bytecode` configuration | The merge closure resolves that configuration via `singleFile`, which throws on two jars. A dedicated `fabric262Bytecode` configuration keeps resolution unambiguous. |
