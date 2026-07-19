# ADR-000 - Development Workflow

**Status:** Accepted
**Date:** 2026-07-19

> Numbering note: this record was authored after ADR-073 but is numbered `000` because it is foundational - it documents the workflow under which every other record in this repository is produced. It changes no prior decision.

## Context

RTP is a long-lived, multi-platform codebase (Bukkit family, Folia, Fabric, NeoForge, proxy JVMs) maintained by a single developer, with AI tooling used as an executor. Two recurring failure modes motivated a disciplined documentation workflow:

- **Architecture drift**: code changes that silently invalidate the documented design, so the docs describe a system that no longer exists.
- **Doc rot / retconning**: design documents edited in place until nobody can reconstruct why a decision was made or what it replaced.

The workflow that prevents these already operates in practice, but it is *implied* by scattered artifacts rather than *decided* anywhere:

- [`docs/dev/RULES.md`](../dev/RULES.md) - the D-family documentation rules (D-001 traceability, D-002 unambiguity, D-003 single source of truth, D-004 audience, D-005 propose-before-implementation) and the Document Type Catalog.
- [`docs/adr/README.md`](README.md) and [`ADR-TEMPLATE.md`](ADR-TEMPLATE.md) - what an ADR is, sequential numbering, subproject ADR directories, and the never-delete/supersede convention.
- [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) and the requirement-authoring style rules - `shall`/`shall not` phrasing, absolute state, what-not-how.
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) - the REQ-* to class to test binding that closes the loop.
- [`.junie/AGENTS.md`](../../.junie/AGENTS.md) *Self-Updating Protocol* - the discovery-to-destination table that keeps the doc system itself current.

None of those answers "why is the workflow shaped this way, and what were the alternatives?" - which is exactly an ADR's job. [ADR-018](ADR-018-agents-md-public-release-structure.md) established precedent for meta-level ADRs.

