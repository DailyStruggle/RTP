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

### Amendment 2026-10-15 - Decision 1 implemented ahead of Decision 5

- `release = 17` is set on `compileJava` of the shared closure in the root `build.gradle` (`java17Modules`), on the existing Java 21 toolchain. `javac --release 17` rejects Java 18+ language features and APIs, so no JDK 17 install or toolchain switch is required on any host. Tests stay on the toolchain level.
- The closure is `rtp-api`, `rtp-core`, `anvil-api`, `yaml-api`, `commands-api`, `effects-api`, `metrics-api`, `bstats-api`, `maps-api`, and `rtp-proxy-common` (every project dependency of the shared modules is itself Java 17).
- The audit in *Context* item 2 missed one pattern-matching `switch` (`MenuActionToCommand.toRunCommand`, rewritten as an `instanceof` chain with a permitted-subclass coverage test) and several record deconstruction patterns in `anvil-api` / `maps-api` `equals` methods (rewritten as type patterns).
- `effects-api` no longer applies Loom: its Minecraft-typed Fabric runtime moved to `rtp-fabric-common` (effects-api-ADR-003 amendment 2026-10-15).
- The shipped plugin jar and the platform carriers remain Java 21 bytecode, so REQ-RTP-SYS-001 keeps the Java 21 runtime floor until those are lowered.

### Amendment 2026-10-15 (2) - pre-1.20.5 carrier path lowered; Bukkit 1.18+ and Fabric 1.20.1

- **Scope.** Unofficial targets are Bukkit / Spigot / Paper 1.18 through 1.20.4 and Fabric 1.20.1, all on Java 17. Folia exists only from 1.19.4 and inherits the same path. NeoForge stays 1.21.1+ (NeoForge for 1.20.1 is a Forge 47 fork; a Forge carrier would need its own ADR). Fabric and Forge on 1.18-1.19 are not targeted.
- **Bytecode.** `java17Modules` also covers `rtp-bukkit-common`, `rtp-bukkit-v1_20_R1`, `rtp-paper-common`, `rtp-paper-v1_20_R1`, `rtp-folia-common`, `rtp-folia-v1_20_R1`, `rtp-fabric-common`, `rtp-fabric-v1_20_R1` and `rtp-plugin` (main and lite source sets). `rtp-plugin`'s `compileClasspath` / `runtimeClasspath` keep requesting JVM 21 variants because they also carry the 1.21+ / 26.x carriers and the Velocity entry point; those are resolved by FQN only on matching (Java 21+) runtimes, so their classfile 65/69 bytecode never loads on Java 17. In the shipped jar every class outside those carriers, NeoForge, Velocity and the 26.x unobf trees is classfile 61.
- **Descriptors.** `plugin.yml` (Pro and lite) declares `api-version: '1.18'`, the lowest level a 1.18 server accepts. On 1.20.5+ servers this also opts the jar into Bukkit's legacy enum-rename rewriting (Commodore) for API levels below 1.20.5, which changes runtime behaviour on the officially supported versions and needs a devstack acceptance run on 1.21.x and 26.x. `fabric.mod.json` declares `"java": ">=17"`.
- **API audit.** The 1.20 path compiled against the 1.18.2 Spigot / Paper APIs, and `rtp-fabric-common` against the Loom-mapped MC 1.20.1 / Fabric API 0.92.5 classpath, surfaced five calls absent on those versions: `Registry#stream()` (replaced by iteration), `Player#sendBlockChanges(Collection)` (probed once, per-block fallback), `ResourceLocation#withDefaultNamespace`, the `MobEffects` constant type, and the `MobEffectInstance(Holder, ...)` constructor (resolved by id / constructor shape). `FoliaAwareScheduler` (Folia only) and `BukkitTextDisplayHologramProvider` (probed, 1.19.4+) are guarded by runtime class probes. The Mojmap-named Fabric audit is a proxy for the intermediary-named runtime.
- **Anvil.** 1.18+ chunks share the root-level `sections` / `block_states` layout and decode; pre-1.18 `Level`-wrapped chunks (worlds upgraded but not yet re-saved) decode to no sections and fall through to the live async load (`AnvilLegacyChunkLayoutTest`).
- **Fabric tickets.** `V1_20_R1FabricVersionAdapter` already implements non-persistent chunk tickets (rtp-fabric-ADR-003 amendment 2026-10-15), so S-002 / S-005 hold on 1.20.1.
- **Bundled addons and NeoForge menu providers.** `java17Modules` also covers the bundled addons' Bukkit path (`LeafRTPCountdownAddon`, `LeafRTPRiftAddon`, `LeafRTPClaimAddon`, `LeafRTPActionAddon`, `rtp-gui-common`, `rtp-gui-bukkit`) and `rtp-neoforge-common`. The unified jar's `ServiceLoader` files list the NeoForge menu providers, which every platform loads before the family filter. At classfile 65, Paper 1.18's Commodore logged conversion errors for them, and Java 17 rejected all four addons. The GUI addon's 26.x Fabric / NeoForge renderers stay at Java 25.
- **Runtime check.** `devstack/docker-compose.legacy.yml` boots Paper 1.18.2 and Fabric 1.20.1 on the `java17` image. Paper 1.18.2 loads all four addons and gives the same `rtp test full` result as Paper 1.21.11 on Java 21. On Fabric 1.20.1, the run uncovered ticket and chunk-result bugs in `V1_20_R1FabricVersionAdapter` (rtp-fabric-ADR-003 amendment 2026-10-15); after the fix the background fill keeps locations at about 1 ms MSPT. `V1_21_R1FabricVersionAdapter` shared those bugs; an opt-in Fabric 1.21.1 service (`--profile fabric-1_21_1`, Java 21) confirms the same fix there.
- REQ-RTP-SYS-001 lowers the runtime floor to Java 17 for these targets; 1.21.x and 26.x still require Java 21 / 25 because Minecraft does.

