# RTP Documentation Index

Canonical entry point. One-line purpose per doc, plus a task → file(+anchor) router so agents can fetch slices instead of whole documents.

## Task router (fetch this file + anchor, not the whole tree)

| Task | Open |
|---|---|
| New-dev walkthrough by behavior / symptom | [`CODE_TOUR.md`](../../resource/LeafRTP/docs/dev/CODE_TOUR.md) |
| Plugin startup / `onEnable` ordering | [`../architecture/06-plugin-setup-lifecycle.md`](../../resource/LeafRTP/docs/architecture/06-plugin-setup-lifecycle.md) |
| `/rtp` command → world / region / permission decision tree | [`../architecture/07-rtp-command-region-selection.md`](../../resource/LeafRTP/docs/architecture/07-rtp-command-region-selection.md) |
| Location selection — per-attempt pipeline (shape → chunk → vert → biome → safety) | [`../architecture/08-location-selection-per-attempt.md`](../../resource/LeafRTP/docs/architecture/08-location-selection-per-attempt.md) |
| Configuration load / `/rtp reload` data flow | [`../architecture/09-configuration-load-and-reload.md`](../../resource/LeafRTP/docs/architecture/09-configuration-load-and-reload.md) |
| Shutdown / `onDisable` flush + chunk-ticket release ordering | [`../architecture/10-shutdown-and-flush-lifecycle.md`](../../resource/LeafRTP/docs/architecture/10-shutdown-and-flush-lifecycle.md) |
| Configuration write / `/rtp config` save path (atomic rename, audit, rollback) | [`../architecture/11-configuration-write-and-persist.md`](../../resource/LeafRTP/docs/architecture/11-configuration-write-and-persist.md) |
| Network model diagrams (multi-server / multi-proxy topology, `/rtp` sequence, reservation-token state machine) | [`../architecture/12-network-model.md`](../../resource/LeafRTP/docs/architecture/12-network-model.md) |
| Dual-layer shape architecture, spatial memory & L3 backlog selection diagrams | [`../architecture/13-dual-layer-shape-architecture-and-l3-selection.md`](../../resource/LeafRTP/docs/architecture/13-dual-layer-shape-architecture-and-l3-selection.md) |
| Action Engine & subspace placement diagrams (anchors, multi-slot separation, confinement) | [`../architecture/14-group-placement-anchor-flow.md`](../../resource/LeafRTP/docs/architecture/14-group-placement-anchor-flow.md) ([leafrtp-ADR-073](../../resource/LeafRTP/docs/adr/leafrtp-ADR-073-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md), [leafrtp-ADR-074](../../resource/LeafRTP/docs/adr/leafrtp-ADR-074-subspace-anchor-providers-and-near-teleport-primitives.md)) |
| Cost-governed chunk resolution & native admission diagrams (purpose-gating, deduplication threshold, MCA checks) | [`../architecture/15-cost-governed-chunk-reads-and-native-admission.md`](../../resource/LeafRTP/docs/architecture/15-cost-governed-chunk-reads-and-native-admission.md) ([leafrtp-ADR-085](../../resource/LeafRTP/docs/adr/leafrtp-ADR-085-purpose-gated-live-loads.md), [leafrtp-ADR-086](../../resource/LeafRTP/docs/adr/leafrtp-ADR-086-cost-governed-on-demand-chunk-reads.md)) |
| Core package hierarchy, 4-tier DAG diagram & decision traceability | [`../architecture/16-package-dag-and-decision-preservation-graph.md`](../../resource/LeafRTP/docs/architecture/16-package-dag-and-decision-preservation-graph.md) ([ADR-115](../adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md)) |
| Full visual architecture catalog & diagram index | [`../architecture/README.md`](../../resource/LeafRTP/docs/architecture/README.md) |
| `/rtp config` command semantics + save mechanics (target spec) | [`CONFIG_COMMAND_SPEC.md`](../../resource/LeafRTP/docs/dev/CONFIG_COMMAND_SPEC.md) ([leafrtp-ADR-023](../../resource/LeafRTP/docs/adr/leafrtp-ADR-023-harden-rtp-config-commands.md) decision, [leafrtp-ADR-027](../../resource/LeafRTP/docs/adr/leafrtp-ADR-027-config-command-and-save-implementation.md) implementation) |
| What absolute rules apply? | [`REQUIREMENTS.md section 3`](REQUIREMENTS.md#3-prohibition-requirements) (S-001 … S-007) |
| Where does my code go? | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Where does my code go in `rtp-core`? (Tier 1..4 package map) | [`../architecture/16-package-dag-and-decision-preservation-graph.md#5-component-tier-taxonomy`](../../resource/LeafRTP/docs/architecture/16-package-dag-and-decision-preservation-graph.md#5-component-tier-taxonomy) |
| Implementing a new server adapter (SPI requirements & functions) | [`SERVER_ADAPTER_GUIDE.md`](../../resource/LeafRTP/docs/dev/SERVER_ADAPTER_GUIDE.md) |
| Threading / Folia / chunk I/O | [`DESIGN.md#threading`](../../resource/LeafRTP/docs/dev/DESIGN.md#threading) |
| Memory lifecycle | [`DESIGN.md#memorytracker`](../../resource/LeafRTP/docs/dev/DESIGN.md#memorytracker) |
| Domain term meaning | [`GLOSSARY.md`](../../resource/LeafRTP/docs/dev/GLOSSARY.md) |
| REQ-* → class → test | [`TRACEABILITY.md`](../../resource/LeafRTP/docs/dev/TRACEABILITY.md) |
| Prior pitfalls / reproduction notes | [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) |
| Requirement-authoring style (`shall`, no temporal framing) | [`RULES.md`](RULES.md) |
| Commenting an option in a shipped YAML config | [`CONFIG_COMMENT_STYLE.md`](CONFIG_COMMENT_STYLE.md) |
| A decision (why something is the way it is) | [`../adr/README.md`](../adr/README.md) |
| How a developer ought to work in this repo, and why (full lifecycle: intake, planning, read-document-modify, design records, verification, divergence handling, communication, self-maintenance, optional AI tooling) | [`../adr/ADR-000-development-workflow.md`](../adr/ADR-000-development-workflow.md) |
| Pull Request (PR) lifecycle, pre-PR verification gates & CI pipeline reference | [`PULL_REQUEST_PROCESS.md`](PULL_REQUEST_PROCESS.md) |
| Multi-server / proxy (Velocity, BungeeCord) plan | [`MULTI_SERVER_PLAN.md`](../../resource/LeafRTP/docs/dev/MULTI_SERVER_PLAN.md) (D-005 gated; admin stub: [`../admin/proxies/INDEX.md`](../../resource/LeafRTP/docs/admin/proxies/INDEX.md)) |
| Why network mode (multi-server, multi-proxy) is in scope | [`../adr/ADR-036-network-mode-multi-server-multi-proxy.md`](../../resource/LeafRTP/docs/adr/leafrtp-ADR-022-network-mode-multi-server-multi-proxy.md) (umbrella; subproject refinements under [`../../platforms/proxy/docs/adr/`](../../platforms/proxy/docs/adr/)) |
| Runtime metrics SPI (TPS / MSPT / heap / pipeline samples) | [`METRICS_PLAN.md`](../../resource/LeafRTP/docs/dev/METRICS_PLAN.md) |
| Behaviour fuzzing beyond leaks (finding oracles, live-server coverage, fault-only mode) | [`BEHAVIOUR_FUZZING_PLAN.md`](BEHAVIOUR_FUZZING_PLAN.md); decision record [`../adr/ADR-111-live-server-behaviour-fuzzing.md`](../adr/ADR-111-live-server-behaviour-fuzzing.md) |
| Fuzzing a whole server without naming a target (command discovery, agentless `jcmd` observation, jar `ddmin` attribution) | [`WHOLE_SERVER_FUZZING.md`](WHOLE_SERVER_FUZZING.md); decision record [`../adr/ADR-125-whole-server-discovery-fuzzing.md`](../adr/ADR-125-whole-server-discovery-fuzzing.md) (Accepted) |
| Why Fabric is in scope | [`../../platforms/fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md`](../../platforms/fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) (renumbered from ADR-022) |
| Why NeoForge is in scope | [`../adr/ADR-033-neoforge-platform-in-scope.md`](../../platforms/neoforge/docs/adr/rtp-neoforge-ADR-002-neoforge-platform-in-scope.md) |
| Why legacy MC / Java are out of scope | [`../adr/ADR-021-legacy-mc-and-java-support-scope.md`](../adr/ADR-021-legacy-mc-and-java-support-scope.md) |
| Adding or updating a locale / translation | [`TRANSLATION_GUIDE.md`](../../resource/LeafRTP/docs/dev/TRANSLATION_GUIDE.md) |
| Spiral 1D math | [`../adr/ADR-001-archimedean-spiral-1d-mapping.md`](../../resource/LeafRTP/docs/adr/leafrtp-ADR-001-archimedean-spiral-1d-mapping.md) |
| Anvil prefilter / biome / shared module | [`../adr/ADR-016-anvil-subsystem.md`](../../api/anvil-api/docs/adr/anvil-api-ADR-001-anvil-subsystem.md) |
| Block tags / state predicates in safety lists | [`../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md`](../../api/tags-api/docs/adr/tags-api-ADR-001-block-tags-and-state-predicates-in-safety-lists.md) |
| Testing guide (what to test when: unit, mutation, devstack) | [`TESTING_GUIDE.md`](TESTING_GUIDE.md) |
| Coverage targets | [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md) |
| Verification gates and research references | [`VERIFICATION_GATES.md`](VERIFICATION_GATES.md) |
| PR audit protocol (blocker classes, large-PR triage, report template) | [`PR_AUDIT_PROTOCOL.md`](PR_AUDIT_PROTOCOL.md) |
| Contributor review experience (stable blocker ledger, comment form, newcomers) | [`CONTRIBUTOR_REVIEW_EXPERIENCE.md`](CONTRIBUTOR_REVIEW_EXPERIENCE.md) |
| Enterprise readiness (gates, evidence, compatibility matrix) | [`ENTERPRISE_READINESS.md`](ENTERPRISE_READINESS.md) |
| Platform support matrix (Spigot/Paper/Folia/Fabric/NeoForge/Velocity) | [`SUPPORT_MATRIX.md`](../../resource/LeafRTP/docs/dev/SUPPORT_MATRIX.md) |
| Semantic Versioning (SemVer) contract (public vs internal API) | [`SEMVER.md`](SEMVER.md) |
| Licensing clarity & Free/Pro support tiers | [`LICENSING.md`](LICENSING.md) |
| Deprecation and API evolution policy | [`DEPRECATION_POLICY.md`](DEPRECATION_POLICY.md) |
| Server-admin docs | [`../FOR_SERVER_ADMINS.md`](../../resource/LeafRTP/docs/FOR_SERVER_ADMINS.md) → [`../admin/`](../../resource/LeafRTP/docs/admin/) ([CONFIGURATION.md](../../resource/LeafRTP/docs/admin/configuration/CONFIGURATION.md), [REGIONS.md](../../resource/LeafRTP/docs/admin/configuration/REGIONS.md), [CORE_CONFIG.md](../../resource/LeafRTP/docs/admin/configuration/CORE_CONFIG.md), [PERFORMANCE.md](../../resource/LeafRTP/docs/admin/configuration/PERFORMANCE.md), [ECONOMY.md](../../resource/LeafRTP/docs/admin/configuration/ECONOMY.md), [SAFETY.md](../../resource/LeafRTP/docs/admin/configuration/SAFETY.md), [ACTIONS.md](../../resource/LeafRTP/docs/admin/ACTIONS.md)) |
| Addon author docs | [`../FOR_ADDON_DEVELOPERS.md`](../../resource/LeafRTP/docs/FOR_ADDON_DEVELOPERS.md) |
| Build a standalone multi-platform plugin or mod on the `api/*` SDK | [`../FOR_PLUGIN_AUTHORS.md`](../FOR_PLUGIN_AUTHORS.md); SDK catalog [`../../api/README.md`](../../api/README.md) |
| Build a destination menu / GUI on `rtp-api` | [`ADDON_MENUS.md`](../../resource/LeafRTP/docs/dev/ADDON_MENUS.md) |
| Offer remote (network-mode) destinations from an addon | [`ADDON_CROSS_SERVER.md`](../../resource/LeafRTP/docs/dev/ADDON_CROSS_SERVER.md) |
| How to load / deploy an addon (ServiceLoader, classpath, lifecycle) | [`ADDON_LOADING.md`](../../resource/LeafRTP/docs/dev/ADDON_LOADING.md) (ADR-057) |
| Publish rtp-api / rtp-core artifacts (JitPack, GitHub Packages, Maven Central) | [`PUBLISHING.md`](PUBLISHING.md) |
| Flat map of every doc | [`../MAP.md`](../MAP.md) |
| Hazards and failure modes | [`HAZARDS.md`](HAZARDS.md) |
| Failure detection and responses | [`HAZARDS.md#failure-modes`](HAZARDS.md#failure-modes) |
| External hooks (claim verifiers, economy, placeholders, world border, anvil prefilter) | [`EXTERNAL_HOOKS.md`](../../resource/LeafRTP/docs/dev/EXTERNAL_HOOKS.md) (ADR-026) |
| Player archetypes, utility functions & valuation weights | [`PLAYER_TAXONOMY_VALUATION.md`](../../resource/LeafRTP/docs/dev/PLAYER_TAXONOMY_VALUATION.md) |
| How and why AI tooling is used in this repository | [`AI_USAGE.md`](AI_USAGE.md) |

## Normative (read before writing code)

| Doc | Purpose |
|-----|---------|
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Absolute laws: threading, memory, command contexts, safety prohibitions (REQ-* IDs, S-00x prohibitions). |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module separation and ArchUnit-enforced import boundaries. |
| [`DESIGN.md`](../../resource/LeafRTP/docs/dev/DESIGN.md) | Threading model, `MemoryTracker` lifecycle, region caching, chunk-reservation flow. |
| [`GLOSSARY.md`](../../resource/LeafRTP/docs/dev/GLOSSARY.md) | Canonical domain terms; overloaded-word disambiguation. |
| [`RULES.md`](RULES.md) | Requirement/ADR authoring rules (`shall`, absolute state, no temporal framing) and the document type catalog. |
| [`../adr/README.md`](../adr/README.md) | ADR index. |
| `../../rtp-<module>/REQUIREMENTS.md` | Module-level requirement files. |

## Reference (consult on demand)

| Doc | Purpose |
|-----|---------|
| [`CONCEPTS.md`](../../resource/LeafRTP/docs/dev/CONCEPTS.md) | Archimedean spiral math; see ADR-001 for the decision. |
| [`TRACEABILITY.md`](../../resource/LeafRTP/docs/dev/TRACEABILITY.md) | REQ-* → class/method → test mapping. |
| [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md) | JaCoCo baseline and targets. |
| [`ENTERPRISE_READINESS.md`](ENTERPRISE_READINESS.md) | Repo-wide plan to make the quality and compatibility claims externally verifiable: measured per-module coverage baseline, enforced gates, supply chain, support matrix. |
| [`SUPPORT_MATRIX.md`](../../resource/LeafRTP/docs/dev/SUPPORT_MATRIX.md) | Platform family x Minecraft version x Java version support matrix. |
| [`SERVER_ADAPTER_GUIDE.md`](../../resource/LeafRTP/docs/dev/SERVER_ADAPTER_GUIDE.md) | Server adapter requirements specification, SPI function catalog, threading, and checklist. |
| [`SEMVER.md`](SEMVER.md) | Semantic versioning contract: public API vs internal boundaries, deprecation rules. |
| [`LICENSING.md`](LICENSING.md) | All-MIT licensing (ADR-108), per-module licence matrix, Free (default) vs Pro (priority support tier). |
| [`DEPRECATION_POLICY.md`](DEPRECATION_POLICY.md) | Deprecation lifecycle, notice window, and compiler annotation policy. |
| [`PUBLISHING.md`](PUBLISHING.md) | Addon artifact publishing guide (JitPack, GitHub Packages, Maven Central). |
| [`PULL_REQUEST_PROCESS.md`](PULL_REQUEST_PROCESS.md) | Pull request workflow: branching, local pre-PR verification gates, CI pipeline anatomy, and merge criteria. |
| [`MULTI_SERVER_PLAN.md`](../../resource/LeafRTP/docs/dev/MULTI_SERVER_PLAN.md) | Proxy / multi-server (Velocity, BungeeCord) roadmap; D-005 gated. |
| [`METRICS_PLAN.md`](../../resource/LeafRTP/docs/dev/METRICS_PLAN.md) | Runtime metrics SPI (TPS/MSPT/heap/queue/pipeline); implementation eligible. |
| [`ROADMAP.md`](ROADMAP.md) | Pointer to the Linear `LeafRTP` team, where forward-looking work is tracked. |
| [`STAKEHOLDERS.md`](../../resource/LeafRTP/docs/dev/STAKEHOLDERS.md) | Roles and review expectations. |
| [`PLAYER_TAXONOMY_VALUATION.md`](../../resource/LeafRTP/docs/dev/PLAYER_TAXONOMY_VALUATION.md) | Player taxonomy, utility functions, site valuation weights, and behavioral benchmark models. |
| [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) | Dated engineering notes. |
| [`../../helpers/StressTestRTP/LEAK_FUZZING.md`](../../helpers/StressTestRTP/LEAK_FUZZING.md) | Behaviour leak fuzzer (`scripts/bench_fuzz.py`): goals, methodology, reading a run, findings (BetterRTP, LeafRTP 3.3.0), limitations. |
| [`BEHAVIOUR_FUZZING_PLAN.md`](BEHAVIOUR_FUZZING_PLAN.md) | Generalising `bench_fuzz.py` to coverage-guided fault finding: what is generic, detector defects, goals, staged plan (finding oracles done; JaCoCo coverage, fault-only mode, new oracles, vocabulary D-005 gated). Governed by [ADR-111](../adr/ADR-111-live-server-behaviour-fuzzing.md). |
| [`WHOLE_SERVER_FUZZING.md`](WHOLE_SERVER_FUZZING.md) | Target-free `bench_fuzz.py --mode server` method: `declare_commands` discovery per permission tier, node x follow-up bandit, leak and stall oracles against a vanilla baseline, jar `ddmin` attribution, BetterRTP 3.6.13 and planted-bug validation targets. Governed by [ADR-125](../adr/ADR-125-whole-server-discovery-fuzzing.md) (Accepted). |
| [`SECURITY_AUDIT_v3.3.0.md`](../../resource/LeafRTP/docs/dev/SECURITY_AUDIT_v3.3.0.md) | v3.3.0 pre-release security and severe-bug audit: fixed, open, sound and uncovered areas. |

> Plans that shipped or were superseded are removed; the ADR is the durable record. Pre-deletion state is recoverable from git.

## Agent / contributor entry points

| Doc | Purpose |
|-----|---------|
| [`../../.junie/AGENTS.md`](../../.junie/AGENTS.md) | Top-level operational guide. Start here. |
| [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) | Human contributor workflow. |
| [`PULL_REQUEST_PROCESS.md`](PULL_REQUEST_PROCESS.md) | Pull request lifecycle, verification gates, and merge criteria. |
| [`../../CHANGELOG.md`](../../resource/LeafRTP/CHANGELOG.md) | Release notes. |
