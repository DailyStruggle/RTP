# Subsystem Blast-Radius & Risk Classification

Gitar uses blast-radius risk classification to demand proof proportional to the potential impact of changes. Routine changes must not dilute focus, while high-consequence concurrency, memory, or network modifications require mandatory human scrutiny and rigorous empirical validation.

---

## Critical Blast Radius (High Risk)
*Mandatory maintainer review required. Automated merge strictly prohibited. Empirical validation attestation required.*

The following subsystems govern concurrent execution, direct hardware/JVM resource management, or distributed state across servers:

- **Execution Pipelines & Task Lifecycles:**
  - `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/teleport/`
  - `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/` (`PregenTask`, `FillTask`, `LoadChunksTask`)
- **Resource & Memory Management:**
  - `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/MemoryTracker.java`
  - Chunk ticket acquisition and lifecycle adapters across all platforms.
- **Regional Scheduling & Concurrency Adapters:**
  - `platforms/rtp-folia/` (all classes)
  - `RTPScheduler` SPI implementations across Bukkit, Paper, Fabric, and NeoForge.
- **Region Prefiltering & Chunk Format Decoders:**
  - `api/anvil-api/` (Anvil, Linear, and chunk layout decoders; ADR-016, ADR-077).
  - Off-tick cache implementations (`L3BacklogCache`, ADR-028).
- **Cross-Server Network & Token Lifecycles:**
  - Multi-server proxy coordination, reservation token lifecycles, and network packet protocols (`ADR-036`).

---

## Moderate Blast Radius (Medium Risk)
*Detailed review required. Verification of configuration round-tripping, command auditing, and localized unit tests.*

- **Command Architecture & Subcommand Parsing:**
  - `commands-api/` module.
  - `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/`
- **Configuration Parsing & Schemas:**
  - `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/`
  - Deserialization logic and snakeyaml wrappers (ADR-025).
- **Spatial Distribution & Mathematics:**
  - Region selection algorithms (`selection.region.selectors.*`, Archimedean spiral mapping ADR-001, ADR-009).
- **Platform Adapters (Non-Threading):**
  - Platform-specific event listeners and player wrapper classes in `rtp-bukkit`, `rtp-paper`, `rtp-fabric`, `rtp-neoforge`.

---

## Minimal Blast Radius (Low Risk)
*Standard CI review. Focus on formatting, encoding hygiene, and documentation accuracy.*

- **Documentation & Notes:**
  - Markdown files under `docs/` and root documentation (`README.md`, `CHANGELOG.md`).
- **Localization Updates:**
  - Language files under `lang/` that preserve complete key parity with `messages.yml`.
- **Standalone Test Fixtures:**
  - New unit tests under `src/test/` that do not alter production bytecode.
- **Visual Effects:**
  - Asynchronous particle/sound additions in `effects-api`.
