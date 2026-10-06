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


### 2026-10-06 — Fabric/NeoForge timers stop when an empty server pauses, dropping the backend from the network

- **Severity:** High
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (devstack acceptance run)
- **Location:** `platforms/rtp-fabric/rtp-fabric-common/.../scheduling/FabricScheduler.java` (`tick`, driven by `FabricEventBridge` `END_SERVER_TICK`); `platforms/rtp-neoforge/rtp-neoforge-common/.../scheduling/NeoForgeScheduler.java` (driven by `ServerTickEvent.Post`); consumer `rtp-core/.../network/BackendStatePublisher.java`
- **Symptom / hypothesis:** Repeating and delayed tasks, including the async heartbeat timer, are only advanced by the server tick. Vanilla 1.21.2+ stops ticking after `pause-when-empty-seconds` (default 60) with no players. In devstack `backend-c` (Fabric 1.21.11) logged "Server empty for 60 seconds, pausing" right after heartbeat #59, published nothing afterwards, and its `rtp:net:backend:backend-c` key expired. `BackendStatePublisher` logs "published" when the row is handed to the transport, not when the write lands.
- **Impact:** In network mode an empty Fabric (and NeoForge 26.x) backend disappears from routing, so lobby players can never be sent to it. That is exactly when cross-server `/rtp` targets it. Cached-location refill, reapers and other timers also stop while paused. Workaround: `pause-when-empty-seconds=0` in `server.properties`.
- **Suggested next step:** Drive async repeating tasks from the scheduler's own executor clock (wall time) instead of tick counts, or keep the server awake while network mode is on; add a devstack assertion that a Fabric backend keeps its heartbeat key after 90 s empty.


### 2026-10-06 — Challenge duels: consent is never cross-checked, non-ops cannot accept, prompts go to the wrong player

- **Severity:** High
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `GateEvaluator.java` ~348-374 (command gate) with `challenge.yml`/`quickchallenge.yml` gates and `docs/admin/ACTIONS.md` ~127; `ActionManager.processWaitQueue` ~486-516; `ActionCommand.java:37` + `ServerAccessorCommandParameters.playerParameter` (`rtp.other`); `ActionManager.executeEnqueueAction` MESSAGE branch ~779-815
- **Symptom / hypothesis:**
  - Each `execute if entity @a[name=[sender_name_N],tag=rtp_chal_[target_name_N]]` line only checks that player N carries their own tag, which their own `onEnqueue` added. Nothing checks `target_1 == sender_2`, and a missing target skips the line. Alice `/challenge Bob` and Carol `/challenge Dave` are paired with each other; a targeted challenger is paired with any open `/challenge`.
  - The shared `player` parameter is validated by the framework against `rtp.other` (`default: op`) before `ActionCommand` reaches its own `rtp.command.challenge.target` check, so non-ops get "bad parameter" when they name a player or click `[CLICK TO ACCEPT]`.
  - Enqueue MESSAGE routing guesses the recipient from the text: any payload containing `[target` or the English phrase "challenged you" goes to the target. The challenger's own "Challenge sent to X" line goes to X, the two `onCancel` lines go to the opposite players, and a translated invite falls through to the sender.
- **Impact:** The headline duel flow forces players into duels with people they did not choose, only works for ops out of the box, and shows confusing or misdirected prompts.
- **Suggested next step:** Implement reciprocity in the matcher (pair entry A with B only if `A.target == B.sender` and `B.target == A.sender`; never pair a targeted entry with an open one); give action commands a per-action player parameter gated on the declared `ParameterSpec.permission()`; route messages with an explicit recipient key (`MESSAGE_TARGET` / `to: target`) instead of text matching.


### 2026-10-06 — Group action placement reports failed teleports as success, teleports twice, and matches offline players

- **Severity:** High
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `GroupPlacementDispatcher.java` ~560-640 (`dispatchTeleports`); `ActionManager.executeLivePlacement` ~1193-1271 and the cached path ~1069-1082; `ActionManager.processWaitQueue` prune ~448-457 (`ActionWaitQueueEntry.enqueuedAt` unused); no quit hook into `ActionManager`
- **Symptom / hypothesis:**
  - `dispatchTeleports` returns `GroupPlacementResult.success(placements)` after `allOf`, ignoring each `setLocation` result and keeping offline participants in `placements`. `executeLivePlacement` then teleports every participant a second time, ignores that result too, and starts the session with all participants (S-004).
  - Wait-queue entries are pruned only when a participant joined another session, so a player who logged off stays queued until restart. A later player is merged with them and starts a confined "duel" alone.
  - Nothing ends a running session when a participant quits; the remaining player stays confined (border, pull-back, damage) until the timer expires, and a rejoining quitter is still mapped and confined again.
