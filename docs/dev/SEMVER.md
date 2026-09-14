# Semantic Versioning (SemVer) Contract

> **Scope:** Repository-wide versioning policy, public API definition, internal boundary classification, and binary compatibility commitments.
> **Related:** [DEPRECATION_POLICY.md](DEPRECATION_POLICY.md), [ARCHITECTURE.md](ARCHITECTURE.md), [ADR-011](../adr/ADR-011-rtp-api-module.md), [ADR-094](../adr/ADR-094-quality-engineering-gates.md).

---

## 1. SemVer 2.0.0 Commitment

RTP adheres to [Semantic Versioning 2.0.0](https://semver.org/) (`MAJOR.MINOR.PATCH`):

- **`MAJOR` version bump (X.0.0):**
  - Breaking changes to the public API contracts (`rtp-api` and SPI framework interfaces).
  - Removal of previously deprecated public API methods, classes, or interfaces.
  - Incompatible changes to core configuration schema syntax requiring manual operator migration.
  - Dropping support for previously tested Minecraft LTS/stable lines or raising the minimum Java runtime baseline.
- **`MINOR` version bump (X.Y.0):**
  - Additions of new backwards-compatible public API methods, interfaces, or SPI hooks.
  - New platform adapter support or new optional subsystems (e.g. proxy backends, new region shapes).
  - Introduction of deprecation notices (`@Deprecated(forRemoval = true)`) for public API elements.
  - Backwards-compatible configuration additions and migrations.
- **`PATCH` version bump (X.Y.Z):**
  - Backwards-compatible bug fixes, performance optimizations, and documentation updates.
  - Point-release compatibility shims for minor Minecraft or server platform updates that do not alter the public API contract.
  - No changes to public API signatures or breaking configuration defaults.

---

## 2. Public API vs. Internal Code Boundary

To make compatibility guarantees actionable and auditable, the codebase is strictly segregated into **Public API** (governed by SemVer guarantees) and **Internal Implementation** (subject to change without breaking SemVer).

### 2.1 Public API Surface (SemVer-Guaranteed)

The public API is designed for third-party addon developers, external integrations, and platform bridge authors. The following modules and packages constitute the public API:

1. **`rtp-api` (`io.github.dailystruggle.rtp.api.*`):**
   - Public interfaces: `RTPWorld`, `RTPPlayer`, `RTPLocation`, `RTPChunk`, `RTPScheduler`, `RTPRegion`, etc.
   - Public event hooks, exception types, and configuration model abstractions.
   - Addon self-registration interfaces via `RTPServerAccessor`.
2. **Platform-Neutral SPI Modules:**
   - **`commands-api` (`io.github.dailystruggle.commandsapi.common.*`):** Command tree abstractions, parameter parsers, command contexts.
   - **`effects-api` (`io.github.dailystruggle.effectsapi.common.*`):** Platform-neutral visual/auditory effect models.
   - **`maps-api` (`io.github.dailystruggle.mapsapi.common.*`):** Minimap/chart rendering models and specs.
   - **`metrics-api` (`io.github.dailystruggle.metricsapi.common.*`):** Telemetry SPI interfaces.
   - **`anvil-api` (`io.github.dailystruggle.anvilapi.*`):** Platform-neutral Anvil/Linear chunk and region file decoders.
   - **`tags-api` (`io.github.dailystruggle.tagsapi.*`):** Block and biome tag query interfaces.
   - **`yaml-api` (`io.github.dailystruggle.rtp.common.configuration.yaml.*`):** Hand-rolled zero-dependency YAML parser AST types (`RtpYaml*`).

**Public API Guarantees:**
- Binary and source compatibility preserved across `MINOR` and `PATCH` releases.
- Deprecation cycle: At least two minor versions (or one major release) notice with `@Deprecated(forRemoval = true, since = "...")` before removal (per [DEPRECATION_POLICY.md](DEPRECATION_POLICY.md)).
- No unannounced signature alterations, return-type modifications, or thrown checked-exception additions.

### 2.2 Internal Implementation (Non-Public, No SemVer Guarantees)

Classes and packages outside the designated public API surface are internal implementation details:

1. **`rtp-core` (`io.github.dailystruggle.rtp.common.*`):**
   - Core runtime algorithms: selection pipeline (`TeleportPipelineTask`), spiral coordinate math, caching engines (`RegionQueueManager`), memory trackers (`MemoryTracker`), and database accessors.
   - Internal utility classes, internal config parser implementations, and concurrency machinery.
   - *Note for Addons:* While advanced addons may inspect core classes, these classes do not carry SemVer stability guarantees and may be refactored across minor versions.
2. **Platform Adapters & Shims:**
   - `rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`.
   - Per-version NMS/intermediary carrier shims (`rtp-*-vXX_YY_R1`).
   - Platform-specific scheduling, reflection, and event listeners.
3. **Assembly & Entry Points:**
   - `rtp-plugin` bootstrap, jar shading, and command forwarding classes.
   - `rtp-proxy-velocity` internal channel messaging handlers.

---

## 3. Deprecation and Evolution Rules

- Deprecations in the public API must be documented in `CHANGELOG.md` under `### Deprecated`.
- Removal of public API elements occurs only upon a `MAJOR` version boundary.
- If an urgent security or platform-safety fix requires altering an API contract in a minor release, it must be documented prominently in the release notes with an emergency compatibility shim or migration guide provided.
