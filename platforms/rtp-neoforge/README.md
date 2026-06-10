# rtp-neoforge — NeoForge platform adapter

In-scope per [ADR-033](../../docs/adr/ADR-033-neoforge-platform-in-scope.md) and the subproject ADR [rtp-neoforge-ADR-001](docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md). This tree is a **sibling** of [`rtp-fabric`](../rtp-fabric) (deliberately not nested under or sharing a tree with it — [`NEOFORGE_NOTES.md`](../../docs/dev/NEOFORGE_NOTES.md) §11) and mirrors the Fabric multiversion submodule layout ([rtp-fabric-ADR-001](../rtp-fabric/docs/adr/rtp-fabric-ADR-001-multiversion-submodule-layout.md)).

### Module layout

```
platforms/rtp-neoforge/
  README.md                     # this file
  REQUIREMENTS.md               # REQ-NEOFORGE-F-* / REQ-NEOFORGE-ARCH-*
  docs/adr/                     # subproject ADRs (restart at 001)
  rtp-neoforge-common/          # Mojmap, version-agnostic NeoForge glue
  rtp-neoforge-v1_21_R1/        # per-MC carrier (NeoForge 21.1.x / MC 1.21.1)
  rtp-neoforge-v26_1_R1/        # per-MC carrier (NeoForge 26.1.x / MC 26.1.2, JDK 25, -PexcludeJdk25 gated)
```

### Supported Minecraft / NeoForge versions

NeoForge support targets **Minecraft 1.21.1 and up** only. NeoForge is Mojmap-at-runtime from 1.20.4+, which is the assumption baked into [rtp-neoforge-ADR-001](docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md) and [`NEOFORGE_NOTES.md`](../../docs/dev/NEOFORGE_NOTES.md) (no obf carrier required). The carrier roadmap:

 Carrier | MC line | NeoForge line | Status | Rationale |
---------|---------|---------------|--------|-----------|
 `rtp-neoforge-v1_21_R1` | 1.21.1 | 21.1.x (pins 21.1.95) | Phase N1 skeleton | Highest-value target: 18 months on, 1.21 is still the most popular NeoForge version (16,000+ mods) and every modern kitchen-sink pack (All the Mods 10, FTB NeoTech) is on 1.21+ NeoForge. |
 (rolling 1.21.x head) | latest 1.21.x (e.g. 1.21.11) | matching 21.x (e.g. 21.11) | planned (add once N2 is runtime-proven) | NeoForge ships a new minor per Mojang drop (21.5/21.6/21.8/21.9/21.10/21.11); keep one carrier tracking the latest 1.21.x so new servers are not pinned to 21.1. No carrier per point release. |
 `rtp-neoforge-v26_1_R1` | 26.1 (pins 26.1.2) | 26.1.x (pins 26.1.2.71) | compiled + runtime-loaded; chunk-stall fix applied (`/rtp` round-trip awaiting runtime re-test) | 1.21 is the last `1.x` series; Mojang's 2026 scheme switches to `26.x`. NeoForge 26.1 is the next stable modding target replacing 1.21.1. The 26.x line is fully deobfuscated (Mojmap with native parameter names) and runs on **Java 25**, so this carrier pins a Java 25 toolchain and ModDevGradle 2.0.141, and is gated under `-PexcludeJdk25` (alongside the JDK-25 Fabric carriers) : a JDK-21-only build still produces a 1.21.x-capable NeoForge jar without it. Built and linked against the real NeoForge 26.1.2 userdev artifacts (26.1.2.71, JDK 25); the adapter loads cleanly at runtime (`Active version adapter: 26.1.2`). A live `/rtp` chunk-generation stall (L1 kept-cache stuck at 0, `getOrLoadChunk` TimeoutException) was traced to `requestFullChunkAsync` removing its own transient load-ticket off the server thread and fixed by relying on `getChunkFuture(create=true)`, matching the Fabric 26.1 path ([rtp-fabric-ADR-008](../rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md)). Re-verify the Mojmap API surface and re-pin versions after any 26.1/26.2 bump. |