- **Impact:** Duels and team matches start with missing opponents; players are damaged out of bounds from the first tick; every group placement fires two teleport events (double `/back` history, combat-tag and cooldown hits from other plugins).
- **Suggested next step:** Return per-player teleport outcomes from the dispatcher and fail or roll back the trigger when any participant was not placed; delete the second teleport loop in `executeLivePlacement`; prune offline entries and apply a TTL from `enqueuedAt`; add a quit/kick hook that calls `surrenderParticipant`/`cancelParticipant`.

### 2026-10-05 — Folia rubberband teleport can hang or report a failed snap as success

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (Folia); unchanged since 3.2.1
- **Location:** `platforms/rtp-folia/rtp-folia-common/.../entity/FoliaRTPPlayer.java` lines ~141-148
- **Symptom / hypothesis:** The second `teleportAsync` runs via `player.getScheduler().run(..., null)`. If the entity is retired (player quits), the task never runs and `completionFuture` never completes. The inner result is ignored (`complete(true)`), and an exceptional completion is not handled.
- **Impact:** S-004: the pipeline can wait forever on a quitting player, or log success for a failed snap.
- **Suggested next step:** Pass a retired callback that completes `false`; use `complete(Boolean.TRUE.equals(s))` plus `exceptionally(... complete(false))`; check the `run(...)` return for `null`.

### 2026-10-05 — Release workflows interpolate step outputs and inputs into shell scripts

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (CI/release)
- **Location:** `.github/workflows/release-bbb.yml` lines ~111, ~168 (`TAG="${{ steps.version.outputs.tag }}"`); `release.yml` lines ~177, ~233, ~329-333; `fuzzing.yml` line ~50 (`inputs.duration`); `gradle.yml` lines ~100-110 (`github.base_ref`)
- **Symptom / hypothesis:** The version comes from a loosely matched branch name (`^[vV]([0-9]+\.[0-9]+.*)$`) or a dispatch input and is template-substituted into later `run:` blocks, so `$(...)` or quotes in it execute.
- **Impact:** Runner code execution in jobs that hold publish and signing secrets. Practical exposure is limited: fork PRs get no secrets and a read-only token, and dispatch needs write access. Hardening rather than an open hole.
- **Suggested next step:** Validate the version against `^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)*$` and fail otherwise; pass outputs and inputs only via `env:` and expand as `"$VAR"`.

### 2026-10-05 — Release jobs can publish partial or unsigned releases

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (CI/release)
- **Location:** `.github/workflows/release-bbb.yml` lines ~107-120 (tag pushed before the build at ~137); `release.yml` lines ~123-129 (missing `.asc` only logged); `scripts/release/update_modrinth_description.py` ~78-81, `update_hangar_description.py` ~117-120 (missing token exits 0)
- **Symptom / hypothesis:** A failed Pro build leaves a public `v*` tag behind. A missing signing key still publishes the Lite jar to GitHub, Modrinth and Hangar. A missing marketplace token silently skips that channel.
- **Impact:** Unverifiable or inconsistent releases across channels; manual cleanup of orphan tags.
- **Suggested next step:** Push the tag only after build and upload succeed; fail when `${LITE_JAR}.asc` is missing (as `maven-central.yml` does); check required tokens at job start (`: "${HANGAR_TOKEN:?}"`).

### 2026-10-05 — effects-api uses `Bukkit.getScheduler()` on Folia and has unbounded counts

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (effects-api)
- **Location:** `api/effects-api/.../bukkit/BukkitListeners/FireworkSafetyListener.java` lines ~53-66; `BukkitHandles.java` line ~369 (glide landing timeout); `common/effects/ParticleEffect.java` lines ~29-46
- **Symptom / hypothesis:** The firework cleanup and the glide timeout schedule through `Bukkit.getScheduler()` with no `isFolia()` branch, unlike the other `BukkitHandles` paths, so they throw on Folia. The firework `NUMBER` and particle `NUMBER` are not clamped; one detonation spawns `NUMBER-1` fireworks in a tight loop.
- **Impact:** On Folia, firework safety entries leak and glide never times out. A config or permission token like `FIREWORK.BALL.5000` stalls the region thread. Both effects are off in the default config.
- **Suggested next step:** Route both through the global/entity scheduler on Folia; clamp firework count (1-8) and particle count (1-256) when parsing.

### 2026-10-05 — Visualization export accepts unbounded image dimensions

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (commands / visualization)
- **Location:** `rtp-core/.../commands/menu/MenuConcreteCommandLeaves.java` lines ~587-658 (`parsePixelDimension`, `parseDimensions`); `visualization/ComprehensiveRegionImageExporter.java` ~183-230
- **Symptom / hypothesis:** `size=`/`width=`/`height=` accept `m`/`g` suffixes (x1e6 / x1e9) with only a lower clamp of 16. Auto-size caps at 4096, but explicit sizes do not.
- **Impact:** A holder of `rtp.menu.admin` can OOM the server with `size=1g` or `width=100000` (the work is async, but shares the heap).
- **Suggested next step:** Clamp each side (e.g. 8192) and the total pixel count after unit expansion; reject over-cap with a configurable message.

