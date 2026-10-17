# Platform Support Matrix

> **Scope:** Repository-wide platform, Minecraft version, and Java runtime compatibility.
> **Requirement:** REQ-RTP-SYS-001 (Java 17+ required, plus whatever the host Minecraft version requires), ADR-021 (legacy MC and Java out of scope), ADR-113 (Java 17 baseline, unofficial 1.18+), ADR-033 (NeoForge in scope), ADR-036 (network mode / proxies).
> **Rule:** Every cell is explicitly classified as **Tested**, **Best-effort**, or **Unsupported**. No cell may say "should work".

---

## 1. Classification Definitions

- **Tested:** Verified by continuous or scheduled automated CI pipelines (including the nightly multi-node devstack acceptance harness, dedicated carrier compilation/unit suites, or multi-release ArchUnit/mock gates). Regressions in tested environments block release.
  - *Devstack Scheduled Acceptance (Nightly):* Velocity (Java 25), Paper (1.21.11, Java 25), Folia (1.21.11, Java 25), Fabric (1.21.11, Java 25), and NeoForge (1.21.1, Java 21) across multi-node containerized integration with Redis, cross-server token flow, and lifecycle verification.
  - *Latest-Release Smoke (Nightly):* Paper 26.3 and Fabric 26.3 (Java 25) as single servers (`devstack/run-versions-smoke.sh`): `rtp test accessor` passes, the location queues fill, and the server logs no crash.
  - *CI Build & Adapter Gates (Every Commit):* Compiles, tests, and enforces architectural invariants across all active carrier modules (Java 17 shared and pre-1.20.5 bytecode, Java 21 for the 1.21.x carriers, Java 25 for 26.x and unobf).
  - A version that has only been booted by hand (for example the Java 17 stack in `devstack/docker-compose.legacy.yml`) is **Best-effort**, not Tested.
- **Best-effort:** Compatible architecture or downstream fork sharing the execution path of a tested platform, but lacking dedicated automated end-to-end integration runs in CI on every commit/night. Bug reports are accepted inside the bug-report window below, with fixes prioritized behind tested platforms; outside it they may be closed without a fix.
- **Bug-report window (ADR-113):** Bug reports are accepted for Minecraft 1.21.11 through the latest release, on every platform column below that is not **Unsupported**. Older Minecraft versions keep their classification but are outside the window. A later release will deprecate 1.21.11, which moves the window floor up without removing 1.21.11 support.
- **Unsupported:** Incompatible threading models, missing required platform APIs, unsupported Java versions (< 17), or platforms explicitly declared out of scope (e.g. legacy Forge <= 1.20.1, Sponge, Bedrock-native). Bug reports on unsupported platforms are closed without investigation.

---

## 2. Java Runtime Support

RTP requires Java 17 or higher, together with the Java runtime the host Minecraft version requires (REQ-RTP-SYS-001). The shared modules, the pre-1.20.5 carriers and the plugin entry points are Java 17 bytecode; the 1.21.x and 26.x carriers are Java 21 / 25 bytecode and only load on those Minecraft versions (ADR-113).

| Java Version | Support Status | Notes |
|:---|:---:|:---|
| **Java 17 LTS** | **Best-effort** | Minecraft 1.18 - 1.20.4 servers (Spigot and Paper) and Fabric 1.20.1. Toolchain is still Java 21 with `release = 17`; no Java 17 CI runtime (booted by hand on Paper 1.18.2 and Fabric 1.20.1). |
| **Java 21 LTS** | **Tested** | Primary reference runtime. CI default toolchain and devstack runtime; bytecode target of the 1.21.x carriers. |
| **Java 22** | **Best-effort** | Non-LTS release; verified compatible with standard JVM language features. |
| **Java 23** | **Best-effort** | Non-LTS release; compatible with JVM toolchains. |
| **Java 25+** | **Tested** | Secondary CI toolchain in `.github/workflows/gradle.yml` and devstack for modern experimental carriers (e.g. MC 26.x unobf Mojmap carriers). |
| **Java <= 16** | **Unsupported** | `UnsupportedClassVersionError` (classfile 61). |

