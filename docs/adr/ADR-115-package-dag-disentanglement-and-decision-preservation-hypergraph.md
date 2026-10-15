# ADR-115 — Package DAG Disentanglement and Finite Decision-Preservation Hypergraph

**Status:** Accepted
**Date:** 2026-10-14

---

## Context

As the LeafRTP codebase has scaled across multi-platform targets (Folia, Paper, Fabric, NeoForge), multi-server proxy architectures, and specialized Anvil pre-filtering engines, the internal package structure of `rtp-core` has accumulated significant architectural coupling:

1. **Package Tangles and Cyclic Coupling (SonarQube Package Tangle Index):**
   An automated AST traversal and package dependency analysis reveals that **52 distinct packages in `rtp-core` currently form a single cyclic strongly connected component (SCC)**. Because every package in this cluster is transitively reachable from every other package, SonarQube flags an elevated Package Tangle Index. In practice, this cyclic coupling prevents modular reuse: a developer or contributor attempting to consume an isolated utility (such as `MemoryTracker`, `ImmediateLoadGovernor`, or `AnvilIoPool`) is forced to ingest the entire transitive web of dependencies, creating friction that encourages duplicated local helpers, fragmented bug fixes, and bloated test suites.

2. **Decision Erasure and Invariant Erosion During Refactoring:**
   When complex runtime safeguards—such as the dual-pass Anvil pre-filter versus native live load equation in ADR-114, count-bound regional tasks in ADR-112, or the weak-reference lifecycle sweeps in ADR-008—lack explicit, machine-readable contracts at the code level, maintenance and refactoring efforts inevitably treat them as superfluous boilerplate. Contributors optimizing for local syntactic conciseness frequently streamline away these critical invariants, introducing severe runtime regressions (server tick stalls, memory leaks, watchdog aborts).

To eliminate cyclic dependencies, achieve a 0.0% Package Tangle Index in SonarQube, and permanently preserve architectural invariants across ongoing maintenance, the package graph must be transformed into a strictly layered Directed Acyclic Graph (DAG), supported by an in-code decision-traceability contract.

---

## Decision

The project adopts a two-part architectural standard governing package structure, dependency directions, and decision preservation across `rtp-core`:

### 1. The 4-Tier Package Directed Acyclic Graph (DAG)

All packages in `rtp-core` shall be organized into four strict unidirectional tiers. Dependencies shall flow strictly downward (Tier 4 $\to$ Tier 3 $\to$ Tier 2 $\to$ Tier 1). Upward and horizontal cyclic dependencies are strictly prohibited:

- **Tier 1 (Foundation & Schema Contracts):**
  Immutable configuration records, domain enums, basic spatial math tables, and SPI interfaces (e.g., `TrackableTask`, `database.pool`). Zero outgoing dependencies on other `rtp-core` packages.
- **Tier 2 (Domain Engines, Algorithms & Storage):**
  Pure domain logic, spatial selection curves (Archimedean spiral, Hilbert mapping), memory tracking (`MemoryTracker`), database accessors, and resource governors. Depends strictly on Tier 1.
- **Tier 3 (Execution Pipelines & Coordination):**
  Runtime workflows and asynchronous schedulers (`TeleportPipelineTask`, `PregenTask`, `ScanTask`, `ActionManager`, network dispatch). Depends on Tier 2 and Tier 1.
- **Tier 4 (Orchestration, Ingress & Presentation):**
  Leaf entry points: the composition root (`common.RTP`), command tree builders, subcommands, interactive written book menus, web editor channels, and event bridges. Depends downward on Tiers 3, 2, and 1. Zero other core packages shall depend on Tier 4.

The four primary cyclic seams sustaining the 52-package tangle shall be decoupled as follows:
- **`tasks.teleport` $\longleftrightarrow$ `tools`:** Inverted via interface extraction (`TrackableTask` / `ChunkTicketHolder` in Tier 1). `tools.MemoryTracker` tracks the interface; `TeleportPipelineTask` implements it.
- **`commands` $\longleftrightarrow$ `commands.*`:** Inverted by isolating base command definitions into `commands.base` and using dynamic command tree registration or provider SPIs so the composition root does not create mutual dependencies with child commands.
- **`selection.region` $\longleftrightarrow$ `tasks`:** Decoupled by separating immutable configuration schemas (`RegionConfigRecord`) from runtime state, and dispatching tasks through Tier 3 execution coordinators.
- **`common.RTP` (Monolithic Static Hub):** Dissolved by passing explicit dependency contexts (`RTPContext` / constructor injection) to domain engines. `RTP.java` is retained purely as a Tier 4 composition root and shall not be imported by internal domain packages.

