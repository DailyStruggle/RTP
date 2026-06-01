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

### 2026-05-24 - `BukkitRTPWorld.getBiomes(world)` / `FoliaRTPWorld.getBiomes(world)` return empty set when the platform setter is null-returning instead of falling back to the world enumeration

- **Discovered during:** /rtp biome=minecraft:BADLANDS namespace-parity fix (this session). User flagged the empty-set fallback explicitly.
- **Location:** `platforms/rtp-bukkit/rtp-bukkit-common/.../bukkitplatform/world/BukkitRTPWorld.java` `getBiomes(RTPWorld<?>)` (line ~117), and the mirrored `platforms/rtp-folia/rtp-folia-common/.../folia/world/FoliaRTPWorld.java` `getBiomes(RTPWorld<?>)` (line ~99). Both do `Set<String> pre = getBiomes.apply(world); return (pre == null) ? new HashSet<>() : new HashSet<>(pre);`.
- **Symptom / hypothesis:** If a custom platform adapter (or an addon that called `setBiomesGetter`) installs a getter that returns `null` for some worlds, the public static `getBiomes(world)` silently returns an empty set rather than falling back to the platform's full biome enumeration (`Biome.values()` / `Registry.BIOME`). Tab-completion, the menu's biome picker, and any caller using this surface to validate user input would then treat every biome as unknown for that world.
- **Impact:** Latent: today's default lambda never returns null, so the empty-set branch is unreachable in shipped configurations. The hazard surfaces only when a third-party setter (test fixture, addon, or future platform-specific override) returns null. The visible failure mode would be /rtp biome=<x> rejected for every x in the affected world, with no log line.
- **Suggested next step:** Replace the `new HashSet<>()` fallback with a call to the default platform enumeration (e.g. inline the `Biome.values()` / `Registry.BIOME` walk from the default lambda, or expose a `defaultBiomes(world)` helper next to `setBiomesGetter` and route the null branch through it). Add a regression test that installs a `setBiomesGetter(w -> null)` lambda and asserts `getBiomes(w)` is non-empty and contains a known vanilla biome.



### 2026-05-24 - cross-server /rtp completes for a disconnected player (CANCELLED -> RESERVED -> COMPLETED resurrection)

- **Discovered during:** verification of a `lobby-a` /rtp log trace (this session). The trace showed `terminal: ... newState=CANCELLED` fire on disconnect, then ~2s later `supplier: ... CANCELLED(pos=0) -> RESERVED(pos=0)`, then ~2s after that `terminal: ... newState=COMPLETED`. The user chose to defer the fix to bundle it with Phase 2 A2 (atomic-claim Lua); recording here so it is not lost.
- **Location:** `platforms/rtp-proxy/rtp-proxy-common/src/main/java/io/github/dailystruggle/rtp/proxy/common/dispatch/DefaultRtpDispatcher.java` `claimAfterSelect` / `sendAfterClaim` (the only pre-claim `sender.isConnected(...)` gate is at line 225, before snapshot read; nothing re-checks before the reservation claim or before the final `COMPLETED` emission). Lobby-side silent acceptance: `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/network/NetworkStatusCache.java#pollOnce` lines 253-263 (the supplier-transition log path only checks `s.nonTerminal()` on the *new* state, not on `prev`).
- **Symptom / hypothesis:** Two distinct defects. (a) The proxy dispatcher does not re-check `sender.isConnected(...)` between the early gate and the reservation-claim step, so a player who disconnects mid-dispatch still has a reservation token allocated and a `COMPLETED` emitted against them. The `PLAYER_DISCONNECTED` branch at `DefaultRtpDispatcher.java:429` exists but only fires on the transfer step, not on the claim step. (b) `NetworkStatusCache.pollOnce` logs any terminal -> non-terminal transition as if it were normal forward progress (`CANCELLED(pos=0) -> RESERVED(pos=0)`) instead of treating it as a protocol violation, swallowing the only signal a lobby has that something is wrong upstream. Terminal states (CANCELLED/COMPLETED/FAILED) are one-way per `QueueStatus.State` line 59 and `ReqRtpNet015NetworkWaitlistTest:87`.
- **Impact:** Per-disconnect: one reservation token wasted, one backend teleport pipeline run for a ghost player (queue depth + heap pressure + MemoryTracker churn on the chosen backend), no user-visible misbehavior. Under load (many players disconnecting mid-roundtrip during a server restart, client crash storm, or proxy stutter), this scales linearly. The lobby silent-acceptance half will mask the same shape of defect introduced by future transports (Redis, SQL `LISTEN/NOTIFY`).
- **Suggested next step:** Bundle with Phase 2 A2 (atomic-claim Lua). In `DefaultRtpDispatcher.claimAfterSelect`, add a `sender.isConnected(...)` re-check immediately before `transport.claim(...)` mirroring the gate at line 225; on negative, emit `CANCELLED(PLAYER_GONE)` and return `DispatchOutcome.Failed(PLAYER_GONE)` without touching the queue. Separately, in `NetworkStatusCache.pollOnce` add a warn-level branch when `prev != null && !prev.nonTerminal() && s.nonTerminal()` so terminal-state resurrections surface as a protocol-violation warning instead of a normal `supplier:` line. Regression test alongside `ReqRtpNet015NetworkWaitlistTest` asserting a CANCELLED row never re-enters `nonTerminal()` via the supplier path.



