# ADR-113 - Java 17 Bytecode Baseline, MC 26.x+ Official Support Boundary, and Format-Adaptive Unofficial Compatibility

**Status:** Accepted
**Date:** 2026-10-14
**Amends:** [ADR-021](ADR-021-legacy-mc-and-java-support-scope.md) (Legacy Minecraft and Java Support Are Out of Scope)

## Context

[ADR-021](ADR-021-legacy-mc-and-java-support-scope.md) placed legacy Minecraft versions and legacy Java versions out of scope, operating under the assumption that `rtp-core` and `rtp-api` pervasively require Java 21-specific language and library features (virtual threads, pattern matching for switch, record patterns, and sequenced collections), and that lowering the language level would impose a permanent tax on all core development.

A systematic audit of the codebase proved this assumption incorrect:
1. **Language features in production use were already finalized in Java 16 or 17.** Records (JEP 395), sealed classes and interfaces (JEP 409), switch expressions (JEP 361), text blocks (JEP 378), and pattern matching for `instanceof` (JEP 394) are fully available in Java 17 (bytecode version 61).
2. **Java 21-exclusive constructs are not used in core architecture.** Virtual threads (`Thread.ofVirtual`) are deliberately avoided because `RTP.scheduler` (`RTPScheduler` SPI) must handle thread routing to satisfy Folia regional threading invariants (Rule F-001..F-003, `DESIGN.md`). Pattern matching for `switch` and record patterns are not present anywhere in the repository.
3. **The delta to Java 17 bytecode compatibility is exactly 9 lines across 2 APIs:**
   - `SequencedCollection.getFirst()` in 4 test/stress call sites (`MockChunkFutureTest`, `AsyncReplyTestJob`, `QueueStarvationTestJob`, `TestStressCmd`), trivially replaced with `get(0)`.
   - `Math.clamp()` in 5 lines within `api/maps-api` (`RegionWalkPathRenderer`, `SelectionHeatmapRenderer`), trivially replaced with standard min/max clamp arithmetic.

### Ecosystem Realities & User Distribution

Targeting Java 21 bytecode (classfile version 65) across all modules creates an artificial compatibility barrier for a massive segment of server deployments:
- **Minecraft 1.20.1 remains the dominant modding anchor.** Across Fabric, Forge, and NeoForge, thousands of modpacks and dedicated community servers run on 1.20.1 under Java 17. These servers possess modern `Material` registries, native plugin chunk tickets (`addPluginChunkTicket`), and native async chunk loading (`getChunkAtAsync`), satisfying all `S-001` through `S-005` safety invariants. However, a Java 21 bytecode requirement prevents LeafRTP from loading on them with `UnsupportedClassVersionError: 65.0`.
- **Java 17 bytecode executes cleanly on Java 17, Java 21, and upcoming Java 25 runtimes.** In contrast, Java 21 bytecode restricts execution strictly to Java 21+.

### The Maintenance Dilemma vs. Mojang Deobfuscation (MC 26.x+)

- **Mojang deobfuscation in MC 26.x+** represents the emerging architectural standard, eliminating the legacy obfuscation mapping overhead across platforms.
- Providing **official** support (automated devstack acceptance suites, multi-version CI matrix testing, and issue tracker SLAs) for dozens of legacy versions (1.18 through 1.21) would burn substantial engineering bandwidth on transient platform quirks.
- Conversely, adopting an **"officially 26.x+, unofficially run fine everywhere"** policy delivers maximum aggregate utility: server operators on 1.18+ (especially 1.20.1 modded) can run the plugin reliably, while maintainers keep official support gates strictly focused on the modern 26.x+ frontier.

### Resilient Region Format Discovery

Off-tick candidate prefiltering (`rtp-anvil`, ADR-016, ADR-077) reads chunk data from disk without touching main-thread chunk generation. Building on coverage-guided fuzz testing (ADR-102), parsers can discover and tolerate minor structural variations in `.mca` and alternative region file formats. This allows candidate coordinates to be safely verified across diverse world saves without binding core logic to specific Minecraft NMS versions.

### Toolchain and Environment Logistics

On Windows host environments, managing dual JDKs (Java 17 and Java 21/25) and configuring toolchain paths introduces local setup friction. On Linux environments, multi-JDK provisioning is streamlined via system package managers (`yum install java-17-openjdk-devel` / `dnf` / `apt` or SDKMAN), making toolchain switching trivial. Staging the physical build configuration adjustments for the developer's transition to Linux avoids local environment overhead while codifying the architectural direction now.

---

## Decision

