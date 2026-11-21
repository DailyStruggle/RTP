# Project Rules

This document defines critical engineering rules across the monorepo (`api/*` standalone SDKs, `platforms/*` carriers, `resource/*` Minecraft plugins and mods, and repository tooling).

- **Scope tiers used below:**
  - **`Repo-wide`** — applies to every module in the repository (Java, Python, scripts, web/media tooling, and documentation).
  - **`Minecraft Java`** — applies to Minecraft plugins, mods, platform carriers, and SDKs (`api/*`, `platforms/*`, `resource/*`, `helpers/*`) whenever the relevant capability (world/entity placement, chunk/region I/O, commands, Folia scheduling, or service registries) is present. Product-specific runtime bindings (for example, `LeafRTP`'s `MemoryTracker`, `TeleportPipelineTask`, or `RTP.scheduler`) apply only within that product's modules.

For operational guidelines and AI-specific instructions, see [`AGENTS.md`](../../.junie/AGENTS.md). The catalog of document types is appended at the end of this file.

## Quick Reference

Scan this table first. Read the detailed rule below only if you are touching code in that area.

| ID    | Family | Scope          | One-line rule                                                                 | Origin / Source                             |
|-------|--------|----------------|-------------------------------------------------------------------------------|---------------------------------------------|
| S-001 | Safety | Minecraft Java | No unsafe entity placement or teleport destinations                           | `REQ-RTP-S-001`                             |
| S-002 | Safety | Minecraft Java | No leaked chunk tickets, channel sessions, or runtime handles                 | `REQ-RTP-S-002`                             |
| S-003 | Safety | Minecraft Java | Respect land-claim and region protection hooks                                | `REQ-RTP-S-003`                             |
| S-004 | Safety | Repo-wide      | Report all failures; never swallow exceptions or faults silently              | `REQ-RTP-S-004`                             |
| S-005 | Safety | Minecraft Java | No blocking chunk, disk, database, or network I/O on main/region tick threads | `REQ-RTP-S-005`                             |
| S-006 | Safety | Minecraft Java | Fail closed (`IllegalStateException`) on premature startup API access         | `REQ-RTP-S-006`                             |
| S-007 | Safety | Minecraft Java | Use configurable message templates for command and busy failure states        | `REQ-RTP-S-007`, `REQ-RTP-F-013`            |
| F-001 | Folia  | Minecraft Java | Verify region thread ownership before scheduling regional work                | `REQ-FOLIA-ARCH-001`, `REQ-FOLIA-ARCH-002`  |
| F-002 | Folia  | Minecraft Java | Use count-bound (not wall-clock time-bound) regional iteration                | `REQ-FOLIA-ARCH-005`, `REQ-FOLIA-ARCH-006`  |
| F-003 | Folia  | Minecraft Java | Isolate global third-party and economy hooks off Folia region threads         | `REQ-FOLIA-ARCH-007`, `REQ-FOLIA-ARCH-008`  |
| D-001 | Doc    | Repo-wide      | Maintain traceability (requirement ↔ code ↔ test)                             | self-contained                              |
| D-002 | Doc    | Repo-wide      | Be specific and unambiguous; define domain terms                              | self-contained                              |
| D-003 | Doc    | Repo-wide      | Single Source of Truth — reference, don't duplicate                           | self-contained                              |
| D-004 | Doc    | Repo-wide      | Write for the audience                                                        | self-contained                              |
| D-005 | Doc    | Repo-wide      | Propose architecture before implementation; treat questions as read-only      | self-contained                              |
| D-006 | Doc    | Repo-wide      | Mirror prose voice and shallow-AST salience for external user copy            | `ADR-130`                                   |
| D-007 | Doc    | Repo-wide      | New subsystem updates owning product scope (`REQUIREMENTS.md` section 0)      | `ADR-057`, product `REQUIREMENTS.md`        |
| D-008 | Code   | Repo-wide      | Large deduplicated classes delegate to package-private helpers                | self-contained                              |
| D-009 | Doc    | Repo-wide      | Operator docs and `CHANGELOG.md` travel with operator-visible changes         | self-contained                              |
| D-010 | Doc    | Minecraft Java | Maintain AST & corpus searchability (`@governedBy`, capability Javadoc)       | `ADR-115`                                   |

## Core Safety & Reliability Rules

The **S-series** originated in `LeafRTP`'s prohibition requirements (`REQ-RTP-S-001..S-007`) and generalizes across all Minecraft plugins, mods, platform carriers, and SDKs in the monorepo (`S-004` applies repo-wide).

- **Rule S-001: No Unsafe Entity Placement or Teleport Destinations** (`Minecraft Java`)
  - **Description**: Modules that teleport, spawn, or reposition players or entities shall verify destination safety rather than placing entities into lava, fire, void, or suffocation hazards.
  - **Origin**: `REQ-RTP-S-001`

- **Rule S-002: No Leaked Chunk Tickets or Runtime Handles** (`Minecraft Java`)
  - **Description**: Every acquired chunk force-load ticket, network/channel session, pooled slab buffer, or scheduled task handle shall be deterministically released on all exit paths to prevent memory leaks and stuck chunks.
  - **Origin**: `REQ-RTP-S-002`

- **Rule S-003: Respect Land Protection** (`Minecraft Java`)
  - **Description**: Modules that move players into world coordinates or mutate blocks on behalf of players shall honor third-party claim and region-protection integrations rather than bypassing protected land.
  - **Origin**: `REQ-RTP-S-003`

- **Rule S-004: Report All Failures** (`Repo-wide`)
  - **Description**: Never silently discard exceptions, I/O faults, return-status errors, or command/pipeline failures. Log unexpected faults with context and report actionable failure feedback to the caller or user.
  - **Origin**: `REQ-RTP-S-004`

- **Rule S-005: No Main-Thread Blocking I/O** (`Minecraft Java`)
  - **Description**: Never perform synchronous chunk loading, region/disk file reads, or blocking database/network waits on main or regional server tick threads. Dispatch blocking work via asynchronous schedulers or dedicated I/O pools.
  - **Origin**: `REQ-RTP-S-005`

- **Rule S-006: Handle Early API Access** (`Minecraft Java`)
  - **Description**: Public APIs and extension registries invoked before runtime initialization completes shall fail closed by throwing `IllegalStateException` rather than throwing `NullPointerException` or silently no-oping.
  - **Origin**: `REQ-RTP-S-006`

- **Rule S-007: Configurable Command and Busy Failure Feedback** (`Minecraft Java`)
  - **Description**: User-facing command failure states (`busy`, `invalid-command`, `bad-parameter`, `no-permission`) shall resolve from configurable message templates (`messages.yml` / `commands.yml`) rather than hardcoded string literals.
  - **Origin**: `REQ-RTP-S-007`, `REQ-RTP-F-013`

## Folia & Regional Threading Rules

These rules apply across all Folia-compatible Minecraft plugins, platform carriers, and SDKs (`Minecraft Java`).

- **Rule F-001: Verify Thread Ownership**
  - **Description**: Before dispatching work to a regional or entity scheduler, verify whether the current thread already owns the target region/entity and execute immediately when safe to avoid unnecessary 1-tick handoff latency.
  - **Origin**: `REQ-FOLIA-ARCH-001`, `REQ-FOLIA-ARCH-002`

- **Rule F-002: Use Count-Bound Execution**
  - **Description**: In regional threads, iterative tasks shall be bounded by a deterministic item or step count rather than wall-clock time. Wall-clock slicing is non-deterministic across independent Folia region threads.
  - **Origin**: `REQ-FOLIA-ARCH-005`, `REQ-FOLIA-ARCH-006`

- **Rule F-003: Isolate Global Third-Party and Economy Interactions**
  - **Description**: Never invoke non-region-safe third-party hooks (for example, Vault economy APIs or global plugin registries) directly on a Folia region thread. Delegate those interactions to a global or async scheduler to prevent `ThreadAccessException` faults.
  - **Origin**: `REQ-FOLIA-ARCH-007`, `REQ-FOLIA-ARCH-008`

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

- **Rule D-005: Propose Architecture Before Implementation & Scope Discipline**
  - **Description**:
    1. **Questions & concerns are read-only:** When the user asks a question, challenges an action, or expresses concern (`"why?"`, `"didn't we just solve that?"`, `"wait, you changed X?"`, `"i hope Y is still valid"`), treat it strictly as a request for evaluation and explanation—never as an implicit instruction to edit, revert, build, or deploy. Perform only read-only verification (`repo_ast_context` with `@source`), answer with exact evidence (`answer(is_terminal=False)` or `ask_user`), and wait for an explicit instruction before mutating or reverting.
    2. **Propose before implementation:** For any refactor or feature that touches more than one class, crosses a module boundary, alters method parameters/bounds, or introduces a new command architecture, present a written proposal (1. Affected classes/modules, 2. Intended before/after structure, 3. Relevant `REQ-*`/ADRs, 4. Risks and trade-offs) and wait for explicit approval before writing code.
    3. **Zero speculative production edits on test failure:** If a test or benchmark fails, inspect the test fixture and verbatim `@source` implementation first (never infer parameter bounds, offsets, or call contracts from elided `Jminor IR`). Never modify unrequested production classes or call sites without presenting evidence and getting explicit approval.
    4. **Artifact & log provenance:** Before diagnosing any local or remote log or benchmark summary, verify its timestamp is newer than the compiled `.jar` and the working-tree edits under test.
- **Rule D-006: Mirror Prose Voice for External User Copy**
  - **Description**: When authoring external listings, README, marketing copy, or release notes, mirror the maintainer's established voice (`ADR-130`): first person, conversational, low ceremony, honest about limits and trade-offs. Prefer concrete evidence over adjectives (benchmark numbers, reproducibility notes; no AI hype buzzwords). State technical specifics plainly (for example, spiral math, `.mca`/`.lca` pre-filtering, or async caching when applicable), but keep internal math out of Zone 1 (above the fold), marketplace taglines, and the Zone 2 operator on-ramp (`Common setups`, `Install`, `Features`), and structure storefront pages for shallow-AST heading-vector scoping (`## Testing and quality gates`, `## Commands, configuration, and FAQ`), contract-first lead sentences, and >=70% usability/safety/reference salience (`ADR-130`). Whenever a `LeafRTP` storefront or overview section mentions `space-filling` or `spiral` math, state in the same section that the math is internal and operators only configure standard shapes (`CIRCLE`, `SQUARE`) and a block radius. Keep reference tables in Section 6 concise (at most 10 data rows per table, linking to `docs/admin/` for the full catalog). Use ASCII punctuation only (hyphens/colons, no em/en dashes; see `resource/<Product>/docs/publishing/FRONT_PAGE.md`). Storefront pages (BuiltByBit, Modrinth, Hangar) are generated from each product's `FRONT_PAGE.md` source by `scripts/release/build_front_pages.py`; paid-support copy goes in `kind: promo` blocks so it never reaches Hangar or Modrinth.

- **Rule D-007: Scope Travels With the Subsystem**
  - **Description**: A change that introduces a new subsystem (a new command family, module, bundled addon, network surface, or operator-facing engine) shall update the In Scope / Out of Scope lists in the owning product's `REQUIREMENTS.md` section 0 (for example, `resource/LeafRTP/docs/dev/REQUIREMENTS.md`) in the same change set, and the D-005 proposal shall state the scope impact. Within `LeafRTP`, features outside selection, the teleport pipeline, and the platform adapters should ship as addons against `rtp-api` (`ADR-057`) unless the proposal records why core is required.

- **Rule D-008: Deduplicate Behind a Facade, Not Into One File**
  - **Description**: When deduplication concentrates shared behaviour in one class (e.g. `MemoryShape`, `MenuRedeemSubcommand`, `ScanTask`, `ConfigParser`), keep that class as the single public entry point and move cohesive concerns (codec, table, persistence, rendering) into package-private helpers in the same package. Apply this opportunistically when a change already touches the concern; do not open refactor-only changes without a D-005 proposal. Public API and persisted formats shall not change as part of such a split.

- **Rule D-009: Operator Docs Travel With the Change**
  - **Description**: A change that adds, renames or removes an operator-visible config key, command, permission or message key, or changes its default, shall update the operator documentation (`docs/admin/`) and `CHANGELOG.md` in the same change set. Message keys also follow *Locale Parity Maintenance* in `AGENTS.md`. This is the contributor-side form of a release invariant (every shipped key is documented); that invariant becomes a requirement once a mechanical check compares the shipped `.yml` keys, commands and permissions with `docs/admin/`.

- **Rule D-010: Maintain AST and Corpus Searchability**
  - **Description**: Because the repository's SQLite FTS5 and AST knowledge graph (`corpus-atlas/`, `scripts/repo_ast.py`) is both the search engine and the corpus, every new or modified `TIER_1_SPI_PURE` (`api/*`, `rtp-api`) or `TIER_2_CORE_DOMAIN` (`rtp-core`, `<product>-common` core domain modules) interface and class shall include a concise class-level Javadoc capability summary (stating its domain purpose in standard terminology) and `@governedBy` / `@trace` tags naming its active `ADR-*`, `REQ-*`, and `S-00x` invariants (`ADR-115`). ADRs (`docs/adr/`, `<subproject>/docs/adr/`) and `TRACEABILITY.md` rows shall wrap exact Java class and JUnit test identifiers in backticks (`` `ClassName` ``, `` `ClassNameTest` ``) so the AST baker links them deterministically. When introducing or refactoring a shared SPI or utility, run `python3 scripts/repo_ast.py search "<capability>"` and `python3 scripts/repo_ast.py audit` to verify the symbol ranks in the top 3 for its capability and carries non-empty `Gov(c)` links.

## Maintenance Protocol

- Any new ADR that introduces a prohibition or a new safety/architectural invariant MUST add a corresponding rule to this file (and its quick-reference row, including its `Scope` tier) in the same change set.
- Any new ADR that introduces a subsystem MUST update the owning product's `REQUIREMENTS.md` section 0 in the same change set (Rule D-007).
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