---

## 3. Platform x Minecraft Version Matrix

### 3.1 Backend Platforms (Server JVMs)

| Minecraft Version | Bug reports | Paper (+ forks) | Folia | Spigot | Fabric | NeoForge |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **MC 26.4 snapshots** | No | **Unsupported** | **Unsupported** | **Unsupported** | **Best-effort** | **Unsupported** |
| **MC 26.3** (latest release) | Yes | **Tested** | **Best-effort** | **Best-effort** | **Tested** | **Best-effort** |
| **MC 26.1 - 26.2** | Yes | **Best-effort** | **Best-effort** | **Best-effort** | **Best-effort** | **Best-effort** |
| **MC 1.21.11** | Yes | **Tested** | **Tested** | **Best-effort** | **Tested** | **Best-effort** |
| **MC 1.21.2 - 1.21.10** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Best-effort** | **Best-effort** |
| **MC 1.21.1** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Best-effort** | **Tested** |
| **MC 1.21.0** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Best-effort** | **Unsupported** |
| **MC 1.20.2 - 1.20.6** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Unsupported** | **Unsupported** |
| **MC 1.20 - 1.20.1** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Best-effort** (1.20.1) | **Unsupported** |
| **MC 1.18 - 1.19.4** | No | **Best-effort** | **Unsupported** | **Best-effort** | **Unsupported** | **Unsupported** |
| **MC 1.17.1 and older** | No | **Unsupported** | **Unsupported** | **Unsupported** | **Unsupported** | **Unsupported** |

The *Bug reports* column is the ADR-113 window. NeoForge 1.21.1 is nightly-tested but sits below it. Folia's floor is 1.21.11, which coincides with the window floor.

#### Platform-Specific Notes:
- **Paper & Forks (Leaf, Leaves, Purpur, Pufferfish, Airplane, DivineMC):**
  - **Status:** **Tested** (1.21.11 in the nightly multi-server devstack, 26.3 in the nightly latest-release smoke); **Best-effort** (1.18 - 1.21.10, 26.1 - 26.2, and downstream forks). Paper 1.18.2, 1.21.11, 26.1.2 and 26.3 have also been booted by hand with the `api-version: '1.18'` jar.
  - Paper ships no snapshot builds, so 26.4 snapshots are not a Paper target.
  - Uses native Paper asynchronous chunk loading (`World.getChunkAtAsync`). Linear (`.linear` / ZSTD) worlds are supported through the live async chunk load path; core does not pre-filter `.linear` files unless an addon registers a reader (ADR-077).
- **Folia:**
  - **Status:** **Tested** (1.21.11 in the nightly multi-server devstack, plus thread-affinity ArchUnit rules on every commit); **Best-effort** (26.1 - 26.3; no 26.x Folia server runs in CI, Folia 26.1.2 passed a one-off bot sweep); **Unsupported** below 1.21.11.
  - **Floor 1.21.11 (ADR-113 amendment 5):** Folia is an experimental fork whose stable builds track only its current releases (1.21.11 and 26.1 when this floor was set). Older Folia builds are not maintained upstream and can crash on their own; in the bot sweep Folia 1.20.4 and 1.21.4 stopped at boot with `Scheduling chunk load off-main`. The jar still loads on older Folia, but no fix is made for those versions and bug reports from them are closed.
  - Operates strictly under Folia Region & Entity schedulers with Count-Bound task pipelines (ADR-004, ADR-015). Off-tick Anvil pre-filtering on common pool avoids cross-region hops.
- **Spigot:**
  - **Status:** **Best-effort** (1.18 - 26.x; compiles via `rtp-bukkit` adapter and unit tested, but no Spigot server runs in CI).
  - Uses background Anvil (`.mca`) parser for off-tick candidate pre-filtering to prevent main-thread chunk load stalls (S-005).