### Amendment 2026-10-15 (3) - bug-report window from 1.21.11; `api-version: '1.18'` checked on 1.21.11 and 26.x

- **Bug-report window.** Bug reports shall be accepted for Minecraft 1.21.11 through the latest release, on every supported platform. This replaces Decision 3's "No Legacy SLA" floor of 26.x: 1.21.11 is inside the window. Versions below 1.21.11 stay best-effort: they keep loading and keep their carriers, but reports from them are not triaged, and maintainers decide case by case whether to fix them. A later release shall deprecate support for 1.21.11; the deprecation announces that the window floor will move up, and does not remove the 1.21.11 carriers or add a version gate.
- **Runtime check.** `devstack/docker-compose.versions.yml` boots Paper 1.21.11, Paper 26.3, Fabric 1.21.11 and Fabric 26.3 as single servers. Paper 1.21.11 and 26.3 give the same `rtp test full` result as Paper 1.18.2 (13 of 16; `commands`, `stress` and `memory-leak` also fail on a console-only idle run of a jar with `api-version: '1.21'`). The four servers pass `rtp test accessor`, and the Fabric servers fill the hot and cold queues at about 0.2 ms MSPT. A Mineflayer player `/rtp` on Paper 1.21.11, Paper 26.1.2, Fabric 1.21.8, Fabric 1.21.11 and Fabric 26.1.2 teleports and receives the default `postteleport` sound, particle and both potion effects. On Paper 1.20.5+, Bukkit's enum-rename rewriting for `api-version: '1.18'` therefore changed no observed behaviour.
- **Fabric fixes from the run.** `V1_21_R5FabricVersionAdapter` and `V1_21_R11FabricVersionAdapter` had the 1.20.1 / 1.21.1 chunk-future defects (rtp-fabric-ADR-003 amendment 2026-10-15, 2). On Fabric 1.21.11 the first player `/rtp` stopped the server: the Fabric effects handler, compiled against 1.21.1, called `Entity#position()`, whose intermediary name 1.21.11 changed, and the `NoSuchMethodError` escaped the tick loop. Positions are now read through `getX/getY/getZ`. Other 1.21.1-named calls in the effect player actions are listed in `docs/dev/POTENTIAL_BUGS.md`.

