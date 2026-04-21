# ADR-018 — `AGENTS.md` Public Release Structure: Thin Router + Canonical Sources

**Status:** Accepted
**Date:** 2026-04-20

## Context

`.junie/AGENTS.md` is a top-level, community-editable guide read at the start of every agent session. Three constraints shape its shape:

1. **Token cost.** Every agent session reads the full file; narrative rationale and dated lore inflate the read without helping most tasks.
2. **Single source of truth.** Topics with canonical homes (stale-chunk guard, Anvil prefilter, shutdown-flush pipeline, per-requirement "already satisfied by" class lists) shall not be restated — duplicated copies mislead agents when code is refactored.
3. **Separation of concerns.** `AGENTS.md` itself mandates that requirements state *what*, not *how*, and forbids temporal narrative phrasing; the file shall therefore not embed implementation-satisfaction tables or dated pitfall notes.

## Decision

`AGENTS.md` is refactored into a **thin router** (~120 lines) containing only:

- A **TL;DR** skim block for single-pass agents.
- The **Pre-Flight Checklist** and **Backup Policy**.
- The **Required Reading** task→doc table.
- The **S-00x Quick Reference** table (IDs + one-line rules; *no* satisfaction prose).
- **Architecture Boundaries**, **Logging & Feedback**, **Environment & Execution** (compressed), **Code & Testing Conventions**, **Self-Updating Protocol**.
- One-line pointers into the canonical sources listed below.

Content lives in its canonical home, not in `AGENTS.md`:

| Topic | Canonical home |
|-------|----------------|
| Per-REQ "already satisfied by" class lists (S-001…S-007, F-013) | `docs/dev/TRACEABILITY.md` |
| S-005 narrative (Spigot fallback, prefilter semantics, stale-chunk guard) | `ADR-015`, `ADR-016`, `docs/dev/DESIGN.md` |
| Dated DB persistence / shutdown-flush / shared-connection notes | `docs/dev/LESSONS_LEARNED.md` |
| Junie-specific AI formatting and communication rules | `.junie/JUNIE.md` |
| Full document index | `docs/dev/INDEX.md` |

## Consequences

- **Positive:**
  - ~50% token reduction per agent read of `AGENTS.md`.
  - Single source of truth per concept; refactors update one file, not three.
  - Public contributors edit a file that enforces the project's own "Separation of Concerns" rule.
  - New `LESSONS_LEARNED.md` gives dated engineering notes a stable home that doesn't pretend to be normative rules.
  - `INDEX.md` becomes the onboarding entry point for new contributors and agents alike.

- **Negative / Trade-offs:**
  - Agents occasionally need a second file read (e.g., `TRACEABILITY.md`) to resolve "already satisfied" pointers. Mitigated by the Required Reading table, which lists the relevant destination for safety-critical tasks.
  - The Self-Updating Protocol routes new notes to the correct destination file rather than appending to `AGENTS.md`.

## References

- `.junie/AGENTS.md`
- `docs/dev/TRACEABILITY.md`, `docs/dev/LESSONS_LEARNED.md`, `docs/dev/INDEX.md`
- ADR-015 (stale-chunk guard), ADR-016 (Anvil prefilter) — canonical homes for S-005 narrative
