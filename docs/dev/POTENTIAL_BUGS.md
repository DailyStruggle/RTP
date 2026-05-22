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


### 2026-05-21 — `:rtp-plugin:test` full-suite run dies in Gradle test writer (`in-progress-results-generic.bin` NoSuchFileException)

- **Discovered during:** Slice D row D4 wiring follow-up (attempted fix for the original `NoClassDefFoundError` diagnosis below). Initial hypothesis (missing `testImplementation` dep) was **wrong** — corrected here.
- **Location:** Task `:rtp-plugin:test`. Failure surfaces as `java.nio.file.NoSuchFileException: ...\rtp-plugin\build\test-results\test\binary\in-progress-results-generic.bin`. When the writer crashes, every test class in the suite then reports `ClassNotFoundException: <FQN>` ("Could not execute test class ...") even though `:rtp-plugin:compileTestJava` is green and `.class` files exist on disk under `build/classes/java/test/`.
- **Symptom / hypothesis:** Two-part upstream/infra issue, not a project defect:
  1. PaperMC snapshot repo intermittently returns HTTP 409 for Loom-remapped fabric-api transitives (`repo.papermc.io/repository/maven-snapshots/remapped/net/fabricmc/fabric-api/...`) and `loom/mappings/layered+hash.2198/...`. When this happens mid-build, Gradle marks dependent tasks UP-TO-DATE on a partial classpath.
  2. Gradle 9.5.0 ships a known regression in the test-results binary writer (related family: gradle/gradle#36601, `results-generic.bin` permission bug introduced 9.3.0). On Windows the binary file is created then disappears before the result aggregator reads it, raising `NoSuchFileException` on `in-progress-results-generic.bin` and aborting the task.
- **Repro shape:**
  - Filtered runs (`.\gradlew :rtp-plugin:test --tests "*TestApiCompatCmdTest"`, `*Network*`, etc.) **pass consistently** on the same classpath.
  - Full-suite `.\gradlew :rtp-plugin:test` (or any combined `--tests` filter spanning roughly half the 36 test classes) **fails deterministically** with the writer error.
  - `:rtp-plugin:assemble` and every production `:<module>:build` is green.
- **Impact:** `:rtp-plugin:test` is red on every full multi-module `gradlew build`, masking real regressions in the rtp-plugin test surface. No production artifact is affected.
- **Suggested next step:** Try (in order) (a) `.\gradlew --refresh-dependencies :rtp-plugin:test` once PaperMC is healthy, (b) pin Gradle wrapper down to 9.2.1 in `gradle/wrapper/gradle-wrapper.properties` (last release before the `results-generic.bin` regression) and re-run, (c) if 9.2.1 is green, leave wrapper pinned until Gradle ships the fix tracked in gradle/gradle#36601 and the related Windows writer issue.
- **2026-05-21 attempt log:** Pinned wrapper to 9.2.1 with user approval. 9.2.1 downloads with many `malformed Jar URL` notes on its own distribution jars, and the Loom cache needed manual eviction (`%USERPROFILE%\.gradle\caches\fabric-loom\minecraftMaven`, kill stray `java.exe`, `--stop`) to clear a `FileSystemException` on `minecraft-merged-intermediary-...layered+hash.2198-v2.jar`. After that `:effects-api:jar` is green, but `:rtp-core:compileJava` still fails resolving `io.github.dailystruggle.effectsapi.common.EffectsGroupKeys` via `api project(':effects-api')` — suggests 9.2.1's project-dep resolution picks a different effects-api artifact (likely the empty root thin jar instead of the `:effects-api` `main` compile output) than 9.5.0 does. Reverted the pin to 9.5.0 to keep the rest of the project buildable. Cleanest next try is probably to leave 9.5.0 in place and instead chase the Gradle test-writer regression via the upstream fix or a `--no-daemon` / `org.gradle.workers.max=1` workaround, rather than downgrading the wrapper.


### 2026-05-21 — `network*` messages.yml keys lack `MessagesKeys` enum entries; Spanish parity test fails

- **Discovered during:** `CHECKLIST-metrics-to-maps.md` Stage 2 (path B: ChartSpecTokens-only landing) — full `gradlew build` failure surfaced on `:rtp-plugin:test` running `ReqRtpF013SpanishLocaleContentTest.spanishLocaleHasNoUnknownKeys`.
- **Location:** `rtp-plugin/src/main/resources/messages.yml` lines ~371-395 (the `# --- Cross-server network mode messages (L6 / rtp-proxy-ADR-014) ---` block) defines eight keys — `networkQueued`, `networkRouting`, `networkReserved`, `networkTransferring`, `networkFallback`, `networkFailed`, `networkRegionUnavailable`, `networkRegionAmbiguous` — none of which appear in `rtp-api/src/main/java/.../configuration/enums/MessagesKeys.java`.
- **Symptom / hypothesis:** `ReqRtpF013SpanishLocaleContentTest` reverse-resolves every key in `lang/es/messages.yml` back to a `MessagesKeys` enum entry via `messages.lang.yml`. Because the eight `network*` keys have no enum entry, the Spanish file (which also carries those keys via the locale TSV pipeline) fails the reverse-resolve. Pre-existing from the in-flight network-mode WIP; the same defect should also surface for any other locale that runs the same content guard, but only the Spanish suite is currently REQ-traceable per AGENTS.md.
- **Impact:** `:rtp-plugin:test` is red on every full multi-module build, masking unrelated regressions in the rtp-plugin module. The `network*` strings still function at runtime (they are read by string key, not by enum), so the user-visible network-mode message flow is unaffected.
- **Suggested next step:** Add the eight `network*` constants to `MessagesKeys.java` with Javadoc mirroring the comments in `messages.yml`. Re-run `:rtp-plugin:test`. Owns the cleanup: the network-mode subproject (`rtp-proxy-*`) since these are its message keys.


### 2026-05-20 — `&c` color code leaks into console on `invalid command` dispatch failure

- **Discovered during:** `/rtp test network all` grammar-fix landing (this session). The original symptom was the bare-token dispatch bug (`invalid command - all`), now fixed by promoting probe modes to real subcommands; but the operator-visible log line was double-emitted with a raw color code: `&c[P0] invalid command - all` followed by `[RTP] invalid command - all`.
- **Location:** the `msgInvalidCommand` / `msgBadParameter` path on the Bukkit base command (likely `rtp-bukkit/.../BukkitBaseRTPCmd` or the `SendMessage.log` console branch). Format string presumably contains `&c[P0]` and is logged through `RTP.log(Level.WARNING, ...)` without running it through the `SendMessage` color translator first.
- **Symptom / hypothesis:** The `&c` legacy color code is translated when sent to a player but logged verbatim when routed through `RTP.log` -> `Bukkit.getConsoleSender().sendMessage` (or similar). The double emission (one with `&c[P0]` prefix, one without) suggests two log paths fire for the same failure: one through `SendMessage.log` and one through a plain `RTP.log`.
- **Impact:** Cosmetic / log-noise. Operators see a raw `&c` in their console on every dispatch failure, which both looks broken and makes log scraping for `invalid command` brittle. The duplicate line also makes audit grep return double-counts.
- **Suggested next step:** Inspect `BukkitBaseRTPCmd.msgInvalidCommand` (and its `msgBadParameter` sibling, REQ-RTP-S-004 auditing surface): either run the message through `ChatColor.translateAlternateColorCodes('&', msg)` before `RTP.log`, or drop the `&c[P0]` prefix from the format and rely on the log handler to colorize. Then de-dupe the dual log path so only one line emits.

### 2026-05-19 — `YamlFileDatabaseTest.setValue_flatRow_isNestedUnderPrimaryKey` fails on full build

- **Discovered during:** Phase 2 reservation-token TTL reaper landing (`rtp-proxy-common` work, REQ-RTP-NET-011). Full `gradlew build` ran green for every module except `rtp-core:test`.
- **Location:** `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/database/YamlFileDatabaseTest.java:395` (`setValue_flatRow_isNestedUnderPrimaryKey`).
- **Symptom / hypothesis:** Test fails with `org.opentest4j.AssertionFailedError` at line 395. The test file is unmodified in the working tree; the failure was already present in the working-tree state at the start of this session and is unrelated to the reaper changes (which touched only `rtp-proxy/rtp-proxy-common`). Likely caused by an earlier, unrelated `rtp-core` flat-row / primary-key refactor whose corresponding test update did not land.
- **Impact:** `:rtp-core:test` is red on every full multi-module build, masking unrelated regressions. No production code path is affected by the test itself.
- **Suggested next step:** Re-run the test in isolation (`.\gradlew :rtp-core:test --tests "*YamlFileDatabaseTest.setValue_flatRow_isNestedUnderPrimaryKey"`), open the test source at line 395, and compare its expected nesting shape against the current `YamlFileDatabase.setValue` implementation. Most likely a one-line fixture / expected-value adjustment.


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
