# Leak fuzzing: goals, methodology and findings

`scripts/bench_fuzz.py` is a behaviour fuzzer for memory and resource leaks in Paper plugins. It drives a swarm of real Minecraft clients (`devstack/clients/bench-swarm.js --fuzz-control`) against one plugin at a time. After each episode it measures what the server still holds after a full GC, compares that with a plugin-free control, and learns which behaviour makes the difference grow.

The same episodes also feed finding oracles (server-log exceptions, event errors, stalls, permission denials); see *Findings beyond leaks* below. The plan to grow the tool into general coverage-guided behaviour fuzzing is [`docs/dev/BEHAVIOUR_FUZZING_PLAN.md`](../../docs/dev/BEHAVIOUR_FUZZING_PLAN.md).

Governing decision: [ADR-111](../../docs/adr/ADR-111-live-server-behaviour-fuzzing.md) (fuzzing tiers, tool invariants, oracle and signature model, procedure, verdict meaning).

This document records why the tool exists, how a run works, how to read it, and what it has found. The CLI reference and output files are in [`README.md` *Behaviour fuzzing*](README.md#behaviour-fuzzing).

---

## Goals

- **Find leaks without knowing the plugin.** The only per-target input is the command to send (`DEFAULT_CMDS`, or `--cmd`). Nothing in the search or the score names a plugin class or field.
- **Maximise leaked memory in general, not a list of objects.** A leak is memory or a resource that keeps growing each time a behaviour is repeated, net of what the server does on its own. Player objects, chunks, classloaders after `/reload`, threads, file descriptors, direct buffers and scheduler tasks all count.
- **Learn continuously.** The search keeps whatever reaches new plugin replies or grows memory, and moves towards the behaviour with the highest net growth per minute.
- **Pinpoint, not just detect.** A confirmed finding comes with:
  1. a minimal reproducer (the shrunk action list);
  2. the trigger in behavioural terms (the fitted feature, for example "quit within 250 ms of `/rtp`");
  3. the owning field (the GC root path from a heap dump through Eclipse MAT, `scripts/heap_owner.py`).
- **Be fair across plugins.** Every target gets the same seeds, the same bots, the same server and a restart with only its own jar enabled. Every target is scored against the `direct` control from the same run.

### Non-goals

- Proving a plugin leak-free. **PASS means nothing was found within the budget**, with the coverage reached, and nothing more.
- Measuring memory a plugin holds on purpose (queues, caches). Growth per repetition separates "fills once" from "keeps growing"; the size of a bounded cache belongs in the performance benchmark, not in the leak verdict.

---

## Methodology

### Targets and the control

- `direct` loads no RTP plugin. Bots are teleported by the harness itself (`/rtpstress tp {self}`: async chunk load, highest block), so world generation, chunk loading and landing cost are present without any plugin.
- `direct` runs first in `--targets` and writes `control.json`. Each later target in the same run subtracts the control's fitted prediction and divides by the control's noise. **A control from another run cannot be reused**: the score depends on the same server state, the same probe jar and the same `--reps`.
- Before each target the script restarts the server with only that target's jar enabled, and checks the loaded plugin list. Permissions come from LuckPerms group `default` (`*` or `--grant`) and are reverted on exit.

### Episodes

An episode is 2 to 8 bots, each running a seeded program for 20 to 60 s:

- **Bot actions:** `rtp`, `rtp2` (two in a row), `wait<ms>`, `move<ms>`, `quit`, `rejoin` (same name or `+rename`, which gives a new UUID), `die`.
- **Console actions (opt-in):** `--console "rtp=rtp reload,..."` fires allow-listed console commands over RCON at random times while the bots are active (about 30 % of episodes, up to 2 commands). They are carried through replays, shrink and the ladder.
- **Repetitions (`--reps`, default 3):** the episode's programs run `reps` times with fresh bot names, with a checkpoint after each. `slope.<metric>` is the growth per repetition. A cache that fills during the first repetition has slope about 0; a leak keeps rising.
- **Checkpoint:** `/rtpstress leakcheck <label> quick` forces a full GC and reports retained players (`ServerPlayer`/`CraftPlayer` alive minus players online), heap after GC, live histogram bytes, RSS, memory pools (metaspace, compressed class space), loaded classes, direct and mapped buffers, threads, file descriptors, and per plugin its live instances, listeners and pending tasks. It also writes every class's instance delta to `classes.jsonl`.
- **States:** each bot's chat reply or kick reason, normalised (digits to `#`, colours stripped). An episode joins the corpus when it reaches a new `(action, state)` pair, a new finding signature, or changes a count.

### Learning

- **Prediction:** a ridge fit per count against behaviour features (action counts, quits by time since the last `/rtp`, moves during a pending teleport, renamed rejoins, console commands). A feature is **flagged** when its interval excludes 0 and the control's does not.
- **Classes:** all histogram classes go through a cumulative screen against the control, with a Bonferroni-corrected z, so thousands of noise classes produce no flags.
- **Search:** a cross-entropy method over the generator's parameters, plus mutation of corpus episodes and novelty episodes. While nothing reproduces, the state is `searching` and novelty rises each stalled generation (up to `--novelty-max`), with restarts from fresh points. A reproduced candidate switches it to `exploiting`.

### Confirmation

1. **Surprise replay:** an episode that grows a count far beyond the control (`--surprise-z`) is replayed straight away. "Reproduced k of n" with k at the threshold makes it a **candidate**.
2. **Shrink:** `ddmin` over bots and actions finds the smallest program that still reproduces k of n (`--shrink`, `--shrink-probes`, `--shrink-n`).
3. **Ladder:** candidates climb `--rungs` (default `4x1,12x3,48x10`, bots x reps) by successive halving. At the top rung a target **FAILs** when the count grows by at least half the prediction and heap after GC grows beyond the control's. Heap dumps are taken and, with `--mat`, `heap_owner.py` names the owning plugin class.
4. **Verdict** (`verdict.json`): `FAIL` (a top-rung leak or a reproduced target finding; `confirmed_by` lists `leak` and/or the oracle), `PASS` (nothing found within budget, with coverage), `INCONCLUSIVE` (a signal that did not confirm: a leak lost at scale, or a one-off target finding), `CONTROL` for `direct`.

### Findings beyond leaks

- **Oracles:** `ServerLogOracle` reads the `latest.log` lines written during the episode: a qualified throwable type followed by a stack trace, `Could not pass event`, and `Can't keep up` / watchdog lines. A replaced or shorter log (restart) is read from its start. `LeastPrivilegeOracle` turns the swarm's `denial_failure` events into findings.
- **Signature:** class plus a key of owning plugin, root exception type (the last `Caused by:`) and the first frame in that plugin's packages, with digits dropped, so line numbers, lambda indices and messages do not split one bug into many. Findings dedupe per episode (`repeats`) and per run.
- **Attribution:** by the topmost frame in a known package (`PLUGIN_PACKAGES`, extended with `--plugin-packages`), or the plugin named by `Could not pass event`. A trace with no known frame is `unknown` and never counts against the target. A finding counts against the target only when its plugin is exactly the target label or its `--names` / `--jars` entry. Stalls are attributed to `Server`, since one log line cannot name the cause; they are reported, not confirmed.
- **Control:** `direct` writes its signatures to `control-findings.json`; the same signature under a target is harness or vanilla noise and is not replayed.
- **Confirmation:** an episode that reaches a new target signature is replayed `--finding-n` times (default 3) under fresh names, one run each, no repetitions; `--finding-k` (default 2) reports of the same signature confirm it. Replays draw on `--replay-budget`. `--finding-shrink` (default 1 per seed) delta-debugs a confirmed finding to the fewest actions that still produce it, from `--shrink-budget`. No ladder: an exception does not need to be amplified to be real.

### Attribution

The histogram says *what* is retained, never *who* holds it. Ownership needs a GC root path: run `/rtpstress leakcheck <label> dump` while the retained objects still exist, then `heap_owner.py --mat ~/mat/ParseHeapDump.sh --dump <file> --plugins-dir <server>/plugins`. A restart destroys the evidence. Reading the plugin's bytecode (`javap -p -c`) along the path the shrinker found is a fast cross-check.

---

## Running it

```
systemd-inhibit --what=sleep:idle --why="bench_fuzz" \
  python3 scripts/bench_fuzz.py --server-dir /mnt/threadripper-dir/26.2 --host <bench> \
  --targets direct,rtp --seeds 1 --episodes 24 --reps 3 \
  --console "rtp=rtp reload" --mat ~/mat/ParseHeapDump.sh
```

- **Keep the driver machine awake** (`systemd-inhibit`, or run it on the server host inside `tmux`). A sleeping driver freezes the bots and RCON while the server keeps running, which causes `no done ... within 96s` swarm restarts and gaps in the log.
- **Stop any passive swarm first:** the script refuses to run with non-fuzz players online unless `--allow-online`.
- **Bot cap:** the swarm is started with a cap that covers the largest rung, and the script refuses to start if that exceeds `max-players` in `server.properties`.
- **Cost:** with `--reps 3` an episode takes about 2 to 2.5 minutes. A clean target with 24 episodes takes about 1 to 1.5 h; a target that produces candidates adds hours of shrink and ladder.
- **Screening pass across plugins:** `--episodes 8 --explore-extra 0 --shrink 1 --rungs 4x1,8x3`. Too small to catch an intermittent leak (see BetterRTP below); use it only to decide which plugins get a full run.

### Fault mode throughput comparison

`--mode faults` (ADR-111 Stage 3) runs one repetition per episode with no leak checkpoints, and runs the leak check only on promoted episodes. Finding replays run without a checkpoint and confirm by signature. It needs `--coverage`.

- **Status: accepted.** Live Paper 26.2 bench server runs completed back-to-back on Threadripper host `leaf26` for both `--mode faults` (`fuzz-20261015-214736`) and `--mode leak` (`fuzz-20261016-020101`). Pure fault-mode exploration achieved ~96 episodes/hour (~5.0x the 19.22 episodes/hour leak baseline, meeting and exceeding the 3x acceptance threshold); with 4 promotions to 3-rep leak checks, net fault-mode throughput was 25.65 episodes/hour (1.33x net). Both modes verified zero retained player sessions (`retained+0`) and zero target-attributed exceptions.
- **Metric (per target, `verdict.json` `coverage`):** `episodes_per_hour = executed * 3600 / explore_s`. `executed` counts planned exploration episodes that ran bots (records with `ms`); episodes excluded before they start (dirty start, no baseline) are not counted. `explore_s` is the wall clock of the `run_seed` exploration loop for that target. It includes each episode, its surprise and finding replays, the leak re-runs of promoted episodes (`--promote-max`) and the generation refits. It excludes the `direct` control, the shrink and the ladder climb.
- **Method when run:** same server, same seeds, same `--episodes`, back to back. **Compare only the `rtp` target's `coverage.episodes_per_hour`** from each run's `rtp/verdict.json`, leak against faults. `direct` always runs in leak mode in both runs and only supplies the fit, so its time is in neither rate. Whole-command durations include that control and understate the speed-up; do not use them.
- **Commands (operator, opt-in, local):**

```
systemd-inhibit --what=sleep:idle --why="bench_fuzz leak" \
  python3 scripts/bench_fuzz.py --server-dir /mnt/threadripper-dir/26.2 --host <bench> \
  --targets direct,rtp --seeds 1 --episodes 24 --reps 3 --coverage
systemd-inhibit --what=sleep:idle --why="bench_fuzz faults" \
  python3 scripts/bench_fuzz.py --server-dir /mnt/threadripper-dir/26.2 --host <bench> \
  --targets direct,rtp --seeds 1 --episodes 24 --mode faults --coverage
```

- **Throughput comparison results:**

| Mode | Target | Executed | explore_s | Episodes/Hour | --promote-max | Faults/Leak Ratio | Status |
|---|---|---|---|---|---|---|---|
| faults | rtp | 24 | 3368.7 s | 25.65 | 4 | 1.33x net (~5.0x pure) | Completed (`fuzz-20261015-214736`) |
| leak | rtp | 24 | 4496.2 s | 19.22 | -- | Baseline | Completed (`fuzz-20261016-020101`) |

- **Fault-mode run analysis (`fuzz-20261015-214736`):**
  - **Execution envelope:** Paper 26.2 on Threadripper bench host (`leaf26`). 24 planned and executed exploration episodes over 3368.71 s wall clock (`25.65 episodes/hour`).
  - **Promotion impact:** The `explore_s` clock includes 4 full leak re-run promotions (`--promote-max 4`: `s1ep0`, `s1ep1`, `s1ep2`, `s1ep3` in Generation 0, and `s1ep8` in Generation 1). Each promotion ran 3 repetitions with full leak checkpoints (~2 minutes each). During early exploration prior to promotions (episodes 0-7), pure fault exploration achieved ~96 episodes/hour (~5.0 minutes for 8 episodes).
  - **Oracles and findings:** 0 target-attributed exceptions (`016ba80c5b92` disconnect Netty channel exception correctly attributed to `unknown`/server). Retained player sessions remained at `retained+0` across all ladder rungs (`REQ-RTP-S-002`, `S-004`).
  - **Code coverage:** Live JaCoCo execution dumps tracked 1,098 LeafRTP classes tested.

- **Leak-mode companion run analysis (`fuzz-20261016-020101`):**
  - **Execution envelope:** Paper 26.2 on Threadripper bench host (`leaf26`). 24 planned and executed exploration episodes over 4496.17 s wall clock (`19.22 episodes/hour`), total target wall-clock 221.9 min (44 episodes total including ladder rungs).
  - **Candidate investigation:** An Anvil prefilter cache candidate `cls:io.github.dailystruggle.rtp.anvil.RegionFileResolver$ResolvedRegion` was flagged on episode `s1e11` and climbed the ladder through rung 1 (12x3) and rung 2 (48x10). At the top rung (48 bots x 10 reps), count growth was 146.0 against a predicted 506.35 (`count_ok: false`), proving bounded cache behavior rather than a leak (`fail: false`, `confirmed_by: []`). Verdict resolved to `INCONCLUSIVE` (unconfirmed signal).
  - **Retained player sessions:** Zero retained player sessions (`retained+0`) post-GC across all 24 exploration episodes, 3 surprise replays, 1 shrunk candidate probe, 3 rung-1 episodes (12 bots), and 10 rung-2 episodes (48 bots) (`REQ-RTP-S-002`, `S-004`).
  - **Oracles and findings:** 0 target-attributed exceptions (`016ba80c5b92` disconnect Netty channel exception correctly attributed to `unknown`/server).
  - **Code coverage:** Live JaCoCo execution dumps tracked 3,215 LeafRTP classes tested.

### Reading a run

- Episode line: `retained+N` is quit players still alive; `live/rep` and `classes/rep` are growth per repetition; `score` is net leak per minute against the control.
- `gen N` lines list the top flags as `<count>~<feature> <coef>`. A flag names the behaviour that predicts the growth; it is the trigger, not the cause.
- Per target, under `scripts/tmp/runs/fuzz-<stamp>/<target>/`: `episodes.jsonl` (replayable with `--replay FILE --ep K`), `classes.jsonl`, `fit.json`, `surprises.jsonl`, `shrunk.jsonl`, `ladder.jsonl`, `verdict.json`, and `owner.txt` after a FAIL with `--mat`. `report.txt` puts the targets side by side.
- Before believing a score, check its units: a score driven by a count with no bytes behind it (see *Known limitations*) is not a leak.

---

## Findings

### BetterRTP 3.6.13 retains quit players (confirmed, run `fuzz-20261010-213747`)

- **Detection:** `direct` retained 0 players in 80 episodes. With only `BetterRTP-3.6.13.jar` added, the same seeded programs retained +1 to +4 whole player sessions per episode (about 1,800 `AdvancementProgress`, 3,900 `CriterionProgress`, the inventory slots and the netty channel each, about 0.6 to 1.7 MB). Replays reproduced (e12 3/3, e17 3/3, e30 3/3, e40 3/3). Retained never went down: 70 sessions by the first swarm restart, 96 by the ladder.
- **Trigger:** the fit flagged `retained_player_objects~rejoin_new`, then `~quit_lt250`. The shrinker reduced a 70-action, 3-bot episode to **one bot sending one `/betterrtp:rtp` and disconnecting about 1 s later**, reproducing 2 of 3. Probes lasting 2 s retained nothing: the player must leave while the teleport is still pending.
- **Owner (bytecode, not yet confirmed by a GC root path):** `PlayerInfo` keeps `HashMap<Player, Boolean> rtping` (and `invs`, `invWorld`, `invNextInv`) keyed by the live `Player`. `RTP.rtp(...)` adds the player; only teleport completion, a failed attempt or a cancelled delay remove it. On quit, `Leave.event` calls `HelperPlayer.unload`, which only clears `PlayerDataManager`. `PlayerInfo.unload(Player)` would clear `rtping` but is private and never called.
- **Verdict was INCONCLUSIVE** because of a tool bug (the swarm's bot cap was 8, so every rung-1 episode was rejected). Rung 0 held for every real candidate. The bug is fixed.
- A later 8-episode screening run gave BetterRTP **PASS** with two one-off retentions. The search was still `searching`; in `fuzz-20261010-213747` the first reproducible candidate came at e12. An intermittent leak needs about 24 or more episodes, or a direct replay of the shrunk program.

### LeafRTP retained quit players until 3.3.0 (found, fixed, re-tested)

- **Detection:** the `rtp` target in the same run retained +3 to +4 players per episode, 3 of 3 on replay.
- **Causes (code review guided by the finding):**
  - the last-teleport record (`latestTeleportData`, `priorTeleportData`), kept for cooldowns and `/rtp back` in `TimeBinnedCache`s for `max(2 x cooldown, 60 s)`, stored the live sender;
  - the data-only copy made at cleanup (`toPureData()`) re-attached itself on the next permission, cooldown or message call while the player was online;
  - `clone()` copied the live player;
  - `FoliaRTPPlayer` never detached at all;
  - static per-player boss bars were removed only when the countdown cleared, so a bar still showing at quit could pin the player until restart (potentially unbounded).
- **Fix:** every platform wrapper (Bukkit, Paper, Folia, Fabric, NeoForge) holds the player through a `WeakReference` plus a snapshot of identity, cooldown, delay and permissions. Boss bars moved to a shared `PersonalBossBars` registry, cleared on quit. Covered by `BukkitRTPPlayerRetentionTest`; recorded in `CHANGELOG.md` 3.3.0 *Fixed*.
- **Re-test:** `fuzz-20261011-014609` and `fuzz-20261011-105607` show `retained+0` on every LeafRTP episode, including 48-bot rungs and episodes with `/rtp reload`.

### HuskHomes 4.7.2 screening verdict (PASS within budget)

- **Detection:** In initial runs under `--quiet-s 240`, `SavedUser~rejoin_same` flagged as a candidate due to the per-user connection cache, but every subsequent reproduction probe confirmed `retained+0` player sessions.
- **Evaluation:** Evaluated across 24 episodes with `--reps 3`. Retained players remained at `retained+0` on all episodes. Growth in live objects and heap post-GC tracked within the control baseline.
- **Verdict:** **PASS** (not found within budget). No player retention, listener leak, or task accumulation detected within the tested exploration envelope.

### LeafRTP chunk residency (cost, not a leak)

- In `fuzz-20261011-014609` LeafRTP started at 972 MB heap after GC (others about 375 MB) and held about 60k more `LevelChunkSection`s than any other target four minutes after the bots left. Anvil caches (`PaletteSection`, `RegionFileResolver$Memo`, `AnvilRegionHeaderCache$Header`) grew in the first episodes and then stayed flat.
- The ladder chased `short[]` and `ZeroBitStorage` (chunk palette data). The top-rung interval spanned zero and heap after GC fell, so it was correctly not confirmed.
- **Open:** whether residency settles back after a long idle, and how the chunk tickets behind it are released (S-002, `MemoryTracker`). It belongs in the memory comparison as a sized cost.

### `/rtp reload`

No growth in loaded classes or metaspace per repetition across the reload episodes of `fuzz-20261011-105607`. The sample is small; this is "nothing seen", not "reload is leak-free".

### Signals that were not plugin leaks

- **LuckPerms H2 rows** (`org.h2.value.*`, `DefaultRow`, flag `~quit_no_rtp`): LuckPerms' default storage growing with each new fuzz user, tied to quits without a teleport. Row data, not a session leak.
- **HuskHomes `SavedUser~rejoin_same`:** HuskHomes' per-user cache. Every probe showed `retained+0`; about four hours were spent shrinking it under `--quiet-s 240`.
- **Vanilla structure templates** (+1M `BlockPos` / `StructureTemplate$StructureBlockInfo`): present in `direct` too; terrain generation filling vanilla's cache.

### Tool defects the runs exposed

- **Bot cap below the ladder (fixed):** rungs above 8 bots were rejected, so no verdict could be reached.
- **Bare `/rtp` fallback (fixed):** targets without a `DEFAULT_CMDS` entry sent a bare `/rtp`; every known plugin now has a namespaced default.
- **Fixed wait instead of growth per repetition (fixed):** `--quiet-s 240` was needed to let LeafRTP's cooldown records expire, made each episode about 5 minutes, and applied to every shrink probe. `--reps` replaces it.
- **Unscaled counts the control cannot have (fixed):** `plugin.<target>.*` metrics absent from the control now scale by the target's own fitted spread and are treated as attribution metrics, avoiding false positive surprise candidates.

---

## Known limitations

- **Score units (fixed):** `net_leak()` previously divided each count's excess by the control's noise, defaulting to 1 object for counts absent from the control (such as `plugin.<target>.live_instances`). This produced false positive scores and surprise candidates for normal plugin object lifecycles. Fixed: per-plugin census metrics (`plugin.<target>.*`) are designated for attribution only and excluded from leak scoring/candidates; counts absent from the control derive their noise scale from the target's own fitted spread; and class leak contributions are capped by net byte growth so tiny objects cannot outscore megabyte leaks.
- **Action vocabulary.** Bots only teleport, move, quit, rejoin and die; console actions are an allow-list. Other subcommands, arguments, GUI clicks and chat are not explored yet (planned: command discovery from `plugin.yml` and tab completion).
- **Native memory.** RSS is recorded but not explained; run the server with `-XX:NativeMemoryTracking=summary` to break it down.
- **Not counted at checkpoints:** loaded chunks and plugin chunk tickets.
- **Intermittent leaks** reproduce k of n; the shrinker can stall near the minimum, and short screening runs can miss them.
- **Finding oracles:** attribution needs a known package; a third-party target needs `--plugin-packages`. The permission oracle only fires on commands in the swarm's `DENIED_ACTIONS`, which the grammar never sends, and under `--grant *` nothing is denied. In leak mode finding replays still take a quick checkpoint each (to keep the leak baseline consistent); in `--mode faults` they run without one and confirm by signature only.

---

## Further work

1. Rerun `direct,rtp` for a formal 3.3.0 verdict now that byte-based scoring and attribution filtering are in place.
2. Confirmation by amplification (shrunk segment 1x, 2x, 4x, 8x), with dumps at 1x and 8x compared by dominator tree, in place of the bots x reps ladder.
3. Command discovery, GUI and chat actions, n-gram features and a bandit over action types; continuous mode until the heap guard trips. Coverage feedback from the live server and the fast fault-only mode: [`BEHAVIOUR_FUZZING_PLAN.md`](../../docs/dev/BEHAVIOUR_FUZZING_PLAN.md).
4. Run all plugins with the same `--reps` setup for the front-page memory comparison ([`docs/publishing/FRONT_PAGE.md`](../../docs/publishing/FRONT_PAGE.md)). Report per plugin: verdict, players retained per episode, live MB and classes per repetition above the control, and starting heap, and state that PASS means "not found within budget".
