# Documentation Guide

This document defines the standard forms of documentation used in the RTP project. Adhering to these standards ensures that information is consistent, discoverable, and serves its intended purpose.

For rules governing how to write documentation, see [`RULES.md`](./RULES.md) (see the `D-*` family).

## Document Structures: Centralized vs. Recurring

Project documents are organized in one of two ways:

- **Centralized ("Living") Documents**: A single, project-wide document located in `docs/dev/`. It provides a high-level view of concerns that span multiple modules.
- **Recurring Documents**: A high-level summary lives in `docs/dev/`, and each submodule (`rtp-core/`, `rtp-api/`, …) carries its own copy with module-specific content.
  - **Naming contract**: every Recurring document MUST use the identical filename and top-level heading in every module where it appears, so that a simple filename search (or IDE find-in-path) yields the complete set.

## Document Type Index

Scan this table first; read the section below for details only on the type you need.

| # | Type                        | Structure    | Location                | Primary audience              |
|---|-----------------------------|--------------|-------------------------|-------------------------------|
| 1 | Requirements                | Recurring    | `docs/dev/`, modules    | PMs, developers, QA           |
| 2 | Design                      | Recurring    | `docs/dev/`, modules    | Developers, architects        |
| 3 | Architecture Decision Records (ADRs) | Centralized | `docs/adr/`        | Developers, architects        |
| 4 | Plans                       | Centralized  | `docs/dev/`             | Developers, PMs               |
| 5 | Traceability & Coverage     | Centralized  | `docs/dev/`             | Developers, PMs, QA           |
| 6 | Rules                       | Centralized  | `docs/dev/RULES.md`     | Developers, AI agents         |
| 7 | Glossary                    | Centralized  | `docs/dev/GLOSSARY.md`  | All contributors              |

---

## 1. Requirements (`Recurring`)

- **Purpose**: To define *what* the system or module must do.
- **Audience**: Project managers, developers, QA testers.
- **Structure**:
    - A high-level `REQUIREMENTS.md` exists in `docs/dev/`.
    - Each submodule has its own `REQUIREMENTS.md` detailing its specific contract.
    - Requirements use a unique ID (e.g., `REQ-CORE-F-001`) and the word "shall."
- **Examples**:
    - [`docs/dev/REQUIREMENTS.md`](./REQUIREMENTS.md) (High-level)
    - [`rtp-core/REQUIREMENTS.md`](../../rtp-core/REQUIREMENTS.md) (Module-specific)

## 2. Design Documents (`Recurring`)

- **Purpose**: To describe *how* the system is built to meet its requirements.
- **Structure**:
    - A central `DESIGN.md` in `docs/dev/` outlines the overall project architecture.
    - Submodules may contain their own `DESIGN.md` to detail internal implementation specifics.
- **Example**:
    - [`docs/dev/DESIGN.md`](./DESIGN.md) (High-level)

## 3. Architectural Decision Records (ADRs) (`Centralized`)

- **Purpose**: To document a significant architectural decision, its context, and its consequences.
- **Structure**: Stored as individual, sequentially numbered files in a single directory.
- **Location**: [`docs/adr/`](../adr/)

## 4. Plans (`Centralized`)

- **Purpose**: To outline the sequence of steps for a large-scale goal.
- **Structure**: A single document per major initiative.
- **Location**: `docs/dev/`
- **Examples**:
    - [`MULTI_PLATFORM_PLAN.md`](./MULTI_PLATFORM_PLAN.md)
    - [`RUNTIME_TEST_SUITE_PLAN.md`](./RUNTIME_TEST_SUITE_PLAN.md)

## 5. Traceability and Coverage (`Centralized`)

- **Purpose**: To provide a consolidated, project-wide view of requirement-to-code traceability and test coverage.
- **Audience**: Developers, project managers, QA testers.
- **Structure**: Living documents that are updated continuously as the project evolves.
- **Location**: `docs/dev/`
- **Examples**:
    - [`TRACEABILITY.md`](./TRACEABILITY.md)
    - [`COVERAGE_PLAN.md`](./COVERAGE_PLAN.md)

## 6. Rules (`Centralized`)

- **Purpose**: To enumerate the hard development rules derived from prohibition requirements and platform-specific invariants, in a form optimized for quick scanning.
- **Audience**: Developers and AI agents (this file is the primary rule-lookup target for both).
- **Location**: [`RULES.md`](./RULES.md)

## 7. Glossary (`Centralized`)

- **Purpose**: To define canonical domain terms and disambiguate overloaded vocabulary so that the same word is not used with different meanings across the codebase and docs.
- **Location**: [`GLOSSARY.md`](./GLOSSARY.md)
