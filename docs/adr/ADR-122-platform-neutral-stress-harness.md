# ADR-122 - Platform-Neutral Stress Harness: Neutral Core, Thin Carriers, Harness-Local Host SPI

**Status:** Accepted (implemented 2026-10-24)
**Date:** 2026-10-23
**Linear:** RTP-55

## Context

`helpers/StressTestRTP` is the in-server half of the [ADR-111](ADR-111-live-server-behaviour-fuzzing.md) leak and behaviour fuzzer. It is a Bukkit plugin of 37 files, so Tier L fuzzing reaches Bukkit, Paper and Folia only. Fabric and NeoForge servers cannot be fuzzed, although RTP ships on both.

Most of the harness does not need Bukkit:

| Group | Files |
|---|---|
| Already platform-neutral (12) | `Attempt`, `AttributionSource`, `MetricsRecorder`, `ModeClassifier`, `RampEvaluator`, `RampWriter`, `SparkProfileSummariser`, `GcSampler`, `JfrAllocationProfiler`, `CpuSampler`, `StorageProfiler`, `CoverageProbe` |
| Neutral logic behind thin Bukkit calls (22) | `Sched`; the event probes `TeleportProbe`, `DirectTeleportProbe`, `ChunkLoadCounter`, `ResidencySampler`, `TicketFootprintProbe`, `LeakProbe`; `RunProvenance`, `SchedulerGauge`, `LoadGateFlush`, `SyncLoadAttributor`, `SparkHook`, `Runner`, `Targets`, `HeapPressureWatcher`, `LandingInspector`, `HarnessTeleporter`, `TickThreadDetector`, `TpsMsptHeapSampler`, `RegionTpsSampler`, `ConsoleWatcher`, `FoliaRegionMonitor` |
| Bukkit-only (3) | `StressCommand`, `StressHost` (extends `org.bukkit.plugin.Plugin`), `StressTestRTPPlugin` |

Three constraints bound the design:

- **Black box.** Competitor arms and the `direct` control run without LeafRTP installed. The harness therefore cannot use RTP's installed `RTPServerAccessor`, and `helpers/` shall not depend on `rtp-api` or `rtp-core`.
- **Existing neutral seam.** `api/server-api`, the foundation of [ADR-116](ADR-116-effects-through-neutral-server-accessor.md), is dependency-free and holds `ServerAccessor` (version, platform, `PlatformFamily`, compatibility checks, logging), the `ServerAccessors` holder and `PlatformFamily`. It covers identity and logging, not players, worlds, scheduling or events.
- **Emergent leak oracle.** Leak detection screens a class histogram against the same-run control and against departures from the run's own baseline. It names no classes. That property shall hold on every platform.

The Python drivers (`scripts/bench_fuzz.py`, `scripts/bench_overnight.py`) also assume Bukkit: jars in `plugins/`, LuckPerms for permissions, the `plugins` command reply to confirm the target loaded, and the `Could not pass event` log line as an oracle input.

## Decision

1. **Module split.** The harness shall be split into:
   - `stresstest-core`: platform-neutral. It owns the class histogram, the listener and task census, JaCoCo coverage, the CSV and JSON writers, the samplers and profilers, the event-probe logic and the `/rtpstress` command logic. It shall import no `org.bukkit.*`, `io.papermc.*`, `net.minecraft.*`, `net.fabricmc.*`, `net.neoforged.*` or `io.github.dailystruggle.rtp.*` type.
   - `stresstest-bukkit`: thin carrier for Bukkit, Paper and Folia. It holds the plugin entry point, command registration and Bukkit event mapping.
   - `stresstest-fabric`: thin carrier for Fabric. A NeoForge carrier shall follow the same shape.
