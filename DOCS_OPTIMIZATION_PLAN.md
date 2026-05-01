# Documentation Token-Efficiency Plan

> **Status:** Proposal — not to commit. Working document for reviewing scope before touching any file.
> **Generated:** 2026-04-21
> **Scope:** every `.md` file under repo root and `docs/`.
> **Goal:** reduce total documentation token footprint and improve retrieval (so routine agent prompts cost fewer tokens), while preserving normative content (REQ-*, S-00x, ADR numbers, TRACEABILITY rows, anchors referenced by other docs).

---

## 1. Baseline (measured 2026-04-21)

| Scope | Files | Bytes | Approx tokens (bytes ÷ 4) |
|---|---:|---:|---:|
| Repo root `.md` | 4 | 29,103 | ~7,276 |
| `docs/` (all) | 61 | 527,857 | ~131,964 |
| **Total** | **65** | **556,960** | **~139,240** |

Top 10 heaviest files (where the tokens actually live):

| Rank | File | Bytes | Lines |
|---:|---|---:|---:|
| 1 | `docs/adr/ADR-016-anvil-subsystem.md` | 49,410 | 751 |
| 2 | `docs/dev/ANVIL_PREFILTER_PLAN.md` | 32,152 | 262 |
| 3 | `docs/dev/RUNTIME_TEST_SUITE_PLAN.md` | 28,163 | 439 |
| 4 | `docs/dev/TRACEABILITY.md` | 28,090 | 137 |
| 5 | `docs/dev/ANVIL_BIOME_PLAN.md` | 21,151 | 350 |
| 6 | `docs/admin/COMMANDS.md` | 17,221 | 281 |
| 7 | `docs/dev/BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` | 17,244 | 283 |
| 8 | `docs/dev/SAFETY_TAGS_AND_STATES_PLAN.md` | 15,729 | 259 |
| 9 | `docs/adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md` | 15,619 | 242 |
| 10 | `docs/dev/ANVIL_SHARED_MODULE_PLAN.md` | 14,656 | 178 |

**Target:** ≥ 40% byte reduction on the combined corpus (≈ 220 KB saved, ≈ 55k tokens) without loss of normative content. Stretch: 50%.

---

## 1b. Progress Checklist (at-a-glance)

High-level progress mirror of §9 / §10. Update both places when a step lands.

- [ ] User questions answered (§10 Q1–Q8)
- [x] Step 1 — Deletion pass (§9.1)
- [x] Step 2 — Indexing pass (§9.2)
- [ ] Step 3 — Dedup pass (§9.3)
- [ ] Step 4 — ADR-016 split (§9.4)
- [ ] Step 5 — Tighten pass (§9.5)
- [ ] Step 6 — Verification pass (§9.6)
- [ ] Step 7 — Final summary (§9.7)

---

## 2. Guiding Principles

1. **Treat software state as instantaneous at release.** Collapse all "Historically / Previously / Currently / After 2026-04-xx" framing in ADRs and design docs into absolute statements. Delete phase-by-phase implementation narrative from ADRs once the phase has landed.
2. **One source of truth per fact.** If a fact appears in ≥ 2 files, pick a canonical home and link to it from the others. Primary canonical homes:
   - Requirements (what) → `docs/dev/REQUIREMENTS.md`
   - Design (how) → `docs/dev/DESIGN.md`
   - Decisions (why) → `docs/adr/ADR-NNN-*.md`
   - Glossary → `docs/dev/GLOSSARY.md`
   - Traceability (REQ ↔ class ↔ test) → `docs/dev/TRACEABILITY.md`
   - Lessons / pitfalls → `docs/dev/LESSONS_LEARNED.md`
3. **Plans are disposable.** A `*_PLAN.md` marked `Shipped` or `Superseded` is **deleted outright** (git retains history). A plan marked `Proposal` / `Draft / deferred` with no active owner is also deleted unless the user flags it as still-pursued. Only **Living** plans stay, trimmed to open work.
4. **Prose → tables and bullets** where it shortens without loss.
5. **Indexing over inlining.** `INDEX.md` gets richer (task → exact file + §) so agents can fetch a slice instead of the whole doc.
6. **Preserve anchors.** Any heading referenced from another doc or from `AGENTS.md` / code comments must keep its text.