This record is a meta document: how a developer ought to work in this repository (assuming a working style similar to the maintainer's), and why. It describes the entire lifecycle at rationale level; the operational rules stay in their canonical homes per D-003.

## Decision

Record the development workflow as actually practiced, end to end. The lifecycle below is a walk through its stages; each stage names the artifacts it produces and the reason the stage exists.

### 1. Intake

Change begins as user feedback, developer intent, or a bug report. Feedback is collected, abstracted, and only then specified. Occasionally that specification crystallizes into a **requirement** - and requirements record only the absolutes: core invariants and needs (regional threading on Folia, "`/rtp` shall teleport the caller"), stated in `shall` terms, implementation-free, identified as `REQ-<module>-<family>-NNN`. A whole plugin evolves from a handful of core requirements; they are not per-change forcing functions, and most changes never touch them.

### 2. Planning tier

Between intake and design sits a deliberately *mutable* staging layer: plan docs (`MULTI_PLATFORM_PLAN.md`, `MULTI_SERVER_PLAN.md`, `METRICS_PLAN.md`, `ROADMAP.md`) and scratch/proposal notes (`docs/dev/scratch/`, PROPOSAL drafts). These are living documents, edited in place, because the ideas in them are not yet decisions. When a plan ships or is superseded, the plan is removed and the ADR becomes the durable record - the mutability is the point of the tier, not a defect of it.

### 3. Default change loop: read, document, modify

The default loop for any change is: read the relevant code and records, document intent at a weight proportional to the change (nothing beyond a changelog entry for a small fix, a scratch note or proposal for medium work, an ADR for architectural work), then modify. Most third-party contributions - a bugfix - require neither a requirement nor an ADR. Rule D-005 is a guard rail on the large end of that spectrum, aimed primarily at low-skill contributors and AI agents whose compulsions or tooling can cause major breakage; it is not a universal forward gate - it binds only changes that reach its multi-class / cross-module trigger, where it is mandatory for every contributor.

### 4. Design records

For larger changes, the architecture and thought process are a layer above the coding (and often emerge *during* coding); that layer is what gets recorded. **ADRs co-exist with code and document the work itself**, so that anyone - including the author years later - asking "why doesn't this work like X model?" has an answer. Answerability is the point: its absence causes quality drift and gives "slop" PRs a foothold. ADRs are also provenance - every architectural decision has an explicit human owner, whether or not execution was AI-assisted. Records are superseded or amended, never retconned. Supporting design surfaces: `DESIGN.md` for the *how* of cross-cutting mechanisms, `GLOSSARY.md` for term definitions, subproject ADR sequences for decisions confined to one module.

### 5. Implementation and verification

**Code is the functional component and has priority.** A bugfix may or may not warrant documentation, depending on the scale of its impact - though most bugfixes still warrant a `CHANGELOG.md` entry; the changelog is the exception to weight-proportional documentation. Implementation is bound back to the record layer through REQ-traceable tests and `TRACEABILITY.md` rows, and guarded mechanically by ArchUnit boundary tests and `check_traceability.sh`. Runtime-testable work ends with a full build; integration behavior is exercised against the devstack where the change warrants it.

### 6. Divergence handling

When code and an ADR are found to disagree, priority is chosen case by case: the divergence reveals information about both, and examining it may show the code needs updated architecture rather than the ADR being right by default. That in-the-moment decision process has produced more value than predetermined architecture. Findings worth keeping flow to a superseding/amending ADR, a requirement refinement, or a dated `LESSONS_LEARNED.md` entry, at the maintainer's judgment. Incidental discoveries made mid-change are recorded rather than chased (`POTENTIAL_BUGS.md`), keeping each change traceable to a single driving need. Drift is also caught mechanically: `TRACEABILITY.md` updates on symbol renames and the automated backstops above.

### 7. Communication surfaces

Completed work is reflected outward at registers appropriate to each audience (Rule D-004): `CHANGELOG.md` (net delta against the last released tag, Pro-only entries tagged), wiki and `docs/admin/` operator docs, locale parity for every user-facing string (the English baseline mirrored into every shipped locale in the same change), and maintainer-voice prose for listings and front pages. These surfaces are downstream of the record layer - they describe what shipped, they do not decide anything.

### 8. Self-maintenance

The doc system maintains itself through the Self-Updating Protocol in [`.junie/AGENTS.md`](../../.junie/AGENTS.md): every durable discovery has a designated destination (environment fixes, engineering pitfalls, glossary terms, aliases, plan-status updates, traceability rows, new ADRs), so knowledge lands where the next reader will look for it instead of decaying in chat history or memory.

### Constraints applied to the workflow

The lifecycle operates under the following constraints (canonical definitions live in [`RULES.md`](../dev/RULES.md); summarized here because they shape the workflow itself):

- **Rule D-005 - Propose Architecture Before Implementation.** For any change that touches more than one class, crosses a module boundary, or introduces a new command architecture, a written proposal (affected modules, before/after structure, relevant REQ-*/ADRs, risks and trade-offs) shall be presented and explicitly approved *before* any code is written. D-005 is the guard rail on the large end of the documentation-weight spectrum: it forces big design onto paper, where a bad idea is cheap, and constrains contributors (human or agent) whose tooling or habits could otherwise cause major breakage. A change that contradicts an existing ADR shall say so and propose a superseding ADR.
- **Rule D-001 - Traceability.** Every requirement, decision, and rule links to a source; code changes trace back to a requirement or decision.
- **Rule D-002 - Unambiguity.** Requirements use `shall`/`shall not`; new or overloaded terms are defined in `GLOSSARY.md`.
- **Rule D-003 - Single Source of Truth.** Each piece of information has exactly one authoritative home; other documents reference it rather than duplicating it. This ADR obeys D-003 by recording rationale and lifecycle topology only - the operational rules remain in `RULES.md` and `.junie/AGENTS.md`.
- **Rule D-004 - Audience separation.** User docs, developer docs, and agent-operational docs are distinct surfaces.
- **S-00x prohibitions.** The safety prohibitions in `REQUIREMENTS.md` section 3 are absolute at every stage of the lifecycle; no proposal, ADR, or implementation may relax them without a superseding requirement change.
- **Requirement-authoring style.** Requirements state *what*, not *how*; no class names, no temporal framing, legal phrasing (`shall`). Implementation detail belongs in `DESIGN.md` or an ADR.

Constraints that apply only to AI agents (pre-flight checklist, stay-on-task/record-don't-chase, git safety, checklist-based state tracking, encoding hygiene) are operational rules on the tool, not on the workflow; they live in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) and are covered under *Optional AI tooling* below.

### Optional AI tooling

AI tooling (agentic LLM assistants) is an *optional* executor of human-directed work, never a design participant. Its use is bounded:

- **The workflow regulates the tool, not the other way around.** The requirement/ADR discipline predates AI use and originates from human-safety-systems practice. Its shape - explicit what, explicit why, explicit trade-offs, numbered records that cannot be quietly retconned - is what makes LLM output auditable. An AI-produced change enters the codebase through the same D-005 gate, ADR record, and traceability row as any other; the human owns the design and approves the result. That gate is what establishes quality and human provenance.
- **Where AI is used**: mechanical instances of human-designed patterns (the Nth `Shape`, platform adapter wrappers), locally-specified unit tests, filling narrow API-knowledge gaps, and formatting human-directed prose into the document templates.
- **Where AI is not used**: system design, code review, automated git operations, and independently-authored requirements or ADRs. Design happens in human brainstorming; AI formats the result. An AI-written ADR or requirement whose substance was not human-directed is a defect.
- **Operational rules for agents** live in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) (pre-flight checklist, checklist-based state tracking, git safety, encoding hygiene). The full rationale, boundaries, and honest failure modes of AI use are documented in [`docs/dev/AI_USAGE.md`](../dev/AI_USAGE.md), which is the canonical source for this topic per D-003.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the workflow implicit in `RULES.md` + `AGENTS.md` | No rationale record; each new contributor or agent re-derives (or violates) the lifecycle; the workflow itself can drift with no supersession trail |
| A `WORKFLOW.md` living doc in `docs/dev/` | Living docs get edited in place; the *decision* to use this workflow deserves immutability plus supersession semantics, i.e. an ADR |
| Document only the requirements/ADR/code triangle | Undersells the practice: planning, verification, communication, and self-maintenance stages are equally load-bearing, and omitting them invites the same folklore problem at those stages |
| Waterfall (requirements frozen before design, design frozen before code) | Contradicts observed reality: architecture and thought process often emerge during coding and are recorded then (the Fabric obf/unobf carrier split, ADR-043 superseding ADR-007's operational details) |
| Code-first with no documentation | Undocumented code-first is what produces quality drift and unanswerable "why doesn't it work like X" questions; documented-during-coding is in fact the practice - the rejection is the *undocumented* variant |
| Mandating (or banning) AI tooling as part of the workflow | Tooling availability and trust vary by contributor and by task; the workflow is tool-neutral by design, with AI-produced output bounded by the same gates as any change |

## Consequences

- **Positive:** one citable anchor for "how should I work here, and why"; new ADRs and proposals can reference ADR-000 instead of re-explaining process; the read-document-modify default, the mutable planning tier, and case-by-case divergence handling become explicit rather than folklore; the AI-tooling boundary is recorded as a decision rather than a habit.
- **Negative / Trade-offs:** risk of overlap with `RULES.md` and `AGENTS.md` - this record deliberately confines itself to rationale and lifecycle topology and references the operational homes per D-003; keeping the summaries here consistent with those sources is a (small) ongoing maintenance cost. The out-of-sequence number `000` is a permanent mild oddity, accepted for its foundational readability.

## References

- [`docs/dev/RULES.md`](../dev/RULES.md) - D-001..D-005 definitions and the Document Type Catalog.
- [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md) - requirement families and S-00x prohibitions.
- [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md) - REQ-* to class to test matrix.
- [`docs/dev/LESSONS_LEARNED.md`](../dev/LESSONS_LEARNED.md) - dated engineering findings.
- [`docs/dev/AI_USAGE.md`](../dev/AI_USAGE.md) - canonical AI-tooling rationale and boundaries.
- [`.junie/AGENTS.md`](../../.junie/AGENTS.md) - agent-operational guide (Self-Updating Protocol, D-005 gate restatement).
- [ADR-018](ADR-018-agents-md-public-release-structure.md) - precedent for meta-level ADRs.
- [ADR-TEMPLATE.md](ADR-TEMPLATE.md) - the record format this workflow produces.
