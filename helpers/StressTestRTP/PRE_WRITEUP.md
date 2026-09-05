# RTP Comparison - Pre-Writeup

> **Status**: working notes, not a finished post. Methodology, controls, caveats,
> and the current result set for `helpers/StressTestRTP`. Trim again before
> publishing.

> **Staleness rule.** A result section is kept only while its competitor
> versions match what is currently shipping. When a competitor releases, the
> affected section is re-run or deleted - never annotated and kept. Superseded
> run tables live in git history (`git log -p` on this file), not here.

---

## 1. What we're measuring

How each `/rtp`-style plugin behaves on a single server driven at sustained load
by a black-box harness.

Primary axes:

| Axis | Source | Lower-is-better |
|---|---|---|
| Cold-start `/rtp` latency | first attempt of each phase, `latency_ms` | yes |
| Warm-path latency distribution | per-target p50/p95/p99/p99.9/max of `latency_ms` | yes |
| Success rate | successes / attempts per phase | no |
| Throughput (TP/s) | successful attempts / phase wall-time | no |
| TPS under sustained burst | min `tps_at_dispatch` per phase | no |
| MSPT during eval | p99 / max `mspt_at_dispatch` per phase | yes |
| Main-thread CPU per teleport | `main_thread_cpu_ms / attempts` | yes |
| Background chunk-load share | off-tick loads / attributed loads | no |

Diagnostic axes:

| Axis | Source | Purpose |
|---|---|---|
| Process CPU per teleport | `process_cpu_ms / attempts` | gross JVM cost per `/rtp`, async loaders included |
| Destination scatter | `(to_x, to_z)` per success | effective radius, distribution shape, queue reuse |
| Folia watchdog stalls | server log, not the harness | region-thread blocking (S-005 class) |

Section 8 lists the axes the current harness added; they are not yet reflected
in any result section below.

---

## 2. Plugins under test

| Plugin | Version as tested | Notes |
|---|---|---|
| RTP | 26.1 / 26.2 (Pro), 3.1.3 (Lite) | Test subject. Configurable centre. |
| BetterRTP | 3.6.13 | Configurable centre. Config reverts on shutdown - see section 7. |
| EzRTP | 3.4.0 | Configurable centre. |
| JustRTP | see run header | Configurable centre. Pre-cache competitor - the closest architectural match to RTP in the roster. Paper/Folia 1.21+ only; no Spigot arm. See section 7. |
| HuskHomes | Paper-4.10 | **No centre config** - pinned to `/spawn`. |
| EssentialsX `/tpr` | 2.21.2 | Excluded - `/tpr` is self-only; cannot be driven from console. |
| DonutRTP | 8 | Excluded - GUI front-end dispatching BetterRTP. See section 7. |

Roster policy: only plugins that load and complete warm-up on the platform under
test appear in a result table. A plugin absent from a table is a compatibility
finding for its author, not a benchmark loss.

---

## 3. Controls applied (apples-to-apples)

This is the control set for the **next** run. Where it differs from what section
5's runs used, the difference is called out in the row and in section 6's
recorded parameters - which stay as the historical record and are not rewritten
to match.

| Variable | Value applied | Why |
|---|---|---|
| Outer radius | **16384 blocks (1024 chunks), every plugin** | Raised from 4096 for the next run set. Equalize selectable area. RTP region `radius: 1024` chunks; BetterRTP `MaxRadius`, EzRTP `radius.max`, JustRTP per-world `radius.max` raised to match. |
| Inner radius | left per-plugin (RTP 1024, EzRTP 500, BetterRTP 10) | Negligible central exclusion at this scale. |
| Pregen envelope | must cover the full 16384 radius per cell | Binding constraint on the radius increase - past the pregenerated edge the run silently becomes a worldgen benchmark. |
| Cooldowns / delays / countdowns | zeroed on every plugin | Cooldown is anti-spam policy, not throughput. |
| Worldgen | pregenerated world retained | A fresh world neutralizes RTP's anvil prefilter and turns the test into a worldgen benchmark. |
| `delay-chunk-unloads-by` (Paper) | 10s -> 0s | Default lets each teleport coast on the retention cache instead of paying a real load. |
| Phase length | ~600 s per plugin, one plugin dispatched per phase | Steady state; avoids cross-plugin residency bleed. |
| Sequence gap | 240 s | TPS settle between phases. |
| JIT warm-up | 30 s, 1 cycle through every target | Without it the first plugin pays the harness's JIT tax. CSV and spark writes disabled during warm-up. |
| Per-player dispatch gap | 3 ticks (default) or 0 ticks (throughput-ceiling runs) | gap=0 is unthrottled dispatch; never compare a gap=0 number against a gap=3 one. |
| Per-attempt timeout | 5 s | Capture-window expiry, not a server failure. See section 4. |
| Client load | 3 real OPed accounts, concurrency 4, burst 10 | No fake-player infrastructure. |
| Server JVM | Java 21, `-Xmx` 16 GB, default G1GC | REQ-RTP-SYS-001. |

**Why the world got bigger.** At 4096 blocks the whole selectable area is small
enough that every engine's cache converges on a few hundred region files, the OS
page cache absorbs the reads, and cached candidates are reused before they can
cost anything. That makes every cache look free, which is the one thing the
benchmark should not conclude for free. At 16384 blocks the selectable area is
16x larger, so a cache must either hold 16x more distinct chunk neighbourhoods
or refill 16x more often, and the section 8 residency, GC and heap-pressure
columns finally have signal to read. Consequence: **no 16384-radius number is
comparable to any 4096-radius number in section 5** - the radius is now part of
the run condition, like the dispatch gap.

---

## 4. What we deliberately do NOT control

- **Algorithmic shape** - BetterRTP `square`, RTP `CIRCLE`, others vary.
  Distribution shape is part of plugin identity.
- **Retry budget** - BetterRTP `MaxAttempts: 32`, RTP's count-bound pipeline.
  Different correctness/perf tradeoffs.