---

## 3. Action Categories

Each file gets exactly one of:

- **KEEP** — already lean; no edits.
- **TIGHTEN** — rewrite prose for density; no structural change; no content loss.
- **MERGE → X** — fold content into file X; delete original; redirect from INDEX.
- **DELETE** — remove entirely. Git history retains the file; no `docs/dev/archive/` dir is created. Used for shipped/superseded/abandoned plans and other obsolete content.
- **SPLIT** — break into smaller addressable slices (only for outliers like ADR-016).

The per-directory tables in §4 each include a `Done` column. Tick `[x]` when the file's listed action has landed.

---

## 4. Per-Directory Plan

### 4.1 Repo root (`./*.md`)

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [x] | `README.md` | 9,950 | **TIGHTEN** | Public-facing. Trim duplicated feature-list prose, keep install/usage, link to `docs/FOR_*`. Target −30%. |
| [ ] | `CHANGELOG.md` | 7,888 | **KEEP** | Normative release history. Do not touch except to remove duplicated blurbs. |
| [ ] | `CONTRIBUTING.md` | 8,949 | **TIGHTEN** | Collapses heavily with `docs/dev/RULES.md` and `AGENTS.md`. Keep only root-level contributor onboarding; link out for style / S-00x. Target −40%. |
| [ ] | `SECURITY.md` | 2,316 | **KEEP** | Already minimal. |
| n/a | `DOCS_OPTIMIZATION_PLAN.md` | — | (this file) | Not committed. |

Expected savings: ~6 KB.

---

### 4.2 `docs/` (top-level trio)

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [ ] | `docs/FOR_ADDON_DEVELOPERS.md` | 2,134 | **KEEP** | Entry point; already lean. |
| [ ] | `docs/FOR_CONTRIBUTORS.md` | 2,592 | **KEEP** | Entry point. Verify no overlap with `CONTRIBUTING.md`; if overlap, keep here and shrink `CONTRIBUTING.md`. |
| [ ] | `docs/FOR_SERVER_ADMINS.md` | 2,378 | **KEEP** | Entry point to `docs/admin/`. |

These three are the recommended *task routers*; they should stay small by design.

---

### 4.3 `docs/admin/` (operator-facing)

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [ ] | `COMMANDS.md` | 17,221 | **TIGHTEN** | Convert command tables to a single table per command group; drop worked examples that repeat syntax. Target −35%. |
| [ ] | `CONFIGURATION.md` | 12,847 | **TIGHTEN** | Convert key descriptions into one compact table (key / type / default / meaning). Target −35%. |
| [x] | `FAILURE_MODES.md` | 8,903 | **MERGE → HAZARDS.md** | Overlap with HAZARDS; consolidate as one document with "Hazard / Symptom / Mitigation" table. |
| [ ] | `FAQ.md` | 9,759 | **TIGHTEN** | Q/A pairs are fine; kill narrative preamble, long rationales, move deep technical answers to `LESSONS_LEARNED.md` with link. Target −30%. |
| [x] | `HAZARDS.md` | 9,577 | **TIGHTEN** (absorbs FAILURE_MODES) | See above. |
| [ ] | `MIGRATION.md` | 4,415 | **KEEP** | Already compact. |
| [ ] | `QUICK_START.md` | 6,278 | **TIGHTEN** | Goal: a server admin can get running in ≤ 3 KB. Target −40%. |
| [ ] | `RUNBOOK.md` | 9,329 | **TIGHTEN** | Extract common pre-flight checklist into a table; kill prose duplication with HAZARDS. Target −30%. |

Expected savings: ~28 KB.

---

### 4.4 `docs/adr/` (decisions)

