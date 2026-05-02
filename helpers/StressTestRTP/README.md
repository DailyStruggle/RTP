# StressTestRTP

A standalone Spigot/Paper/Folia helper plugin that drives a measurable,
repeatable stress test against any `/rtp`-style command — RTP, RTP-Pro,
or any competitor — and writes ms-precision metrics aligned to the
front-page comparison table.

This plugin lives under [`helpers/`](..), **not** under `addons/`. It
does not depend on `rtp-api`, `rtp-core`, or any specific RTP plugin.
Treats the target plugin as a black box: dispatch a configurable
command, listen for `PlayerTeleportEvent`, record latency.

---

## Why this exists

The RTP front-page table compares RTP / RTP-Pro to BetterRTP, EzRTP,
AsyRTP, SorekillRTP, AdvancedRTP, JakesRTP, EssentialsX `/rtp`, and
HuskHomes RTP across five performance columns. (DonutRTP was reviewed
and excluded — it is a GUI front-end that dispatches BetterRTP commands,
not an independent RTP implementation; see `PRE_WRITEUP.md` §6.)

| Column                 | This plugin's measurement                              |
|------------------------|--------------------------------------------------------|
| Cold-start `/rtp`      | First successful attempt's `latency_ms` after start    |
| Warm-queue `/rtp`      | Median `latency_ms` across all successful attempts     |
| TPS under 10× burst    | Minimum sampled TPS during the run                     |
| MSPT during eval       | p95 sampled MSPT during the run                        |
| Memory footprint       | Peak heap-used MB sampled during the run               |

The same JAR drives every plugin in the table. Drop the JAR onto a test
server, change `target-command` in `config.yml`, run `/rtpstress start`,
and read the columns straight off the generated `summary.txt`.

---

## What it does

1. Registers `/rtpstress` (permission `stresstestrtp.admin`, default OP).
2. Samples TPS / MSPT / heap-used once per second on an async timer.
3. On `/rtpstress start [seconds] [concurrency]`:
   - Rolls a fresh CSV at `plugins/StressTestRTP/runs/<timestamp>.csv`.
   - Loops at ~10 Hz: while concurrency slots are open, picks a roster
     player, registers a `PlayerTeleportEvent` expectation, and
     dispatches the configured command from the console sender.
   - Each `PlayerTeleportEvent` (cause `COMMAND` / `PLUGIN` / `UNKNOWN`)
     for an expecting player closes its attempt with `success=true`,
     records distance, and appends a CSV row.
   - Attempts that don't see an event within `attempt-timeout-ms` are
     recorded as `TIMEOUT` failures.
4. On `/rtpstress export`: writes a sibling `<timestamp>-summary.txt`
   with the front-page columns prefilled.

---

## Quick start

```powershell
.\gradlew :helpers:StressTestRTP:build
# JAR: helpers/StressTestRTP/build/libs/StressTestRTP-1.0-SNAPSHOT.jar
```

Drop the JAR into the target server's `plugins/` folder alongside the
RTP-style plugin under test. Restart the server. Then in-game:

```
/rtpstress start 60 4         # 60 s run, concurrency 4 (round-robin across targets)
/rtpstress sequence 60 30 4   # run each target 60 s, 30 s gap, concurrency 4
/rtpstress status             # rolling p50/p95/p99/min-TPS/p95-MSPT/heap
/rtpstress burst 10           # one-shot 10× burst (TPS-burst column)
/rtpstress export             # write <timestamp>-summary.txt
/rtpstress reset-cold         # run cache-reset commands (config.yml)
/rtpstress stop               # end the current run early

# `sequence` is the recommended head-to-head methodology: each
# target-commands entry is exercised in isolation for `perTargetSeconds`,
# then the harness idles for `gapSeconds` so the server (and any pre-warmed
# queue) recovers before the next plugin runs. Defaults come from
# `sequence.per-target-seconds` / `sequence.gap-seconds` in config.yml.
```

To benchmark **multiple co-installed RTP-style plugins head-to-head in a
single run**, edit `plugins/StressTestRTP/config.yml` and list each as a
separate `target-commands` entry. Bukkit lets you address a specific
plugin's command by prefixing the plugin name (`rtp:rtp` vs.
`betterrtp:rtp`), so plugins that all register `/rtp` no longer collide:

```yaml
target-commands:
  - label: "rtp"
    command: "rtp:rtp player:{player}"
  - label: "betterrtp"
    command: "betterrtp:rtp tp {player}"
  - label: "essentialsx"
    command: "essentials:rtp {player}"
  - label: "asyrtp"
    command: "asyrtp:wild {player}"
```

Attempts are dispatched **round-robin** across the list, and the CSV's
`target_label` column lets you slice the results per plugin. The summary
(`/rtpstress export`) prints a per-target breakdown when more than one
label is observed.

> **RTP / RTP-Pro syntax:** RTP's command parser uses `key:value`
> arguments (see `docs/admin/COMMANDS.md`). Bare `/rtp <name>` is
> rejected as `invalid command`. Always use `rtp:rtp player:{player}` for
> the in-tree plugin; only competitors typically take a positional name.

> **Back-compat:** the legacy scalar `target-command: "..."` key is
> still honoured when `target-commands` is absent (single entry, label
> `default`).

`/rtpstress reload` is **not** provided on purpose — re-running the
test from scratch on a known-fresh server state is the only way to
guarantee comparable cold-start numbers between competitors.

---

## Roster

`config.yml` `roster` accepts:

- `all` (default) — every online player except the operator who issued
  `/rtpstress`. If only the operator is online, falls back to
  driving the operator (the "admin-self" mode from the proposal).
- `permission:<node>` — every online player with that permission.
- `names:` — combined with a YAML list `roster-names: [a, b, c]`.

The harness only drives players that are **currently online**. To
benchmark with many players, log many alts in (or wait for the
follow-up out-of-process bot driver — see *Future work*).

---

## Folia

Supported. The harness is structured to never violate Folia threading:

- Sampler runs on the async scheduler.
- Runner ticks on the async scheduler.
- Every command dispatch hops to the target player's
  `EntityScheduler` first.
- The `PlayerTeleportEvent` listener is read-only at `MONITOR` priority.

Folia detection is automatic via reflective probing of
`io.papermc.paper.threadedregions.RegionizedServer`. The same JAR drops
onto Spigot, Paper, and Folia unchanged.

---

## CSV format

Header (always written, one line):

```
attempt_id,player,world,target_label,dispatch_epoch_ms,teleport_epoch_ms,latency_ms,
success,fail_reason,from_x,from_z,to_x,to_z,distance,
tps_at_dispatch,mspt_at_dispatch,heap_used_mb_at_dispatch
```

`target_label` is the `label` field of the `target-commands` entry that
dispatched this attempt (or `default` when the legacy scalar key is in
use). Filter the CSV by this column to compare plugins.

`fail_reason` is `TIMEOUT`, `NULL_TO`, or empty (success). `latency_ms`
is `-1` for in-flight rows that the server crashed mid-write — anything
else is a wall-clock millisecond delta from dispatch to teleport event.

---

## Front-page summary

`/rtpstress export` writes a human-readable summary that mirrors the
front-page comparison table:

```
StressTestRTP run summary
generated: 2026-05-01T17:42:13
target-commands: rtp=`rtp:rtp player:{player}`, betterrtp=`betterrtp:rtp tp {player}`
attempts: 184 (ok=181)

--- Front-page comparison columns (all targets, combined) ---
Cold-start /rtp:    412 ms
Warm-queue /rtp:    38 ms  (median)
TPS under burst:    19.84  (min observed)
MSPT during eval:   12.40 ms (p95)
Memory footprint:   612 MB peak

--- Latency percentiles (success only, all targets) ---
p50: 38 ms
p95: 122 ms
p99: 264 ms

--- Per-target breakdown (success only) ---
rtp                  n=92   cold=412 ms  p50=38 ms   p95=84 ms   p99=164 ms
betterrtp            n=89   cold=701 ms  p50=141 ms  p95=410 ms  p99=812 ms

raw csv: 20260501-174118.csv
```

---

## Phase-aggregate CPU per teleport

Alongside the per-attempt CSV, each run also writes a sibling
`<stamp>-phases.csv` with one row per measurement phase (one TIMED
window, one BURST, or one SEQUENCE per-target window). Header:

```
phase_label,start_epoch_ms,end_epoch_ms,wall_ms,attempts,successes,
process_cpu_ms,main_thread_cpu_ms,
cpu_ms_per_attempt_total,cpu_ms_per_attempt_main
```

- **`process_cpu_ms`** — total user+system CPU charged to the JVM during
  the phase. Includes background work (GC, async chunk loaders, other
  plugins still ticking), so it answers *"what does this plugin cost
  the box"*, not *"what does this plugin alone consume"*.
- **`main_thread_cpu_ms`** — the server tick thread's CPU time only.
  This is the most differentiating axis: a plugin that does sync chunk
  I/O on the tick thread shows huge main-thread CPU; a properly async
  plugin shows very little. Note that wall-clock blocking
  (`.join()` waiting on a chunk future) is **not** counted as CPU —
  that gap surfaces in MSPT instead, and the disparity between the two
  is itself a useful diagnostic.
- The two `cpu_ms_per_attempt_*` columns are pre-divided convenience
  values; `plot_stress.py` re-derives attempt-weighted means across
  multiple runs from the raw `process_cpu_ms` / `main_thread_cpu_ms`
  sums when summarising.

Per-attempt CPU is intentionally **not** collected: the work for one
`/rtp` is split across the tick thread, the async chunk loader, the
safety scanner, and the entity scheduler, so per-attempt CPU cannot be
honestly assembled on Bukkit. Phase-aggregate CPU divided by completed
attempts is the most defensible per-teleport number.

`plot_stress.py` reads the phases CSV automatically when present and
emits two extra charts (`cpu_per_tp_total.png`, `cpu_per_tp_main.png`)
plus two extra columns in `summary.md`. If the phases CSV is missing
(older runs, or if you only kept the per-attempt CSV) those artefacts
are simply omitted — no breakage.

---

## Methodology notes

- **Don't trust a single run.** Fire `/rtpstress start` at least three
  times per plugin per server-restart and report median-of-medians.
- **Pre-generate the world** before benchmarking, otherwise you're
  measuring chunk generation, not RTP.
- **Run on identical hardware and identical server JARs** when
  comparing competitors. Differences in tick budget between Paper builds
  swamp differences between RTP plugins.
- **Disable other plugins** that hook `PlayerTeleportEvent` — they can
  add latency that the harness will attribute to the plugin under test.

---

## Spark profiler integration (optional)

When the [spark](https://spark.lucko.me/) profiler plugin is installed,
StressTestRTP automatically brackets each measurement phase with
`spark profiler start --timeout N --only-ticks-over T` /
`spark profiler stop --comment <target_label>`. One profile is produced
per `sequence` target (and one per `start` / `burst` run), and the spark
upload's comment matches the CSV's `target_label` column so the two
artifacts correlate 1:1.

This gives you white-box "where did the time go" data (sync chunk loads,
GC pauses, region scans) alongside the harness's black-box per-attempt
timings. When spark isn't installed the hook silently no-ops — no hard
dependency.

Configure under `spark:` in `config.yml` (defaults: enabled, 90 s
timeout, only-ticks-over 50 ms). Set `spark.enabled: false` if you'd
rather drive `/spark profiler` manually.

---

## Future work (out of scope for v1)

- **Out-of-process bot driver** (a sibling helper, e.g.
  `helpers/StressTestRTPBots/`) — connects N protocol-level bots to
  the test server so the roster scales past the alts the operator has.
- **Automated competitor sweep** — a wrapper script that swaps
  `target-command`, restarts the server, and concatenates summaries.

Both follow once v1 has produced a baseline run for RTP and RTP-Pro.

---

## Non-goals

- **No GUI.** Plain commands and plain CSV/TXT files.
- **No live charts / web dashboard.** The CSV is the artifact; analysis
  belongs in a notebook.
- **No bypass of permissions / cooldowns / economy.** Those are part of
  what's being measured.
- **No interaction with `rtp-api` or `rtp-core`.** This is a black-box
  harness by design — it must work identically against RTP and against
  every competitor.

If you want any of the above, fork this plugin or open a discussion
before bolting flags onto it.