- **Safety-check thoroughness** - RTP runs anvil prefilter + biome + vert +
  neighbour grid; others run far less. Honest framing: RTP trades warm-path
  latency for higher safety guarantees (S-001), so a normalized "speed"
  comparison would flatter the shallow verifier.
- **Concurrency model** - HuskHomes and BetterRTP serialise per-player; RTP
  supports concurrent in-flight. We measure both as-shipped.
- **Biome distribution per cell** - individual cold-start values vary by ~30%.

### Practicality: benchmark-shaped vs production-shaped defaults

A cache can be tuned to win a ten-minute benchmark or to survive a Tuesday
evening, and the two are not the same setting. A short entry TTL, a small cap,
or a refill loop that only keeps up at benchmark concurrency all look excellent
inside one measured phase and stop looking excellent at hour six. The reverse
trap is ours: a long-lived pre-verified queue that holds coordinates
indefinitely can look like a memory leak in a run too short to show the plateau.

So practicality is recorded as its own axis, not folded into latency:

| Practicality question | Where it is answered |
|---|---|
| Does the cache survive longer than the phase? | Entry TTL vs the ~600 s phase length. A TTL near or below phase length means the run measures a *refilling* cache, and a longer deployment measures a different plugin. See the entry-lifetime classes below. |
| Does the cache size itself, and off what? | Static cap vs adaptive sizing. Adaptive on free memory means the plugin's own behaviour changes with `-Xmx`, so the arm must state the heap it ran on. |
| What does it cost to hold? | `peak_resident_chunks`, `peak_plugin_tickets`, `tick_thread_alloc_bytes_per_attempt` (section 8) - coordinate tuples vs resident chunk neighbourhoods. |
| What does it cost to refill? | `chunks_off_tick_share`, `region_file_reads`, `gc_*` during the steady-state tail of the phase, not the opening minute. |
| Does it change behaviour under pressure? | `heap_pressure_*` (section 8). An engine that silently cuts attempts or serves cache-only above a heap limit is not the same engine either side of that line. |

Rule of thumb for the write-up: **quote the second half of the phase.** The
first half of any phase is a pre-warmed cache draining; the second half is the
refill loop's real cost, and that is what an operator actually runs.

#### Entry-lifetime classes (not one TTL axis)

"Unmeasured cache economics" is not a symmetric charge across the roster,
because the engines do not share an expiry model. Three distinct classes:

| Class | Engine | What a 600 s phase sees |
|---|---|---|
| **Event-invalidated, no timer** | RTP. Entries in `keptLocations` / `unkeptLocations` are not expired on a clock; they leave the buffer when they are served, when the region config changes, or when spatial memory marks the bin bad. In a world with no claim plugin and no terrain edits, prior lifecycle measurement puts effective entry lifetime at "until served" - unbounded. | A representative steady state. Nothing was about to expire, so nothing is hidden by phase length. Refill cost is the *serve* rate, which the phase measures directly. |
| **Persistent spatial memory** | RTP (`MemoryShape` bad-location bitmaps) and EzRTP, which likewise retains spatial state across attempts rather than discarding it on a short timer. | Amortizing, not expiring: the longer the run, the cheaper the next attempt. A short phase *understates* this design - the opposite of the TTL trap. Read it in `region_file_reads` and `bin_candidates` over the phase tail. |
| **Time-expired** | JustRTP, `~15 min` entry TTL (pin the figure - section 7). | A cache that never once expired. This is the class where phase length genuinely caps what can be claimed, and the only class the long-phase run in section 8 exists to fix. |

Consequence for the write-up: do not level the three with a single "all caches
unmeasured" disclaimer. An unbounded-lifetime cache measured for 600 s is at
steady state; a 15-minute-TTL cache measured for 600 s is at t=0 of its own
lifecycle. Both statements are honest; they are not the same statement.

The cost that is genuinely unmeasured for RTP is the other half of the trade:
long-lived entries mean **retention**, and retention needs
`peak_resident_chunks` / `peak_plugin_tickets` at the raised radius, plus
verify-on-serve latency for a coordinate that has aged (an entry cached at
minute 1 and served at hour 6 must still be safe). That is a residency and
staleness question, not a hit-rate one.

---

## 5. Results

Only runs on currently-shipping competitor versions are kept. Earlier Spigot
1.20.1 and Paper 1.20.1 passes were removed: every competitor in them has since
released, so their numbers are not citable. See the staleness rule at the top.

### 5.1 Paper, unthrottled dispatch - `20260617-232754` (RTP-Paper 26.1, `per-player-gap-ticks: 0`)

The canonical single-server head-to-head. The 3-tick per-player gap was set to
`0`, so the 3-client / concurrency-4 harness dispatches as fast as each engine
can absorb. Three phases back-to-back (`rtp` -> `ezrtp` -> `betterrtp`), each
~600 s with the 240 s inter-phase settle gap. HuskHomes was not dispatched this
run (see section 5.2).

| Plugin | Att / Succ | TP/s | p50 | p95 | p99 | p99.9 | max | TPS min | MSPT p99 / max |
|---|---|---|---|---|---|---|---|---|---|
| **RTP** | 16560 / 16560 (100%) | **18.7** | **1 ms** | **2 ms** | **46 ms** | 518 ms | 687 ms | 17.5 | 85.8 / 98.4 ms |
| ezrtp | 7137 / 7018 (98.3%) | 13.1 | 30 ms | 189 ms | 322 ms | 1936 ms | 2172 ms | 10.3 | 156.8 / 292 ms |
| BetterRTP | 1633 / 1606 (98.3%) | 6.0 | 480 ms | 3217 ms | 4402 ms | 6229 ms | 6383 ms | 2.5 | 859 / 3663 ms |