1. **Lower Core and Shared Modules Bytecode Baseline to Java 17:**
   - Shared, platform-agnostic modules (`rtp-api`, `rtp-core`, `rtp-anvil`, `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `bstats-api`) shall compile targeting Java 17 bytecode (`release = 17`, classfile version 61).
   - The 9 call sites using Java 21 APIs (`List.getFirst()`, `Math.clamp()`) shall be refactored to standard Java 17 equivalents.
   - REQ-RTP-SYS-001 is amended to reflect Java 17 bytecode compatibility for common modules, while permitting Java 17, 21, and 25 runtimes.

2. **Decouple Carrier Submodule Toolchains:**
   - Platform carrier submodules targeting modern and future Minecraft versions (e.g., `rtp-fabric-v26_1_R1`, `rtp-neoforge-v26_1_R1`, and modern Paper/Folia adapters) are unconstrained by the Java 17 core baseline.
   - Carriers may specify modern language versions (`languageVersion = JavaLanguageVersion.of(21)` or `25`) as required by their target platform, consuming the Java 17 core artifacts without friction.

3. **Establish Official Support Boundary at Minecraft 26.x+:**
   - **Official Support:** Reserved exclusively for Minecraft 26.x and newer (the official deobfuscation era). Official CI validation, automated headless devstack acceptance suites (`devstack/run-acceptance.ps1`), active issue tracker triage on Linear, and SLA commitments apply only to 26.x+.
   - **Unofficial Compatibility:** Best-effort compatibility for Java 17+ and Minecraft 1.18 through 1.21.x (including 1.20.1 modded environments). The plugin shall not introduce artificial version gates or bytecode barriers that prevent execution on these versions. Where runtime primitives satisfy safety rules (`S-001` through `S-005`), the plugin will operate cleanly.
   - **No Legacy SLA:** Maintainers will not accept bug reports, maintain dedicated CI pipelines, or develop intrusive shims for pre-26.x platform quirks. Edge-case adjustments for older environments remain community-maintained.

4. **Format-Adaptive Region Reading via Fuzzing Hardening:**
   - In alignment with ADR-077 and ADR-102, region reading subsystems shall employ lenient parsing, format discovery, and adaptive recovery to accommodate minor format variations without server crashes or dedicated NMS adapters.
   - Strict pre-1.13 worlds (numeric block IDs, lack of flattening, no native async chunk loading) remain unsupported due to fundamental violations of `S-001` (safe block validation) and `S-005` (zero synchronous chunk I/O on the main thread).

5. **Staged Implementation for Linux Environment Transition:**
   - Code adjustments (replacing the 9 Java 21 API call sites) and Gradle toolchain updates (`libs.versions.toml`, `build.gradle` javaToolchain configuration) are staged for implementation following the developer's transition to a Linux environment, where dual Java 17 and Java 21/25 JDKs can be provisioned cleanly via package management (`yum`/`dnf`/`apt`).

---

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Retain Java 21-only baseline repository-wide (ADR-021 status quo) | Forcibly locks out the large Minecraft 1.20.1 modded ecosystem (Fabric/Forge on Java 17) and 1.18–1.20.4 servers for the sake of only 9 lines of code, producing net-negative ecosystem utility. |
| Official CI and SLA support across 1.18–1.21.x | Doubles or triples CI build times and burdens maintainers with legacy platform shims instead of advancing modern frontiers (deobfuscation, network mode, proxies). |
| Backport to Java 8 / 11 (Minecraft 1.8–1.12) | Pre-1.13 lacks `Material` flattening, lacks native plugin chunk tickets (`S-002`), and lacks async chunk loading (`S-005`). Supporting it would require invasive shims and compromise core safety guarantees. |
| Implement Java 17 toolchain immediately on Windows | Incurs manual PATH and multi-JDK environment configuration overhead; deferring execution to the Linux transition allows seamless setup via native package managers. |

---

## Consequences

- **Positive:**
  - Broadens runtime accessibility to Minecraft 1.20.1 modded servers and 1.18–1.20.4 survival networks without code bloat or heavy shims.
  - Preserves all modern Java features in production use (records, sealed types, switch expressions, text blocks, pattern matching for `instanceof`).
  - Maintains strict developer focus by anchoring official support and CI verification to the deobfuscated 26.x+ era.
  - Harnesses coverage-guided fuzz testing (ADR-102) to safely absorb region format deviations off-thread.
  - Zero disruption to current Windows development workflow by staging toolchain rollout for the Linux environment transition.
- **Negative / Trade-offs:**
  - Full local repository compilation will require both Java 17 and Java 21+ JDKs configured in Gradle toolchains.
  - Shared modules cannot use Java 21+ language features (e.g., pattern matching for `switch`, record patterns, sequenced collections) until the common baseline is raised in the future.
  - Pre-26.x server operators encountering platform-specific edge cases must rely on community workarounds or submit self-contained PRs.

---

## References

- [ADR-010](ADR-010-versioned-platform-adapter-submodules.md) - Versioned Platform Adapter Submodules
- [ADR-016](ADR-016-anvil-subsystem.md) - Anvil Read-Only Subsystem
- [ADR-021](ADR-021-legacy-mc-and-java-support-scope.md) - Legacy Minecraft and Java Support Are Out of Scope (amended by this ADR)
- [ADR-077](ADR-077-multi-format-region-support.md) - Multi-Format Region Support: Pluggable Region Readers
- [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md) - Superseding ADR-024: Technical Parity and Pro as SLA & Support Tier
- [ADR-102](ADR-102-coverage-guided-fuzz-testing-and-parser-input-hardening.md) - Coverage-Guided Fuzz Testing and Parser Input Hardening
- REQ-RTP-SYS-001 (Java Runtime Baseline), `docs/dev/REQUIREMENTS.md`