**Policy:** ADRs record a decision at a point in time but must read as **absolute statements about the current system**. Any "Phase 1 done 2026-04-18, Phase 2 planned" language is pruned once shipped. No ADR is rewritten to change its decision; ADRs only lose narrative fluff. To revise a decision, supersede with a new ADR.

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [ ] | `README.md` | 3,005 | **KEEP** | ADR index. |
| [ ] | `ADR-TEMPLATE.md` | 737 | **KEEP** | Template. |
| [ ] | `ADR-001` spiral 1D | 5,225 | **TIGHTEN** | Remove derivation prose; keep formula + decision. Target −30%. |
| [x] | `ADR-002` h2/sqlite | 3,311 | **TIGHTEN** | Remove comparison narrative, keep decision + trade-offs table. |
| [ ] | `ADR-003` bridge module | 3,505 | **TIGHTEN** | Same. |
| [ ] | `ADR-004` count-bound pipes | 4,174 | **TIGHTEN** | Same. |
| [x] | `ADR-005` paperlib removal | 3,550 | **TIGHTEN** | Same. |
| [ ] | `ADR-006` async queue pregen | 4,364 | **TIGHTEN** | Same. |
| [ ] | `ADR-007` per-user queues | 4,085 | **TIGHTEN** | Same. |
| [ ] | `ADR-008` memory tracker | 5,396 | **TIGHTEN** | Same. |
| [ ] | `ADR-009` configurable distributions | 4,589 | **TIGHTEN** | Same. |
| [ ] | `ADR-010` versioned adapters | 3,880 | **TIGHTEN** | Same. |
| [ ] | `ADR-011` rtp-api module | 3,572 | **TIGHTEN** | Same. |
| [ ] | `ADR-012` chunk reservation | 4,020 | **TIGHTEN** | Same. |
| [ ] | `ADR-013` addons external | 3,781 | **TIGHTEN** | Same. |
| [ ] | `ADR-014` brigadier bridge | 3,546 | **TIGHTEN** | Same. |
| [ ] | `ADR-015` stale-chunk guard | 7,392 | **TIGHTEN** | Keep regression-guard test reference; trim phase narrative. Target −30%. |
| [ ] | **`ADR-016` anvil subsystem** | **49,410** | **SPLIT + TIGHTEN** | By far the heaviest file. Split into: `ADR-016-anvil-subsystem.md` (decision, ≤ 6 KB) + move implementation details to `docs/architecture/06-anvil-prefilter.md` + move phased rollout story to archive. Fold §13 "follow-ups" that have landed into the absolute description. Target overall −70% (≈ 35 KB saved). |
| [ ] | `ADR-017` block tags | 15,619 | **TIGHTEN** | Collapse matrix examples. Target −40%. |
| [x] | `ADR-018` AGENTS.md structure | 4,520 | **TIGHTEN** | Target −30%. |
| [ ] | `ADR-019` claim plugin fold-in | 5,628 | **TIGHTEN** | Target −30%. |

Expected savings: ~55 KB (dominated by ADR-016 split).

---

### 4.5 `docs/architecture/` (diagrams / flow)

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [ ] | `01-teleport-execution-pipeline.md` | 1,811 | **KEEP** | |
| [ ] | `02-budgeted-cache-generator.md` | 1,338 | **KEEP** | |
| [ ] | `03-chunk-ticket-lifecycle.md` | 1,451 | **KEEP** | |
| [ ] | `04-active-gc-sweep.md` | 1,354 | **KEEP** | |
| [ ] | `05-scan-task-crawler.md` | 2,348 | **KEEP** | |
| [ ] | `06-anvil-prefilter.md` (new) | — | **CREATE** | Receives implementation detail split from ADR-016. |

Already lean. No changes except the new file.

---

### 4.6 `docs/dev/` (engineering)

This directory is where the bulk of savings come from. It contains 11 `*_PLAN.md` files, many already Shipped/Superseded.

#### Living core (stays, tightened)

