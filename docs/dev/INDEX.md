# RTP Documentation Index

Canonical entry point. One-line purpose per doc, plus a task → file(+anchor) router so agents can fetch slices instead of whole documents.

## Task router (fetch this file + anchor, not the whole tree)

| Task | Open |
|---|---|
| New-dev walkthrough by behavior / symptom | [`CODE_TOUR.md`](CODE_TOUR.md) |
| Plugin startup / `onEnable` ordering | [`../architecture/06-plugin-setup-lifecycle.md`](../architecture/06-plugin-setup-lifecycle.md) |
| `/rtp` command → world / region / permission decision tree | [`../architecture/07-rtp-command-region-selection.md`](../architecture/07-rtp-command-region-selection.md) |
| Location selection — per-attempt pipeline (shape → chunk → vert → biome → safety) | [`../architecture/08-location-selection-per-attempt.md`](../architecture/08-location-selection-per-attempt.md) |
| Configuration load / `/rtp reload` data flow | [`../architecture/09-configuration-load-and-reload.md`](../architecture/09-configuration-load-and-reload.md) |
| Shutdown / `onDisable` flush + chunk-ticket release ordering | [`../architecture/10-shutdown-and-flush-lifecycle.md`](../architecture/10-shutdown-and-flush-lifecycle.md) |
| What absolute rules apply? | [`REQUIREMENTS.md §3`](REQUIREMENTS.md#3-prohibition-requirements) (S-001 … S-007) |
| Where does my code go? | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Threading / Folia / chunk I/O | [`DESIGN.md#threading`](DESIGN.md#threading) |
| Memory lifecycle | [`DESIGN.md#memorytracker`](DESIGN.md#memorytracker) |
| Domain term meaning | [`GLOSSARY.md`](GLOSSARY.md) |
| REQ-* → class → test | [`TRACEABILITY.md`](TRACEABILITY.md) |
| Prior pitfalls / reproduction notes | [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) |
| Requirement-authoring style (`shall`, no temporal framing) | [`RULES.md`](RULES.md) |
| A decision (why something is the way it is) | [`../adr/README.md`](../adr/README.md) |
| Fabric status / blockers | [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) |
| Why Fabric is in scope (and Forge / NeoForge are not) | [`../adr/ADR-022-fabric-platform-in-scope.md`](../adr/ADR-022-fabric-platform-in-scope.md) |
| Why legacy MC / Java are out of scope | [`../adr/ADR-021-legacy-mc-and-java-support-scope.md`](../adr/ADR-021-legacy-mc-and-java-support-scope.md) |
| Adding or updating a locale / translation | [`TRANSLATION_GUIDE.md`](TRANSLATION_GUIDE.md) |
| Spiral 1D math | [`../adr/ADR-001-archimedean-spiral-1d-mapping.md`](../adr/ADR-001-archimedean-spiral-1d-mapping.md) |
| Anvil prefilter / biome / shared module | [`../adr/ADR-016-anvil-subsystem.md`](../adr/ADR-016-anvil-subsystem.md) |
| Block tags / state predicates in safety lists | [`../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md`](../adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md) |
| Coverage targets | [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md) |
| Server-admin docs | [`../FOR_SERVER_ADMINS.md`](../FOR_SERVER_ADMINS.md) → [`../admin/`](../admin/) ([CONFIGURATION.md](../admin/CONFIGURATION.md), [REGIONS.md](../admin/REGIONS.md), [CORE_CONFIG.md](../admin/CORE_CONFIG.md), [PERFORMANCE.md](../admin/PERFORMANCE.md), [ECONOMY.md](../admin/ECONOMY.md), [SAFETY.md](../admin/SAFETY.md)) |
| Addon author docs | [`../FOR_ADDON_DEVELOPERS.md`](../FOR_ADDON_DEVELOPERS.md) |
| Flat map of every doc | [`../MAP.md`](../MAP.md) |
| Hazards and failure modes | [`../admin/HAZARDS.md`](../admin/HAZARDS.md) |
| Failure detection and responses | [`../admin/HAZARDS.md#failure-modes`](../admin/HAZARDS.md#failure-modes) |

## Normative (read before writing code)

| Doc | Purpose |
|-----|---------|
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Absolute laws: threading, memory, command contexts, safety prohibitions (REQ-* IDs, S-00x prohibitions). |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module separation and ArchUnit-enforced import boundaries. |
| [`DESIGN.md`](DESIGN.md) | Threading model, `MemoryTracker` lifecycle, region caching, chunk-reservation flow. |
| [`GLOSSARY.md`](GLOSSARY.md) | Canonical domain terms; overloaded-word disambiguation. |
| [`RULES.md`](RULES.md) | Requirement/ADR authoring rules (`shall` phrasing, absolute state, no temporal framing) and the document type catalog. |
| [`../adr/README.md`](../adr/README.md) | ADR index. |
| `../../rtp-<module>/REQUIREMENTS.md` | Module-level requirement files. |

## Reference (consult on demand)

| Doc | Purpose |
|-----|---------|
| [`CONCEPTS.md`](CONCEPTS.md) | Archimedean spiral math; see ADR-001 for the decision. |
| [`TRACEABILITY.md`](TRACEABILITY.md) | REQ-* → class/method → test mapping. |
| [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md) | JaCoCo baseline and targets. |
| [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) | Active Fabric frontier status. |
| [`ROADMAP.md`](ROADMAP.md) | Forward-looking work. |
| [`STAKEHOLDERS.md`](STAKEHOLDERS.md) | Roles and review expectations. |
| [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) | Dated engineering notes. |

> Plans that shipped or were superseded are removed; the ADR is the durable record. Pre-deletion state is recoverable from git.

## Agent / contributor entry points

| Doc | Purpose |
|-----|---------|
| [`../../.junie/AGENTS.md`](../../.junie/AGENTS.md) | Top-level operational guide. Start here. |
| [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) | Human contributor workflow. |
| [`../../CHANGELOG.md`](../../CHANGELOG.md) | Release notes. |
