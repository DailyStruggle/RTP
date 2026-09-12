# Platform Support Matrix

> **Scope:** Repository-wide platform, Minecraft version, and Java runtime compatibility.
> **Requirement:** REQ-RTP-SYS-001 (Java 21+ required), ADR-021 (legacy MC and Java out of scope), ADR-033 (NeoForge in scope), ADR-036 (network mode / proxies).
> **Rule:** Every cell is explicitly classified as **Tested**, **Best-effort**, or **Unsupported**. No cell may say "should work".

---

## 1. Classification Definitions

- **Tested:** Verified by automated CI build/test suites, dedicated platform test harnesses (e.g., devstack acceptance tests, `rtp test *` subcommands, or ArchUnit/mock suites), or verified dev-server runs on each release candidate. Regressions in tested environments block release.
- **Best-effort:** Compatible architecture or downstream fork sharing the execution path of a tested platform, but lacking dedicated automated CI verification on every commit. Community bug reports are accepted and addressed, but fixes are prioritized behind tested platforms.
- **Unsupported:** Incompatible threading models, missing required platform APIs, unsupported Java versions (< 21), or platforms explicitly declared out of scope (e.g. legacy Forge <= 1.20.1, Sponge, Bedrock-native). Bug reports on unsupported platforms are closed without investigation.

---

## 2. Java Runtime Support

RTP requires Java 21 or higher across all platforms and components (REQ-RTP-SYS-001).

| Java Version | Support Status | Notes |
|:---|:---:|:---|
| **Java 21 LTS** | **Tested** | Primary reference runtime. CI target and base compilation bytecode target across all modules. |
| **Java 22** | **Best-effort** | Non-LTS release; verified compatible with standard JVM language features. |
| **Java 23** | **Tested** | Tested against standard multi-release builds; compatible with JVM toolchains. |
| **Java 25+** | **Best-effort** | Tracked for modern experimental carriers (e.g. MC 26.x unobf Mojmap carriers). |
| **Java <= 20** | **Unsupported** | Fails closed on initialization. Modern language features, records, and virtual threads require Java 21+. |

---

## 3. Platform x Minecraft Version Matrix

### 3.1 Backend Platforms (Server JVMs)

| Minecraft Version | Paper (+ forks) | Folia | Spigot | Fabric | NeoForge |
|:---|:---:|:---:|:---:|:---:|:---:|
| **MC 26.x (Snapshot / Experimental)** | **Tested** | **Tested** | **Best-effort** | **Tested** | **Best-effort** |
| **MC 1.21.x** (1.21.0 - 1.21.4+) | **Tested** | **Tested** | **Tested** | **Tested** | **Tested** |
| **MC 1.20.5 - 1.20.6** | **Tested** | **Tested** | **Best-effort** | **Tested** | **Unsupported** |
| **MC 1.20.0 - 1.20.4** | **Tested** | **Tested** | **Tested** | **Tested** | **Unsupported** |
| **MC 1.19.4 and older** | **Unsupported** | **Unsupported** | **Unsupported** | **Unsupported** | **Unsupported** |

#### Platform-Specific Notes:
- **Paper & Forks (Leaf, Leaves, Purpur, Pufferfish, Airplane, DivineMC):**
  - **Status:** **Tested** (Paper); **Best-effort** (downstream forks).
  - Uses native Paper asynchronous chunk loading (`World.getChunkAtAsync`). Linear (`.linear` / ZSTD) format supported via off-tick pre-filtering (ADR-077).
- **Folia:**
  - **Status:** **Tested**.
  - Operates strictly under Folia Region & Entity schedulers with Count-Bound task pipelines (ADR-004, ADR-015). Off-tick Anvil/Linear pre-filtering on common pool avoids cross-region hops.
- **Spigot:**
  - **Status:** **Tested** (1.20.1, 1.21.x); **Best-effort** (point releases).
  - Uses background Anvil (`.mca`) parser for off-tick candidate pre-filtering to prevent main-thread chunk load stalls (S-005).
- **Fabric:**
  - **Status:** **Tested** (1.20.x, 1.21.x, 26.x).
  - First-class Loom-remapped obf and Mojmap-unobf carrier modules with native async chunk futures (`ServerLevel.getChunkSource().getChunkFuture`) and `FabricScheduler`.
- **NeoForge:**
  - **Status:** **Tested** (1.21.x); **Best-effort** (26.x).
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
   - Boots multi-node Docker Compose network: 1 Redis + 2 Velocity proxies + 2 Paper backends + 1 Folia backend + 1 Fabric backend.
   - Executes cross-server reservation token redemption, timeout reaping, and failover validation.