(Latency percentiles over successful attempts: RTP n=16782, ezrtp n=7150,
BetterRTP n=1636. TPS/MSPT from the 50 ms `-heap.csv` sampler, restricted to
each phase's measurement window.)

Findings:

- **RTP holds a flat distribution under unthrottled dispatch.** p50 1 ms /
  p95 2 ms / p99 46 ms at 100% success, TPS never below 17.5, MSPT max 98 ms.
  ~95% of teleports are served instantly from the pre-verified queue; the
  slowest 1% pay a single bounded async chunk load. The "p99 stays bounded as
  load rises" criterion held.
- **Serialized engines blow out exactly where predicted once the gap is
  removed.** BetterRTP's foreground chunk-load + retry model collapses: p99
  4.4 s, max 6.4 s, MSPT max 3.66 s, TPS to 2.5. EzRTP fares better but still
  tails badly: p99 322 ms, p99.9 ~1.9 s, TPS floor 10.3.
- **Contrast ratio is the headline axis.** At the same offered load RTP's p95 is
  2 ms while BetterRTP's is 3217 ms (~1600x) and ezrtp's is 189 ms (~95x).

Caveats:

- **Heap max is not a per-plugin metric - dropped.** `heap_used_mb` topped out
  at ~16.37 GB in all three phases because that is the `-Xmx` ceiling, identical
  for every engine. A meaningful memory axis is MB/attempt or GC-trough
  behaviour, not max.
- **gap=0 is a throughput-ceiling condition**, not the section 3 default. RTP's
  18.7 TP/s here is not comparable to the 12.9 TP/s of the gap=3 run in section
  5.5. The valid cross-plugin comparison is *within* this run.
- `cpu_ms_with_chunks*` used the base `chunk-load-cost-us` and is relative-only
  (section 6 calibration note). Directly-measured columns are unaffected.

Defensible headline (shareable by Lite via the common `rtp-core` engine):

> On Paper, RTP sustained 18.7 TP/s at 100% success (16560/16560) under
> unthrottled dispatch, with p50 1 ms / p95 2 ms / p99 ~2 ticks (46 ms) and TPS
> never below 17.5. ~95% of teleports are served instantly from a pre-verified
> queue; the slowest 1% is a single bounded async chunk load, never a server
> stall. At the same offered load BetterRTP's p95 was 3.2 s (~1600x) with TPS
> crashing to 2.5, and EzRTP's was 189 ms (~95x).

### 5.2 HuskHomes under gap=0: server crash, and the overload thesis

Dispatched under the same `per-player-gap-ticks: 0` config that RTP sustained at
100%, HuskHomes' RTP path stalled the main thread and the server crashed before
the phase completed, so no `huskhomes` phase summary was recorded.

Attribution:

- **Corroborated by prior runs.** Earlier runs already showed HuskHomes
  degrading toward player-timeout under sustained load; its per-player
  serialization cannot drain unthrottled dispatch. The crash is the endpoint of
  a documented trend, not a one-off.
- **It is a load ceiling, not a "HuskHomes crashes servers" claim.** HuskHomes
  handles normal `/rtp` traffic fine; its ceiling simply sits below the load RTP
  absorbs at 100%.
- **Architectural, not a bug claim.** The mechanism is main-thread
  serialization failing to drain - the same property that produced BetterRTP's
  4.4 s p99 in section 5.1, taken to its conclusion.

Operator-facing thesis:

> You should not be able to crash your server by spamming a teleport command.
> With RTP you can't - the harness pushed as hard as it allows
> (`per-player-gap-ticks: 0`, 3 clients) and RTP held at 100% success, p95 2 ms,
> TPS never below 17.5. Under the same offered load, HuskHomes' RTP path stalled
> the main thread and crashed the server.

RTP's async chunk loads + pre-verified queue + bounded buffers make overload
unreachable from the command path: at worst the queue drains and players see a
brief "finding a safe location" wait, never a tick-thread stall.

Open items: re-run HuskHomes **last** under gap=0 so a crash never costs the
other phases' data, and capture the crash artifact (`crash-reports/` entry plus
log tail). Confirm it is a watchdog/main-thread stall or OOM before quoting it -
a `NoSuchMethodError`-class failure would be environmental, not a load result.

### 5.3 Folia - `20260617-191448` (RTP-Folia 26.1, RTP-Pro, two phases ~600 s)

The platform where Paper near-parity collapses. Same controls as section 3,
3 clients. RTP edition here is **Pro** (the tuned `rtp-folia` adapter).

| Metric | RTP-Pro (Folia) | EzRTP (Folia) | Paper ref (RTP / EzRTP, section 5.5) |
|---|---|---|---|
| Attempts | 8113 | 3180 | 7755 / 6987 |
| Throughput (per s) | 13.5 | 5.3 | 12.9 / 11.6 |
| main-thread CPU / attempt (ms) | 3.96 | 6.34 | 17.97 / 19.06 |
| total CPU / attempt (ms) | 199 | 593 | 765 / 817 |
| % chunk loads background | 14% | ~0% (22 of 45,814) | 85% / 47% |

- **EzRTP throughput halves on Folia** (11.6 -> 5.3 /s) while **RTP holds or
  improves** (12.9 -> 13.5 /s). The Paper speed parity is a Paper-only artifact.
- **Folia slashes RTP's main-thread cost** (17.97 -> 3.96 ms/attempt): region
  threads parallelize what Paper serialized onto one tick thread.
- **EzRTP does essentially zero background chunk loading** on Folia (22 of
  45,814), i.e. it loads chunks inline on region threads.

Server-side evidence (independent of the harness), from
`RTP-Folia/26.1/logs/latest.log`:

- **7 Folia watchdog stalls** ("Tick region ... has not responded"), max region
  unresponsive time **20.39 s**.
- The stack attributes the hang to EzRTP doing a synchronous blocking chunk load
  on a region scheduler thread: `EzRTP-3.4.0
  BukkitChunkLoadStrategy.loadChunk -> org.bukkit.World.loadChunk ->
  ServerChunkCache.syncLoad -> BlockableEventLoop.managedBlock -> Unsafe.park`.