### Amendment 2026-10-15 (4) - "Tested" means a scheduled CI boot; nightly smoke on the latest release

- **Terms.** Decision 3 tied "official" support to 26.x+, yet the nightly multi-server devstack boots only 1.21.11 (and NeoForge 1.21.1). Two separate terms replace it. *Tested* (`SUPPORT_MATRIX.md`) shall mean a scheduled CI job boots that platform on that Minecraft version. The *bug-report window* (amendment 3) is per Minecraft version and covers Tested and Best-effort cells alike. A version booted only by hand is Best-effort.
- **Latest release in CI.** The nightly `versions-smoke` job in `.github/workflows/devstack-acceptance.yml` boots Paper 26.3 and Fabric 26.3 (Java 25) from `devstack/docker-compose.versions.yml`, one job per service, through `devstack/run-versions-smoke.sh`. A run passes when `rtp test accessor` logs `pass=true`, `rtp info` shows a non-empty hot or cold queue (the Fabric chunk-future defects of amendments 2 and 3 left both at 0), and the log has no tick-loop crash, `OutOfMemoryError`, `UnsupportedClassVersionError` or crash report. The pinned `VERSION` shall move to each new Minecraft release, so the top of the window always has a nightly boot. Folia and NeoForge have no 26.x server in CI and stay Best-effort there.
- **Matrix correction.** `SUPPORT_MATRIX.md` had marked Fabric 1.20.x / 1.21.x, Paper and Folia 1.20.x, and the 26.x columns as Tested with no CI behind them; Fabric 1.20.1 and 1.21.1 stopped within a minute until amendment 2's fix. Tested now covers only the cells above plus Paper, Folia and Fabric 1.21.11 and NeoForge 1.21.1. The matrix gains a bug-report column and marks Fabric 1.20.2 - 1.20.6 Unsupported: `RTPFabricMod` sends them to the 1.20.1 adapter, which predates the 1.20.5 chunk-result and item changes, and none has been booted.
- **Storefront.** `docs/publishing/FRONT_PAGE.md` lists per-platform floors (Paper / Spigot 1.18, Folia 1.20, Fabric 1.20.1 and 1.21, NeoForge 1.21.1) up to 26.3, Java 17+, and the bug-report window. The release workflows' Modrinth and Hangar version tags are unchanged (1.20 - 26.3).

### Amendment 2026-10-15 (5) - Folia floor at 1.21.11

- **Floor.** Folia shall be supported from Minecraft 1.21.11. This replaces amendment 2's "Folia exists only from 1.19.4 and inherits the same path" and amendment 4's storefront floor of Folia 1.20. Folia is an experimental Paper fork whose maintained builds track only its current stable releases (1.21.11 and 26.1 when this floor was set); older Folia builds receive no upstream fixes.
- **Classification.** Folia below 1.21.11 is *Unsupported* in `SUPPORT_MATRIX.md`, not Best-effort: bug reports from it are closed, and no shims are added for it. The floor coincides with the amendment 3 bug-report window, so every supported Folia version is inside the window. Folia 1.21.11 stays Tested (nightly multi-server devstack); Folia 26.x stays Best-effort.
- **No gate.** The Folia carriers (`rtp-folia-v1_20_R1`, `rtp-folia-v1_21_R1`) stay, and the jar adds no version gate, so older Folia keeps loading at the operator's risk. The floor tracks Folia's own stable line; when Folia drops 1.21.11 the floor may move with it, independent of the deprecation of 1.21.11 on the other platforms.
- **Evidence.** In the bot version sweep (`devstack/run-version-sweep.sh`), Folia 1.20.4 and 1.21.4 stopped during boot with `IllegalStateException: Scheduling chunk load off-main` from the region tick, with no RTP frame in the trace; Folia 1.21.11 and 26.1.2 passed, and Folia 26.3 booted and filled the queue. The sweep matrix keeps no Folia row below 1.21.11.

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
