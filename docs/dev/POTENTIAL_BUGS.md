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

### Template

```markdown
### YYYY-MM-DD — <short title>

- **Discovered during:** <issue ref / short task description>
- **Location:** `<path/to/File.java>` line <N> (or symbol name)
- **Symptom / hypothesis:** <one or two sentences>
- **Impact:** <user-visible effect, best guess>
- **Suggested next step:** <minimal investigation or fix sketch>
```

## Open

### 2026-05-16 — `economy-isolation` Vault debit running on caller thread (real isolation breach)

- **Discovered during:** Phase-M1 Paper devstack smoke for the B → C gate in `docs/dev/scratch/CHECKLIST-metrics-and-multiserver.md` (Paper 26.1.2, 23:01 transcript).
- **Location:** the Vault economy adapter path exercised by `[RTP test/economy-isolation]` — likely `rtp-bukkit/rtp-bukkit-common/.../economy/` (Vault wrapper); subcommand at `rtp-plugin/.../bukkit/commands/test/EconomyIsolationTestCmd` (or equivalent under `commands/test/`).
- **Symptom / hypothesis:** Live Paper run reports `debit ran on caller thread 'Craft Scheduler Thread - 2 - RTP'; Vault isolation breached (latency=49863us)`. Vault calls are expected to be dispatched via Global Region / Async Scheduler per Folia threading rules (mirrored on Paper for parity), but the debit is executing on the caller's async RTP scheduler thread instead of being hopped onto an isolated executor.
- **Impact:** Vault implementations that touch non-thread-safe state (most of them) can be corrupted by RTP-initiated debits/credits; on Folia this would throw `ThreadAccessException`. On Paper it is a latent data-race risk. Predates this session.
- **Suggested next step:** Audit the Vault wrapper for an explicit `RTP.scheduler.runAsync` / global-region hop around `economy.withdrawPlayer(...)`; if absent, add one and re-run `[RTP test/economy-isolation]`. Add a regression test that asserts the debit thread name differs from the caller's.

### 2026-05-16 — `disconnect-midflight` probe emits WARNING-level `[ENQUEUE_TRACE] ... DROPPED` for synthetic UUID

- **Discovered during:** Phase-M1 Paper devstack smoke for the B → C gate in `docs/dev/scratch/CHECKLIST-metrics-and-multiserver.md` (Paper 26.1.2, 23:01 transcript).
- **Location:** `AbstractServerAccessor.sendMessage` (Bukkit-family adapter) — the `[ENQUEUE_TRACE]` branch that fires when `Bukkit.getPlayer(uuid)` returns null. Probe origin: `[RTP test/disconnect-midflight]` subcommand under `rtp-plugin/.../bukkit/commands/test/`.
- **Symptom / hypothesis:** The disconnect-midflight test deliberately uses a synthetic probe UUID with no online player, so the drop is *expected*; but it is logged at WARNING and counted by the new `FullAudit` `>= Level.WARNING` filter, flipping the row to FAIL.
- **Impact:** False FAIL in `/rtp test full` on every server (Paper devstack shows it cleanly); also pollutes the operator's WARN-level log on every synthetic probe. No runtime defect.
- **Suggested next step:** Either (a) downgrade the `[ENQUEUE_TRACE] ... DROPPED` line to `Level.FINE` (it is a diagnostic, not a user-impacting warning), or (b) have the disconnect-midflight probe register its synthetic UUID with `SendMessage` so the drop is silenced for that one ID. (a) is the cleaner fix.














<!-- Append new entries above this comment, newest first. Resolved entries are deleted, not archived. -->