- No watchdog stalls attributed to RTP.

This is the S-005-class pattern manifesting as a multi-second region freeze.

Defensible headline:

> On Folia under identical load, EzRTP-3.4.0 blocked region threads on
> synchronous `World.loadChunk`, tripping the Folia watchdog 7 times (one region
> unresponsive 20.4 s) and sustaining only 5.3 TP/s; RTP sustained 13.5 TP/s
> with zero watchdog stalls and a 3.96 ms main-thread cost per teleport. Harness
> completion-timeouts are a capture-window metric and are not used as the
> failure measure; the region stalls are server-emitted.

### 5.4 Folia Lite - `20260617-205614` (RTP-Folia 26.1, RTP-Lite, single phase ~600 s)

Same host, controls, and harness as 5.3, but the RTP jar is **RTP-Lite**
(reported as `RTP v3.1.3`) and the roster is RTP-only.

**Edition difference.** Lite and Pro share the platform-neutral `rtp-core`
teleport engine, but not the Folia *platform path*. The tuned `rtp-folia`
adapter is Pro-only and excluded from the MIT lite jar (ADR-024 2026-06-10
amendment, ADR-061); on Folia the lite jar runs the correctness-first
`FoliaAwareScheduler` in `rtp-paper-common`, routing through paper-api's
regionized scheduler statics and teleporting via `Entity#teleportAsync`. So this
run measures whether Lite's unoptimized fallback keeps pace with Pro's adapter -
not "the same code".

| Metric | RTP-Lite (Folia) | RTP-Pro ref (section 5.3) |
|---|---|---|
| Attempts | 7507 | 8113 |
| Successes | 7507 (100%) | 8109 (~100%, 4 capture-timeouts) |
| Throughput (per s) | 12.5 | 13.5 |
| main-thread CPU / attempt (ms) | 4.15 | 3.96 |
| total CPU / attempt (ms) | 177 | 199 |
| % chunk loads background | 10.8% (19,246 / 177,911) | 14% |
| Folia watchdog stalls | 0 | 0 (none attributed to RTP) |
| TPS (avg / min) | 19.8 / 16 | ~20 |

- **Lite's unoptimized Folia path keeps pace with Pro's tuned adapter** -
  throughput, main-thread cost, and background-chunk share are all within
  run-to-run noise. The shared `rtp-core` engine, not the Folia adapter,
  dominates per-teleport cost at this load, so the Folia advantage over EzRTP
  holds for both editions.
- **100% success, zero watchdog stalls**, under the same 3-client burst load
  that froze EzRTP region threads for up to 20.4 s.

**What this does NOT settle.** 3 clients measures per-teleport cost at light
load, not the scaling ceiling. Pro's tuned adapter fans pipeline work across
per-region threads and is built to pull ahead as per-region contention rises;
that headroom is an architectural expectation, not a number this run
demonstrates. Market the Pro Folia advantage as scaling headroom under
contention, never as a measured throughput win over Lite at this load.

Additional caveats: the two `latest.log` lines matching "stalled" are the
substring of *"installed"* (metrics binding log lines), not watchdog stalls.
`chunks_per_attempt` (21.1) is the Folia attribution artifact of section 9.

Defensible headline:

> RTP-Lite on Folia sustained 12.5 TP/s at 100% success with zero watchdog
> stalls and a 4.15 ms main-thread cost per teleport - statistically
> indistinguishable from RTP-Pro (13.5 TP/s, 3.96 ms) at this 3-client load, and
> achieved on the correctness-first `FoliaAwareScheduler` fallback rather than
> Pro's tuned adapter. The free build delivers the same Folia resilience EzRTP
> lacked; Pro's adapter is built to scale further under heavier contention.

### 5.5 Paper, gap=3 four-plugin reference - `20260617-175906`

Retained only because it is the sole run containing HuskHomes as a completed
phase and the background-chunk-share discriminator across four engines. Section
5.1 supersedes it for latency and throughput ranking (harsher gap=0 config).

| Metric | RTP | EzRTP | BetterRTP | HuskHomes |
|---|---|---|---|---|
| Attempts / successes | 7755 / 7755 | 6987 / 6987 | 2646 / 2639 | 1957 / 1950 |
| Success rate | 100% | 100% | 99.7% | 99.6% |
| Throughput (per s) | 12.9 | 11.6 | 4.4 | 3.2 |
| chunks / attempt | 1.58 | 3.38 | 5.86 | 4.59 |
| % chunk loads background (async) | 85% | 47% | 8% | 1% |
| main-thread CPU / attempt (ms) | 17.97 | 19.06 | 25.57 | 33.48 |
| total CPU / attempt (ms) | 765 | 817 | 1811 | 2589 |
| TPS avg / min (phase) | 19.37 / 15.96 | 15.50 / 12.82 | 9.99 / 7.37 | 19.39 / 17.85 |
| MSPT avg / p95 / max (ms) | 26.8 / 35.1 / 87.5 | 56.0 / 86.6 / 1259 | 130.4 / 185.4 / 6646 | 9.9 / 31.9 / 57.1 |
| Heap growth in-phase (MB) | +2676 | -1379 | +11633 (to ceiling) | +4012 |
| Heap post-GC trough (MB) | 9948 | 5863 | 2147 | 5487 |

Two cross-cutting findings this run is kept for:

1. **The background-chunk share is the discriminator.** It lines up exactly with
   health: RTP 85% -> EzRTP 47% -> BetterRTP 8% -> HuskHomes 1%. The more chunk
   work a plugin keeps on the main thread, the worse its tick tail (BetterRTP,
   6.6 s max MSPT) or its throughput ceiling (HuskHomes, 3.2/s).
2. **No hard memory leak in any phase.** Every phase's heap trough dropped back
   under GC (EzRTP net-decreased). Earlier "memory climbing" was reclaimable
   churn from a high allocation rate, not retention.

