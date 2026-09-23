# Potential Bugs Backlog

A queue of incidental discoveries — suspected bugs, latent races, missing validations, stale comments — that were spotted while working on an **unrelated** task and deliberately **not** fixed in-line, per the *Stay-On-Task Policy* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md).

This file is a backlog, not a tracker. Promote an entry to a real issue (or fold it into a future task's `Effective Issue`) when it is ready to be worked on. Once an entry is resolved, **delete it** — this file does not maintain a resolved-bug archive.

## What this file is — and is not

**Yes:** "I was doing X, I noticed Y looks broken, Y is *not* part of X, and I am walking away from Y. Recording it here so a future task can pick it up."

**No** — do not use this file for any of:

- Work you are doing or just finished as part of the current task. Use the `<UPDATE>` checklist, the `submit` summary, the commit message, and `CHANGELOG.md` for user-visible changes.
- A diary of your own fix attempts, build outputs, packaging chains, or per-session follow-ups. If you opened the entry and resolved it in the same session, **delete the entry** — it never belonged here. Do not annotate it with `**Resolved:**` / `**Follow-up:**` bullets.
- Durable engineering lore or repro recipes → [`LESSONS_LEARNED.md`](LESSONS_LEARNED.md).
- Roadmap items or deferred design → the relevant plan doc or an ADR.
- Session resumption state → your `<UPDATE>` checklist or `docs/dev/scratch/CHECKLIST-<slug>.md`.
- Test failures or CI noise from the current change → fix them or escalate; not here.

A correct entry describes **someone else's future problem** that the current task is choosing not to solve. If you catch yourself writing a multi-paragraph resolution log on an entry you authored this session, that is the misuse signature — remove the entry instead.

## How to add an entry

Append to the *Open* section below using the template. Keep entries short — one paragraph each. If a deeper analysis is warranted, link to a separate doc rather than inlining it here.

Entries in the *Open* section are ordered by **priority** (highest first): runtime crashes and safety/thread-safety hazards first, then correctness/maintainability and operator-facing config issues, then performance, and finally cosmetic / log-noise / test-noise findings. When adding a new entry, insert it at the position matching its severity rather than strictly by date.

### Template

```markdown
### YYYY-MM-DD — <short title>

- **Severity:** Critical | High | Medium | Low | Cosmetic
- **Status:** Open | Triaged | Under Investigation | In Progress | Blocked
- **Discovered during:** <issue ref / short task description>
- **Location:** `<path/to/File.java>` line <N> (or symbol name)
- **Symptom / hypothesis:** <one or two sentences>
- **Impact:** <user-visible effect, best guess>
- **Suggested next step:** <minimal investigation or fix sketch>
```

## Open

### 2026-09-23 — PlaceholderProvider scan_landPercentage uses flawed shape count subtraction

- **Severity:** Low
- **Status:** Open
- **Discovered during:** Display bug on paper 26.1.2 (scan status 0.00% land)
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tools/PlaceholderProvider.java` line 945
- **Symptom / hypothesis:** `scan_landPercentage` placeholder computes `((denom - bad) * 100.0) / denom` where `denom = ms.getEffectiveGoodCount() + bad`. Since `totalBiomeCount` is only recorded on biomes and bad runs include gap-bridging and twins, this ratio can diverge or clamp to 0.00% instead of reading `ScanTask.latestLandPercentage`.
- **Impact:** PlaceholderAPI `%rtp_scan_landPercentage%` may display 0.00% or inaccurate percentages during active scans.
- **Suggested next step:** Update `PlaceholderProvider` to query active `ScanTask` instances for `task.latestLandPercentage`.

### 2026-09-23 — ScanProgressBars replaces scan_landPercentage with progressFraction

- **Severity:** Cosmetic
- **Status:** Open
- **Discovered during:** Display bug on paper 26.1.2 (scan status 0.00% land)
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/tasks/tick/ScanProgressBars.java` line 75
- **Symptom / hypothesis:** `ScanProgressBars.update()` replaces `[scan_landPercentage]` in boss bar template with `String.format("%.1f", progressFraction * 100.0)`, displaying overall scan completion fraction instead of actual land percentage.
- **Impact:** The boss bar displays scan progress percentage where `[scan_landPercentage]` is placed.
- **Suggested next step:** Compute average `latestLandPercentage` across active `ScanTask` instances and substitute that into `[scan_landPercentage]`, or add a distinct `[scan_progress]` placeholder.

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
