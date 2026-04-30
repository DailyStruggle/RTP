# Project Guidelines

Operational guide for AI agents and human contributors working in the RTP repository. Keep this file thin — it is a **router**, not an encyclopaedia. Detailed rationale, implementation pointers, and engineering lore live in the canonical sources listed below. Structural rationale: see [ADR-018](../docs/adr/ADR-018-agents-md-public-release-structure.md).

> 📎 New here? Start at [`docs/dev/INDEX.md`](../docs/dev/INDEX.md).

---

## TL;DR (scan first)

1. Run the **Pre-Flight Checklist** before every code or terminal action.
2. Never perform synchronous chunk I/O on the main thread (S-005).
3. Never silently swallow a teleport failure (S-004).
4. Use **PowerShell** syntax (`.\gradlew`, `;` not `&&`).
5. Use the `search_project` tool — not `grep`/`find` — to search the codebase.
6. Java 21+ is required (REQ-RTP-SYS-001).
7. Before modifying an uncommitted **code** file, create a `.bak` copy beside it. Skip for git-clean files and for docs/markdown.
8. **Stay on task.** If you spot an unrelated potential bug, record it in [`docs/dev/POTENTIAL_BUGS.md`](../docs/dev/POTENTIAL_BUGS.md) and keep going — do not fix it in the current change.

---

## Pre-Flight Checklist (mandatory)

Before generating code or terminal commands, explicitly state and verify:

1. **Target platform** — Folia, Paper, Spigot, or Fabric.
2. **Thread context** — on Folia, `Bukkit.isOwnedByCurrentRegion` before scheduling.
3. **Chunk I/O** — zero synchronous chunk loads or blocking `.get()` on the main thread.
4. **Terminal** — PowerShell (`.\gradlew`, `;`, correctly-escaped quotes).
5. **Safety rule** — name the S-00x rule(s) that apply (see table below).
6. **Backups** — `.bak` copy required only for uncommitted **code** files. Skip for git-clean files and for docs/markdown.
7. **Architecture** — if multi-class/module, has the proposal been approved? (Rule D-005)

## Backup Policy

`.bak` copies protect uncommitted code only; git covers committed revisions and docs diffs are cheap.

| File type | Dirty | Clean |
|-----------|-------|-------|
| Code | `.bak` required | No `.bak` (use git) |
| Docs / markdown / config | No `.bak` | No `.bak` |

- Check status with `git status --porcelain <path>` or `git diff --quiet -- <path>`.
- Name: `<original>.bak` in the same directory (e.g., `LocationGenerator.java.bak`).
- Delete after the change is verified and committed.
- When in doubt on code, create the `.bak`.

---

## Required Reading (task → doc)

Read only what the task requires. Do not read everything.

| Task | Read before starting |
|------|----------------------|
| Any safety-critical code (threading, chunk I/O, teleport) | [`docs/dev/REQUIREMENTS.md §3`](../docs/dev/REQUIREMENTS.md), relevant platform `REQUIREMENTS.md` |
| Modifying scheduling or concurrency | [`docs/dev/DESIGN.md`](../docs/dev/DESIGN.md), [`docs/dev/REQUIREMENTS.md §3`](../docs/dev/REQUIREMENTS.md) |
| Placing new code in a module | [`docs/dev/ARCHITECTURE.md`](../docs/dev/ARCHITECTURE.md) + *Architecture Boundaries* below |
| Introducing or renaming a domain term | [`docs/dev/GLOSSARY.md`](../docs/dev/GLOSSARY.md) |
| Writing or updating tests | [`docs/dev/COVERAGE_PLAN.md`](../docs/dev/COVERAGE_PLAN.md), [`docs/dev/TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) |
| Making a structural change | [`docs/adr/README.md`](../docs/adr/README.md) + relevant ADR |
| New feature or platform work | [`docs/dev/MULTI_PLATFORM_PLAN.md`](../docs/dev/MULTI_PLATFORM_PLAN.md) |
| Database / command / shutdown work | [`docs/dev/LESSONS_LEARNED.md`](../docs/dev/LESSONS_LEARNED.md) |
| Verifying a requirement is already satisfied | [`docs/dev/TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) (REQ-* → class → test) |