**HuskHomes reads as slow-and-heavy but self-limiting**: lowest throughput,
highest per-attempt main-thread cost, ~99% foreground. Because it self-throttles
at gap=3, TPS stayed healthy and MSPT smooth - it never stalls, it just cannot
keep up. Remove the gap and it crashes (section 5.2).

---

## 6. Recorded test parameters

Captured verbatim from each server's `plugins/StressTestRTP/config.yml` so the
runs are reproducible. The runs share identical harness settings; they differ
only in target roster, RTP edition (Pro vs Lite), host platform, and
`per-player-gap-ticks`.

| Parameter | Value |
|---|---|
| dispatch-as-player | true |
| roster | all |
| attempt-timeout-ms | 5000 (capture window; see section 9) |
| console-fail-enabled | true |
| console-fail-patterns | [] (empty -> built-in defaults; source of the warm-up false-failure warning) |
| save-worlds-between-phases | auto |
| default-concurrency | 4 |
| per-player-gap-ticks | 3, or 0 for section 5.1 / 5.2 |
| default-burst | 10 |
| no-progress-kickstart-ms | 30000 |
| sequence.per-target-seconds | 600 |
| sequence.gap-seconds | 240 |
| sequence.warmup-seconds | 30 |
| sequence.warmup-target-cycles | 1 |
| sample-period-ms | 50 |
| chunk-load-cost-us (base, used) | 28221.0 (Spigot default) |
| chunk-load-cost-us-paper / -folia | 1830.0 each (present but NOT applied) |
| spark | enabled, profile-per-phase, timeout 660 s, only-ticks-over-ms 0, rotate 60 s |

| | Paper `20260617-175906` | Folia `20260617-191448` | Folia Lite `20260617-205614` | Paper gap=0 `20260617-232754` |
|---|---|---|---|---|
| Host | RTP-Paper / 26.2 | RTP-Folia / 26.1 | RTP-Folia / 26.1 | RTP-Paper / 26.1 |
| RTP edition | Pro | Pro | Lite | Pro |
| Targets (in order) | rtp, betterrtp, huskhomes, ezrtp | rtp, ezrtp | rtp | rtp, ezrtp, betterrtp |
| Phases recorded | 4 | 2 | 1 | 3 |

Target commands: `rtp:rtp`, `betterrtp:betterrtp`, `huskhomes:rtp`,
`ezrtp:forcertp {player} world`. The EzRTP dispatch uses the force variant so
its cooldown/limit gating is bypassed; the others use their plain
`/rtp`-equivalent.

**Namespaced-command hazard.** When two plugins register `/rtp`, Bukkit's
command map keeps one `PluginCommand` per label, so `<plugin>:rtp` can resolve
to the *wrong* plugin. Verify each arm with `/<plugin>:rtp <TAB>` before trusting
a row, and prefer a plugin-unique command (`forcertp`, `betterrtp`) where one
exists. A whole prior run set was invalidated by this.

**Calibration note.** Despite `chunk-load-cost-us-paper` / `-folia` being set,
`StressTestRTPPlugin.beginRun()` reads only the base `chunk-load-cost-us`
(28221.0, the Spigot default), confirmed by the server log line
`chunk-load-cost-us=28221.00 amending phase CPU`. Every `cpu_ms_with_chunks*`
figure above is therefore **relative-only / overstated**. All directly-measured
columns (throughput, main-thread CPU, TPS, success, watchdog stalls) are
unaffected. Set the base key per platform before quoting any absolute.

---

## 7. Version-independent plugin findings

Structural findings that do not depend on a particular benchmark run, so they
survive competitor releases. Anything version-pinned belongs in section 5.

### RTP

- Cold-start carries no first-attempt penalty: the pre-warmed `keptLocations`
  queue serves the first `/rtp` of a phase as fast as the thousandth.
- Bounded p99 is the direct payoff of the count-bound pipeline (ADR-015).
- **No entry TTL by default.** Queue entries are invalidated by events (served,
  config change, spatial-memory rejection), not by a clock, so in a claim-free
  and edit-free world effective entry lifetime is "until served". Prior
  lifecycle measurement is the basis for that claim; re-confirm it at the raised
  radius rather than assuming it scales. The trade this buys and the trade it
  costs are in section 4 *Entry-lifetime classes*: hit rate does not decay with
  idle time, and the bill arrives as retention plus a staleness obligation
  (verification happens on serve, so an aged coordinate is re-checked, not
  trusted).
- The anvil prefilter reads `.mca` NBT off-thread and needs **no Folia region**,
  materializing a live chunk only for candidates that survive the off-thread
  screen. This is the structural Folia advantage; on pregenerated Paper it is
  invisible, which is why Paper speed parity misleads.

### BetterRTP

- **Config edits revert on shutdown.** Verify the radius actually took effect
  via the destination scatter, or `attrib +R` the file after editing.
- `MaxAttempts: 32` retry loop: failures surface as long latency on the one
  successful retry, not as failure rows.
- Has a `Queue.Enabled` pre-warm; serialises per-player ("already rtp'ing"
  rejections are caught by `ConsoleWatcher`).
- **Folia main-thread chunk-load violation** (2026-06-17, Folia 26.1.2-8).
  `TickThread` logged `Thread failed main thread check: Async chunk retrieval`
  on `Folia Region Scheduler Thread #2` during the Global Region tick. Stack:
  BetterRTP's bundled PaperLib (`me.SuperRonanCraft.BetterRTP.lib.paperlib`)
  `AsyncChunksSync.getChunkAtAsync` -> `CraftWorld.getChunkAt`, dispatched from
  `QueueGenerator.lambda$addQueue$4` via `AsyncHandler.sync`. The shaded
  PaperLib falls back to the **synchronous** implementation, then calls
  `getChunkAt` from a thread that does not own the chunk. Its queue pre-fill is
  therefore partially broken on Folia 26.1.x, inflating cold/warm latency and
  abort rate. Disclose in any Folia 26.1.x head-to-head including BetterRTP.

### HuskHomes

