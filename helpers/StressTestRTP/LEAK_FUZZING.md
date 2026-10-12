# Leak fuzzing: goals, methodology and findings

`scripts/bench_fuzz.py` is a behaviour fuzzer for memory and resource leaks in Paper plugins. It drives a swarm of real Minecraft clients (`devstack/clients/bench-swarm.js --fuzz-control`) against one plugin at a time. After each episode it measures what the server still holds after a full GC, compares that with a plugin-free control, and learns which behaviour makes the difference grow.

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
- **States:** each bot's chat reply or kick reason, normalised (digits to `#`, colours stripped). An episode joins the corpus when it reaches a new `(action, state)` pair or changes a count.

### Learning

- **Prediction:** a ridge fit per count against behaviour features (action counts, quits by time since the last `/rtp`, moves during a pending teleport, renamed rejoins, console commands). A feature is **flagged** when its interval excludes 0 and the control's does not.
- **Classes:** all histogram classes go through a cumulative screen against the control, with a Bonferroni-corrected z, so thousands of noise classes produce no flags.
- **Search:** a cross-entropy method over the generator's parameters, plus mutation of corpus episodes and novelty episodes. While nothing reproduces, the state is `searching` and novelty rises each stalled generation (up to `--novelty-max`), with restarts from fresh points. A reproduced candidate switches it to `exploiting`.

### Confirmation

1. **Surprise replay:** an episode that grows a count far beyond the control (`--surprise-z`) is replayed straight away. "Reproduced k of n" with k at the threshold makes it a **candidate**.
2. **Shrink:** `ddmin` over bots and actions finds the smallest program that still reproduces k of n (`--shrink`, `--shrink-probes`, `--shrink-n`).
3. **Ladder:** candidates climb `--rungs` (default `4x1,12x3,48x10`, bots x reps) by successive halving. At the top rung a target **FAILs** when the count grows by at least half the prediction and heap after GC grows beyond the control's. Heap dumps are taken and, with `--mat`, `heap_owner.py` names the owning plugin class.
4. **Verdict** (`verdict.json`): `FAIL`, `PASS` (nothing found within budget, with coverage), `INCONCLUSIVE` (a signal that did not confirm at scale), `CONTROL` for `direct`.

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

---

## Further work

1. Rerun `direct,rtp` for a formal 3.3.0 verdict now that byte-based scoring and attribution filtering are in place.
2. Confirmation by amplification (shrunk segment 1x, 2x, 4x, 8x), with dumps at 1x and 8x compared by dominator tree, in place of the bots x reps ladder.
3. Command discovery, GUI and chat actions, n-gram features and a bandit over action types; continuous mode until the heap guard trips.
4. Run all plugins with the same `--reps` setup for the front-page memory comparison ([`docs/publishing/FRONT_PAGE.md`](../../docs/publishing/FRONT_PAGE.md)). Report per plugin: verdict, players retained per episode, live MB and classes per repetition above the control, and starting heap, and state that PASS means "not found within budget".