Full doc catalog: [`docs/dev/INDEX.md`](../docs/dev/INDEX.md).

---

## Prohibition Requirements (S-00x Quick Reference)

Absolute prohibitions from [`REQUIREMENTS.md §3`](../docs/dev/REQUIREMENTS.md). Violating any is a critical defect. For the implementing class / test that already satisfies each rule, see [`TRACEABILITY.md`](../docs/dev/TRACEABILITY.md).

| ID | Rule | Common wrong move |
|----|------|-------------------|
| S-001 | No unsafe-block teleport destinations | A second block check in adapters or commands |
| S-002 | No permanently force-loaded chunks | Extra `close()` on a chunk ticket (double-release) |
| S-003 | No teleport into claim-protected land | Inline claim-plugin calls in the pipeline or commands |
| S-004 | No silently discarded teleport failures | Silent `return` or catch-and-swallow in a pipeline stage |
| S-005 | No chunk loading on the main thread | Calling synchronous `world.getChunkAt()` on any main-thread path |
| S-006 | No NPE when addons call API before core loads | Null-guard returns that silently no-op (throw `IllegalStateException` instead) |
| S-007 | Configurable "busy" and "invalid command" messages | Hardcoding strings for command failure states |

**S-005 nuance** (Anvil pre-filter, stale-chunk guard, per-platform async coverage): see [ADR-015](../docs/adr/ADR-015-stale-chunk-guard-countbound-pipes.md), [ADR-016](../docs/adr/ADR-016-anvil-subsystem.md), and [`DESIGN.md`](../docs/dev/DESIGN.md). Do not refactor any `isChunkLoaded` guard or the `FailTypes.nullChunk` attribution without reading them first — the regression guard is `ReqRtpS004NullChunkAttributionTest`.

Related functional requirement: **REQ-RTP-F-013** — all user-facing messages are configurable via `messages.yml`. See `TRACEABILITY.md` row `REQ-RTP-F-013`.

---

## Folia Threading (hard rules)

- **Zero blocking** — never block a tick thread waiting for chunk loads.
- **Async chaining** — refactor `.get()` / `.join()` into `CompletableFuture` chains (`thenCompose`, `thenAccept`).
- **Region ownership** — use `Bukkit.isOwnedByCurrentRegion` to skip unnecessary 1-tick delays.
- **Vault / economy** — Global Region Scheduler or Async Scheduler only (region threads throw `ThreadAccessException`).
- **Entity Scheduler** — target it for player modifications, including teleports.
- **Task pipelines** — Count-Bound on Folia; Time-Bound is permitted only on Spigot/Paper.
- **Database processing** — enabled on all platforms; on Folia it runs via `RTP.scheduler.runTaskTimerAsynchronously`.

---

## Architecture Boundaries

Place new code following this decision order:

1. **`rtp-api`** — public interfaces and shared models for addon developers. No platform imports.
2. **`rtp-core`** — core logic (regions, queues, spiral math, `MemoryTracker`). No platform imports; changes here affect every platform.
3. **`commands-api` / `effects-api`** — unified frameworks. Extend these, don't fork per-platform.
4. **Platform adapter** (`rtp-spigot`, `rtp-paper`, `rtp-folia`, `rtp-fabric`) — platform-specific only. Never push platform logic into core.
5. **`rtp-plugin`** — Bukkit-family entry point. No business logic.
6. **`addons/`** — third-party integrations that depend only on `rtp-api`.

---

## Propose Before Implementation (Rule D-005)

For any change that touches more than one class, crosses a module boundary, or introduces a new command architecture, present a proposal **before** writing code. Include:

1. Affected classes / modules.
2. Intended before/after structure.
3. Relevant REQ-* requirements or ADRs.
4. Risks and trade-offs.

Wait for explicit approval before implementing. If the change contradicts an existing ADR, say so and propose a superseding ADR.

---

## Stay-On-Task Policy (record, don't chase)

To minimise time spent on unrelated fixes, record incidental discoveries instead of acting on them.

- While working on the current `Effective Issue`, if you notice a **potential bug, suspicious code path, missing validation, stale comment, or latent race** that is **not** required to satisfy the current task, **do not fix it**.
- Append a one-entry record to [`docs/dev/POTENTIAL_BUGS.md`](../docs/dev/POTENTIAL_BUGS.md) before returning to the task. Required fields:
  1. **Date** (YYYY-MM-DD) and **discovered-during** (link/short ref to the issue or task you were on).
  2. **Location** — file path + line range or symbol.
  3. **Symptom / hypothesis** — one or two sentences. What looks wrong, why you suspect it.
  4. **Impact** — best guess at user-visible effect (e.g. "may place player on water in rare race", "log spam only").
  5. **Suggested next step** — minimal investigation or fix sketch (no implementation).
- Exceptions where you may fix in-line:
  - The discovery is a **direct cause** of the current `Effective Issue` symptom.
  - The discovery violates an **S-00x prohibition** (S-001…S-007) that the current change would otherwise leave broken.
  - The user has explicitly broadened scope in an `<issue_update>`.
- Otherwise: record, mention the entry in your `<UPDATE>` / submit summary, and continue.

---

## Logging & Feedback

- Use `RTP.log()` / `RTPServerAccessor.log()` in `rtp-core` and `rtp-api`. Never `Bukkit.getLogger()` or `System.out.println`.
- **Zero `printStackTrace()`** — always `RTP.log(Level.WARNING, "msg", e)`.
- No `org.bukkit.*` imports in `rtp-core` or `rtp-api`. Route through `RTPServerAccessor`.
- Platform-specific `msgInvalidCommand` / `msgBadParameter` overrides (e.g., `BukkitBaseRTPCmd`) **must** call `RTP.log(Level.WARNING, msg)` — required for REQ-RTP-S-004 auditing and `rtp test full`.
- Color handling: `SendMessage.log` in `rtp-spigot` uses `Bukkit.getConsoleSender().sendMessage(msg)` to preserve color codes. Tests / auditors should use `SendMessage.addInterceptor(Consumer<String>)` rather than dual-logging.

---

## Code & Testing Conventions