### 2026-10-05 — Config import, permission migration and setup/prefab confirm block the main thread

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (commands)
- **Location:** `rtp-core/.../commands/config/ConfigImportCmd.java` lines ~180-270, ~324-371; `ConfigImportPermissionsCmd.java` ~91-139; `commands/setup/SetupConfirmCmd.java` ~88-124; `PrefabConfirmCmd` (`PrefabDiskIO.writeWithBackup` + reload)
- **Symptom / hypothesis:** These leaves run inside the sync `CommandsAPI` drain and do a directory walk/copy, N captured console commands (one per LuckPerms group plus every `permission set`), multi-file YAML writes with backups, and a full reload inline.
- **Impact:** Operator-triggered multi-second tick stalls, with watchdog risk on large permission sets.
- **Suggested next step:** Run the disk and migration work via `RTP.scheduler.runTaskAsynchronously`, then hop back to the main thread for the reload and messages.

### 2026-10-06 — Bukkit console capture sleeps on the main thread and records every plugin's log output

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (shared Bukkit adapter)
- **Location:** `platforms/rtp-bukkit/rtp-bukkit-common/.../server/AbstractServerAccessor.java` lines ~928-1045 (`executeCommandWithCapture`); callers `ConfigImportPermissionsCmd` ~91-124 and `ConfigImportCmd.executePermissionMigration`
- **Symptom / hypothesis:** After dispatch it polls with `Thread.sleep(50)` for up to 1 s for a first line, then debounces for up to 3 s, on the calling thread, which is the main thread for these commands. For the whole window it adds an unfiltered handler to the root JUL logger and an appender to the Log4j root logger, so any concurrent log line from any plugin or thread reaches the caller's `ArrayList` consumer.
- **Impact:** Permission migration stalls the tick for up to ~3 s per LuckPerms group (watchdog risk on large setups). Unrelated log lines can be parsed as group or node output and, with `apply=true`, turned into console `lp` commands; LuckPerms' async output races the non-thread-safe list. Operator-only.
- **Suggested next step:** Run capture off the main thread (or complete a future from the capturing sender with a scheduled timeout); filter log records to the provider's logger (e.g. `LuckPerms`) and serialize captures; collect into a concurrent queue; sanitize parsed group names to `[A-Za-z0-9_-]+` before templating.


### 2026-10-06 — Public teleport API: coordinate targets skip every gate; ACTION targets teleport

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (rtp-api surface)
- **Location:** `rtp-core/.../RTP.java` ~377-410 (`RtpTarget.Kind.COORDINATE` branch of the API teleport); `resolveApiRegion` default branch; `rtp-api/.../RtpTarget.java` `coordinate(...)` / `action(...)` (new in 3.3.0)
- **Symptom / hypothesis:** A COORDINATE target calls `player.setLocation` directly, before `apiTargetPermissionDenied` and without the safety/claim pipeline. If the named world is not loaded it falls back to `getRTPWorlds().get(0)` instead of failing. An ACTION target (a GUI navigation id) is not rejected and falls through to a default-region teleport.
- **Impact:** The only in-tree caller is `/rtp back` (gated on `rtp.back`), so players cannot exploit it, but any addon calling `RTPAPI.teleport` with a coordinate gets an unchecked teleport, and `/rtp back` after the origin world is unloaded lands at the same X/Y/Z in the first world (possibly inside terrain). It is new public API in a minor release, so the contract freezes on release.
- **Suggested next step:** Fail with `INVALID_TARGET` when the world is missing and for ACTION; apply `apiTargetPermissionDenied` to COORDINATE; document (or restrict) coordinate teleports as unchecked by design.

### 2026-10-06 — Region-file readers trust preceding lengths, file size and malformed palettes

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (anvil-api)
- **Location:** `api/anvil-api/.../LinearRegionReader.java` ~115-167; `AnvilRegionByteCache.java` ~180-236; `PaletteSection.java` ~52-69 and `BiomePaletteSection` (same pattern)
- **Symptom / hypothesis:** `.linear`: only the target chunk length is capped; the preceding lengths are summed into an `int` with no cap, so a crafted file overflows the offset (a different chunk is decoded as the target) or forces up to ~2 GB of ZSTD decompression on an I/O worker; `dataPayloadLength` is not range-checked. The byte cache allocates the whole file (up to 2 GiB) with no size cap, and an `Error` during the owner's read never completes the in-flight future, so later readers of that file block forever. A multi-entry palette with missing or truncated `data` returns `palette[0]` instead of UNKNOWN, despite the comments.
- **Impact:** Needs a corrupt or crafted region file in the world folder (for example a downloaded world). Worst cases: an I/O worker hangs or OOMs, or a column reads as the first palette entry, which can be a safe block over real lava (latent S-001 on corrupt data).
- **Suggested next step:** Cap each table length and accumulate in `long`; cap region file size before allocating; complete/remove the in-flight future in `finally`; return null (UNKNOWN) for malformed palette data and treat it as UNKNOWN in the prefilter.

