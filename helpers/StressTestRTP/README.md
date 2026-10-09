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

The RTP front-page table compares RTP / RTP-Pro across five performance
columns. The default `target-commands` set ships the three competitors
worth measuring on modern (1.21+ / 26.x) servers: **HuskHomes**,
**BetterRTP**, and **EzRTP**, all actively maintained, independent RTP
engines. Older entries (AsyRTP, SorekillRTP, AdvancedRTP, JakesRTP,
EssentialsX `/tpr`) ship commented-out in `config.yml`: JakesRTP has no
Folia support, EssentialsX's `/tpr` is self-only and tends to crash
under high call volume, and the rest are effectively unmaintained on
modern versions. Uncomment any of them for a one-off run after verifying
it loads on the target build. (DonutRTP was reviewed
and excluded — it is a GUI front-end that dispatches BetterRTP commands,
not an independent RTP implementation; see `PRE_WRITEUP.md` section 6.)

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
2. Samples TPS / MSPT / heap-used on an async timer once per server tick
   (50 ms, configurable via `sample-period-ms`). A `/rtp` completes in tens
   of milliseconds, so a coarser 1000 ms cadence would alias past the
   heap-growth and MSPT spikes the pipeline produces between samples.
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
/rtpstress ramp betterrtp     # fixed-rate stages 5..320 TP/s; finds the stress point
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
benchmark with many players, run the offline-mode bot roster
(`devstack/clients/bench-swarm.js`, see *Ramp mode*).

---

## Ramp mode (stress point)

`start` / `sequence` are closed-loop: one attempt in flight per player
and at most `default-concurrency` in flight overall. By Little's law the
offered rate is then at most `in-flight / latency`, so 4 accounts against
a ~300 ms plugin can never offer more than ~13 TP/s, and a fast plugin's
throughput reads as the harness's ceiling, not its own. Ramp mode offers
load **open-loop at a fixed rate** and finds the rate at which each
plugin stops keeping up.

```
/rtpstress ramp <target> [stageSeconds]   # target = a target-commands label
/rtpstress ramp <target> 120 100          # flat 100 TP/s for 120 s (rates override ramp.stages)
/rtpstress status                         # current stage + stress point so far
/rtpstress stop                           # ends the ramp; completed stages are on disk
```

- Idle window (`ramp.idle-seconds`, default 30) with bots online and no
  dispatch. Its second half is the idle baseline (CPU cores, MSPT p50)
  applied to every stage, so the `net_*` cost-per-teleport columns in
  `<stamp>-phases.csv` are filled. It runs before any teleport so a
  plugin's post-teleport background work (queue fill, generation) is
  billed to that plugin, not subtracted as ambient.
- Optional warm-up (`ramp.warmup-seconds`, default 30) at the first
  stage's rate; no CSV rows are written for it.
- Each stage (`ramp.stages`, default `5,10,20,40,80,160,320` TP/s, each
  `ramp.stage-seconds`, default 60) issues dispatch slots at exactly the
  offered rate. Every slot goes to an idle roster player (no attempt in
  flight, past `ramp.per-player-gap-ms`, default 0). If none is idle the
  slot is counted as **`harness_shed`**, harness-side saturation, and is
  not retried.
- Concurrency is bounded only by the roster size: still one in-flight
  attempt per player, and `default-concurrency` is ignored.
- After the dispatch window the stage drains (at most
  `attempt-timeout-ms` + 1 s), so late completions count toward the stage
  that dispatched them. Then one row is appended to `<stamp>-ramp.csv`
  and flushed before the next stage starts, so a server crash keeps
  every completed stage. `ramp.gap-seconds` (default 5) idles between stages.
- Each stage is also a normal measurement phase (label
  `<target>@ramp<i>-<rate>tps`), so `<stamp>-phases.csv` keeps its CPU,
  chunk and GC columns per stage. One spark profile spans the ramp.

### Stress point

A stage **passes** iff all of these hold (thresholds under `ramp.pass`):