- Serialises per-player and uses a normal-distribution placement biased toward
  the outer radius - clashes with sustained dispatch. Not a bug; HuskHomes'
  `/rtp` is a secondary feature of a cross-server homes plugin.
- **No centre config** - pinned to `/spawn`, so it cannot be moved off a
  configurable plugin's cell. Sequence spawn-anchored plugins apart.
- **Folia main-thread chunk-load violation** (same date/platform, same root
  cause, different thread class): repeated `TickThread` errors on **`Folia Async
  Scheduler Thread`s**, stack
  `NormalDistributionEngine.generateSafeLocation` ->
  `BukkitSavePositionProvider.findSafeGroundLocation` -> shaded PaperLib
  (`net.william278.huskhomes.libraries.paperlib`) `AsyncChunksSync` ->
  `CraftWorld.getChunkAt`. Both plugins ship the same vendored PaperLib, so this
  is a common PaperLib-on-Folia-26.1.x failure, not a coincidence. Consequence:
  HuskHomes' safety check cannot reliably read the destination chunk on Folia
  26.1.x.

### EzRTP

- Ships a 5 s countdown by default; disable it or the measurement is UX padding.
- Genuinely async-looking on Paper (~47% background chunk loads) but does
  **essentially zero** background loading on Folia, loading inline on region
  threads - the section 5.3 watchdog stalls.
- **Retains spatial state rather than short-TTL entries.** Its memory of where
  it has already looked is not on a ~minutes timer, so like RTP it belongs in
  the amortizing class of section 4, not the time-expired one: a longer run
  should make it cheaper per attempt, not reset it. Confirm from the shipped
  config which keys bound that state (size cap, persistence across restart) and
  record them in section 6 before drawing the contrast in public.

### JustRTP

- **The roster's only genuine pre-cache peer.** kotorinet/justRTP,
  CC BY-NC-SA, Paper/Folia 1.21 through 26.2, Java 21+. It keeps a background
  cache of pre-validated spots so a normal `/rtp` serves from memory rather than
  searching live - the same shape as RTP's `keptLocations`. Every other
  competitor in section 5 either loads chunks in the foreground or serialises
  per player, so JustRTP is the first arm where the comparison is cache-vs-cache
  rather than cache-vs-search.
- **Native Folia support, no vendored PaperLib.** It does not inherit the
  `AsyncChunksSync` -> `CraftWorld.getChunkAt` main-thread violation that breaks
  BetterRTP's and HuskHomes' queue pre-fill on Folia 26.1.x. Expect it to be the
  strongest Folia arm, and do not read a weak Folia result as vindication of our
  design if it turns out otherwise.
- **The cache sizes itself off free memory, TPS, player count and live demand.**
  Two consequences for measurement: (a) the plugin's behaviour is a function of
  `-Xmx`, so its arm is only citable alongside the heap it ran on, and (b) it
  self-throttles on TPS, meaning a bad TPS reading in an *earlier* phase can
  shrink its cache before its own phase starts. Sequence it after a full settle
  gap and record `heap_pressure_*` for it specifically.
- **~15 minute entry TTL - the practicality question.** Cached locations expire
  on a short timer. Inside a ~600 s measured phase that TTL never fires, so the
  benchmark reads an intact cache and reports the best case. In production the
  TTL becomes the dominant term: on a quiet server every entry expires before
  anyone uses it, so the cache is pure background cost at a near-zero hit rate
  and `/rtp` falls back to a live search at exactly the moment the operator was
  promised instant. On a busy server it never matters. Worth stating plainly
  rather than scoring: a TTL that short is a *freshness* choice - guard against
  a spot being built over or claimed since validation - and the price of that
  choice is a cache that only pays off above some traffic threshold. RTP takes
  the other side of the trade, long-lived coordinates plus verification on
  serve, and pays for it in retention instead.
- **Measurement requirement, not an accusation.** To keep this honest the
  JustRTP arm must run **longer than one TTL** (>= 2x TTL, so ~30 min against the
  current 600 s phase) or the figure published is a cache that was never allowed
  to expire. RTP runs the same long phase - but for the opposite reason, and the
  write-up should say so: RTP has no expiry timer to outlive, so its long phase
  tests *retention and staleness*, not hit-rate decay. Until such a run exists,
  no JustRTP hit-rate or latency number goes in the write-up.
- **Verify the TTL from the shipped config before quoting it.** The 15 min
  figure is an operator-side observation; pin it to the actual key and value in
  the tested build's cache config and record both in section 6.
- **Excluded surfaces.** `/rtp gui`, zones, air RTP, matchmaking queue and
  cross-server are out of scope - the harness drives the plain teleport path
  only. Also: JustRTP registers `/rtp`, so it collides with our own label
  exactly as JakesRTP did (section 6 command-map hazard). Dispatch via a
  plugin-unique form and confirm with `/justrtp:rtp <TAB>`; this is the
  highest-risk arm in the roster for silent self-measurement.

### EssentialsX

- Excluded from the head-to-head: `/tpr` is self-only and cannot be driven from
  console without per-player chat dispatch.
- **`/tpr` is a timeout source on Spigot.** Including the `/tpr` target in the
  roster produces a high, reproducible timeout rate for that target's slice;
  removing the target (EssentialsX still installed, nothing else changed)
  eliminates it. It is the act of dispatching `/tpr`, not EssentialsX's
  presence, that drives it. Silent failure: the attempts simply never produce a
  `PlayerTeleportEvent` within the deadline. Hypothesis (not root-caused):
  `/tpr` is a teleport-*request* command, so its handshake/warmup path does not
  emit the event the probe waits on. Not observed on Paper/Folia. See
  `docs/dev/LESSONS_LEARNED.md` *Stress Testing*.
- Default posture: **`/tpr` excluded from Spigot rosters.** A run that keeps it
  must say so and must not be compared against rosters that excluded it.

### DonutRTP

