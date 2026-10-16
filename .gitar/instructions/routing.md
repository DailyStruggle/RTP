# Invariant & Single Source of Truth (SSoT) Routing

Automated code review must never rely on ad-hoc or paraphrased rules. Paraphrasing rules in review comments causes instruction drift, conflicting feedback, and degraded maintainability over time (`Rule D-003`).

Gitar must act as an authoritative **router**, directing pull request authors directly to the repository's canonical Single Source of Truth (SSoT) specifications, requirements, and immutable Architectural Decision Records (ADRs).

---

## Canonical Authority Matrix

When identifying an issue or requesting justification, always cite the governing document and specific anchor link:

### 1. Safety Prohibitions & Absolute Laws (S-001 through S-007)
- **Main-Thread Chunk I/O Prohibition (Rule S-005):**
  - Route to: `docs/dev/REQUIREMENTS.md#3-prohibition-requirements` (`REQ-RTP-S-005`)
  - Off-tick prefiltering context: `docs/adr/ADR-016-anvil-subsystem.md` and `docs/adr/ADR-077-multi-format-region-support.md`
- **Chunk Ticket & Resource Leaks (Rule S-002):**
  - Route to: `docs/dev/REQUIREMENTS.md#3-prohibition-requirements` (`REQ-RTP-S-002`)
  - Lifecycle implementation: `docs/dev/DESIGN.md#memorytracker`
- **Zero Swallowed Teleport Failures (Rule S-004):**
  - Route to: `docs/dev/REQUIREMENTS.md#3-prohibition-requirements` (`REQ-RTP-S-004`)
- **Safe Destinations & Claim Protection (Rules S-001, S-003):**
  - Route to: `docs/dev/REQUIREMENTS.md#3-prohibition-requirements` (`REQ-RTP-S-001`, `REQ-RTP-S-003`)
- **Pre-Init API Fail-Closed Contract (Rule S-006):**
  - Route to: `docs/dev/REQUIREMENTS.md#3-prohibition-requirements` (`REQ-RTP-S-006`)

### 2. Concurrency & Regional Scheduling (Folia / Multi-Platform)
- **Regional Ownership & Thread Safety:**
  - Route to: `docs/dev/RULES.md#folia-specific-architectural-rules` (`Rule F-001`, `Rule F-003`)
  - Threading model: `docs/dev/DESIGN.md#threading`

### 3. Architectural Governance & Module Boundaries
- **Pre-Implementation Proposals (Rule D-005):**
  - Route to: `docs/adr/ADR-000-development-workflow.md`
- **Package DAG & Tier Invariants:**
  - Route to: `docs/adr/ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md`
  - Structural boundaries: `docs/dev/ARCHITECTURE.md`
- **Platform Isolation:**
  - Route to: `docs/dev/ARCHITECTURE.md#module-responsibilities` (prohibiting platform imports in `rtp-core` and `rtp-api`)

### 4. Live Server Fuzzing & Memory Verification
- **Leak Fuzzing & Memory Lifecycle Attestation:**
  - Route to: `docs/adr/ADR-111-live-server-behaviour-fuzzing.md`
  - Execution procedure: `helpers/StressTestRTP/LEAK_FUZZING.md`

### 5. Operator Documentation & Requirements Parity
- **Traceability Matrix (Rule D-001):**
  - Route to: `docs/dev/TRACEABILITY.md`
- **Subsystem Scope Definition (Rule D-007):**
  - Route to: `docs/dev/REQUIREMENTS.md#0-in-scope--out-of-scope`
- **Admin Configuration Reference:**
  - Route to: `docs/admin/configuration/` and `docs/admin/COMMANDS.md`
- **Comprehensive Rules Catalog:**
  - Route to: `docs/dev/RULES.md`