**Out of scope: Minecraft 1.20.1 (and earlier) on NeoForge.** 1.20.1 is overwhelmingly a **Forge** ecosystem (ATM9, FTB Inferno, Enigmatica 9 are all Forge), and legacy Forge is out of scope per [ADR-033](../../docs/adr/ADR-033-neoforge-platform-in-scope.md). NeoForge only became the default loader at 1.20.5+ (the post-1.20.4 fork point), and a 1.20.1 NeoForge build would re-enter the obf/mapping era avoided by starting at 1.21. Point 1.20.1 users at Forge (out of scope) or `rtp-fabric`.

### Build (unified jar)

The NeoForge platform ships inside the **unified `LeafRTP-Pro-<version>.jar`**: `rtp-plugin` merges the NeoForge carrier bytecode and `META-INF/neoforge.mods.toml` into the released jar after Loom's remap (Mojmap names preserved), so one artifact loads on Bukkit/Paper/Folia (`plugin.yml`), Fabric (`fabric.mod.json`), Velocity (`velocity-plugin.json`), **and** NeoForge (`neoforge.mods.toml`). Drop the same jar into a NeoForge server's `mods/` folder.

The NeoForge toolchain ([ModDevGradle](https://github.com/neoforged/ModDevGradle)) resolves NeoForge userdev artifacts from network repositories that are not available on every build host, so the NeoForge modules are included **by default** and can be dropped on network-constrained hosts (JitPack, fully-offline CI) by passing `-PexcludeNeoforge` (or setting the `EXCLUDE_NEOFORGE` env var) — see [`settings.gradle`](../../settings.gradle). When excluded, the unified jar simply carries no `neoforge.mods.toml`.

```powershell
# Build the unified jar (includes NeoForge; requires network for NeoForge artifacts)
.\gradlew :rtp-plugin:assemble

# Build just the NeoForge carrier in isolation
.\gradlew :rtp-neoforge:rtp-neoforge-v1_21_R1:build

# Launch a dev server for the /rtp round-trip (Phase N1 exit gate)
.\gradlew :rtp-neoforge:rtp-neoforge-v1_21_R1:runServer

# Offline / network-constrained build (drops NeoForge from the graph)
.\gradlew build -PexcludeNeoforge
```

### Phase N1 status

This is the **bring-up skeleton** (Phase N1, [`MULTI_PLATFORM_PLAN.md`](../../docs/dev/MULTI_PLATFORM_PLAN.md) Phase 4). What ships now:

- ModDevGradle build wiring (`rtp-neoforge-common` + `rtp-neoforge-v1_21_R1`) and `META-INF/neoforge.mods.toml`.
- `@Mod` entry point `RTPNeoForgeMod` wiring the server lifecycle, per-tick scheduler drain, and the `RegisterCommandsEvent` command trampoline.
- `NeoForgeScheduler` — a complete server-thread `RTPScheduler` (port of `FabricScheduler`; NeoForge is single-main-thread, no Folia regions).
- `NeoForgeCommandRegistrar` trampoline scaffold (reuses the `commands-api` Brigadier bridge, [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)).
- `V1_21_R1NeoForgeVersionAdapter` carrier stub for NM-typed surface isolation.

Explicit Phase N1/N2 TODOs (owned by the maintainer, project lead `@leaf_26`), to be completed on a network-capable host:

- `NeoForgeServerAccessor` (the `RTPServerAccessor` implementation) + binding into `RTPAPI.serverAccessor`, plus installing the scheduler via `RTP.scheduler`. The S-006 fail-loud contract must hold for any API entry before this lands.
- Event bridge (join/quit/world-load), database handler, the S-005 async chunk-generation path, anvil-prefilter parity, and non-persistent chunk tickets.
- REQ-traceable guards `ReqRtpNeoforgeS005ChunkLoadingTest` / `ReqRtpNeoforgeS006EarlyApiTest` (see [`REQUIREMENTS.md`](REQUIREMENTS.md) and [`TRACEABILITY.md`](../../docs/dev/TRACEABILITY.md)).
