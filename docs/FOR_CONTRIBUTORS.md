# Start Here — Core Contributors

**Current Plugin Version:** `3.0.0-beta.1`

This page guides developers who contribute to `rtp-core`, `rtp-api`, or a platform adapter module.
If you are fixing a bug, adding a feature, or updating the architecture, start here.

---

## Recommended Reading Order

### 1. [CONTRIBUTING.md](../CONTRIBUTING.md)
Build instructions, code style rules, branch naming, the four-step requirement workflow, and CI expectations.
Read this before opening a pull request.

### 2. [ARCHITECTURE.md](dev/ARCHITECTURE.md)
Module breakdown and architectural boundaries: what belongs in `rtp-core`, `rtp-api`, and each platform adapter.
The architecture tests in `RTPArchitectureTest.java` enforce these boundaries — know them before writing code.

### 3. [CONCEPTS.md](dev/CONCEPTS.md)
Plain-language explanation of the teleport pipeline, queue system, and bounded selection algorithm.
Useful context before diving into the implementation.

### 4. [DESIGN.md](dev/DESIGN.md)
Deep-dive into bounded execution, the `CountBoundTaskPipe`, `MemoryTracker`, and fault-tolerance contracts.
The authoritative source on *why* the core is structured the way it is.

### 5. [REQUIREMENTS.md](dev/REQUIREMENTS.md)
Functional, non-functional, and architectural requirements with unique `REQ-*` IDs.
Every new feature or fix must trace to an existing requirement or introduce a new one.

### 6. [TRACEABILITY.md](dev/TRACEABILITY.md)
The requirement → design decision → implementing class → test matrix.
The `check_traceability.sh` CI script fails if any `REQ-*` ID lacks a row here — add the row before pushing.

### 7. [STAKEHOLDERS.md](dev/STAKEHOLDERS.md)
Actor definitions and their goals. Keeps requirements grounded in real user needs.

### 8. [GLOSSARY.md](dev/GLOSSARY.md)
Definitions for every domain term. Use these exact terms in code, comments, and commit messages for consistency.

### 9. [docs/adr/](adr/README.md)
Architecture Decision Records — the *why* behind key design choices and the alternatives that were rejected.
Read the relevant ADRs before changing anything the decisions describe.

---

## Reference Material

- [FAILURE_MODES.md](admin/FAILURE_MODES.md) — per-component failure catalog; useful when writing or reviewing error-handling code.
- [HAZARDS.md](admin/HAZARDS.md) — hazard register; consult when a change touches chunk loading, scheduling, or memory management.
- [CHANGELOG.md](../CHANGELOG.md) — release history; follow the existing format when adding an Unreleased entry.
