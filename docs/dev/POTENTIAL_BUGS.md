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














### 2026-05-11 — `MemoryShapeTest.TestShape` missing `radius2` default

- **Discovered-during:** Adding `MemoryShape.chunkToLocations` + `addBadChunk` (issue update: amplify `addBadLocation` via the chunk preimage).
- **Location:** `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/selection/region/selectors/memory/shapes/MemoryShapeTest.java:29-40` — `TestShape.createDefaultData()` enum-map.
- **Symptom / hypothesis:** `new TestShape()` throws `IllegalArgumentException: All values must be filled out on shape instantiation`. `GenericMemoryShapeParams.radius2` was added to the enum (and is required by `Shape.<init>:57`'s exhaustive validation), but `TestShape.createDefaultData()` was not updated — only `Circle.defaults` and `Square.defaults` were. Every test in `MemoryShapeTest` that constructs a `TestShape` now fails at the constructor.
- **Impact:** All 9 tests in `MemoryShapeTest` fail. Pre-existing (working tree state before `chunkToLocations` work); not introduced by this change. Build remains green elsewhere because `MemoryShapeTest` is the only consumer of `TestShape`.
- **Suggested next step:** Add `data.put(GenericMemoryShapeParams.radius2, 100L);` to `TestShape.createDefaultData()`. One-line fix; no other test class is affected.

<!-- Append new entries above this comment, newest first. Resolved entries are deleted, not archived. -->