2. **Harness-local host SPI.** A host interface in `stresstest-core` shall replace `StressHost` and shall be the only platform seam the core uses. It shall expose:
   - logging and configuration;
   - command registration, sender reply and sender permission check;
   - console command dispatch;
   - online players with UUID, name, location and world;
   - worlds, and player teleport;
   - scheduling on the global, region, entity and async contexts, each delayed and repeating, with cancellation;
   - a tick-thread check, and TPS and MSPT readings;
   - server identity;
   - the loaded plugin or mod list with name, version, classloader and jar;
   - event subscription for join, quit, teleport, chunk load and chunk unload;
   - a listener census and a pending-task census where the platform offers them;
   - chunk tickets where the platform offers them.

   An operation the platform does not offer shall be reported as `not measured` in every output, as the census already is on Folia. It shall not return zero or an empty result in place of a measurement.
3. **Identity through server-api.** The host SPI shall implement `server-api`'s `ServerAccessor` for identity and logging. Each carrier shall shade and relocate `server-api` so that it never clashes with the copy an installed LeafRTP provides. Nothing harness-specific shall be added to the published `server-api`.
4. **Emergent oracle preserved.** Leak detection on every carrier shall remain the control- and departure-screened class histogram. No carrier shall name, allow-list or deny-list classes. Class names on obfuscated Fabric carriers shall need no remapping, because the oracle compares classes against the same-run control and never by name.
5. **Per-carrier teleport and jar ownership.** The `direct` control arm (`HarnessTeleporter`) shall teleport through the host SPI, with one implementation per carrier. Jar ownership for attribution and coverage `includes=` shall come from the plugin classloader on Bukkit and from `FabricLoader` mod origins on Fabric.
6. **Python platform profile.** `scripts/bench_fuzz.py` and `scripts/bench_overnight.py` shall select a platform profile that fixes:
   - the jar folder (`plugins/` or `mods/`);
   - the permission backend (LuckPerms export, or ops / Fabric permissions);
   - the loaded-target check (`plugins` reply, or the mod list);
   - the log oracle lines. `Could not pass event` shall apply only to the Bukkit profile. Stack-trace attribution through `--plugin-packages` shall apply to every profile.

   The ADR-111 tool invariants (plugin-agnostic, stdlib Python, seeded, isolated, same-run control) shall hold under every profile.
7. **Verification.** The Bukkit carrier shall reproduce the existing harness results with byte-compatible CSV output. The Fabric carrier shall be verified on a Fabric devstack.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Use RTP's installed `RTPServerAccessor` | Absent on competitor and control arms; breaks the black-box contract by making `helpers/` depend on `rtp-api` |
| Grow `server-api` with players, scheduling, events and censuses | Puts harness-only surface into a published API that effects-api and addons depend on |
| Separate Fabric harness written from scratch | Two copies of the histogram, census and writers drift, and cross-platform results stop being comparable |
| Name leaking classes per platform (allow-lists or deny-lists) | Breaks the emergent oracle and the plugin-agnostic invariant of ADR-111; needs a mapping per obfuscated version |
| Keep the harness Bukkit-only | Leaves Fabric and NeoForge, both shipped platforms, without Tier L coverage |

## Consequences

- **Positive:** Fabric, and later NeoForge, servers can be fuzzed with the same procedure and the same emergent leak oracle as Bukkit. Results stay comparable across platforms because one core produces them. The published `server-api` gains nothing harness-specific. Platform gaps show up as `not measured` instead of as false zeros.
- **Negative / Trade-offs:** The module split moves most of the 37 files and touches every carrier entry point. Each carrier maps its own events (join, quit, teleport, chunk load and unload) onto the SPI, and each carrier needs its own teleport for the `direct` control arm. Shading and relocating `server-api` adds a build step per carrier. The Python drivers carry a profile per platform, and the Bukkit-only `Could not pass event` signal has no direct equivalent on Fabric, so stack-trace attribution carries that load there.

## References

- [ADR-111](ADR-111-live-server-behaviour-fuzzing.md) - live-server behaviour fuzzing
- [ADR-116](ADR-116-effects-through-neutral-server-accessor.md) - neutral server accessor (`api/server-api`)
- Linear: RTP-55
- [`helpers/StressTestRTP/LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md)
- `api/server-api/src/main/java/io/github/dailystruggle/server/api/ServerAccessor.java`, `ServerAccessors.java`, `PlatformFamily.java`
- `scripts/bench_fuzz.py`, `scripts/bench_overnight.py`
