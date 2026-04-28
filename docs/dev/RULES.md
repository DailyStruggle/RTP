# Project Rules

This document outlines critical development rules that MUST be followed to ensure the stability, safety, and performance of the RTP plugin. The **S-series** rules are derived from the [Prohibition Requirements](REQUIREMENTS.md#3-prohibition-requirements); other rule families (F-, D-) have their own sources, cited per rule.

For operational guidelines and AI-specific instructions, see [`AGENTS.md`](../../.junie/AGENTS.md). The catalog of document types is appended at the end of this file.

## Quick Reference

Scan this table first. Read the detailed rule below only if you are touching code in that area.

| ID    | Family | One-line rule                                      | Source                                      |
|-------|--------|----------------------------------------------------|---------------------------------------------|
| S-001 | Safety | No unsafe teleport destinations                    | `REQ-RTP-S-001`                             |
| S-002 | Safety | No leaked force-loaded chunks                      | `REQ-RTP-S-002`                             |
| S-003 | Safety | Respect land protection                            | `REQ-RTP-S-003`                             |
| S-004 | Safety | Report all failures; never discard silently        | `REQ-RTP-S-004`                             |
| S-005 | Safety | No main-thread chunk I/O                           | `REQ-RTP-S-005`                             |
| S-006 | Safety | Throw `IllegalStateException` on early API access  | `REQ-RTP-S-006`                             |
| F-001 | Folia  | Verify region thread ownership before scheduling   | `REQ-FOLIA-ARCH-001`, `REQ-FOLIA-ARCH-002`  |
| F-002 | Folia  | Use count-bound (not time-bound) execution         | `REQ-FOLIA-ARCH-005`, `REQ-FOLIA-ARCH-006`  |
| F-003 | Folia  | Isolate economy interactions off region threads    | `REQ-FOLIA-ARCH-007`, `REQ-FOLIA-ARCH-008`  |
| D-001 | Doc    | Maintain traceability (requirement ↔ code ↔ test)  | self-contained                              |
| D-002 | Doc    | Be specific and unambiguous; define terms          | self-contained                              |
| D-003 | Doc    | Single Source of Truth — reference, don't duplicate| self-contained                              |
| D-004 | Doc    | Write for the audience                             | self-contained                              |
| D-005 | Doc    | Propose architecture before implementation         | self-contained                              |

## Core Development Rules

- **Rule S-001: No Unsafe Teleport Destinations**
  - **Description**: Do not teleport players to locations that are inherently dangerous (e.g., lava, fire, void).
  - **Requirement**: `REQ-RTP-S-001`

- **Rule S-002: No Leaked Force-Loaded Chunks**
  - **Description**: Ensure every acquired chunk ticket is released to prevent memory leaks and performance degradation.
  - **Requirement**: `REQ-RTP-S-002`

- **Rule S-003: Respect Land Protection**
  - **Description**: Do not teleport players into areas protected by third-party claim plugins.
  - **Requirement**: `REQ-RTP-S-003`

- **Rule S-004: Report All Failures**
  - **Description**: Never discard a teleport request silently. All failures must be reported to the user and logged.
  - **Requirement**: `REQ-RTP-S-004`

- **Rule S-005: No Main-Thread Chunk I/O**
  - **Description**: Never perform chunk loading or validation on the main server thread. Use asynchronous schedulers.
  - **Requirement**: `REQ-RTP-S-005`

- **Rule S-006: Handle Early API Access**
  - **Description**: Prevent `NullPointerException`s by throwing `IllegalStateException` when the API is called before the core plugin is loaded.
  - **Requirement**: `REQ-RTP-S-006`

## Folia-Specific Architectural Rules

These rules are critical for ensuring thread safety and performance on the Folia platform.

- **Rule F-001: Verify Thread Ownership**
  - **Description**: Before dispatching a task to a regional scheduler, always verify if the current thread already owns the target region. Execute immediately if it does to avoid unnecessary 1-tick delays.
  - **Requirement**: `REQ-FOLIA-ARCH-001`, `REQ-FOLIA-ARCH-002`

- **Rule F-002: Use Count-Bound Execution**
  - **Description**: In regional threads, all iterative background tasks must be bounded by a fixed instruction or item count, not wall-clock time. Time-based slicing is non-deterministic on Folia.
  - **Requirement**: `REQ-FOLIA-ARCH-005`, `REQ-FOLIA-ARCH-006`

- **Rule F-003: Isolate Economy Interactions**
  - **Description**: Never perform economy transactions (e.g., Vault API calls) directly on a Folia region thread. Delegate all economy interactions to a global or async scheduler to prevent `ThreadAccessExceptions`.
  - **Requirement**: `REQ-FOLIA-ARCH-007`, `REQ-FOLIA-ARCH-008`

## Documentation and Specification Rules

These rules govern how project documentation, requirements, and design specifications are written and maintained.

- **Rule D-001: Maintain Traceability**
  - **Description**: All requirements, design decisions, and rules must be linked to a source or justification. High-level concepts must trace down to specific implementations, and code changes must trace back to a requirement or decision.

- **Rule D-002: Be Specific and Unambiguous**
  - **Description**: Use clear, precise, and unambiguous language. Define new or specialized terms in `GLOSSARY.md`. For requirements, use "shall" for mandatory items and "should" for recommendations.

- **Rule D-003: Single Source of Truth (SSoT)**
  - **Description**: Every piece of project information (a requirement, a rule, a definition) must have exactly one authoritative location. Documents shall reference this single source rather than duplicating information.

- **Rule D-004: Write for the Audience**
  - **Description**: Tailor the language, detail, and format to the intended audience. `README.md` is for users (high-level), `docs/dev/` is for developers (technical), and `.junie/` is for AI agents (operational).

- **Rule D-005: Propose Architecture Before Implementation**
  - **Description**: For any refactor or new feature that touches more than one class, crosses a module boundary, or introduces a new command architecture, a written proposal must be presented and approved before any code is written.

## Maintenance Protocol

- Any new ADR that introduces a prohibition or a new safety/architectural invariant MUST add a corresponding rule to this file (and its quick-reference row) in the same change set.
- When a rule's underlying requirement is renamed or renumbered, update both the detailed bullet and the quick-reference table row; stale requirement IDs silently break `Rule D-001`.

---

## Appendix: Document Type Catalog

Project documents follow one of two structures:

- **Centralized ("Living")** — a single project-wide document in `docs/dev/` covering a concern that spans modules.
- **Recurring** — a high-level summary in `docs/dev/` plus a module-specific copy in each submodule. Recurring docs MUST use the identical filename and top-level heading in every module so that a filename search yields the complete set.

| # | Type | Structure | Location | Primary audience |
|---|------|-----------|----------|------------------|
| 1 | Requirements (`what`) | Recurring | `docs/dev/REQUIREMENTS.md`, module `REQUIREMENTS.md` | PMs, devs, QA |
| 2 | Design (`how`) | Recurring | `docs/dev/DESIGN.md`, module `DESIGN.md` | Devs, architects |
| 3 | ADRs (`why`, immutable) | Centralized | `docs/adr/ADR-NNN-*.md` | Devs, architects |
| 4 | Plans | Centralized | `docs/dev/*_PLAN.md` | Devs, PMs |
| 5 | Traceability / Coverage | Centralized | `docs/dev/TRACEABILITY.md`, `COVERAGE_PLAN.md` | Devs, PMs, QA |
| 6 | Rules (this file) | Centralized | `docs/dev/RULES.md` | Devs, AI agents |
| 7 | Glossary | Centralized | `docs/dev/GLOSSARY.md` | All contributors |

IDs use `REQ-<module>-<family>-NNN` (requirements) and ADR-NNN (decisions). Requirements use `shall` / `shall not`; see Rule D-002.