| Criterion | Default | CSV `fail_criteria` token |
|---|---|---|
| `achieved_tps >= 0.95 x offered_tps` (achieved = successful teleports / stage seconds) | `min-achieved-fraction: 0.95` | `ACHIEVED_BELOW_OFFERED` (+ `HARNESS_SATURATED` when shed slots alone exceed the 5 % shortfall) |
| MSPT p95 over the stage <= 50 ms | `max-mspt-p95: 50.0` | `MSPT_P95` |
| `(timeouts + errors) <= 1 %` of attempts | `max-fail-fraction: 0.01` | `FAILURE_RATE` |

The **stress point** is the highest passing stage. The ramp stops after
`ramp.stop.consecutive-failures` (default 2) failing stages in a row; a
pass after a single failure resets the count. It also stops if wall-clock
TPS stays below `ramp.stop.low-tps-threshold` (default 5) for
`ramp.stop.low-tps-seconds` (default 10) continuously. That stage is
aborted, recorded with `aborted=true`, and fails `LOW_TPS_ABORT`. The
end of the ramp logs one summary line and writes `<stamp>-ramp-summary.txt`:
stress point stage and offered rate, plus the first failing stage and
the criterion it failed on.

A stress point whose next stage failed with `HARNESS_SATURATED` is a
**lower bound**: the roster, not the plugin, ran out. Add bots and re-run.

**Busy vs failure.** Plugin-side "busy / cooldown / already teleporting"
rejections are shed load (`busy_rejections`): they lower `achieved_tps`
but are not failures. They are recognised only when the rejection reaches
the **console** (`ConsoleWatcher` -> `CONSOLE_FAIL:` reason) and matches
`ramp.busy-patterns`. A rejection sent only to the player via chat never
reaches the server log. It surfaces as a timeout, and therefore as a
failure. `bench-swarm.js` counts such chat replies out-of-band
(`busy_chat=` in its stats line) so the two can be reconciled.

MSPT is the sampler's value (Paper `getAverageTickTime`, a ~100-tick
average; wall tick time on Spigot/Folia). TPS for the abort rule and
`tps_min` is measured by a 20-tick wall-clock timer with a staleness
bound, not Paper's 1-minute `getTPS()[0]`, which lags a 10 s rule.

### `<stamp>-ramp.csv` columns

```
target,stage_index,offered_tps,stage_seconds,attempts,successes,timeouts,errors,
busy_rejections,harness_shed,achieved_tps,achieved_fraction,fail_fraction,
latency_p50_ms,latency_p95_ms,latency_p99_ms,mspt_p50,mspt_p95,mspt_samples,
tps_min,heap_peak_mb,roster_size,peak_in_flight,aborted,complete,verdict,fail_criteria,
start_epoch_ms,end_epoch_ms
```

`verdict` is `PASS`, `FAIL`, or `PARTIAL`. `PARTIAL` (`complete=false`)
marks a stage cut short by `/rtpstress stop`; it is kept for the record
but never counts toward the stress point. `-1` means NOT MEASURED.

### Bot roster: `bench-swarm.js`

Full clients cost too much RAM, so the roster is many lightweight
offline-mode Mineflayer bots, split across a few Node worker processes
(`--workers`, default one per 16 bots, at most CPUs - 1):

```powershell
cd devstack/clients
npm install
node bench-swarm.js --host <bench-host> --port 25565 --count 64
```

- Server prerequisites: `online-mode=false` (the Linux bench server
  already runs offline) and `roster: "all"`. The bots are named
  `bench_000`..`bench_063` (`--prefix`, `--start-index`).
- **Connection throttle:** every bot connects from one IP, and Paper
  rejects reconnects faster than `bukkit.yml`
  `settings.connection-throttle` (default 4000 ms). Either set it to
  `-1` for the bench, or keep `--join-delay-ms` above it (default 4500;
  64 bots are then online after ~5 min). All connects and reconnects go
  through one queue spaced by this delay.
- Bots stay idle: no chat, no movement. They auto-respawn on death and
  reconnect after a kick or disconnect with exponential backoff
  (`--reconnect-base-ms` 5000 doubling to `--reconnect-max-ms` 60000).
  Ctrl+C disconnects them cleanly.
