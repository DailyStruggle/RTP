# ADR-018 — `AGENTS.md` Public Release Structure: Thin Router + Canonical Sources

**Status:** Accepted
**Date:** 2026-04-20

## Context

`.junie/AGENTS.md` grew to 184 lines / ~32 KB as successive sessions accreted engineering lore, per‑feature narratives, and dated "gotcha" notes. Prior to public release as an open, community‑editable guide, this created three problems:

1. **Token cost.** Every agent session reads the full file; ~50% of its content is narrative rationale or dated lore that most tasks do not need.
2. **Source-of-truth duplication.** Key topics (stale-chunk guard, Anvil prefilter, shutdown-flush pipeline, per-requirement "already satisfied by" class lists) were restated in `AGENTS.md` in addition to their canonical home in an ADR, `DESIGN.md`, `REQUIREMENTS.md`, or `TRACEABILITY.md`. Stale copies actively mislead agents when code is refactored.
3. **Self‑contradiction with project rules.** `AGENTS.md` itself mandates "Separation of Concerns — requirements define *what*, not *how*" and "no temporal narrative phrasing" in requirement docs, yet the `Already satisfied by:` blocks and dated `(2026-04-18)` pitfalls embed exactly that kind of implementation/temporal content in a top-level rules file.

A gap analysis concluded that a structural split would reduce the file to a thin routing layer while preserving every piece of information in a canonical source.

## Decision

`AGENTS.md` is refactored into a **thin router** (~120 lines) containing only:

- A **TL;DR** skim block for single-pass agents.
- The **Pre-Flight Checklist** and **Backup Policy**.
- The **Required Reading** task→doc table.
- The **S-00x Quick Reference** table (IDs + one-line rules; *no* satisfaction prose).
- **Architecture Boundaries**, **Logging & Feedback**, **Environment & Execution** (compressed), **Code & Testing Conventions**, **Self-Updating Protocol**.
- One-line pointers into the canonical sources listed below.

Content extracted out of `AGENTS.md`:

| Moved from `AGENTS.md` | Canonical destination |
|------------------------|-----------------------|
| `Already satisfied by:` paragraphs for S-001…S-007, F-013 | `docs/dev/TRACEABILITY.md` (per-REQ rows) |
| S-005 narrative: Spigot fallback, prefilter semantics, stale-chunk guard | `ADR-015`, `ADR-016`, `docs/dev/DESIGN.md` |
| Dated DB persistence / shutdown-flush / shared-connection notes | `docs/dev/LESSONS_LEARNED.md` (new) |
| `AI Formatting Rules & Communication Style` (Junie-specific) | `.junie/JUNIE.md` (Junie-only; not in public guide) |
| Full document index | `docs/dev/INDEX.md` (new) |

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Leave `AGENTS.md` as a single large file | Continued token bloat; public contributors inherit the duplication problem. |
| Rewrite inline, no extraction | Loses irrecoverable engineering lore (dated pitfalls that aren't in any ADR). |
| Move everything into ADRs | ADRs are decision records, not lesson logs or task-oriented cheat sheets. |
| Keep Junie formatting rules in public `AGENTS.md` | Other agents (Claude Code, Aider, Cursor, Copilot) impose their own formatting; Junie-specific guidance biases non-Junie contributors. |

## Consequences

- **Positive:**
  - ~50% token reduction per agent read of `AGENTS.md`.
  - Single source of truth per concept; refactors update one file, not three.
  - Public contributors edit a file that enforces the project's own "Separation of Concerns" rule.
  - New `LESSONS_LEARNED.md` gives dated engineering notes a stable home that doesn't pretend to be normative rules.
  - `INDEX.md` becomes the onboarding entry point for new contributors and agents alike.

- **Negative / Trade-offs:**
  - Agents now occasionally need a second file read (e.g., TRACEABILITY.md) to see the "already satisfied" pointer that used to be inline. Mitigated by the Required Reading table explicitly listing it for safety-critical tasks.
  - The Self-Updating Protocol must now route new notes to the correct destination file rather than always appending to `AGENTS.md`. The protocol has been updated to reflect this.

## References

- `.junie/AGENTS.md` (post-refactor)
- `.junie/AGENTS.md.bak` (pre-refactor snapshot, retained for the migration commit only)
- `docs/dev/TRACEABILITY.md`, `docs/dev/LESSONS_LEARNED.md`, `docs/dev/INDEX.md`
- ADR-015 (stale-chunk guard), ADR-016 (Anvil prefilter) — canonical homes for S-005 narrative
