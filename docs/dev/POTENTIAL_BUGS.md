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

- **Discovered during:** <issue ref / short task description>
- **Location:** `<path/to/File.java>` line <N> (or symbol name)
- **Symptom / hypothesis:** <one or two sentences>
- **Impact:** <user-visible effect, best guess>
- **Suggested next step:** <minimal investigation or fix sketch>
```

## Open

### 2026-09-03 - spotless violations in uncommitted rtp-core files from concurrent track cause `./gradlew build` failure

- **Discovered during:** [Track C - Agent 3] LeafRTPGroupAddon ADR-078 refactor verification.
- **Location:** `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/selection/region/cache/HotBudgetAllocatorTest.java` and `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/cache/RingCacheStage.java`.
- **Symptom / hypothesis:** Running `./gradlew build` fails during `:rtp-core:spotlessCheck` due to formatting differences in `HotBudgetAllocatorTest.java`.
- **Impact:** Root multi-module `./gradlew build` fails on spotless check; module-level `./gradlew :addons:LeafRTPGroupAddon:build` succeeds.
- **Suggested next step:** Run `./gradlew.bat :rtp-core:spotlessApply` within the task responsible for `HotBudgetAllocatorTest`.

### 2026-09-02 - `GroupCacheWorker` second pulse often does not promote backlog to cold (addon test fails intermittently)

- **Discovered during:** the `releaseToNetworkKept` ticket-hygiene fix in `rtp-core` (unrelated module; the group addon references none of the changed symbols).
- **Location:** `addons/LeafRTPGroupAddon/src/main/java/io/github/dailystruggle/rtp/groupaddon/GroupCacheWorker.java` (`pulse` screening/promotion step); asserted by `GroupSubspaceCacheTest#testGroupCacheWorkerPulseBacklogToCold`.
- **Symptom / hypothesis:** Pulse 1 fills the backlog (`sizeBacklog > 0`), but after pulse 2 both `sizeCold` and `sizeHot` are often still 0, so backlog screening promotes nothing. Reproduced at roughly 50% across 4 isolated `--rerun-tasks` runs (2026-09-02), so the promotion depends on which bin the randomized candidate lands in rather than failing outright.
- **Impact:** Group placements would never warm their cold cache from the backlog, forcing every group teleport onto live allocation. Addon-only; core `/rtp` unaffected. Currently red in `./gradlew build`.
- **Suggested next step:** Step through `GroupCacheWorker.pulse` with the test's `DummyMemoryShape`; likely the screening branch rejects every backlog candidate (bin selection or validator contract) rather than a capacity issue, since caps are 2/5/10.



### 2026-05-23 - multi-config entry editor renders differently from built-in `default` (shape/vert stored as section vs FactoryValue)

- **Discovered during:** menu nested-config editing fix (this session, route A). Resolved the nested-row editability symptom (clickable dotted `OpenConfigKey` + dotted-param registration in `SubConfigCmd`) but left the visual/UX divergence between built-in `default` and runtime-added entries (e.g. `default1234`) untouched.
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/MultiConfigParser.java` `addParser`; `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionConfigLoader.java#load` (the shape/vert factory-merge pass); `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/menu/multiconfig/MultiConfigMenuBuilder.java#buildEntry`.
- **Symptom / hypothesis:** For built-in `default`, `parser.getData().get(RegionKeys.shape)` returns a `Shape` `FactoryValue<?>` instance (set by `RegionConfigLoader.load` during the normal startup load), which renders as one clickable `shape: <toString>` row. For a freshly menu-added `default1234`, the same getter returns the raw `RtpYamlSection` from the seeded YAML because `MultiConfigParser.addParser` does NOT run the shape/vert factory-merge pass that `RegionConfigLoader.load` does, so `buildConfigFile` flattens it into many `shape.radius` / `shape.centerX` rows. Two visibly different editor styles for the same conceptual entry; only the rendering differs, not the underlying parser ability.
- **Impact:** Cosmetic / UX inconsistency; admins see one editor for `default` and a different (flattened, longer, more granular) editor for any added entry. Both editors now allow value edits after this session's fix, but the difference is jarring and the flattened form lacks the shape/vert type-picker entry-point that `default` exposes.
- **Suggested next step:** On `MultiConfigParser.addParser` (or in the menu's ADD branch at `MenuRedeemSubcommand.dispatchMultiConfigMutate`), after the new child `ConfigParser` is created, run the same shape/vert factory-merge pass that `RegionConfigLoader.load` performs so the stored `shape`/`vert` value is a `FactoryValue<?>` from the start. Then both built-in and runtime-added entries render through the same single-clickable-row branch. Separately, consider routing top-level `shape`/`vert` clicks through the existing `CommandTreeMenuBuilder.buildShapeVertTypePicker` -> `buildShapeVertSubParamPage` chain (already implemented at lines 969 / 1057) instead of opening an anvil for the whole shape param; this would make sub-param edits (`radius`) addressable without staging the entire shape value.



<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