- Defaults keep memory low: `--view-distance 2` (the server still sends
  real chunks, but only min(client, server) of them; raise it to model
  real clients' send cost), physics off (no gravity simulation;
  teleport-confirm and position packets are still sent; use `--physics`
  if `allow-flight=false` causes mid-air fly kicks), and a lean
  Mineflayer plugin set (`--full-plugins` restores all).
- **Chunk decode off:** each teleport makes the server send a fresh
  chunk square, and decoding those on one Node thread saturated the
  event loop at ramp rates. Keepalives then queued behind chunk data and
  both sides dropped the bots ("client timed out after 60000 ms",
  server "keepalive timeout"). The bots now receive chunks and still
  acknowledge every chunk batch, so server send cost stays real, but do
  not decode them (`--keep-world` restores decoding).
- **Server-side load distance:** Paper loads chunks around a player by
  the *server's* view/simulation distance, whatever the client asks.
  During a ramp the harness caps each roster player to
  `ramp.player-view-distance` (default 2, Paper only, 0 disables), the
  same for every target. Without it, 64 bots at view-distance 10 meant
  ~529 fresh chunks per landing: TPS fell below 1 and every attempt
  timed out before any plugin was measured.
- A stats line every 30 s (one per worker) reports online count,
  connects, kicks (top reasons), errors, deaths, busy chat replies and
  process RSS.
- With `dispatch-as-player: true` the bots need each target's `/rtp`
  permission, and per-player plugin cooldowns must be zero (or bypassed)
  for the bench. Otherwise every repeat dispatch is a cooldown rejection,
  not load.

Ramp procedure: start the swarm, wait until `online=64/64`, run
`/rtpstress ramp <target>` from the console, then repeat for each target
with a server restart between targets so each plugin starts cold.

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

## Ticket-footprint calibration (setup phase)

Every retained-memory figure the harness reports is quoted per cached
location, and a cached location is one `addPluginChunkTicket` call.
Turning that into bytes needs one more number: how many chunks the
server actually makes resident in response to a single ticket.

That number is a **platform decision, not a plugin one**. Vanilla
propagates ticket levels outward, so a ticket below the `FULL` threshold
pins a neighbourhood rather than one chunk. Paper and Folia each
reimplemented that subsystem and need not agree with vanilla or with
each other. Assume it and every bytes-per-cached-location inference is
off by exactly the factor assumed - in the flattering direction if you
guess low.

So it is measured, once, at plugin enable, before any teleport is
recorded:

1. Pick a chunk ~20k blocks from origin (well outside the run's
   origin-centred teleport radius, so the probe never warms ground the
   run then measures) that reports `isChunkLoaded() == false`.
2. Apply exactly one `addPluginChunkTicket` on the thread owning that
   chunk, wait for that chunk to actually report loaded (up to 400
   ticks - on a cold start it may need generation, and on Folia a fresh
   region thread), then count `ChunkLoadEvent`s within 7 chunks of it
   for the settle window. A chunk that never loads is reported as NOT
   MEASURED, not as a zero footprint.
3. Release the ticket and count the unloads.

If the enable-time probe raced server start-up, it is retried
automatically at the next `/rtpstress start`, or on demand with
`/rtpstress probe-footprint` while no run is active.

Used heap and committed heap are sampled at all three boundaries -
before the ticket, after the load settles, after the release settles -
and collections inside the window are counted, so the reading covers
the whole lifecycle instead of only the growth half.

Counting events rather than diffing `World#getLoadedChunks()` is
deliberate: the array form allocates a reference to every loaded chunk
on the server, and it is not region-safe to walk on Folia. Events are
already delivered on the owning thread, so the probe reports the same
quantity on Spigot, Paper, and Folia.

Results land in `ticket-footprint.txt` and in these phase columns:

| Column | Meaning |
|---|---|
| `ticket_footprint_chunks` | chunks made resident by one ticket |
| `ticket_footprint_shape` | `1x1` / `3x3` / `5x5`, or `IRREGULAR` / `NONE` |
| `ticket_footprint_released` | chunks unloaded when the ticket was removed |
| `ticket_probe_noise_loads` | loads outside the attribution radius |
| `ticket_footprint_heap_bytes` | used-heap delta across the window |
| `ticket_footprint_bytes_per_chunk` | that delta per chunk |
| `ticket_footprint_heap_label` | `UNCOLLECTED_ALLOCATION_INCLUSIVE`, or `UNATTRIBUTABLE_CONCURRENT_ALLOCATION` when growth exceeds 16 MiB per counted chunk (someone else allocated in the window) |
| `ticket_footprint_heap_used_before_bytes` | used heap before the ticket |
| `ticket_footprint_heap_used_after_load_bytes` | used heap once the load settled |
| `ticket_footprint_heap_used_after_unload_bytes` | used heap once the release settled |
| `ticket_footprint_heap_retained_after_unload_bytes` | growth still resident after release |
| `ticket_footprint_heap_reclaimed_bytes` | growth that came back on release |
| `ticket_footprint_committed_delta_bytes` | committed-heap change across the probe |
| `ticket_probe_gc_collections` | collections inside the probe window |
| `ticket_footprint_reclaim_label` | `RECLAIMED_ON_UNLOAD` / `RETAINED_PENDING_COLLECTION` / `NO_NET_GROWTH` / `GC_DURING_WINDOW_UNATTRIBUTABLE` |

Reading them:

- **`ticket_footprint_chunks` is the multiplier.** Multiply by the
  cached-location cap under test to get the resident-chunk cost of a
  full hot cache.
- **`ticket_probe_noise_loads` decides whether you can trust it.** `0`
  means the window was quiet and the footprint is attributable to the
  ticket. Non-zero means unrelated chunk traffic overlapped the window,
  and the footprint is an **upper bound**. Probe an empty server.
- **`ticket_footprint_released` is the retention check.** Equal to
  `ticket_footprint_chunks` means retention is bounded and symmetric. A
  shortfall means the ticket did not fully release, and every residency
  figure in the run should be read as accumulating.
- **The heap figures are upper bounds, not retained sets.** No
  collection is forced - a `System.gc()` on a server under measurement
  would corrupt the GC columns recorded in the same run - so both
  include transient allocation. The label says so in every row.
- **`ticket_probe_gc_collections` gates the heap columns.** `0` means
  no collection ran inside the window, so the growth and the
  post-release reading are attributable to the ticket. Non-zero sets
  the reclaim label to `GC_DURING_WINDOW_UNATTRIBUTABLE` and no heap
  figure from that probe should be quoted.
- **`ticket_footprint_reclaim_label` is the OOM-exposure reading, and
  the interesting one.** An unload drops references; it does not free
  bytes. `RETAINED_PENDING_COLLECTION` - growth still resident after
  the ticket is gone, with no collection in the window - is the normal
  Paper result and is **not** a leak, it is deferred reclamation. It is
  also exactly why sizing a heap from steady-state footprint fails: a
  burst that demands memory faster than the collector reclaims released
  chunks can exhaust a heap the footprint figure called ample. Size
  against headroom, not footprint. `RECLAIMED_ON_UNLOAD` means most
  growth came back unaided; `NO_NET_GROWTH` means there was nothing to
  reclaim.
- **`ticket_footprint_committed_delta_bytes` invalidates absolute heap
  numbers when non-zero.** The JVM grew the heap for one ticket, so the
  run is describing heap-growth policy as much as the plugin. Pin
  `-Xms == -Xmx` before quoting any absolute figure.

`-1` and empty mean NOT MEASURED. In particular, `-1` here never means
a one-chunk footprint.

Tunable under `ticket-footprint-probe` in `config.yml`
(`enabled`, `origin-distance-blocks`, `settle-ticks`). Raise
`settle-ticks` on a slow disk if the count looks truncated.

---

## Folia: what completed the row, and which TPS you are reading

Two columns exist because Folia's numbers are otherwise easy to misread.

