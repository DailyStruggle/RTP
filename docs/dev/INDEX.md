# RTP Documentation Index

One-line purpose for every document under `docs/`. New contributors and AI agents should start here. If you add a new doc, add its row below.

## Normative (read before writing code)

| Doc | Purpose |
|-----|---------|
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Absolute laws: threading, memory, command contexts, safety prohibitions (REQ-* IDs). |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module separation and ArchUnit-enforced import boundaries. |
| [`DESIGN.md`](DESIGN.md) | Threading model, `MemoryTracker` lifecycle, region caching, chunk-reservation flow. |
| [`GLOSSARY.md`](GLOSSARY.md) | Canonical domain terms; overloaded-word disambiguation. Do not invent synonyms. |
| [`../adr/README.md`](../adr/README.md) | Architecture Decision Records index (ADR-001…). |
| `../../rtp-<platform>/REQUIREMENTS.md` | Module-level requirement files (`rtp-api`, `rtp-core`, `rtp-spigot`, `rtp-paper`, `rtp-folia`). |

## Reference (consult on demand)

| Doc | Purpose |
|-----|---------|
| [`CONCEPTS.md`](CONCEPTS.md) | O(log n) Archimedean spiral math underpinning location selection. |
| [`TRACEABILITY.md`](TRACEABILITY.md) | REQ-* → class/method → test mapping; the canonical "already satisfied by" table. |
| [`COVERAGE_PLAN.md`](COVERAGE_PLAN.md) | JaCoCo baseline (49% → 80% target); critical-gap packages. |
| [`MULTI_PLATFORM_PLAN.md`](MULTI_PLATFORM_PLAN.md) | Current Fabric roadmap phase status; known blockers. |
| [`STAKEHOLDERS.md`](STAKEHOLDERS.md) | Stakeholder roles and review expectations. |
| [`RULES.md`](RULES.md) | Requirement-document authoring rules (linguistic style, `shall`/`shall not`). |
| [`DOCUMENTATION_GUIDE.md`](DOCUMENTATION_GUIDE.md) | How to author, structure, and cross-link docs in this repo. |
| [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md) | Dated engineering notes, reproduction pitfalls, non-obvious behaviors. |

## Feature / Subsystem Plans

| Doc | Purpose |
|-----|---------|
| [`ANVIL_PREFILTER_PLAN.md`](ANVIL_PREFILTER_PLAN.md) | Implementation plan for the Anvil read-only prefilter (ADR-016). |
| [`ANVIL_SHARED_MODULE_PLAN.md`](ANVIL_SHARED_MODULE_PLAN.md) | Shared `rtp-anvil` module design. |
| [`ANVIL_BIOME_PLAN.md`](ANVIL_BIOME_PLAN.md) | Anvil-backed biome lookups. |
| [`BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md`](BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md) | Biome + bad-location visitor design. |
| [`SAFETY_TAGS_AND_STATES_PLAN.md`](SAFETY_TAGS_AND_STATES_PLAN.md) | Block-tag and block-state predicates in safety lists (ADR-017). |
| [`EMPTY_LIST_CONFIG_PLAN.md`](EMPTY_LIST_CONFIG_PLAN.md) | Empty-list config semantics. |
| [`YAML_SIMPLIFICATION_PLAN.md`](YAML_SIMPLIFICATION_PLAN.md) | YAML config surface reduction. |
| [`RUNTIME_TEST_SUITE_PLAN.md`](RUNTIME_TEST_SUITE_PLAN.md) | `rtp test full` runtime test suite design. |

## Agent / Contributor Guides

| Doc | Purpose |
|-----|---------|
| [`../../.junie/AGENTS.md`](../../.junie/AGENTS.md) | Top-level operational guide for AI agents and contributors. Start here. |
| [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) | Human contributor workflow, PR expectations. |
| [`../../CHANGELOG.md`](../../CHANGELOG.md) | User-facing release notes. |