- **Excluded - not an independent implementation.** The bundled JAR is a GUI
  front-end that dispatches BetterRTP commands; it does no chunk loading, safety
  checking, or distribution math. Every teleport button in its bundled
  `gui/*.yml` hard-codes `betterrtp world world_N`, and its `settings.yml`
  exposes only `duration` and `buffer-blocks` - no radius, centre, shape, or
  cooldown keys, because those are BetterRTP's.
- Publishing it as its own row would be "BetterRTP's pipeline plus GUI overhead"
  and would halve BetterRTP's dispatches. Write-up line: *operators using
  DonutRTP can read the BetterRTP row as their own.*

---

## 8. Harness axes added since these runs (methodology follow-up)

The harness gained ~1000 lines of instrumentation after the section 5 runs were
captured, so **no result section above carries these columns**. They are listed
here as the methodology delta to fill on the next run. Schema conventions: for
counts and durations `-1` means NOT MEASURED and never zero; for strings, empty
means NOT AVAILABLE. The harness writes its own schema notes alongside the CSVs.

| New axis | Columns | What it settles |
|---|---|---|
| Served-mode split | `served_mode`, `served_mode_source`, `served_mode_threshold_ms`, `served_mode_direct`; per phase `mode_threshold_ms`, `mode_threshold_method`, `fast_mode_fraction`, `fast_p50/p95/p99_ms`, `cold_p50/p95/p99_ms` | Replaces any bare mean over a bimodal (queue-hit vs chunk-load) population. `served_mode` is inferred from observables only; the direct reading from the plugin under test has its own column so the two are never conflated, and it is empty for every competitor arm. |
| Foreground/background chunk split | `chunks_on_tick`, `chunks_off_tick`, `chunks_off_tick_share` | Turns the async share - the section 5.5 discriminator - from a derived figure into a direct measurement. On-tick means the single tick thread on Spigot/Paper, or the region thread owning the loaded chunk on Folia. |
| Tick-thread occupancy | `tick_intervals`, `tick_interval_total_ms`, `tick_interval_max_ms`, `tick_region_ownership` | Records occupancy as coalesced intervals, not a total: one 8 ms stall and sixteen 0.5 ms ones are not the same tick health, and a total cannot tell them apart. Loads within 10 ms merge into one interval so work on two different ticks can never be merged. |
| Folia region accounting | `region_context_acquisitions`, `region_freeze_threshold_ms`, `region_freezes`, `region_freezes_tick_stall`, `region_freezes_hop_stall`, `region_worst_freeze_ms` | Gives the harness its own region-freeze evidence instead of leaning entirely on the server watchdog (section 5.3), and counts region hops - the cost a live-block-API verifier pays per scattered candidate. The freeze threshold is a compile-time constant stated in the row, chosen before any run was read. |
| Region-file reads and bin occupancy | `region_file_reads`, `bin_candidates`, `bin_occupancy_max`; per phase `region_reads_per_attempt`, `bin_candidates_per_batch` | Measures the candidates-per-32x32-bin the cost model previously assumed to be 64. Implied by observed chunk loads, so it cannot see region bytes read *without* materializing a chunk - exactly what our prefilter does - and therefore understates RTP's avoided reads. |
| Storage class and read latency | per phase `storage_class`, `storage_class_method`, `storage_read_p50/p90/p99/max_us`, `storage_read_label` | Lets a downstream model select a cost distribution instead of assuming one. `FIRST_TOUCH_UNKNOWN_PAGE_CACHE`: farthest-from-origin first, no file probed twice, but pages already resident read warm - so a lower bound on cold-read latency. |
| GC and allocation churn | `gc_young_*`, `gc_old_*`, `gc_unclassified_collections`, `gc_total_*`, `gc_time_fraction_of_wall`, `tick_thread_alloc_bytes[_per_attempt]`, `tick_alloc_scope` | The churn term that heap-used alone cannot separate from retention - the gap section 5.5's "no hard leak" finding had to argue around. `gc_time_fraction_of_wall` sums parallel GC threads and can exceed 1.0; it is not a pause fraction. Allocation is one recorded tick thread, not JVM-wide (`FOLIA_GLOBAL_REGION_PARTIAL` on Folia). |
| Chunk residency | `peak_resident_chunks`, `peak_plugin_tickets`, `peak_target_plugin_tickets` | Separates a design that keeps coordinate tuples and releases its tickets from one that keeps chunk neighbourhoods resident. Peaks, not averages - an average hides retention. The ticket census needs Paper's `World#getPluginChunkTickets()` and runs on its own slow timer (`ticket-sample-period-ms`, min 250 ms) because the query walks every ticketed chunk; it is `-1` on Spigot and Folia. |
| Heap-pressure triggers | `heap_pressure_events`, `heap_pressure_first_heap_used_mb`, `heap_pressure_first_trigger`, plus `<stamp>-heap-triggers.csv` | Some engines govern their own heap and cut max attempts / serve cache-only above a limit; a comparison drawn across that boundary is not like-for-like. The harness records the trigger line and the heap level only - no response is inferred. Patterns are deliberately narrow (`heap-pressure-patterns`), since a false positive becomes a claimed behaviour change that never happened. |
| Crash-safe phase capture | `<stamp>-phases-partial.csv` | The per-attempt and 50 ms heap CSVs already flush per row, but the phase *summary* was only written at phase end, so the section 5.2 crash lost its aggregate. `flushPartialPhase()` now snapshots the in-flight phase (self-throttled to once per 2 s, called from `Runner.tick()`); `endPhase` deletes the sidecar, so a leftover file is itself the signal that the phase it describes crashed. |

Method work to do before the next run:

1. Set the base `chunk-load-cost-us` per platform so `cpu_ms_with_chunks*`
   becomes quotable (section 6 calibration note).
2. Configure `residency-target-plugin` per arm, or leave one target per run so
   the harness can infer it, otherwise `peak_target_plugin_tickets` stays `-1`.
3. Override `heap-pressure-patterns` with the vocabulary of each plugin under
   test; the built-in defaults will not match a competitor's wording.