- **No synchronous chunk I/O** — always the platform adapter's async abstraction.
- **`MemoryTracker` lifecycle** — any allocator of a chunk ticket or `TeleportPipelineTask` must register with `MemoryTracker` and release on all exit paths (normal, exception, disconnect).
- **Bounded algorithms only** — no unbounded `while`-reroll loops. Use the Archimedean spiral 1D mapping; document complexity in an ADR if you add a new algorithm.
- **Require-by-contract API entry points** — public `rtp-api` methods throw `IllegalStateException` (not null / no-op) when called before core is loaded.
- **Tests must be traceable** — reference the REQ-* ID in the class name (e.g., `ReqRtpS005ChunkLoadingTest`) or a `@DisplayName` / Javadoc comment. Update [`TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) when adding a REQ-traceable test.

---

## Environment & Execution

- **Shell**: PowerShell on Windows. Use `.\gradlew`, chain with `;` (never `&&`).
- **Multi-module Gradle**:
  - One module build: `.\gradlew :<module>:build`
  - Targeted tests: `.\gradlew :<module>:test --tests "<pattern>"`
  - Filter output: append `2>&1 | Select-String "BUILD|PASSED|FAILED|ERROR"`
- **Preferred runner**: the `run_test` tool is faster than the Gradle CLI for pass/fail checks over a directory of tests.
- **Search**: use `search_project` with short keywords. Never `grep`/`find`. For file listings: `Get-ChildItem -Recurse <path> -Filter "*.java"`.
- **Runtime**: Java 21+ required.
- **Known-harmless warnings**, **Gradle daemon / JDK mismatch**, **`run_test` stdout suppression**, **`rtp test full` interpretation** — see [`LESSONS_LEARNED.md`](../docs/dev/LESSONS_LEARNED.md).
- **Database / shutdown-flush / command-pipeline pitfalls** — see [`LESSONS_LEARNED.md`](../docs/dev/LESSONS_LEARNED.md).

---

## Current Development Focus

Active frontier: **Fabric (`rtp-fabric`)**. Unstable — see [`MULTI_PLATFORM_PLAN.md`](../docs/dev/MULTI_PLATFORM_PLAN.md) for phase status and known blockers (S-005 violation in `FabricWorld.getChunkAt`; null stub in `FabricServerAccessor.getLocationGenerator`; unresolved Loom dependency).

Fabric is explicitly **out of scope** in [`REQUIREMENTS.md §0`](../docs/dev/REQUIREMENTS.md). Do not backport Fabric-specific patterns into `rtp-core` or `rtp-api`. Safe-to-modify modules: `rtp-core`, `rtp-api`, `rtp-spigot`, `rtp-paper`, `rtp-folia`, `addons/`. Brigadier bridge rationale: [ADR-014](../docs/adr/ADR-014-brigadier-bridge-via-commands-api.md). `rtp-api` abstractions were confirmed sufficient for Fabric (April 2026 gap analysis) — gaps are implementation gaps, not interface gaps.

---

## Requirement Documentation Rules

When authoring `REQUIREMENTS.md`, module `REQUIREMENTS.md`, or ADRs:

- **Separation of concerns** — requirements state *what*, not *how*. No class names, data structures, or implementation actions. Move those to `DESIGN.md` or an ADR.
- **Legal phrasing** — `shall` for obligations, `shall not` for prohibitions. Avoid descriptive present tense ("The system does…") and imperatives ("Implement…", "Never…"). `must` is acceptable; `shall` is preferred.
- **Absolute state** — no temporal framing ("Historically", "Currently", "Prior to", "is implemented"). Whether the codebase already fulfils a requirement is irrelevant to the requirement text.

Full style guide: [`docs/dev/RULES.md`](../docs/dev/RULES.md).

---

## Self-Updating Protocol

When you discover something durable, record it in the **correct** file:

| Discovery | Destination |
|-----------|-------------|
| Toolset / PowerShell / Gradle environment fix | this file (`Environment & Execution` section) |
| Dated engineering pitfall, reproduction note, non-obvious behavior | [`docs/dev/LESSONS_LEARNED.md`](../docs/dev/LESSONS_LEARNED.md) |
| Overloaded or ambiguous domain term | [`docs/dev/GLOSSARY.md`](../docs/dev/GLOSSARY.md) (Multipurpose Terms table) |
| Roadmap phase completion, unblocking, or plan rename | *Current Development Focus* above **and** [`MULTI_PLATFORM_PLAN.md`](../docs/dev/MULTI_PLATFORM_PLAN.md) |
| Renamed / moved class referenced by a REQ-* | [`docs/dev/TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) row |
| New REQ-traceable test | [`docs/dev/TRACEABILITY.md`](../docs/dev/TRACEABILITY.md) row |
| Architecturally significant decision | New ADR under [`docs/adr/`](../docs/adr/) (use `ADR-TEMPLATE.md`) |
| Incidental potential bug found while doing unrelated work | [`docs/dev/POTENTIAL_BUGS.md`](../docs/dev/POTENTIAL_BUGS.md) (see *Stay-On-Task Policy*) |

Do **not** add code-level optimizations, algorithm explanations, or per-feature narratives to this file — those belong in code comments, ADRs, or `CHANGELOG.md`.

