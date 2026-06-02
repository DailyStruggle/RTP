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
```

### Build (opt-in)

The NeoForge toolchain ([ModDevGradle](https://github.com/neoforged/ModDevGradle)) resolves NeoForge userdev artifacts from network repositories that are not available on every build host. To keep the default `./gradlew build` green offline, the NeoForge modules are **included only when `-PincludeNeoforge` is passed** (or the `INCLUDE_NEOFORGE` env var is set) — see [`settings.gradle`](../../settings.gradle).

```powershell
# Configure + build the NeoForge modules (requires network for NeoForge artifacts)
.\gradlew -PincludeNeoforge :rtp-neoforge:rtp-neoforge-v1_21_R1:build

# Launch a dev server for the /rtp round-trip (Phase N1 exit gate)
.\gradlew -PincludeNeoforge :rtp-neoforge:rtp-neoforge-v1_21_R1:runServer
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