4. Narrow `console-fail-patterns` - the empty default is what produced the
   spurious warm-up "zero successful attempts" warning in every run above.
5. Fill the HuskHomes gap=0 slot, dispatched **last** (section 5.2).
6. Arrival-block safety audit (classify landings: safe / water / lava / cave /
   suffocating / void) on the pregenerated world. This is the correctness axis
   the whole benchmark still lacks: a plugin can look fast precisely because it
   skips verification, and the harness counts a completed `PlayerTeleportEvent`
   without asking whether the landing was safe.
7. Re-cut the world at the section 3 radius (16384 blocks) and pregen every cell
   to the full envelope before dispatching. Retire the 4096-radius rows from the
   comparison table rather than mixing scales.
8. Add the JustRTP arm: plugin-unique dispatch verified by `<TAB>`, per-world
   radius raised to match, cache TTL and cap recorded in section 6.
9. **Long-phase run for every cache-based engine.** At least 2x the longest
   *finite* cache TTL in the roster (~30 min per phase against JustRTP's
   ~15 min), reported first-half vs second-half so the drain and the refill are
   separable. This is the run that makes section 4's practicality axis quotable,
   and the one the new residency / GC / heap-pressure columns exist for. Note
   the asymmetry: for a time-expired cache the long phase reveals hit-rate
   decay, while for an event-invalidated one (RTP, and EzRTP's spatial state) it
   reveals whether residency plateaus or keeps climbing. Report those as two
   different questions, not one column.
10. **Confirm RTP's unbounded entry lifetime at the raised radius.** The "no TTL
    in a claim-free world" claim rests on earlier lifecycle measurement at 4096;
    re-derive it from `peak_resident_chunks`, `peak_plugin_tickets` and queue
    occupancy at 16384 before publishing it as a design advantage.

---

## 9. Caveats and what this does not prove

Must appear in the public write-up.

**Rig and load**

1. **Single-server, single-machine** rig; not a multi-server / proxy setup.
2. **Three real online accounts**, not hundreds of players. Absolute numbers are
   a floor; with 50 real players everything degrades but the *ranking* should
   hold. Fake-player infrastructure (`helpers/StressTestRTPBots`) is future work.
3. **Cooldowns and countdowns disabled** everywhere - pipeline throughput, not
   anti-spam or UX policy.
4. **All plugins forced to the same outer radius** (4096 blocks in section 5's
   runs, 16384 from the next run set); an author may argue that is not their
   default deployment shape. **Never mix radii across a comparison** - a bigger
   world is a different working set, not a harder version of the same one.
5. **Some plugins ignore centre-XY config** and run at world spawn, so phase
   order alternates configurable and spawn-anchored cells.
6. **No GC tuning beyond default Paper flags** (G1GC).
7. **Never mix dispatch gaps.** gap=0 and gap=3 numbers are different
   conditions; compare only within a run.

**Measurement**

8. **`TIMEOUT` is a capture-window expiry, not a server failure.** It means no
   completion was captured within 5 s. At high throughput a sub-tick teleport
   and a genuinely slow one can look alike to the oracle - the harness races
   itself. The race is **biased in the slower plugin's favour**: false timeouts
   concentrate on near-0 ms teleports (RTP's queue fast path), while an engine
   with guaranteed scheduler hops floors its latency above the race window. So
   RTP's handful of timeouts are treated as capture artifacts, and a
   competitor's are treated as real only when corroborated by server-side
   evidence.
9. **`chunks_per_attempt` is unreliable on Folia** (e.g. RTP 29.6 vs its Paper
   1.58): inflated by region ticketing plus `ChunkLoadCounter` over-attribution
   on region threads. Not a behavioural number.
10. **MSPT on Folia is the global-region sample, not per-region**, so it is
    blind to a single hung region and is not a useful Folia discriminator. Use
    throughput plus watchdog evidence.
11. **Heap max is not a per-plugin metric** - it is the `-Xmx` ceiling.
12. **MSPT is world-tick cost, not "server frozen"**: at peak MSPT the server
    was still chat-responsive at the ~50 ms level.
13. **`cpu_ms_with_chunks*` is relative-only** until the calibration note in
    section 6 is addressed.
14. **A ~600 s phase is shorter than some caches live - but that cuts
    differently per engine.** For a time-expired cache (JustRTP, `~15 min`) the
    phase ends before a single entry expires, so the result is a best case by
    construction. For an event-invalidated cache (RTP, whose entries carry no
    timer in a claim-free world) there is no expiry event to miss, so the phase
    is a steady state and the open question is retention, not hit rate. Do not
    write one disclaimer covering both - see section 4 *Entry-lifetime classes*.

**What this benchmark does NOT prove**

- That RTP is faster than every plugin in every configuration. Untested: low
  concurrency, very large or very small radii, cross-server, low-RAM servers.
- That any competitor is "bad". BetterRTP is reasonable for low-volume servers;
  HuskHomes is optimized for cross-server homes and its `/rtp` is secondary.
  The findings are about where each design's ceiling sits.
- That the observed defects cannot be fixed. The PaperLib-on-Folia violations in
  section 7 are one dependency swap away for their authors.
- That results generalize to 50+ concurrent players (see caveat 2).
- **Anything about long-run cache economics** *for the time-expired class.* No
  phase yet outlives JustRTP's `~15 min` TTL, so its hit rate and refill cost
  are unmeasured. This is not a uniform charge: RTP's entries carry no expiry
  timer, and EzRTP's spatial state amortizes rather than expires, so for those
  engines a 600 s phase is a steady state and what remains unmeasured is
  **retention over hours and staleness of aged entries**, not hit-rate decay.
  Section 4's practicality questions are posed, not answered - per class.
- **Anything about destination safety.** The harness counts completed teleports,
  not safe landings. Until the section 8 arrival audit lands, no claim about
  correctness or safety is supported by these numbers.

---

*End of pre-writeup. Edit before publishing.*