**`attribution_source`** (per attempt) names the observation that set
`teleport_epoch_ms`: `PLUGIN_EVENT`, `TELEPORT_EVENT`, `POSITION_POLL`,
`CONSOLE`, or `TIMEOUT`. The external channels (`PlayerTeleportEvent`,
the pinned position poll) fire on the *destination* region's tick after
the async chunk load and the cross-region entity handoff, so on Folia
they read the landing 1-2 ticks (50-100 ms) after the plugin issued the
teleport. When the plugin under test is LeafRTP, the harness registers
for its `PreTeleportEvent` / `PostTeleportEvent` by class name (no
compile dependency) and completes the attempt from `PostTeleportEvent`
instead - the plugin's own completion instant. `plugin_latency_ms`
(Post minus Pre) is the teleport call alone. Competitor arms keep the
external channels and write `-1` there. Compare `latency_ms` across
arms only within one `attribution_source`.

**`external_latency_ms`** (per attempt) is dispatch to the first
`PlayerTeleportEvent` or position-watch sighting, recorded on every arm
including LeafRTP's. This is the cross-plugin latency column. A LeafRTP row
is held up to 1 s for the sighting and writes `-1` if none arrives.

**`fail_reason=NOT_AT_DESTINATION`**. LeafRTP fires `PostTeleportEvent`
whether or not the platform teleport succeeded, so on Post the probe checks
that the player is within 3 blocks (XZ) of the destination, in the same world.

**`landing_class`** (with `to_world`, `to_y`, `landing_floor/feet/head`) is
the landing block column read with fixed criteria: `SAFE`, `LAVA`, `WATER`,
`SUFFOCATING`, `NO_FLOOR`, `HAZARD`, `VOID`, or `UNCHECKED_*` when it could
not be read without loading a chunk or crossing a region.

**`main_thread_cpu_scope`** (phases) is `main-thread` on Spigot/Paper and
`folia-region-threads:N` on Folia, where `main_thread_cpu_ms` sums every
region scheduler thread. Folia rows without this column measured one thread.

**`cpu_*_ms`** (phases) split `process_cpu_ms` by thread name, summed from
per-thread CPU deltas sampled every `cpu-breakdown-sample-ms` (default
1000) and at phase boundaries:

| Column | Threads |
|---|---|
| `cpu_server_thread_ms` | the tick thread (`Server thread`) |
| `cpu_region_threads_ms` | Folia region scheduler threads |
| `cpu_scheduler_ms` | Bukkit async workers (`Craft Scheduler Thread`) |
| `cpu_scheduler_by_plugin` | the same, as `plugin=ms;...` from the name Paper gives a worker while it runs a task; `(idle)` is time between tasks |
| `cpu_async_scheduler_ms` | Paper/Folia `AsyncScheduler` workers |
| `cpu_chunk_system_ms` | chunk load, generation and region I/O workers |
| `cpu_network_ms` | Netty |
| `cpu_other_java_ms` | every other Java thread; top 8 names in `cpu_other_top` |
| `cpu_non_java_ms` | process CPU minus all of the above: GC, JIT and VM threads, plus the last interval of any thread that exited between samples |
| `cpu_gc_ms` | the JVM's GC-thread CPU counter, a subset of `cpu_non_java_ms`; JDK 26+ only, `-1` otherwise |

A worker is charged to the plugin named at sample time, so a worker that
switched plugins within one interval bills the whole interval to the later
one. The harness's own async work appears as `StressTestRTP`. Check
`cpu_other_top` after a run on a new platform: a large entry there is a
thread family the name patterns in `CpuSampler.classify` do not cover yet.

`chunk-load-cost-us` is read per platform family (`-paper`, `-folia`, or
the base key on Spigot) with no fallback, so `chunk_load_cost_ms` /
`cpu_ms_with_chunks` stay empty on Paper and Folia unless their own key is
set. On those platforms chunk-worker CPU is already in `process_cpu_ms`
and is measured directly as `cpu_chunk_system_ms`.

**`chunks_sync_requested` / `chunks_sync_by_plugin`** (phases) name the
plugin that synchronously requested each load, from the `ChunkLoadEvent`
call stack. They are `-1` unless `chunks_sync_selftest` is `PASS` (one sync
and one async load of a generated chunk at startup, `sync-load-selftest`).
`chunks_on_tick` classifies by firing thread, which Paper and Folia make
near 100% for every plugin; publish chunk counts from
`chunks_inclusive_per_attempt`.