### 2026-10-06 — Claim boundary providers disable on any error and under-cover large or irregular claims

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (claim addon boundary providers)
- **Location:** `addons/LeafRTPClaimAddon/.../TownyBoundaryProvider.java` ~149, `FactionsBoundaryProvider.java` ~152 and ~181-250, `GriefPreventionBoundaryProvider.java` ~57-64 and ~91, `AdaptiveClaimProber.java` ~18-49
- **Symptom / hypothesis:** Any `Throwable` sets `exists = false` for the session (the checker fail-open pattern fixed earlier). The prober samples only the four axes through the hit and stops at 512 blocks, so L-shaped or large claims are truncated. Towny keys town blocks as 16-block chunks and ignores a non-default town block size. Factions `getBoundaryAt` excludes only wilderness, so a SafeZone/WarZone hit enumerates every claim reflectively with no cap. GriefPrevention anchors a player to the first claim in the world. Providers call Towny/GP/Factions/Bukkit APIs from async completion threads (the same pattern as the existing checkers).
- **Impact:** Availability and placement quality, not S-003: per-location claim checkers still veto every final destination. Effects: claim hazards under-marked (more rerolls), claim anchors silently empty after one error, wrong-claim anchors, and long reflective scans on large system factions.
- **Suggested next step:** Disable only on linkage errors (reuse `ClaimCheckFailure`); flood-fill claimed chunks with a cap instead of axis probing; read Towny's town block size; skip or locally bound SafeZone/WarZone; prefer the claim containing the player.

### 2026-10-06 — Action session ending: lost delayed steps, double terminal handlers, per-block violations, stuck game modes

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `ActionSessionImpl.java` ~632-674 (`triggerExpire`/`triggerCancel`/`triggerDeath` with `finally { disarm(); }`), ~796-797 (delayed step guard), ~231-242 (move-event violation path), ~704-723 (`disarm`), ~919 (`ACTION: DISARM`); bundled `koth.yml` / `teams.yml` `onBoundaryViolation`
- **Symptom / hypothesis:**
  - Delayed steps (`delay:`) in `onExpire`/`onCancel`/`onDeath` are scheduled and then `disarm()` sets `active=false`, so they return without running.
  - `active` flips only inside `disarm()` after the steps run, so a terminal step that kills a player (synchronous `PlayerDeathEvent`) also runs `onDeath`; a Folia region-thread death can race tick expiry the same way.
  - The move path counts a violation for every block walked outside (the tick path fires only on the in-to-out edge), so `violations >= N` gates trip after N blocks and pull-backs stack.
  - `ACTION: DISARM` ends the whole session for everyone and runs no `onExpire`; `disarm()` resets the border and scoreboards but not game modes. In the bundled KOTH and teams definitions a second boundary violation therefore ends the match with every participant left in adventure mode holding the arena kit.
- **Impact:** Configured end-of-match rewards and returns silently never run; rewards can run twice; boundary rules punish far too early; players stay in adventure mode after a bundled match ends this way.
- **Suggested next step:** Add a one-shot `ending` CAS at the top of each terminal handler; let delayed terminal steps run after disarm (check participants are online instead); count violations only on the in-to-out transition with a pull-back-in-flight flag; give DISARM an optional per-violator form (`ELIMINATE`) or run a `onDisarm` list, and restore game mode in the bundled definitions.

### 2026-10-06 — Action config parsing silently misreads documented syntax

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `ActionConfigLoader.java` ~528-533 (boundary), ~735-747 and ~790-792 (step `gate`/`run`); `GateExpressionParser.java` ~23-24, ~80-98 (durations); `GateEvaluator.java` ~147-181 (scoreboard gate)
- **Symptom / hypothesis:** `boundary: NONE` (documented in `docs/admin/ACTIONS.md` ~105) and any typo fall back to `SUBSPACE` without a log line. Action durations accept only one number and one unit: `1m30s` becomes the 300 s default, `shrinkOver: 1m30s` becomes 0, `500ms` is read as 500 seconds, `1w` as 1 second; the global duration syntax in `CORE_CONFIG.md` supports all of these. A step `gate:` is honoured only together with `run:`, so an inline gated step runs unconditionally; a map-valued `run:` throws and drops the action. Scoreboard gates read only `rtp_*` objectives and score everything else as 0.
- **Impact:** Owners following the docs get confinement they disabled, wrong match lengths, and gates that always pass or always fail, with nothing logged.
- **Suggested next step:** Support `NONE` and warn on unknown values; parse durations with the shared `ConfigParser.parseDurationSeconds`; accept `gate` with inline actions (or reject with a warning); read real objectives through the server accessor or fail closed with a warning.