| Done | File | Bytes | Action | Notes |
|:--:|---|---:|---|---|
| [x] | `INDEX.md` | 3,242 | **EXPAND (net small)** | Enrich task → file mapping with anchors so agents fetch slices. Grow maybe +1 KB but save elsewhere. |
| [x] | `ARCHITECTURE.md` | 3,950 | **KEEP** |  |
| [ ] | `CONCEPTS.md` | 9,243 | **TIGHTEN** | Merge overlap with GLOSSARY. Target −30%. |
| [ ] | `DESIGN.md` | 12,530 | **TIGHTEN** | Strip "current state / as of" framing. Target −25%. |
| [ ] | `GLOSSARY.md` | 14,121 | **TIGHTEN** | Consolidate Multipurpose Terms table; drop redundant definitions covered in CONCEPTS. Target −25%. |
| [ ] | `REQUIREMENTS.md` | 8,107 | **TIGHTEN** | Rewrite any non-`shall` phrasing per RULES.md §Legal phrasing. Target −15%. |
| [x] | `RULES.md` | 6,451 | **KEEP** | Normative style guide. |
| [x] | `TRACEABILITY.md` | 28,090 | **TIGHTEN** | Convert narrative cells to terse references (REQ → class#method → test). Target −45%. |
| [x] | `LESSONS_LEARNED.md` | 5,333 | **KEEP** | Dated log; append-only. |
| [ ] | `COVERAGE_PLAN.md` | 11,201 | **TIGHTEN** | Drop resolved milestones. Target −40%. |
| [ ] | `MULTI_PLATFORM_PLAN.md` | 7,703 | **TIGHTEN** | Keep only active-frontier status (Fabric). Target −40%. |
| [ ] | `ROADMAP.md` | 11,267 | **TIGHTEN** | Drop completed items. Target −40%. |
| [x] | `STAKEHOLDERS.md` | 3,982 | **KEEP** |  |
| [x] | `DOCUMENTATION_GUIDE.md` | 4,779 | **MERGE → RULES.md** | Overlaps heavily with RULES. |

#### Plan files (mostly archivable)

| Done | File | Bytes | Status in header | Action |
|:--:|---|---:|---|---|
| [x] | `ANVIL_PREFILTER_PLAN.md` | 32,152 | Living (ADR-016) | **TIGHTEN** — most phases landed. Compress to open work only, or **DELETE** if all phases shipped (ADR-016 + §13 follow-ups already capture the decision and the landed state). Confirm with user. Target −70% if kept. |
| [x] | `ANVIL_BIOME_PLAN.md` | 21,151 | **Superseded 2026-04-19** | **DELETE** — superseded; successor plan covers remaining work. |
| [x] | `ANVIL_SHARED_MODULE_PLAN.md` | 14,656 | **Shipped 2026-04-18** | **DELETE** — all 7 phases landed; ADR-016 is the durable record. |
| [x] | `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` | 17,244 | Draft (design-only, active pivot) | **DELETE** — removed per user request 2026-04-30. |
| [x] | `BSTATS_CUSTOM_CHARTS_PLAN.md` | 12,305 | Proposal, unassigned | **DELETE** unless user flags as active. |
| [x] | `EMPTY_LIST_CONFIG_PLAN.md` | 9,419 | Proposal, unassigned | **DELETE** unless user flags as active. |
| [x] | `RUNTIME_TEST_SUITE_PLAN.md` | 28,163 | Living → completed | **DELETE** — sufficiently complete, remainder abandoned per user 2026-04-30; `docs/admin/COMMANDS.md` covers shipped subcommands. |
| [x] | `SAFETY_TAGS_AND_STATES_PLAN.md` | 15,729 | Accepted / in progress | **DELETE** — complete and ready for cleanup per user 2026-04-30; ADR-017 is the durable record. |
| [x] | `YAML_SIMPLIFICATION_PLAN.md` | 9,995 | Draft / deferred | **DELETE** — deferred with no owner; resurrect from git if revived. |
| [x] | `REGEX_PARAMETER_PLAN.md` | 5,030 | Proposal, unassigned | **DELETE** — completed/abandoned per user 2026-04-30. |
| [x] | `RTP_TEST_FULL_RELEASE_PLAN.md` | 9,372 | Pre-release findings snapshot | **DELETE** — fixes landed; deleted per user 2026-04-30. |
| [x] | `QUEUETASK_PROBE_FIRST_PLAN.md` | 2,963 | Slices shipped | **DELETE** — slices landed; deleted per user 2026-04-30. |
| [x] | `BIOME_LOOKUP_PERF_PLAN.md` | ~32,000 | Draft / working memory; direction locked, PRs landed | **DELETE** — complete and functioning per user 2026-04-30. |
| [x] | `RTP_TEST_ISOLATION_PLAN.md` | ~7,000 | Approved D-005 2026-04-28; phases landed | **DELETE** — complete per user 2026-04-30. |
| [x] | `CACHE_PACING_PLAN.md` | ~7,500 | Stage A landed; Stages B/C not pursued | **DELETE** — removed per user 2026-04-30. |

Expected savings: ~110 KB (plan deletion is the single biggest lever). Candidates for immediate deletion (no user ambiguity): `ANVIL_BIOME_PLAN.md` (superseded), `ANVIL_SHARED_MODULE_PLAN.md` (shipped), `YAML_SIMPLIFICATION_PLAN.md` (deferred, no owner). That trio alone = **45.8 KB**.

---

## 5. Cross-File Deduplication Map

Known overlaps to resolve (canonical → duplicates to replace with links):

| Topic | Canonical | Duplicates currently repeating content |
|---|---|---|
| S-00x prohibitions | `docs/dev/REQUIREMENTS.md §3` | `AGENTS.md`, `CONTRIBUTING.md`, ADR-015, ADR-016, several admin docs |
| Folia threading rules | `docs/dev/DESIGN.md` (Threading) | `AGENTS.md`, `LESSONS_LEARNED.md`, ADR-004 |
| Gradle / PowerShell invocation | `AGENTS.md` (Environment & Execution) | `CONTRIBUTING.md`, `README.md`, multiple plans |
| ADR authoring style | `docs/dev/RULES.md` | `DOCUMENTATION_GUIDE.md` (merge target), ADR-TEMPLATE header comments |
| Glossary terms (Region, Tickets, Verdict, etc.) | `GLOSSARY.md` | `CONCEPTS.md`, ADR-016, plans |
| Command syntax | `docs/admin/COMMANDS.md` | `README.md`, FAQ |
| Traceability mapping | `TRACEABILITY.md` | Scattered "See test X" lines in ADRs |

For each row: keep the canonical text; in the duplicate, replace the inline copy with `See [canonical §anchor](…)`.

---

## 6. Indexing Improvements (retrieval-side wins)

Even without shrinking prose, agents spend fewer tokens per prompt if they can fetch a slice:

1. **`docs/dev/INDEX.md`** — extend the task-routing table with file **and anchor** (`DESIGN.md#folia-threading`). Current table lists files only.
2. **`AGENTS.md`** (project guidelines) — already a router. No changes.
3. Add a single **`docs/MAP.md`** (~1 KB) with a flat list: every normative doc, one line each, in the form `path — one-sentence purpose`. This becomes the cheapest first-fetch for an agent that doesn't know which file to open.
4. **Kill dead links** — after archival, run a link check and update INDEX / AGENTS.md references.

---

## 7. Projected Savings Summary

| Bucket | Current bytes | After | Δ |
|---|---:|---:|---:|
| Repo root | 29,103 | ~22,000 | −7 KB |
| `docs/` (top) | 7,104 | 7,104 (+ `MAP.md` ~1 KB) | +1 KB |
| `docs/admin/` | 78,329 | ~52,000 | −26 KB |
| `docs/adr/` | 138,949 | ~83,000 | −56 KB |
| `docs/architecture/` | 8,302 | ~11,000 (+ new split file) | +3 KB |
| `docs/dev/` core | 144,026 | ~96,000 | −48 KB |
| `docs/dev/` plans | 170,813 | ~35,000 (rest archived) | −136 KB |
| **Total active corpus** | **~557 KB** | **~306 KB** | **−251 KB (~45%)** |
| Approx tokens | ~139,240 | ~76,500 | **~−62,700 tokens** |

Archived files still exist in git, so nothing is lost; they just no longer count toward the agent's working set.

---

## 8. Verification

1. **Pre-check** — snapshot current byte sizes per file (this doc, §1 and §4).
2. **Tokenization** — run a quick tokenizer script against the before/after snapshots:
   ```powershell
   # scripts/measure-docs-tokens.ps1 (to be added, then discarded)
   # Uses tiktoken via a Python one-liner; outputs a CSV.
   ```
   Agreed heuristic if no tokenizer handy: `tokens ≈ bytes / 4` for English prose, `bytes / 3` for dense tables.
3. **Link integrity** — after archival and rename, run:
   ```powershell
   Get-ChildItem -Recurse -Filter *.md | Select-String -Pattern '\]\([^)]+\.md' | Out-File link-audit.txt
   ```
   and manually resolve any `docs/dev/*_PLAN.md` links that point to archived files.
2. **Content integrity** — preserve:
   - all REQ-* IDs (`Select-String -Pattern 'REQ-RTP-[A-Z]-\d+'` count should not decrease).
   - all S-00x IDs (`Select-String -Pattern '\bS-0\d\d\b'` count should not decrease).
   - all ADR-NNN numbers (same check).
   - all heading anchors referenced from `AGENTS.md` and other docs (`Select-String -Pattern '\]\([^)]*#[^)]+\)'` inventory before/after).
3. **Human heuristic** — user reads new `docs/MAP.md` and top-tier files (`INDEX.md`, `ARCHITECTURE.md`, `DESIGN.md`, `REQUIREMENTS.md`, `RULES.md`, `GLOSSARY.md`, ADR-016) and confirms readability.

---

## 9. Execution Order (if approved)

Do the cheapest-and-safest first, get measurements, then iterate. Tick each item as it lands.

- [ ] **1. Deletion pass** — remove Shipped / Superseded / deferred-with-no-owner plan files outright (git retains history). Zero prose rewriting. Expected ~90 KB saved.
  - [x] 1a. `ANVIL_BIOME_PLAN.md` (superseded, ~21 KB) — confirmed-safe.
  - [x] 1b. `ANVIL_SHARED_MODULE_PLAN.md` (shipped, ~15 KB) — confirmed-safe.
  - [x] 1c. `YAML_SIMPLIFICATION_PLAN.md` (deferred, no owner, ~10 KB) — confirmed-safe.
  - [x] 1d. `BSTATS_CUSTOM_CHARTS_PLAN.md` — pending user decision (§10 Q2).
  - [x] 1e. `EMPTY_LIST_CONFIG_PLAN.md` — pending user decision (§10 Q2).
  - [x] 1f. `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` — deleted 2026-04-30.
  - [x] 1g. `ANVIL_PREFILTER_PLAN.md` — pending user decision (§10 Q2).
- [x] **2. Indexing pass**
  - [x] 2a. Create `docs/MAP.md` (one-line per normative doc).
  - [x] 2b. Enrich `docs/dev/INDEX.md` task-routing table with `file#anchor` references.
- [ ] **3. Dedup pass** — resolve cross-file overlaps per §5; replace copies with links.
  - [ ] 3a. S-00x prohibitions → canonicalize to `REQUIREMENTS.md §3`.
  - [ ] 3b. Folia threading rules → canonicalize to `DESIGN.md`.
  - [ ] 3c. Gradle / PowerShell invocation → canonicalize to `AGENTS.md`.
  - [ ] 3d. ADR authoring style → canonicalize to `RULES.md`.
  - [ ] 3e. Glossary terms → canonicalize to `GLOSSARY.md`.
  - [ ] 3f. Command syntax → canonicalize to `docs/admin/COMMANDS.md`.
  - [ ] 3g. Traceability mapping → canonicalize to `TRACEABILITY.md`.
- [ ] **4. ADR-016 split** — extract implementation detail to `docs/architecture/06-anvil-prefilter.md`; ADR keeps decision only.
- [ ] **5. Tighten pass** (priority order from §1 top-10):
  - [ ] 5a. `ADR-016` (post-split residue).
  - [ ] 5b. `ANVIL_PREFILTER_PLAN.md` (if kept).
  - [x] 5c. `RUNTIME_TEST_SUITE_PLAN.md` — deleted 2026-04-30 (completed/abandoned).
  - [ ] 5d. `TRACEABILITY.md`.
  - [x] 5e. `SAFETY_TAGS_AND_STATES_PLAN.md` — deleted 2026-04-30.
  - [ ] 5f. `ADR-017`.
  - [ ] 5g. Remaining `docs/admin/` tighten + `FAILURE_MODES.md` → `HAZARDS.md` merge.
  - [ ] 5h. Remaining `docs/dev/` core tighten (`DESIGN.md`, `GLOSSARY.md`, `CONCEPTS.md`, `REQUIREMENTS.md`, `COVERAGE_PLAN.md`, `MULTI_PLATFORM_PLAN.md`, `ROADMAP.md`).
  - [ ] 5i. `DOCUMENTATION_GUIDE.md` → `RULES.md` merge.
  - [ ] 5j. Remaining ADR-001…ADR-015, ADR-018, ADR-019 tighten.
  - [ ] 5k. Repo root tighten (`README.md`, `CONTRIBUTING.md`).
- [ ] **6. Verification pass**
  - [ ] 6a. Re-measure bytes/tokens; record before/after.
  - [ ] 6b. Link-check (`Get-ChildItem -Recurse -Filter *.md | Select-String '\]\([^)]+\.md'`).
  - [ ] 6c. REQ-* / S-00x / ADR-NNN ID count not decreased.
  - [ ] 6d. Anchor inventory unchanged for cross-doc references.
- [ ] **7. Final summary** — report before/after numbers and open follow-ups.

---

## 10. Open Questions for the User

Before execution, please confirm or adjust. Tick once user has answered.

- [ ] **Q1. Disposal method** — outright deletion (git retains history), no `docs/dev/archive/` dir. OK?
- [ ] **Q2. Plans in limbo** — for each, decide delete vs. keep (trimmed):
  - [x] Q2a. `BIOME_AND_BAD_LOCATION_VISITOR_PLAN.md` (Draft, active design pivot 2026-04-20c) — deleted 2026-04-30.
  - [ ] Q2b. `BSTATS_CUSTOM_CHARTS_PLAN.md` (Proposal, unassigned).
  - [ ] Q2c. `EMPTY_LIST_CONFIG_PLAN.md` (Proposal, unassigned).
  - [ ] Q2d. `ANVIL_PREFILTER_PLAN.md` (Living — most phases landed; ADR-016 is durable record).
  - [x] Q2e. `SAFETY_TAGS_AND_STATES_PLAN.md` — deleted 2026-04-30 (complete per user).
- [ ] **Q3. ADR-016 split** — move implementation detail to `docs/architecture/06-anvil-prefilter.md`?
- [ ] **Q4. `DOCUMENTATION_GUIDE.md` → `RULES.md`** merge acceptable?
- [ ] **Q5. `FAILURE_MODES.md` + `HAZARDS.md`** merge acceptable?
- [ ] **Q6. Savings target** — ≥ 40% acceptable, or push for 60%+?
- [ ] **Q7. `.bak` waiver** — no per-file backups for this pass.
- [ ] **Q8. Commit policy** — one commit per §9 step (individually revertible)?

Once these are answered, step 1 (deletion pass) banks ~90 KB with effectively zero risk.