### 2. The Finite Decision Hypergraph and Invariant Javadoc Standard

The relationship between documentation, requirements, and implementation is codified as a finite, closed hypergraph:
$$\mathcal{H} = (V, \mathcal{E}), \quad V = \mathcal{REQ} \cup \mathcal{ADR} \cup \mathcal{S} \cup \mathcal{C} \cup \mathcal{T}$$

Every safety-critical class and method shall declare its governing invariants via standardized Javadoc tags:
- `@governedBy ADR-NNN (Canonical Title)`
- `@trace REQ-MODULE-FAMILY-NNN`
- `@invariant S-00x: Explicit prohibition statement`
- `@invariant Name: Mathematical or operational invariant`
- `@prohibition Negative specification of what refactorings must NOT alter`

Contributors touching any code element $c$ can derive intended behavior directly from the bounded neighborhood:
$$\text{Gov}(c) = \{ v \in (\mathcal{REQ} \cup \mathcal{ADR} \cup \mathcal{S}) \mid (c, v) \in \mathcal{E} \}$$
eliminating ambiguity and ensuring that design rationale is preserved across future modifications.

---

## Invariants

1. **Package Cycle Freedom:**
   The Package Tangle Index of `rtp-core` shall be 0.0%, enforced by ArchUnit rule `slices().matching("io.github.dailystruggle.rtp.common.(*)..").should().beFreeOfCycles()`.
2. **Downward Flow Invariant:**
   No class in Tier 1 or Tier 2 shall import or reference any class in Tier 4.
3. **Behavioral Parity:**
   Structural package refactoring shall not alter runtime behavior, mathematical distributions, command semantics, or chunk safety filters.
4. **Finite Context Radius:**
   Every safety-critical class in Tier 2 and Tier 3 shall reference its governing ADR and REQ IDs directly in its class-level Javadoc.
5. **No Static Gravity Well:**
   Classes in `selection`, `tools`, `database`, and `tasks` shall not access static fields of `RTP.java`.

---

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Monolithic Package Merge:** Merge all tangled packages into a single flat package (`rtp.common.core`). | Replaces package cycles with internal class entanglement. Destroys readability, violates Rule D-008, and prevents ArchUnit from enforcing architectural boundaries. |
| **Status Quo (Document-Only Guidelines):** Keep the cyclic package structure and rely purely on external documentation to prevent code duplication and invariant erosion. | Fails in practice. As long as the dependency graph is cyclic, the cognitive and compilation cost of reusing coupled classes exceeds the cost of writing private helpers. Structural feedback must be mechanical. |
| **Separate Gradle Submodules for Every Tier:** Split `rtp-core` into `rtp-core-foundation`, `rtp-core-domain`, `rtp-core-pipelines`. | Heavy build-system overhead. A multi-module split inflates Gradle build times and complicates internal testing. ArchUnit slice rules provide identical boundary enforcement within a single Gradle module without build overhead. |

---

## Consequences

- **Positive:**
  - SonarQube Package Tangle Index drops from an entangled 52-package cluster to 0.0%.
  - Cognitive load for developers and contributors is strictly bounded: understanding or modifying a Tier 2 algorithm requires inspecting only Tier 1 schemas and its explicit governing ADR.
  - Eliminates the friction that encourages duplicated helper code; existing infrastructure becomes trivial to instantiate and test in isolation.
  - Preserves architectural decisions permanently against accidental erosion during refactorings via explicit in-code negative specifications and ArchUnit boundary gates.
- **Negative / Trade-offs:**
  - Requires a one-time mechanical refactoring stream to extract interfaces (`TrackableTask`), relocate base command classes, and adjust constructor injection across domain engines.
  - Requires maintaining Javadoc invariant tags when new ADRs are accepted or requirements are updated.

---

## References

- Visual Architecture Specification & Diagram: [`docs/architecture/16-package-dag-and-decision-preservation-graph.md`](../architecture/16-package-dag-and-decision-preservation-graph.md)
- Development Workflow & D-005 Proposal Gate: [`docs/adr/ADR-000-development-workflow.md`](ADR-000-development-workflow.md)
- ArchUnit Quality Engineering Gates: [`docs/adr/ADR-094-quality-engineering-gates.md`](ADR-094-quality-engineering-gates.md)
- Absolute Prohibition Requirements (S-001..S-007): [`docs/dev/REQUIREMENTS.md`](../dev/REQUIREMENTS.md)
- Requirements Traceability Matrix: [`docs/dev/TRACEABILITY.md`](../dev/TRACEABILITY.md)