- **Fabric:**
  - **Status:** **Tested** (1.21.11 in the nightly multi-server devstack, 26.3 in the nightly latest-release smoke); **Best-effort** (1.20.1, 1.21 - 1.21.10, 26.1 - 26.2, and the 26.4 snapshot carrier). Fabric 1.20.1, 1.21.1, 1.21.8 and 26.1.2 have been booted by hand.
  - **Unsupported:** 1.20.2 - 1.20.6. `fabric.mod.json` lets the jar load there and `RTPFabricMod` routes it to the 1.20.1 adapter, but no server has run it, and 1.20.5 changed the chunk-result and item APIs that adapter is compiled against.
  - First-class Loom-remapped obf and Mojmap-unobf carrier modules with native async chunk futures (`ServerLevel.getChunkSource().getChunkFuture`) and `FabricScheduler`.
- **NeoForge:**
  - **Status:** **Tested** (1.21.1 in the nightly multi-server devstack on Java 21); **Best-effort** (1.21.2 - 1.21.11 and 26.x; no server on those versions has been booted).
  - Native ModDevGradle Mojmap runtime mod with `NeoForgeScheduler` and Brigadier command adapter (ADR-033). Legacy Forge (<= 1.20.1) is explicitly **Unsupported**.

---

### 3.2 Proxy & Network Platforms

| Proxy Software | Proxy Version | Support Status | Notes |
|:---|:---:|:---:|:---|
| **Velocity** | 3.3.x+ | **Tested** | Primary reference proxy platform. Implemented in `rtp-proxy-velocity` with Redis/SQL state binding, reservation-token lifecycle, and `devstack` acceptance harness. |
| **BungeeCord** | Modern (1.20+) | **Best-effort** | Supported via backend plugin-messaging transport (`bungeecord:main` channel). Dedicated proxy module (`rtp-proxy-bungee`) planned for Phase 3. |
| **Waterfall** | Final release | **Best-effort** | BungeeCord fork; shares plugin-messaging transport path. |
| **Travertine / HexaCord** | Any | **Unsupported** | Legacy protocol proxies incompatible with modern networking and Java 21. |

---

### 3.3 Hybrid & Modded Server Runtimes

| Platform | Support Status | Notes |
|:---|:---:|:---|
| **Mohist** (1.20.x, 1.21.x) | **Best-effort** | Bukkit/Forge hybrid. Executes via Spigot adapter path with Anvil pre-filtering. |
| **Arclight** (1.20.x, 1.21.x) | **Best-effort** | Bukkit/NeoForge hybrid. Executes via Spigot adapter path. |
| **Sponge / SpongeForge** | **Unsupported** | Incompatible plugin architecture; no Bukkit or Fabric SPI compatibility. |
| **Magma / Banner** | **Unsupported** | Unverified stability on modern Java 21 / hybrid threading. |

---

## 4. Verification & Continuous Validation

Support classifications are backed by automated verification tiers:

1. **Build & Unit Test Verification:**
   - Run on every commit via GitHub Actions (`.\gradlew build` / `./gradlew build`).
   - Validates all platform carriers (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`, `rtp-proxy`).
2. **ArchUnit Prohibition Enforcement:**
   - Verifies architectural boundaries: no platform leaks into `rtp-core` or `rtp-api`, zero main-thread synchronous chunk loading (S-005), and strict thread scheduling compliance.
3. **Multi-Server Acceptance Devstack:**
   - Boots multi-node Docker Compose network: 1 Redis + 2 Velocity proxies + 2 Paper lobbies + 1 Paper backend + 1 Folia backend + 1 Fabric backend + 1 NeoForge backend.
   - Executes cross-server reservation token redemption, timeout reaping, and failover validation.
4. **Latest-Release Smoke:**
   - Nightly `versions-smoke` job in `devstack-acceptance.yml`, one job per service in `devstack/docker-compose.versions.yml` (Paper 26.3, Fabric 26.3).
   - Runs `devstack/run-versions-smoke.sh`: accessor self-test, non-empty location queue, no tick-loop crash, OOM or classfile-version error. The pinned `VERSION` is bumped with each Minecraft release.