### 2026-05-23 - multi-config entry editor renders differently from built-in `default` (shape/vert stored as section vs FactoryValue)

- **Discovered during:** menu nested-config editing fix (this session, route A). Resolved the nested-row editability symptom (clickable dotted `OpenConfigKey` + dotted-param registration in `SubConfigCmd`) but left the visual/UX divergence between built-in `default` and runtime-added entries (e.g. `default1234`) untouched.
- **Location:** `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/MultiConfigParser.java` `addParser`; `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/RegionConfigLoader.java#load` (the shape/vert factory-merge pass); `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/menu/multiconfig/MultiConfigMenuBuilder.java#buildEntry`.
- **Symptom / hypothesis:** For built-in `default`, `parser.getData().get(RegionKeys.shape)` returns a `Shape` `FactoryValue<?>` instance (set by `RegionConfigLoader.load` during the normal startup load), which renders as one clickable `shape: <toString>` row. For a freshly menu-added `default1234`, the same getter returns the raw `RtpYamlSection` from the seeded YAML because `MultiConfigParser.addParser` does NOT run the shape/vert factory-merge pass that `RegionConfigLoader.load` does, so `buildConfigFile` flattens it into many `shape.radius` / `shape.centerX` rows. Two visibly different editor styles for the same conceptual entry; only the rendering differs, not the underlying parser ability.
- **Impact:** Cosmetic / UX inconsistency; admins see one editor for `default` and a different (flattened, longer, more granular) editor for any added entry. Both editors now allow value edits after this session's fix, but the difference is jarring and the flattened form lacks the shape/vert type-picker entry-point that `default` exposes.
- **Suggested next step:** On `MultiConfigParser.addParser` (or in the menu's ADD branch at `MenuRedeemSubcommand.dispatchMultiConfigMutate`), after the new child `ConfigParser` is created, run the same shape/vert factory-merge pass that `RegionConfigLoader.load` performs so the stored `shape`/`vert` value is a `FactoryValue<?>` from the start. Then both built-in and runtime-added entries render through the same single-clickable-row branch. Separately, consider routing top-level `shape`/`vert` clicks through the existing `CommandTreeMenuBuilder.buildShapeVertTypePicker` -> `buildShapeVertSubParamPage` chain (already implemented at lines 969 / 1057) instead of opening an anvil for the whole shape param; this would make sub-param edits (`radius`) addressable without staging the entire shape value.



### 2026-05-22 - locale TSV pipeline doubles backslash-heavy values on every round-trip

- **Discovered during:** CHECKLIST-multiconfig-menu step 14. The `menuInfoBadPointsLabel` value in `messages.yml` had grown to ~2 MB at HEAD - a single line of `\\\\\\\\...\u2691 bad-points map`. The TSV round-trip (`locale-files-to-csv` -> `locale-files-from-csv`) re-escaped every `\` to `\\` on write, doubling the value's size each pass. This session's pipeline run pushed the value from 2 MB to 16.7 MB and broke snakeyaml's 3 MB document cap. The runaway seed value itself was replaced with a clean `"#9D7CD8&l# bad-points map"` in baseline and every locale; the underlying escape-doubling bug in the TSV scripts remains.
- **Location:** `scripts/locale-files-to-csv.ps1` and `scripts/locale-files-from-csv.ps1` - their YAML-value escape rule for backslashes is not idempotent. Any string value containing a literal backslash will double in length on every full pipeline round-trip.
- **Symptom / hypothesis:** The from-csv writer is emitting `\` -> `\\` (correct YAML double-quoted-string escape) but the to-csv reader is treating the resulting `\\` as a literal `\\` (two chars) rather than collapsing it back to `\` on the next read. After N round-trips a value with K backslashes becomes K * 2^N chars long. The `menuInfoBadPointsLabel` value almost certainly started as a single escape sequence that grew unnoticed across ~22 prior pipeline runs.
- **Impact:** Any future baseline string containing a literal `\` (Windows paths in examples, regex patterns, etc.) will silently grow on every pipeline run. The 3 MB snakeyaml cap eventually hard-breaks `LocaleParityTest`. Current shipped values appear to be safe because no other key uses `\`.
- **Suggested next step:** Decide whether the canonical TSV value column should hold (a) the YAML-encoded form (with `\\`) or (b) the decoded form (with `\`); fix one of the two scripts so they round-trip identically. Add a regression test that runs the pipeline twice and asserts byte-equality of the locale tree between passes.




### 2026-05-20 - `&c` color code leaks into console on `invalid command` dispatch failure

- **Discovered during:** `/rtp test network all` grammar-fix landing (this session). The original symptom was the bare-token dispatch bug (`invalid command - all`), now fixed by promoting probe modes to real subcommands; but the operator-visible log line was double-emitted with a raw color code: `&c[P0] invalid command - all` followed by `[RTP] invalid command - all`.
- **Location:** the `msgInvalidCommand` / `msgBadParameter` path on the Bukkit base command (likely `platforms/rtp-bukkit/.../BukkitBaseRTPCmd` or the `SendMessage.log` console branch). Format string presumably contains `&c[P0]` and is logged through `RTP.log(Level.WARNING, ...)` without running it through the `SendMessage` color translator first.
- **Symptom / hypothesis:** The `&c` legacy color code is translated when sent to a player but logged verbatim when routed through `RTP.log` -> `Bukkit.getConsoleSender().sendMessage` (or similar). The double emission (one with `&c[P0]` prefix, one without) suggests two log paths fire for the same failure: one through `SendMessage.log` and one through a plain `RTP.log`.
- **Impact:** Cosmetic / log-noise. Operators see a raw `&c` in their console on every dispatch failure, which both looks broken and makes log scraping for `invalid command` brittle. The duplicate line also makes audit grep return double-counts.
- **Suggested next step:** Inspect `BukkitBaseRTPCmd.msgInvalidCommand` (and its `msgBadParameter` sibling, REQ-RTP-S-004 auditing surface): either run the message through `ChatColor.translateAlternateColorCodes('&', msg)` before `RTP.log`, or drop the `&c[P0]` prefix from the format and rely on the log handler to colorize. Then de-dupe the dual log path so only one line emits.



### 2026-05-16 — `economy-isolation` Vault debit running on caller thread (real isolation breach)

- **Discovered during:** Phase-M1 Paper devstack smoke for the B → C gate in `docs/dev/scratch/CHECKLIST-metrics-and-multiserver.md` (Paper 26.1.2, 23:01 transcript).
- **Location:** the Vault economy adapter path exercised by `[RTP test/economy-isolation]` — likely `platforms/rtp-bukkit/rtp-bukkit-common/.../economy/` (Vault wrapper); subcommand at `rtp-plugin/.../bukkit/commands/test/EconomyIsolationTestCmd` (or equivalent under `commands/test/`).
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