### 2026-10-06 — Action start runs on an unpinned thread; cached slots ignore `centerRadius`

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `ActionManager.java` ~1036-1126 and ~1180-1271 (`arm()` / `triggerStart()` inside `thenApply`); `ActionCacheWarmTask.java` ~114-121 (`GroupProfileSpec.of` without `centerRadius`), `resolveSlotCount` default 2
- **Symptom / hypothesis:** Session start (world border, `onStart` console commands) runs on whichever thread completed the last validation or verifier future; Bukkit's async catcher rejects the console commands (swallowed to a WARNING) and on Folia the live path touches entities from another region's thread. The cache warm task builds profiles with `centerRadius = 0`, so cached KOTH/arena slots can sit on the exclusion hill, and a 4-player action drains and releases the 2-slot cache on every trigger.
- **Impact:** `onStart` kits and game-mode changes silently do not run on some triggers; cached placements break the centre-clearance rule.
- **Suggested next step:** Hop to `RTP.scheduler` (global thread, then per-player scheduler) before `arm()`/`triggerStart()`; pass `centerRadius` and derive the slot count from the `players` gate.

### 2026-10-06 — GUI main menu has no pagination; modded failed opens teleport the player

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** `addons/LeafRTPGuiAddon/rtp-gui-common/.../MenuLayout.java` ~73-120 and `MenuModel.build`; `rtp-gui-fabric/.../FabricMenuRenderer.java` ~176-215 and `rtp-gui-neoforge/.../NeoForgeMenuRenderer.java` ~153-201 (`fallbackTeleport`); Fabric/NeoForge `DestinationPickerMenu` ~133-139 (`stripTitle` / name formatting)
- **Symptom / hypothesis:** The main menu has at most 4 inner rows of 7. With 22+ destinations the submenu row (biome selector, special teleports, operator hub) is skipped, and past 28 extra destinations are dropped, with no log and no next page. On Fabric/NeoForge any failed `open()` (also used for page turns, Back and submenu clicks) runs `RTPAPI.teleport(defaultRegion)` and discards the result. Modded renderers strip only legacy `&x` codes, so hex and MiniMessage names that render on Paper show as raw markup.
- **Impact:** Large networks lose the operator hub and biome page silently; a modded player who clicks a page button can be random-teleported, with no message if it fails; region names look broken on Fabric/NeoForge.
- **Suggested next step:** Paginate `MenuModel.build` like the biome menu and reserve the submenu row; fall back to a teleport only for the root `/rtp` open and report the result; expand MiniMessage/hex before stripping (or build styled components).

### 2026-10-06 — Weekly Jazzer job fuzzes one Anvil target for 2 s and passes

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (Jazzer fuzz targets)
- **Location:** `api/anvil-api/src/test/.../AnvilRegionFuzzTest.java` (five `@FuzzTest` methods); `.github/workflows/fuzzing.yml` ~46-55; root `build.gradle` `tasks.withType(Test)` ~147-172
- **Symptom / hypothesis:**
  - Jazzer 0.30.0 fuzzes one `@FuzzTest` per run ("Only one fuzz test can be run at a time"). A local `JAZZER_FUZZ=1` run of the class fuzzed `fuzzNbtReaders` for about 3 s and reported the LZ4, Linear, column-probe and chunk-view targets as SKIPPED; the task still passed.
  - The workflow passes `-Djazzer.duration`, but the option is `jazzer.max_duration`, and Gradle `-D` sets a property on the Gradle JVM, not on the forked test JVM. Every target runs for the annotation's `maxDuration = "2s"`, not the advertised 5 minutes.
  - `JAZZER_FUZZ` is not a `Test` task input, so a cached `:anvil-api:test` result can be reused and no fuzzing happens at all; a second local run did exactly that.
  - No seed corpus or `<Class>Inputs/` regression files are committed, so the normal build runs each target on an empty input only.