**`chunks_landing_area`**. Paper loads a player's view area after the
teleport event, so those loads used to land on whichever attempt was in
flight, or in background when none was, which made `chunks_per_attempt`
depend on how long a plugin's teleports take. A load within
`viewDistance + 1` chunks (Chebyshev) of an account's last successful
destination is now charged to that teleport until the same account's next
dispatch. The window is never time-based, so one account's consecutive
teleports cannot overlap. These loads are excluded from
`chunks_loaded_attributed` and `chunks_loaded_background`, so attributed +
landing area + background = `chunks_loaded`. `chunks_per_teleport` is
(attributed + landing area) / attempts.

**`region_tps_*`**. Folia has no server-wide TPS; `Server#getTPS()`
throws, so the `tps` column there is a wall-clock timer on the *global*
region, which is never where a teleport lands. It can read 20.0 while
every player region is saturated, and the dips it does show are
global-region hiccups. `RegionTpsSampler` reads
`Server#getRegionTPS(World, cx, cz)` for the region each online player
stands in, on `region-tps-sample-period-ms`, and writes:

| Column | Meaning |
|---|---|
| `region_tps_5s_at_dispatch` (per attempt) | 5 s TPS of the dispatching player's region |
| `region_tps_scope` (per phase) | `FOLIA_PLAYER_REGIONS`, `SERVER_NATIVE` (Paper: `tps` already covers the server), or `GLOBAL_REGION_TIMER` (Folia build without the API; `tps` is global-region only) |
| `region_tps_samples` | player-region samples in the phase |
| `region_tps_5s_min` / `region_tps_5s_mean` | over those samples |
| `region_tps_1m_min` | lowest 1 m window seen |
| `region_tps_below_target_fraction` | samples at or under a fixed 19.0 TPS |

These are over player-region *samples*, not distinct regions - the API
exposes no region id. `-1` means NOT MEASURED (off Folia, or before the
first sample). Per-region tick durations are not in the public API; use
the spark integration below for MSPT percentiles on Folia.

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

## Test consistency protocol

The numbers are only comparable if every plugin is measured under the
**same** conditions. Aliasing, JIT warm-up, cache state, and machine
noise can each swing a result by more than the difference between two
plugins, so pin all of them down before you trust a column. Follow this
checklist top-to-bottom for each comparison campaign.

### 1. Pin the environment (set once, never change mid-campaign)

- **Hardware**: one physical box, no other tenants. Disable CPU
  frequency scaling / turbo where you can (a thermally throttled box
  produces a downward MSPT/TPS drift that looks like a regression).
- **Server JAR**: identical build (`paper-1.20.x-bNNN`) and identical
  `server.properties` (`view-distance`, `simulation-distance`,
  `max-tick-time`) for every plugin under test.
- **JVM**: identical flags (`-Xms` == `-Xmx` so the heap never resizes
  mid-run — a heap grow shows up as a false "memory footprint" spike),
  identical Java version, identical GC. Pin `-Xms=-Xmx` even if you
  normally don't; a resize event aliases straight into the heap series.
- **Sampler cadence**: leave `sample-period-ms` at the `50` default
  (one tick) for every plugin. Changing the cadence between plugins
  makes the p95-MSPT and peak-heap columns incomparable.
- **World**: pre-generated to a radius larger than the RTP max range,
  and **byte-for-byte identical** between plugins (see section 3).

### 2. Pin the workload

- Use `/rtpstress sequence <perTarget> <gap> <concurrency>` rather than
  separate `start` runs: it exercises every `target-commands` entry
  back-to-back with identical duration, concurrency, and roster, then
  idles `gap` seconds so the box (and any pre-warmed queue) recovers
  before the next plugin. One sequence run == one apples-to-apples
  comparison.
- Keep `concurrency` and `perTarget` identical across campaigns. A
  longer phase warms more JIT and fills more cache, so a 60 s phase and
  a 600 s phase are not comparable even for the same plugin.
- Keep the **roster size constant**. RTP latency is per-player; driving
  4 alts vs. 1 operator changes the contention profile.

### 3. Reset state between plugins (the consistency killer)

Cache and queue state leaks across runs and is the single biggest
source of irreproducible numbers:

- **Cold-start column**: only meaningful on a freshly restarted server
  with an empty queue. Restart the server between plugins (or at least
  before the run you read cold-start from) — there is intentionally no
  `/rtpstress reload`, because re-running from a known-fresh state is
  the only way to get a comparable cold-start.
- **World reset**: if a plugin mutates the world (sets spawn, places
  blocks), restore the pre-generated world directory from a backup
  before the next plugin so chunk-load cost is identical.
- **Cache reset**: configure `cache-reset-commands` for the plugin's
  own queue/cache-clear command and run `/rtpstress reset-cold` between
  phases when you cannot afford a full restart. A restart is always the
  gold standard; `reset-cold` is the fast approximation.

### 4. Warm up, then measure

- The first successful attempt after a restart is your **cold-start**
  number — record it, then discard the rest of that warm-up phase.
- HotSpot needs a few hundred attempts to inline the hot path. Treat
  the first warm-up phase as throwaway; read warm-queue / p95-MSPT /
  peak-heap only from the measured phases that follow.

### 5. Repeat and aggregate

- Run the **whole `sequence`** at least three times, ideally across
  three independent server restarts.
- Report **median-of-medians** for latency and **worst-case** (min TPS,
  p95 MSPT, peak heap) — averaging away a tick spike hides exactly the
  pathology the table is meant to expose.
- `plot_stress.py` re-derives attempt-weighted means across multiple
  runs; point it at all the run CSVs from a campaign rather than
  eyeballing a single `summary.txt`.

### 6. Sanity-check before trusting a column

- **Min TPS == 20.00 and p95 MSPT well under 50 ms** across the whole
  run usually means the workload was too light to differentiate
  plugins — raise `concurrency` until the box is actually under load.
- **A flat heap series** (`mb_per_attempt ≈ 0`) means either the run
  was too short to observe growth or the heap was reset mid-run (an
  `-Xms != -Xmx` resize, or a forced GC). Re-check section 1.
- **`fail_reason=TIMEOUT` on a large fraction of attempts** means the
  target command was rejected without firing `PlayerTeleportEvent`
  (wrong syntax, cooldown, permission). Fix `target-commands` /
  `ConsoleWatcher` patterns before reading any latency column — timed-out
  attempts are not in the latency percentiles and silently bias the
  comparison.

---

## Spark profiler integration (optional)

When the [spark](https://spark.lucko.me/) profiler plugin is installed,
StressTestRTP automatically brackets each measurement phase with
`spark profiler start --timeout N [--only-ticks-over T] [--thread S]` /
`spark profiler stop --comment <target_label>`. One profile is produced
per `sequence` target (and one per `start` / `burst` run), and the spark
upload's comment matches the CSV's `target_label` column so the two
artifacts correlate 1:1.

This gives you white-box "where did the time go" data (sync chunk loads,
GC pauses, region scans) alongside the harness's black-box per-attempt
timings. Both a spark plugin jar and the spark that Paper 1.21+ bundles
are detected; the bundled one registers no Bukkit plugin, so the hook
also checks for the `/spark` command. When neither is present the hook
logs one line and no-ops — no hard dependency.

Configure under `spark:` in `config.yml` (defaults: enabled, 90 s
timeout, only-ticks-over 50 ms). Set `spark.enabled: false` if you'd
rather drive `/spark profiler` manually.

- `spark.threads` (default `Server thread,RTP-Anvil-IO-*`) is a comma list;
  each entry becomes its own `--thread <name>` (names with spaces are not
  quoted; spark re-joins them). An entry containing `*` is a wildcard and
  adds `--regex` (exact entries are escaped), so pool threads spawned after
  the profile starts are still sampled. `*` alone samples every thread;
  `""` omits the flag (server thread only).
- With `save-to-file`, spark writes `plugins/spark/profile-<stamp>.sparkprofile`
  (the log line names the folder that was found). The auto-summary polls
  that folder every ~1 s, starting after `auto-summary-delay-ticks`, until
  `spark.auto-summary-timeout-seconds` (default 30) have passed since the
  stop. It accepts only a profile that is new since the stop and whose size
  is unchanged across two polls. Spark's save can take several seconds on
  Windows.

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