- **Impact:** The fuzzing badge gives false assurance: the LZ4 decoder, Linear reader and region view builders (ADR-016's "fuzzable" rationale for the in-house decoder) have effectively never been fuzzed in CI.
- **Suggested next step:** One `@FuzzTest` per class (or one matrix entry per method with `--tests Class.method`); forward `jazzer.max_duration` with `systemProperty` and declare `JAZZER_FUZZ` as a task input (or `outputs.upToDateWhen { false }` in fuzz mode); seed corpora from the existing `.mca`/`.linear` test fixtures; commit any crash reproducers under `<Class>Inputs/`.

### 2026-10-05 — Claim/group anchors hardcode Y=64, starving the elevation filter

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (selection/placement)
- **Location:** `rtp-core/.../SubspaceAnchorResolver.java` lines ~98, ~157; `ClaimAnchoredRegionTracker.createClaimSubspace`; filter in `SubspaceShape.java` lines ~414-415
- **Symptom / hypothesis:** The anchor Y is always 64, and slots are rejected when `|slotY - anchorY| > elevationTolerance`.
- **Impact:** Group or near-claim placement on hills, mountains or in the Nether returns `INSUFFICIENT_SAFE_SLOTS` even when safe columns exist. This fails closed (availability only).
- **Suggested next step:** Take the anchor Y from the first validated slot or a resolved column, or skip the elevation gate for claim anchors.



### 2026-10-05 — Signed plugin-message heartbeats can be replayed indefinitely

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** web editor + proxy security hardening (heartbeat HMAC signing)
- **Location:** `rtp-core/.../network/pluginmessage/AbstractPluginMessageNetworkBinding.java` lines ~169-181 (`admit`); `PluginMessageEnvelope` verify path; Velocity `VelocityProxyCacheListener`
- **Symptom / hypothesis:** The HMAC covers the payload, but liveness is stamped with the receiver's clock (`new Entry(hb, now)`); no signed send time or sequence is checked. A captured valid heartbeat is accepted as fresh on every resend.
- **Impact:** Anyone holding one signed payload can keep a stopped backend, or stale load/region data, in the live set, so the dispatcher routes players to a dead or wrong server (teleport fails, S-004 surfaces it).
- **Suggested next step:** Add a signed `sentAtMs` to the envelope and reject when `|now - sentAtMs| > staleTimeout + skew`, plus a per-server monotonic check; bump `pmv`.

### 2026-10-05 — Backend Redis ignores `transport.redis.tls` and `username`

- **Severity:** Medium
- **Status:** Open
- **Discovered during:** web editor + proxy security hardening (admin docs for Redis TLS)
- **Location:** `rtp-core/.../network/NetworkModeBootstrap.java` lines ~1127-1145 (`case "redis"`) and the request-queue / waitlist Redis wiring; compare `NetworkConfig.parseRedis` (proxy)
- **Symptom / hypothesis:** The backend passes only `host`, `port` and the password to `RespPool`; TLS and the ACL user are honoured only when `host` is a `rediss://user@host` URL. The proxy also reads the `tls: true` / `username` keys.
- **Impact:** A backend configured like the proxy (`tls: true`) connects in plain text: the connection fails against a TLS-only Redis, or sends the password and signed rows unencrypted when Redis accepts both.
- **Suggested next step:** Build the backend endpoint with `RespEndpoint` from `host` + `tls` + `username` as `NetworkConfig.parseRedis` does; add a bootstrap test.


### 2026-10-06 — Unsolicited `GetServer`/`GetServers` replies set the backend's topology

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (shared Bukkit adapter)
- **Location:** `platforms/rtp-bukkit/rtp-bukkit-common/.../network/BukkitNetworkBridge.java` lines ~283-314 (`onPluginMessageReceived`); consumer `PeerRegionRegistry` line ~386 (`topologyPeers().contains(serverId)`)
- **Symptom / hypothesis:** Topology replies on `bungeecord:main` / `BungeeCord` are applied whenever they arrive, with no check that a request is outstanding and no signature. A server id present only in topology is accepted as a peer when no heartbeat exists.
- **Impact:** Low: BungeeCord and Velocity both drop client-sent BungeeCord-channel messages, so only a backend that players can reach without the proxy is exposed, and a forged peer only affects tab-complete and routing acceptance (transfers still need proxy-issued tokens).
- **Suggested next step:** Accept topology replies only while a request is pending (generation counter), cap the peer-set size, and keep heartbeat-backed peers authoritative.

### 2026-10-06 — YAML depth, modded-platform startup noise, API enum additions, devstack leftovers

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (yaml-api, API surface, devstack run)
- **Location:** `api/yaml-api/.../RtpYamlReader.java` (`parseMappingBody` / `parseChildBlock` / `FlowParser`), `RtpYamlWriter.emitScalar` PLAIN branch; `platforms/rtp-neoforge/.../utils/NeoForgeJarUtils.java` ~78; `addons/LeafRTPGuiAddon/rtp-gui-common/.../RTPGuiCommonAddon.java` ~188; `rtp-api` `RtpTarget.Kind`, `RTPResult.Reason`, `RTPAPI.checkPermission`; `devstack/run-acceptance.ps1` ~837-846 (`Test-Gui`)
- **Symptom / hypothesis:** The YAML reader has no nesting-depth or size cap (a deeply nested or `[[[[...` document from an import or a trusted editor apply throws `StackOverflowError`), and the writer emits PLAIN scalars verbatim (section setters already force DOUBLE). NeoForge cannot extract the bundled docs ("URI scheme is not file" from the union filesystem). On Fabric 1.21.x the GUI addon's 26.x-only renderer logs a `NoClassDefFoundError` stack trace although it is caught and skipped. New enum constants (`COORDINATE`, `ACTION`, `NO_PERMISSION`) break addons that switch over them without a default; every other 3.2.1 API change is additive (final classes with private constructors and factories, no new abstract methods). `RTPAPI.checkPermission` returns `false` before init instead of throwing (S-006 style). The GUI acceptance step copies the 26.x-only `LeafRTPGuiAddon.jar` into the 1.21.x `backend-c`/`backend-d` `mods/` folders, where Fabric Loader and FML abort the server; the stale copies were renamed `*.disabled-by-audit` on 2026-10-06.
- **Impact:** Thread death on a pathological config; no offline docs on NeoForge; alarming log line on Fabric 1.21.x; addon compatibility caveat for the release notes; devstack runs fail on the modded backends after any GUI run.
- **Suggested next step:** Thread a depth counter (max ~128) and a byte cap through the YAML reader; read docs through `Files`/`FileSystem` instead of `new File(uri)`; log the skipped renderer at FINE without a stack trace; mention the enum additions in the CHANGELOG; only stage the GUI jar into `mods/` for 26.x backends.

### 2026-10-06 — Action and GUI polish: bundled definitions, hardcoded text, dead settings

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (action and GUI addons)
- **Location:** bundled `addons/LeafRTPActionAddon/.../actions/*.yml`; `ActionCommand.java` ~202/280/285, `ActionCancelCmd.java` ~101-158, `ActionSubCmd.java` ~59-77; `ActionManager.processWaitQueue` ~462-483; `rtp-gui-common/.../MenuIcons.java` ~116, `MenuModel.java` nav/operator labels, `GuiMenuConfig.rows()` ~107; Fabric/NeoForge `DestinationPickerMenu` filler ~96/130; `FabricMenuRenderer` ~172/184; `RTP.getTargetStatus` ~903-912 and `getAllowedTargets` ~681-723
- **Symptom / hypothesis:**
  - Bundled definitions: titles are `PLAYER:` commands, so non-op players never see them (`/title` needs op); `broadcast` is not a vanilla command; teams has no win condition, and the first death sets every participant back to survival.
  - Action command failures, cancel results and raw internal reasons ("Revalidation error: ...") are hardcoded English (S-007). After a reload, renamed aliases keep pointing at the old definition; `alias: cancel` replaces the built-in subcommand; `/rtp action cancel [session_id]` is documented but not accepted. A single queued entry is never re-evaluated alone, and one player can queue twice.
  - GUI: `textReady`/`textUnavailable` are never used (lore is hardcoded), status shows raw enum names, nav and operator labels are hardcoded, `menuRows` has no effect, a mistyped filler fills slots with compasses on Fabric/NeoForge, and Fabric logs two INFO lines per open. Buttons show READY for players locked out by `lockAfterUses` or already teleporting, and the biome page offers biomes the blacklist excludes.
- **Impact:** Cosmetic or confusing behaviour on the features advertised for 3.3.0; no safety or security impact.
- **Suggested next step:** Use `CONSOLE: title` and `say`/`tellraw` in the bundled files and add a team win check; move strings to `messages.yml`/`guimenu.yml` with locale parity; drop stale aliases and reserve built-in names; report lock-out in `getTargetStatus` and honour `biomeWhitelist` in `getAllowedTargets`.

### 2026-10-06 — Fuzz targets: unreachable paths, exception-only oracles, missing parsers

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (Jazzer fuzz targets)
- **Location:** `AnvilRegionFuzzTest.java`; `rtp-proxy-common/src/test/.../RespProtocolFuzzTest.java`
- **Symptom / hypothesis:**
  - The Linear target always reads chunk `(0, 0)`, so the preceding-length loop with the open uncapped `int` sum (region-file readers entry above) is unreachable. `readChunkView` is only constructed, never queried, so lazy palette decoding is not exercised.
  - Oracles only check which exception escapes. The open `palette[0]` fail-open returns normally and cannot be detected; no target asserts "malformed input never yields ACCEPT" through `AnvilPrefilter`.
  - `RespProtocolFuzzTest` tolerates `NumberFormatException` and the Anvil targets tolerate `IllegalArgumentException`, although the parsers convert or never throw these; a regression that leaks them would go unnoticed.
  - Parsers of untrusted or semi-trusted input with no target: `RtpYamlReader` (imported configs; would find the open depth issue at once), `EditorLoopbackJson`, `EditorSessionManager.parseJsonStringMap`, `PluginMessageEnvelope.open`, `ProxyDirectWire`, `BiomeBinCodec`/`WorldBiomeStore.load`, `TinyJsonReader`, `SafetyTokenParser`, and the Bukkit plugin-message length reader.
- **Impact:** Coverage gap only; no production defect.
- **Suggested next step:** Let the fuzzer pick the chunk coordinates (`FuzzedDataProvider`); add a prefilter target asserting the verdict is never ACCEPT on malformed data; narrow the tolerated exceptions; add targets for YAML, editor JSON and the network envelope first.

### 2026-10-05 — `RespPool.PooledConnection` does not delegate `executeCommandBytes`

- **Severity:** Low
- **Status:** Open
- **Discovered during:** web editor + proxy security hardening (Redis RESP limits)
- **Location:** `platforms/rtp-proxy/rtp-proxy-common/.../transport/redis/resp/RespPool.java` lines ~133-249 (`PooledConnection`); `RespConnection.executeCommandBytes` line ~131
- **Symptom / hypothesis:** Every other command method is overridden to call `delegate`, but `executeCommandBytes` is not, so a pooled call runs on the wrapper's own never-connected socket (NPE or "closed" `IOException`).
- **Impact:** None today (no callers); the first binary-safe Redis command routed through the pool would fail every time.
- **Suggested next step:** Add the delegating override; consider a reflection test asserting `PooledConnection` overrides every public `RespConnection` command method.

### 2026-10-05 — Editor apply parser is structure-blind and has no file-count cap

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (web editor)
- **Location:** `rtp-core/.../commands/editor/EditorSessionManager.java` lines ~1244-1267 (`indexOf("\"files\"")`), `parseJsonStringMap`; `ApplyCmd` offline drop read
- **Symptom / hypothesis:** The byte-store, session and offline apply paths locate `files` by substring rather than parsing the JSON root. They have no `MAX_FILES` or per-file budget (the channel path caps at 256), and the offline `editor/<token>.json` is read with no size cap. The embedded `sha256` is self-declared, so it guards only against corruption.
- **Impact:** An admin applying a hostile token can trigger thousands of YAML parses, `.bak` writes and a reload. Path checks still hold, so there's no write outside the allow-list.
- **Suggested next step:** Parse the root with `EditorLoopbackJson`, take `files` from the root object only, and apply the channel caps (256 files, 4 MiB) to every apply path.

### 2026-10-05 — `trusted-editors.json` is not owner-only and concurrent writers drop entries

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (web editor)
- **Location:** `rtp-core/.../commands/editor/channel/TrustedEditors.java` lines ~154-184; new instance per channel in `EditorChannelWiring.trusted()`
- **Symptom / hypothesis:** The trust file is written without `EditorKeys.restrictToOwner`, unlike the channel keys. Each open channel holds its own in-memory copy and rewrites the whole file, so the last writer wins.
- **Impact:** On shared hosts another local account could pre-trust a browser key (defense in depth; write access to the data folder is already strong). Two concurrent sessions can erase each other's trust, which brings back the prompt.
- **Suggested next step:** Apply `restrictToOwner` to the temp file and the target; use a process-wide instance, or reload and merge under a lock before each write.

### 2026-10-05 — Small lifecycle and bounds gaps from the v3.3.0 audit

- **Severity:** Low
- **Status:** Open
- **Discovered during:** v3.3.0 pre-release audit (bStats, commands, editor, CI)
- **Location:** `rtp-core/.../metrics/bstats/RtpBStatsCatalogue.java` ~159, ~480-494 and `RtpBStats.shutdown`; `commands/docs/DocsExportSubCmd.java` ~61-69; `commands/menu/MenuRedeemSubcommand.java` ~3074-3075; `commands/editor/BiomeBinCodec.java` ~145-155 / `WorldBiomeStore` palette; `visualization/ComprehensiveRegionImageExporter.java` ~115-117; `.github/workflows/fuzzing.yml` ~59 and `mutation-testing.yml` ~52; `devstack/docker-compose.yml` ports; `FabricServerAccessor` / `NeoForgeServerAccessor` `executeCommandWithCapture` (10 s timeout); `RTP.java` `NO_PERMISSION` results
- **Symptom / hypothesis:** The bStats cost sampler is never cancelled and its static handle blocks rescheduling after a reload. Docs export runs on `CompletableFuture.runAsync` rather than `RTP.scheduler`. `dispatchRun` treats a null permission probe as allow-all. The biome palette and `histogram` grow with the largest biome index. Export filenames embed the unsanitized `region.name`. Two `upload-artifact@v4` refs are unpinned. Devstack publishes an open Redis and an offline-mode proxy on all interfaces. On Fabric/NeoForge a capture that times out still runs later and appends to the caller's list after the caller has moved on. The API's `NO_PERMISSION` failure text (`Missing permission for <target>`) is hardcoded English and shown verbatim by the GUI addon (S-007-adjacent).
- **Impact:** Individually minor: reload leaks, a rule violation, latent fail-open paths, admin-only path oddities, and supply-chain/dev-host hygiene.
- **Suggested next step:** Cancel and null the sampler in shutdown; use `RTP.scheduler`; fail closed on a null probe; cap the palette; sanitize export names to `[A-Za-z0-9._-]`; pin the SHAs; bind devstack ports to `127.0.0.1`; drop late capture output once the caller has timed out; give `NO_PERMISSION` a configurable message key.

<!-- Append new entries above this comment, ordered by priority (highest severity first). Resolved entries are deleted, not archived. -->
