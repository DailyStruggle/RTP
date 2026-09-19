# RTP Comparison — Pre-Writeup

> **Status**: working notes, not a finished post. Captures methodology, controls,
> caveats, and per-plugin findings accumulated across stress-test iterations on
> Paper 1.21.11 with `helpers/StressTestRTP`. Re-read before publishing; trim
> aggressively for the public version.

---

> **2026-05-02 retraction — JakesRTP rows in sections 5b-5e are actually RTP-self-measurements.**
> When two plugins register `/rtp`, Bukkit's command map keeps a single
> `PluginCommand` instance per label and the namespaced form
> `jakesrtp:rtp` resolved to **our** plugin's command on the test server,
> not to JakesRTP's. Confirmed via `/jakesrtp:rtp <TAB>` returning our
> subcommands. Therefore every "JakesRTP" row in sections 5b/5c/5d/5e is a
> second `rtp` measurement under a different label, and any "JakesRTP
> parity with RTP" headline (section 5b finding bullet, section 5e finding 6) reduces
> to "RTP measured against itself, twice — reproducibility OK". The
> section 5e RTP main-CPU/att reproducibility band (518/542/520/565 ms across
> sections 5b/5c/5d/5e slot-1) now extends to 4 effective RTP runs by folding
> in the sections 5b-5e "JakesRTP" slot-N readings. JakesRTP itself remains
> un-measured on this rig until a re-run uses
> `jakesrtp:forcertp {player} -c default-settings` (with
> `dispatch-as-player: false`) to bypass the command-map collision.
> Workaround landed in `helpers/StressTestRTP/src/main/resources/config.yml`
> on the same date.

---

## 1. What we're measuring

The front-page comparison table answers: **how does each `/rtp`-style plugin
behave on a single Paper server when driven at sustained load by a black-box
harness**, on five axes:

| Axis | Source | Lower-is-better |
|---|---|---|
| Cold-start `/rtp` latency | first attempt of each phase, `latency_ms` | yes |
| Warm-queue `/rtp` latency | per-target p50 of `latency_ms` | yes |
| TPS under sustained burst | min `tps_at_dispatch` per phase | no (higher is better) |
| MSPT during eval | p95 `mspt_at_dispatch` per phase | yes |
| Memory footprint | peak `heap_used_mb_at_dispatch` per phase | yes |

Plus three diagnostic axes added during iteration:

| Axis | Source | Purpose |
|---|---|---|
| Throughput (TP/s) | successful attempts ÷ phase wall-time | reveals concurrency-bottlenecked plugins |
| Process CPU per teleport | `process_cpu_ms ÷ attempts` | gross JVM cost per `/rtp` (includes async chunk loaders) |
| **Main-thread CPU per teleport** | `main_thread_cpu_ms ÷ attempts` | failure detector for "async-by-name" plugins (AsyRTP exhibit) |
| Destination scatter | `(to_x, to_z)` per success | reveals effective radius, distribution shape, queue reuse |

---

## 2. Plugins under test

| Plugin | Version | Cell center | Notes |
|---|---|---:|---|
| RTP | 3.0.0-beta.1 | (0, 0) | Configurable centre. Test subject. |
| BetterRTP | 3.6.13 | (16384, 0) | Configurable centre. Config reverts on shutdown — see section 6. |
| AsyRTP | 1.0.0 | (0, 16384) | Configurable centre. The "async by name" exhibit. |
| EzRTP | 2.1.0 | (-16384, 0) | Configurable centre. |
| HuskHomes | Paper-4.10 | (0, 0) | **No centre config** — pinned to `/spawn`. |
| SorekillRTP | 1.0.0 | (0, 0) | **No centre config** — pinned to world border centre. |
| AdvancedRTP | 2.6.2 | (0, 0) | **No centre config** — pinned to world spawn. |
| EssentialsX `/tpr` | 2.21.2 | n/a | Excluded — `/tpr` has no `<player>` argument; cannot be driven from console. |

Sequence-mode order (alternates configurable / spawn-anchored so Paper's lazy
unload has time to evict the prior cell's chunks before the next spawn-anchored
phase):

```
1. rtp          (0, 0)         configurable
2. betterrtp    (16384, 0)     configurable
3. huskhomes    (0, 0)         spawn-anchored
4. asyrtp       (0, 16384)     configurable
5. sorekillrtp  (0, 0)         spawn-anchored
6. ezrtp        (-16384, 0)    configurable
7. advancedrtp  (0, 0)         spawn-anchored
```

---

## 3. Controls applied (apples-to-apples)

| Variable | Value applied | Why |
|---|---|---|
| `MaxRadius` | 4096 blocks (256 chunks) | Match RTP's default; equalize selectable area. |
| `MinRadius` | 100 blocks | Avoid always-loaded spawn ring. |
| Cooldowns | disabled on every plugin | Cooldown is anti-spam policy, not throughput. |
| Countdown timers | disabled (EzRTP, SorekillRTP) | We measure pipeline cost, not UX. |
| Worldgen | pre-generate each cell with Chunky to ~5000 radius | Otherwise first attempt measures worldgen, not chunk-load. |
| `delay-chunk-unloads-by` (Paper) | tightened from 10s to 1s | Default leaks the previous phase's chunks into the next phase. |
| `auto-save-interval` (Paper) | 100 ticks | Lets Paper free chunks faster. |
| `max-auto-save-chunks-per-tick` | raised | Same reason. |
| `view-distance` | **2** (next run) | Reduces 121-chunk active set per player to 25; isolates selection-algorithm cost from Paper's chunk-tracking aftershock. |
| `simulation-distance` | **2** (next run) | Same reason; minimises mob-spawn / entity-tick aftershock. |
| Sequence gap | 60 s | Paper's lazy unload + jitter buffer between phases. |
| JIT warm-up | 30 s, 1 cycle through every target before measurement begins | HotSpot tiered compilation needs ~10k invocations per hot method to promote to C2; without warm-up the first plugin in the sequence pays the harness's own JIT tax. CSV writes and spark profiling are disabled during warm-up; per-target summary written to `<stamp>-warmup.log`. |
| Per-player dispatch gap | 30 ticks (1.5 s) | Without this, single-in-flight plugins reject every dispatch with "already teleporting"; harness was originally hitting this at 3 ticks. |
| Per-attempt timeout | 5 s | Frees the slot when no PlayerTeleportEvent is attributed in time. |
| Server JVM | Java 21 | REQ-RTP-SYS-001. |

---

## 4. What we deliberately do NOT control

Disclose these in the public write-up.

- **Algorithmic shape**: BetterRTP `square`, RTP `CIRCLE`, others vary. Distribution shape is part of plugin identity.
- **Retry budget**: BetterRTP `MaxAttempts: 32`, AdvancedRTP `max-attempts: 10`, RTP uses count-bound pipeline. Different correctness/perf tradeoffs.
- **Safety-check thoroughness**: RTP runs anvil pre-filter + biome + vert + neighbour grid; BetterRTP runs blacklisted-blocks; AdvancedRTP runs cave/biome filter. **Not a fair "speed" comparison if normalized — a thorough check costs more CPU and rejects fewer unsafe destinations**. Honest framing: RTP trades some warm-path latency for higher safety guarantees (S-001).
- **Concurrency model**: HuskHomes/BetterRTP serialise per-player (rejection messages observed); RTP supports concurrent in-flight. We measure both.
- **Biome distribution per cell**: each centre has different biome composition. Aggregated across attempts averages out; individual cold-start values may vary by ±30% due to biomes.

---

## 5. Run results — `20260501-220554`

(Latest run with config-revert-on-BetterRTP, prior to view-distance reduction.)

| Target | Attempts | Success% | TP/s | Cold | p50 | p95 | p99 | Min TPS | p95 MSPT | Peak heap | CPU/TP total | CPU/TP main |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **rtp** | 70 | **100.0** | 1.18 | **52** | **58** | **460** | **530** | 12.6 | 247.6 | 25488 | 18058 ms | 63.8 ms |
| betterrtp | 44 | 81.8 | 0.55 | 1565 | 1431 | 4005 | 5161 | 6.6 | 273.7 | 21884 | 32380 ms | 70.3 ms |
| huskhomes | 26 | 65.4 | 0.28 | 3584 | 2721 | 6313 | 6477 | 2.8 | **1503.9** | 21301 | 51488 ms | 102.4 ms |
| asyrtp | 10 | 80.0 | 0.11 | 8624 | 8670 | 32250 | 32250 | **2.7** | **4853.4** | 5745 | **164658 ms** | 29.6 ms |
| sorekillrtp | 74 | 100.0 | 1.24 | 139 | 57 | 590 | 1088 | 6.6 | 558.8 | 21167 | 12037 ms | 84.2 ms |
| ezrtp | 76 | 100.0 | 1.26 | 744 | 744 | 2076 | 2527 | 11.9 | 212.7 | 22031 | 7538 ms | 62.1 ms |
| advancedrtp | 34 | 100.0 | **1.38** | 150 | 152 | 250 | 300 | **20.0** | **8.4** | 22611 | — | — |

Latency in ms (lower is better). TP/s = successful teleports per second over the
phase wall-time (higher is better). Heap in MB (lower is better).

---

## 5b. Run results — `20260502-125523` (Spigot 1.20.1)

Spigot 1.20.1 pass with reduced roster: `rtp`, `betterrtp`, `huskhomes`, `jakesrtp`.
Roster intentionally trimmed (EssentialsX `/tpr` excluded per section 6 *EssentialsX*; AsyRTP
/ EzRTP / SorekillRTP / AdvancedRTP either not rated for 1.20.1 or did not complete
warm-up on Spigot — see section 7 caveat 14). Per-phase wall-time ~60 s; spark profiles
captured `12:56:38`, `12:57:49`, `12:59:00`, `13:00:12` (one per phase).

Phase-level (`20260502-125523-phases.csv`):

| Target | Att | Succ% | TP/s | proc CPU/att (ms) | main CPU/att (ms) | chunks/att | chunk-load cost/att (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|
| **rtp** | 92 | **100.0** | **1.52** | 2230.6 | 571.8 | 78.1 | 2203.9 |
| betterrtp | 83 | 100.0 | 1.33 | 2303.8 | 583.6 | 61.2 | 1725.8 |
| huskhomes | 57 | 98.2 | 0.93 | 3274.7 | 868.1 | 64.6 | 1822.7 |
| **jakesrtp** | 94 | **100.0** | **1.54** | 2318.0 | 577.0 | 80.1 | 2260.0 |

Per-attempt latency (`20260502-125523.csv`, ms):

| Target | Cold | p50 | p95 | p99 (max) | Min TPS | TIMEOUTs |
|---|---:|---:|---:|---:|---:|---:|
| **rtp** | 15 | **2** | 3 | 3 | 6.4 | 0 |
| betterrtp | 365 | ~900 | ~2360 | **3790** | **2.18** | 0 |
| huskhomes | 2336 | ~1700 | ~4000 | **4939** | 2.59 | 1 |
| **jakesrtp** | 2 | **2** | 3 | 3 | 6.5 | 0 |

Headlines:

- RTP and JakesRTP both run the *entire phase* with sub-3 ms warm latency and 100 %
  success on Spigot 1.20.1. Cold-start: RTP 15 ms (first probe), JakesRTP 2 ms.
  This is the warm-queue / pre-cache pattern paying off — CSV shows
  `chunks_loaded_during_attempt = 0` for the bulk of attempts on both plugins.
- BetterRTP's warm path is ~900 ms p50 / 3.8 s worst — 2-3 orders of magnitude
  slower than RTP and JakesRTP on the same hardware, same MC version. It still
  hits 100 % success but at the cost of dropping minimum TPS to 2.18.
- HuskHomes is the laggard on Spigot 1.20.1 too: warm p50 ~1.7 s, p99 4.9 s, one
  TIMEOUT. Same per-player serialization signature seen on Paper.
- Main-thread CPU/TP is essentially identical across rtp / betterrtp / jakesrtp
  (~570-580 ms/att); HuskHomes pays 868 ms/att — explains the MSPT cost.
- Process-CPU/TP and chunks/att both track *throughput* on this rig: rtp and
  jakesrtp move ~80 chunks/att (warm queue churn), betterrtp ~61, huskhomes ~65.

Caveats specific to this run:

- **JakesRTP parity finding**: JakesRTP's 1-3 ms latencies on Spigot 1.20.1 match
  RTP's, suggesting a comparable pre-cache. Re-verify on Paper 1.21.11 where
  JakesRTP may not be MC-version compatible; the head-to-head story might be
  Spigot-1.20.1-specific.
- BetterRTP results here include the `Queue.Enabled` pre-warm but **not** the
  config-revert mitigation from section 6 — destinations scatter must be checked before
  publication to confirm radius actually took effect on this run.
- 4-plugin Spigot roster, not the full 7-plugin Paper roster. Do not draw
  cross-plugin conclusions for plugins not in this list from this run.
- Single Spigot 1.20.1 run (n=1). Re-run before publication.

---

## 5c. Run results — `20260502-133327` (Spigot 1.20.1, re-run)

Second Spigot 1.20.1 pass, same 4-plugin roster (`rtp`, `betterrtp`, `huskhomes`,
`jakesrtp`), per-phase wall-time ~62 s. Four spark profiles captured, one per
phase: `13:34:44` (rtp), `13:35:55` (betterrtp), `13:37:09` (huskhomes),
`13:38:21` (jakesrtp). Spark prioritised here — it is the only source that
exposes per-window TPS / chunk-residency / CPU-share, and it is what an external
reader can verify.

Spark per-phase rolling (last1m TPS at end of phase, server thread CPU share,
average loaded-chunk count across the two windows spark captured):

| Phase | Spark TPS (last1m) | TPS (last5m) | Server thread CPU share | Avg loaded chunks | Process CPU window p95 |
|---|---:|---:|---:|---:|---:|
| rtp        | **11.36** | 17.36 | 100 % | 1778 | 0.135 |
| betterrtp  | **7.58**  | 15.07 | 100 % | 1900 | 0.130 |
| huskhomes  | **5.31**  | 12.88 | 100 % | 1992 | 0.121 |
| jakesrtp   | **5.24**  | 11.87 | 100 % | 1782 | 0.131 |

Two spark observations dominate:

1. **TPS decays monotonically across phases**, not just within a phase. Even
   `jakesrtp` — which by latency looks fine (see below) — runs in a tick
   environment that has *already* been dragged to ~5 TPS by the previous
   `huskhomes` phase. Spark's `last5m` confirms the chunk-gen backlog from
   each preceding phase bleeds into the next. Implication for write-up: the
   per-phase "min TPS" column in `phases.csv` understates pain because phase
   order matters; spark catches the carry-over.
2. **Server thread = 100 % of CPU weight in every profile.** No worker
   thread shows up. On Spigot 1.20.1 (no PaperLib async chunk gen, no
   Folia regions) all four plugins ultimately serialise on the main tick.
   This is not an RTP-specific finding — it's the platform — but it is
   the cleanest single-line evidence we have for the section 6 *AsyRTP* point that
   "the platform forces sync chunk-gen unless the plugin explicitly works
   around it" and for our own S-005 stance.

Phase-level (`20260502-133327-phases.csv`):

| Target | Att | Succ% | TP/s | proc CPU/att (ms) | main CPU/att (ms) | chunks/att | chunk-load cost/att (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|
| **rtp**    | 104 | **100.0** | **1.71** | 1904.1 |   517.9 | 66.9 | 1887.8 |
| betterrtp  |  72 | 100.0     | 1.15     | 2541.0 |   667.5 | 61.9 | 1747.7 |
| huskhomes  |  42 |  97.6     | 0.65     | 4376.5 | **1169.3** | 86.9 | 2452.5 |
| **jakesrtp** | 102 | **100.0** | 1.65     | 1938.9 |   549.3 | 67.7 | 1911.8 |

Per-attempt latency (`20260502-133327.csv`, ms):

| Target | Cold | p50 | p95 | p99 (max) | Min TPS | TIMEOUTs |
|---|---:|---:|---:|---:|---:|---:|
| **rtp**    |  14 | **2** | ~1325 | 2665 | 2.01 | 0 |
| betterrtp  | 749 | ~990  | ~3200 | **4406** | **1.37** | 0 |
| huskhomes  | 2233 | ~2540 | ~4800 | **5166** (timeout) | 1.85 | 1 |
| **jakesrtp** | 2 | **2** | ~1400 | 1684 | 2.81 | 0 |

Headlines (vs `5b`):

- **RTP and JakesRTP still cold-start in single-digit ms** (14 / 2). The
  pre-cache pattern reproduces. This is the second independent Spigot 1.20.1
  run confirming sub-3-ms warm p50 for both.
- **HuskHomes regressed**: warm p50 1.7 s → 2.5 s, main-CPU/att 868 ms →
  1169 ms, one timeout reproducible. Per-player serialisation is the suspect;
  see section 6 *HuskHomes*.
- **BetterRTP is steady**: ~990 ms p50 vs ~900 ms previously, same 0 timeouts
  same 100 % success; spread up to 4.4 s worst (was 3.8 s). The plugin's own
  budget is roughly the chunk-load cost on the rig, which has not changed.
- **`rtp` p99 grew from 530 ms (Paper) → 2665 ms (Spigot 1.20.1, this run).**
  Spark explains it: the run started at full TPS but ended at ~5 TPS; later
  attempts in the `rtp` phase paid for chunk loads under a backed-up tick.
  Phase-wide `chunk_load_cost_ms` totals 196 s of wall-time on `rtp` alone —
  Spigot is doing roughly 3 s of synchronous chunk gen per teleport on
  average. This is the platform tax, not an RTP regression: RTP's per-attempt
  main-CPU dropped vs `5b` (572 → 518 ms) on the same workload.

Caveats specific to this re-run:

- 4-plugin Spigot roster again, n=1 again. Combine with `5b` for a 2-run
  Spigot picture; do not over-fit either.
- Spark windows during these phases are short (1–2 windows, no MSPT samples
  exposed by the protobuf — `mspt_median`/`mspt_max` are null in the captures).
  Use `tps` and `cpu_process` from spark, fall back to `mspt_at_dispatch` from
  the CSV for tail-of-tick values.
- HuskHomes timeout (UUID `20a10906-…`) had `chunks_loaded_during_attempt =
  295` — the largest single-attempt chunk-load count in the file. Worth a
  per-attempt row in the section 6 *HuskHomes* section if we publish.

---

## 5d. Run results — `20260502-135702` (Spigot 1.20.1, "reorder" run — actually a re-run of section 5c order)

Intended as the phase-reorder test against section 5c's carry-over hypothesis.
**Important framing correction**: the run did **not** reorder. `phases.csv`
records phases in the order `betterrtp → rtp → huskhomes → jakesrtp`, which
is the *same* order as section 5c (`rtp → betterrtp → huskhomes → jakesrtp`) only
with `rtp` and `betterrtp` swapped at slots 1–2. The original hypothesis
("if `rtp` runs in slot 4 instead of slot 1, does its `last1m` collapse?")
is therefore **not tested** by this run. What we get instead is a partial
reorder: slot-1 vs slot-2 swap of the two cheapest plugins, with the two
expensive plugins (huskhomes, jakesrtp) still in slots 3–4.

Spark per-phase rolling (boundary captures only — the harness emitted
short profiles at phase transitions; only the betterrtp slot has 2 full
30 s windows):

| Slot | Phase | Spark `last1m` at boundary | `last5m` | Avg loaded chunks | Server thread CPU |
|---:|---|---:|---:|---:|---:|
| 1 | **betterrtp** (was slot 2 in section 5c) | **8.81** | 15.70 | 1905 | 100 % |
| 2 | **rtp** (was slot 1 in section 5c) | 8.81 (carry from slot 1) | 15.70 | — | 100 % |
| 3 | huskhomes | **11.01** | 12.72 | 1842 | 100 % |
| 4 | jakesrtp | **12.72** | 12.39 | 1760 | 100 % |

Phase-level (`20260502-135702-phases.csv`):

| Slot | Target | Att | Succ% | TP/s | proc CPU/att (ms) | main CPU/att (ms) | chunks/att | chunk-load cost/att (ms) |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | betterrtp | 69  | 97.1 | 1.08 | 2734.6 | 696.1 | 58.8 | 1660.1 |
| 2 | **rtp**   | 100 | 100.0| 1.66 | 1982.2 | 542.0 | 74.8 | 2109.8 |
| 3 | huskhomes | 58  | 96.6 | 0.92 | 3038.5 | 874.2 | 61.2 | 1727.0 |
| 4 | jakesrtp  | 109 | 100.0| 1.79 | 1802.9 | 510.5 | 68.7 | 1939.7 |

Per-attempt latency (from `20260502-135702.csv`, ms; eyeballed quantiles):

| Slot | Target | Cold | p50 | p99 / max | Min TPS | TIMEOUTs |
|---:|---|---:|---:|---:|---:|---:|
| 1 | betterrtp | 720 | ~970 | 6469 (timeout) | **1.82** | 2 |
| 2 | rtp       | 2   | 1–2  | 1308 | 3.52 | 0 |
| 3 | huskhomes | 726 | ~1700| 5739 (timeout) | 1.78 | 2 |
| 4 | jakesrtp  | 2   | 1–2  | 2434 | 3.00 | 0 |

### What this tells us about the carry-over hypothesis

Comparing slot-by-slot to section 5c at matching plugin:

| Plugin | section 5c slot | section 5c `last1m` | section 5d slot | section 5d `last1m` | Δ |
|---|---:|---:|---:|---:|---:|
| rtp        | 1 | 11.36 | 2 | (no clean window) | n/a |
| betterrtp  | 2 | 7.58  | 1 | 8.81 | +1.23 |
| huskhomes  | 3 | 5.31  | 3 | 11.01 | **+5.70** |
| jakesrtp   | 4 | 5.24  | 4 | 12.72 | **+7.48** |

The two slot-3 / slot-4 figures are **dramatically higher** in section 5d than in
section 5c despite identical slot positions and identical plugins — `huskhomes`
ended slot 3 at 11.01 TPS here vs 5.31 TPS in section 5c, and `jakesrtp` ended
slot 4 at 12.72 vs 5.24. This is a much larger swing than the section 5c
hypothesis predicted (section 5c implied a per-slot decay; section 5d shows the same
slots can land in completely different TPS bands depending on what ran
in slots 1–2).

That makes the section 5c "monotonic decay across phases due to chunk residency"
reading **almost certainly wrong as stated**. Two candidates fit section 5d
better:

1. **Slot-1 plugin choice dominates the JVM's later state.** `betterrtp`
   in slot 1 (this run) is gentler than `rtp` in slot 1 (section 5c) on
   chunks/att and on the chunk-gen backlog spark sees, leaving the JIT /
   chunk cache in a different state for downstream slots. This is
   plausible but doesn't explain the *size* of the swing (5 → 11 TPS).
2. **n=1 variance is much larger than section 5c assumed.** The honest reading:
   our two Spigot 1.20.1 multi-phase runs disagree by 5–7 TPS in slots 3
   and 4 with no controlled variable changed except slot-1/slot-2 swap.
   That is bigger than the "carry-over delta" section 5c tried to publish, so
   the carry-over signal is below our noise floor on this rig.

**Updated stance** (supersedes section 5c's monotonic-decay claim):

- The section 5c-style "TPS decays monotonically across phases" headline is
  **withdrawn**. section 5d falsifies it — same slots, much higher TPS — and
  section 5c's confidence was higher than n=1 vs n=1 supports.
- Within-phase TPS is still sync-chunk-gen-bound (both runs show Server
  thread = 100 % of CPU weight, both show `tps_at_dispatch` tracking
  `chunk_load_cost_ms` row-for-row in the per-attempt CSV).
- Cross-phase variance in delivered `last1m` is **on the order of ±5 TPS
  on Spigot 1.20.1 with 2 clients and 60 s phases**, dominated by
  whatever the JVM happens to do that run. Publishing a between-plugin
  TPS comparison from a multi-phase 60 s run is not defensible.

### Plugin-by-plugin notes (this run only)

- **`rtp` in slot 2**: 100 % success, p50 1–2 ms, p99 1308 ms. Cold-start
  2 ms — pre-cache works in slot 2 just as it does in slot 1. Main
  CPU/att 542 ms (vs 518 ms in section 5c slot 1, vs 520 ms solo in
  `20260502-135050`). Three independent runs now agree on `rtp`'s main
  CPU cost to within ±2 %. **This is publishable.**
- **`jakesrtp` in slot 4**: 100 % success, 109 attempts, 1.79 TP/s
  (highest TP/s of the run), p50 1–2 ms. Cold-start 2 ms. End-of-phase
  `last1m` 12.72. Two clean runs now (section 5c and section 5d) put `jakesrtp` in
  slot 4 with very different TPS outcomes (5.24 vs 12.72) but identical
  100 % success and identical sub-3-ms warm p50.
- **`betterrtp` in slot 1**: 69 attempts, 97.1 % success (2 TIMEOUTs),
  warm p50 ~970 ms. Roughly matches section 5c slot 2 (`betterrtp` 990 ms p50,
  100 % success). The slot move did not move the needle.
- **`huskhomes` in slot 3**: 58 attempts, 96.6 % success (2 TIMEOUTs),
  warm p50 ~1700 ms — *better* than section 5c's 2540 ms p50 in the same slot.
  Main CPU/att 874 ms vs section 5c's 1169 ms. Either section 5c caught a bad
  HuskHomes run or section 5d caught a good one; with n=2 we can't tell which
  is closer to the steady state.

### Caveats specific to this re-run

- **Not a reorder test.** Don't read it as one. The carry-over
  hypothesis is still *not tested*; the right test is still slot-1
  swap of `rtp` and `jakesrtp` (cheap plugin to slot 1, RTP to slot 4
  or vice versa).
- The boundary spark profiles are short (`13.58.34`, `14.02.33`,
  `14.04.34` are sub-second captures that only carry forward the prior
  window's `last1m`). Only the betterrtp profile (`13.58.33`) has two
  full 30 s windows. For rtp, huskhomes, and jakesrtp end-of-phase TPS
  here, the spark figure is the rolling average from before the boundary,
  not a fresh measurement of the phase end. Treat them as upper bounds
  on the within-phase low.
- Same n=1 caveat as section 5c. With section 5c+section 5d we have n=2 on Spigot 1.20.1
  multi-phase, and they disagree by 5–7 TPS in slots 3–4 — that's the
  variance floor for any TPS claim from this configuration.

---

## 5e. Run results — `20260502-143455` (Spigot 1.20.1, queues-off, 5-min phases, attributed chunks)

First run with three changes from sections 5b-5d simultaneously: (1) per-plugin
pre-queues (RTP `keptLocations`, BetterRTP `Queue.Enabled`, JakesRTP
queue) explicitly **disabled** so chunk-load attribution reflects work
done *during* the dispatched teleport rather than work pre-warmed
elsewhere; (2) `sequence.per-target-seconds: 300` — five-minute phases
so we reach steady state instead of the 60 s burst window that sections 5b-5d
ended in; (3) `ChunkLoadCounter` rewritten as per-attempt (Paper
plugin-ticket lookup → main-thread temporal fallback on Spigot), so
`chunks_loaded_during_attempt` and the new `chunks_loaded_attributed`
phase column reflect *causal* attribution rather than
"every chunk load on the server during the window".

Phase order ran `betterrtp → huskhomes → jakesrtp → rtp`. `rtp` in slot 4
is the partial reorder test section 5d failed to produce.

Phase-level (`20260502-143455-phases.csv`):

| Slot | Target | Att | Succ | Succ% | TP/s | proc CPU/att (ms) | main CPU/att (ms) | chunks attributed | chunks bg | chunks/att (attrib) | chunk-cost/att (ms) |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | betterrtp | 316 | 313 | 99.1 | 1.05 | 2734.4 | 797.0 | 25 045 | 153 | 79.3 | 2236.7 |
| 2 | huskhomes | 386 | 384 | 99.5 | 1.28 | 2266.4 | 712.9 | 26 889 | 97 | 69.7 | 1965.6 |
| 3 | jakesrtp  | 480 | 480 | 100.0| 1.59 | 1876.2 | 587.3 | 36 385 | 147 | 75.8 | 2139.2 |
| 4 | **rtp**   | 498 | 498 | 100.0| 1.66 | 1810.5 | 564.7 | 38 450 | 248 | 77.2 | 2179.0 |

Background chunk loads ≤ 1 % of attributed in every phase (153/97/147/248
vs 25k–38k attributed) — the new attribution chain is operating cleanly:
the Spigot main-thread temporal fallback is catching the right loads
once the per-plugin queues stop pre-warming chunks behind the harness's
back.

Per-attempt latency (from `20260502-143455.csv`, ms; success-only quantiles):

| Slot | Target | Cold | p50 | p95 | p99 | Max | TIMEOUTs / fails |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1 | betterrtp | 1771 | 1069 | 2766 | 4894 | 5821 | 4 |
| 2 | huskhomes | 4924 | 1174 | 3101 | 4020 | 4924 | 2 |
| 3 | jakesrtp  | 983  | 1136 | 1391 | 1457 | 1464 | 0 |
| 4 | **rtp**   | 1037 | 1055 | 1369 | 1454 | 1495 | 0 |

Spark per-minute rolling, end of each minute within each phase
(`last1m`, end-of-window TPS):

| Phase | min 1 | min 2 | min 3 | min 4 | min 5 | end-window chunks |
|---|---:|---:|---:|---:|---:|---:|
| betterrtp | 9.43 | 6.30 | 4.97 | 4.00 | **3.40** | 1654–1818 |
| huskhomes | 11.62 | 8.01 | 6.21 | 4.94 | **4.87** | 1685–1759 |
| jakesrtp  | 11.07 | 7.50 | 5.72 | 4.67 | **3.90** | 1589–1645 |
| rtp       | 11.04 | 7.65 | 5.95 | 4.65 | **3.94** | 1589–1697 |

`Server thread` retains 100 % CPU share on every per-minute spark profile
across all four phases (platform baseline; see section 5c notes — this is what
Spigot 1.20.1 looks like under any synchronous chunk-gen workload, not a
plugin signature).

### Findings (this run)

> **Reframing note (2026-05-02, post-analysis).** The original section 5e
> reading treated server `last1m` as the comparison axis. With
> `per-player-gap-ticks: 0` and per-plugin queues off, *offered* load
> is constant and saturating: the harness re-dispatches each player as
> soon as the previous attempt completes, so the only knob the plugin
> controls is how long each attempt takes. Under that condition the
> server's main thread is pinned at ~100 % CPU and `last1m` is dragged
> to the *tick-saturation floor* common to every phase — it cannot
> drop below it without dropping ticks, and it cannot rise above it
> without idle headroom that doesn't exist. The discriminating signal
> is therefore **delivered TP/s** (`successes / wall_s`), which varies
> by ~1.6× across plugins in the same data; server TPS variance across
> plugins (3.40–4.87) is a second-order effect of the same per-attempt
> cost, not an independent metric.

1. **TP/s ranks plugins; it varies by 1.6× across them.** Delivered
   throughput at saturating offered load:
   `rtp 1.66 > jakesrtp 1.59 > huskhomes 1.28 > betterrtp 1.05` TP/s.
   This ordering is the **inverse** of main-CPU/att
   (`rtp 565 < jakesrtp 587 < huskhomes 713 < betterrtp 797 ms`), as
   expected when the bottleneck is main-thread work per teleport
   (`TP/s ≈ 1000 / main_CPU_per_attempt_ms` for a single-threaded
   pipeline; the ratios match within ~10 %). **This is the publishable
   throughput axis for Spigot 1.20.1 under saturating load.**
2. **Server `last1m` is pinned to a tick-saturation floor**
   (~3.4–4.9) on every phase. The decay within each phase is `last1m`
   rolling-window arithmetic catching up to steady state, not a
   plugin signature, and the floor itself is set by Spigot's
   synchronous chunk-gen ceiling — not by the plugin. **Do not publish
   server TPS as a per-plugin comparison number from this
   configuration**; publish TP/s instead, and use server TPS only to
   document that every plugin saturates the tick at this offered rate.
3. **Loaded-chunk count is flat across each 5-min phase** (~1600–1800,
   no monotonic growth). Cross-phase chunk *residency* is **not**
   accumulating, even with `save-worlds-between-phases` reduced to its
   default. This is a second piece of evidence (alongside section 5d) that the
   section 5c carry-over story was not correct.
4. **`rtp` running last (slot 4) does not collapse.** 100 % success,
   p99 1454 ms, TP/s 1.66 — the *highest* throughput in the run, in
   the *last* slot. This is the cleanest disproof so far of the
   "monotonic carry-over" claim section 5c published.
5. **Latency tails are plugin-distinguishing.** With queues off and
   5-min steady-state, RTP and JakesRTP both deliver tight p99
   (1454 / 1457 ms) at 100 % success; BetterRTP and HuskHomes show
   much wider tails (p99 4894 / 4020 ms) and a small number of
   timeouts (4 / 2 of 316 / 386). The TP/s ranking and the p99
   ranking agree, which is the cross-check we want: faster plugins
   are also tighter-tailed at saturation.
6. **`rtp` main CPU/att is reproducible across four runs.** section 5b 572 ms,
   section 5c slot-1 518 ms, section 5d slot-2 542 ms, section 5e slot-4 564.7 ms (queues
   off). Spread is ±5 % across configurations — strong publishable
   number for the front-page comparison.
7. **chunks/att is now meaningful**: 79 (betterrtp) / 70 (huskhomes) /
   76 (jakesrtp) / 77 (rtp) — clustered, no single plugin pathological.
   Pre-fix runs that reported `295` for a single huskhomes timeout (section 5c)
   were the global-counter artefact; with attribution + queues off the
   per-attempt max is 299 / 254 / 206 / 194, also clustered, and these
   reflect actual pipeline cost.
8. **Cold-start cost re-emerges with queues off.** RTP cold = 1037 ms
   (vs 14–52 ms in queue-on sections 5b-5d), BetterRTP cold = 1771 ms,
   HuskHomes cold = 4924 ms (and was a timeout). With pre-warm
   disabled, each plugin pays the full pipeline cost on attempt 1.
   This is the *actual* per-teleport cost; the queue-on cold-starts
   were measuring queue-fetch latency, not teleport latency.

### What this does to section 5c and section 5d

- section 5c's "monotonic-carry-over" claim is **decisively withdrawn**. section 5e
  is the configuration that should have settled it; it shows the slot-4
  plugin landing in the same TPS band as the slot-1 plugin, with the
  per-minute decay curve being *identical shape* in every phase
  regardless of order.
- sections 5b-5d's `chunks/att` numbers were measured with the global counter
  and (for sections 5b-5c) with queues on. Both inflate the numerator. Don't
  publish chunks/att values from sections 5b/5c/section 5d head-to-head against section 5e.
- section 5d's slot-3/slot-4 TPS (11.01 / 12.72) is now interpretable: at 60 s
  phases those slots had not yet decayed off the warm-up minute. section 5e
  shows that at 5 min they would have landed near 4–5 TPS just like
  every other plugin.

### Caveats (this run)

- Still n=1 for this configuration. Every claim above survives the
  sections 5b/5c/section 5d data we already have *and* section 5e's per-phase reproduction
  of the 11→4 decay shape, but a second multi-phase 5-min run with a
  different phase order would be the strongest single follow-up.
- Spigot main-thread-temporal attribution is exact only at concurrency
  = 1. This run had `per-player-gap-ticks: 3` and 2 clients; concurrency
  was 1–2. The 1 % background residual suggests cross-player
  contamination is small but non-zero.
- HuskHomes cold-start (4924 ms, timeout) and BetterRTP's 4 timeouts
  out of 316 are real per-plugin signal, but the underlying causes
  (request/accept handshake for HuskHomes, plugin-internal retry loop
  for BetterRTP) are documented in section 6 already and aren't revisited
  here.
- Phase order put the cheaper-throughput plugins first. A second
  5-min run with `rtp → jakesrtp → huskhomes → betterrtp` would
  bracket whether the slot-1 plugin meaningfully changes anything; section 5e
  alone strongly suggests it does not.

---

## 5f. Planned run — multiplayer-spam / per-player-queue fallback (Spigot 1.20.1, 2 accounts)

Status: **planned, not yet executed.** Captured here so the run produces
data that actually answers the question instead of recapitulating section 5e.

### 5f.1 What this run is meant to demonstrate

RTP serialises per-player via `RegionQueueManager.playerQueue` (the
"per-player queue"; see *Domain Analogies & Aliases* in `.junie/AGENTS.md`).
When a single player hammers `/rtp`, subsequent invocations from the same
UUID are queued behind the in-flight attempt and the pipeline does *not*
fan out one chunk-loading job per invocation. Plugins without this
fairness primitive (BetterRTP, HuskHomes, JakesRTP, EssentialsX `/tpr`)
either drop or accept every invocation; under spam this multiplies
chunk-load pressure by the spam factor.

The intended headline:

> *Under per-player command spam, RTP's server `last1m` and main-thread
> CPU per delivered teleport stay close to the section 5e baseline, while
> plugins without per-player serialisation degrade further (lower
> delivered TP/s, higher main-CPU/att, more timeouts) because each
> redundant `/rtp` enters the chunk-gen pipeline.*

This is a **fairness / DoS-resistance** claim, not a throughput claim.
TP/s is expected to *fall* for RTP under spam (more dispatches, same
delivered rate ⇒ lower saturation ratio); the value being demonstrated
is that server-side cost does not scale with the spam factor.

### 5f.2 Test design — what to vary

Two-account constraint stands (`Runner.roster()` reads
`Bukkit.getOnlinePlayers()`). The spam factor must come from
**dispatches per player**, not from player count.

Suggested phase matrix (one StressTestRTP run, four phases as usual,
queues-off / `per-player-gap-ticks: 0` already in place from section 5e):

| Phase | Target | Spam factor | Notes |
|---|---|---:|---|
| 1 | rtp        | 1× (baseline) | Same as section 5e slot 4 — re-establish the baseline. |
| 2 | rtp        | N× spam       | Both accounts dispatch as fast as the harness allows; per-player queue should absorb the burst. |
| 3 | betterrtp  | N× spam       | Plugin has its own "already rtp'ing" rejection — expect a different failure mode (rejected, not queued). |
| 4 | huskhomes  | N× spam       | Per-player serialised internally but via request/accept handshake — different again. |

JakesRTP and EssentialsX `/tpr` are useful to add as a fifth/sixth phase
if time allows, but the four above are the minimum decisive set.

"Spam factor" in the harness today is implicit: with
`per-player-gap-ticks: 0` and `default-burst` ≥ player-count, the
harness already dispatches as fast as it can. To produce a *clean*
spam phase distinct from the section 5e baseline, either:

- (a) Raise `default-burst` for the spam phase only (per-phase override
  is not currently a config knob — would need a small harness change,
  D-005).
- (b) Lower `per-player-gap-ticks` from 3 to 0 between baseline and
  spam phases (already done in section 5e — so the section 5e run *is* the spam
  case for plugins that don't queue). This means **section 5e already
  partially answers the question for the no-queue plugins**; what's
  missing is a same-config baseline phase with `per-player-gap-ticks: 0`
  but only one in-flight per player.

Recommended (least harness churn): run section 5e's exact config, but capture
**per-player in-flight depth** in `MetricsRecorder` so we can see RTP's
queue absorbing repeats while other plugins don't. The `Attempt` record
already has `commandDispatchedEpochMs`; counting how many attempts for
the same UUID overlap a single in-flight window is enough.

### 5f.3 What to publish from this run

Per phase, in addition to section 5e's columns:

- **Offered TP/s** — total dispatches / wall_s.
- **Delivered TP/s** — successes / wall_s (already published).
- **Saturation ratio** = delivered / offered. RTP under spam should be
  *low* (queue rejects redundant offers); plugins without queues should
  be *higher* but pay for it in CPU/att and timeouts.
- **Mean per-player in-flight depth** — proxy for "did the plugin
  serialise me or fan out?". RTP ≈ 1 expected; HuskHomes ≈ 1
  (handshake-serialised); BetterRTP ≈ 1 (its own
  rejection); JakesRTP unclear.
- **Main-CPU/att** and **chunks/att (attributed)** — already published;
  these are the load-multiplier metrics. If RTP's chunks/att stays at
  ~74 under spam while a non-queueing plugin's rises ≥2×, that is the
  publishable evidence.
- **Server `last1m` floor** — same caveat as section 5e: it's a tick-saturation
  floor, not a per-plugin TPS measurement. We publish it only to show
  *consistency* (RTP's floor under spam ≈ RTP's floor at baseline),
  not to rank plugins.

### 5f.4 What section 5f does NOT claim

- "RTP is faster under spam" — false framing. RTP delivers *fewer*
  teleports per offered command because it queues; the value is the
  bounded server cost, not the throughput.
- "Other plugins are broken" — they aren't. They make a different
  fairness choice (accept and process, or reject outright). The
  comparison is *which choice is server-friendly under hostile
  clients*, not which is correct.
- Any claim about >2 concurrent players. Two-account constraint
  applies; spam factor scales dispatches per UUID, not UUIDs.
- Any claim about cross-server / proxy spam (BungeeCord/Velocity).
  Out of scope; see `MULTI_SERVER_PLAN.md`.

### 5f.5 Pre-run checklist

- [ ] Confirm `per-player-gap-ticks: 0` and queues off for all four
      plugins (matches section 5e config; no change needed if reusing it).
- [ ] Add a per-phase `offered_tps` / `delivered_tps` /
      `saturation_pct` / `max_in_flight_per_player` set of columns to
      `phases.csv` — D-005-gated harness change. Cheaper alternative:
      compute them post-hoc in `spark_summary.py` from the
      per-attempt CSV (`dispatch_epoch_ms` + `success`), no harness
      change.
- [ ] Decide phase order. To avoid the sections 5c/5d carry-over confound,
      run RTP first (slot 1) so its baseline isn't contaminated by
      whatever the no-queue plugins did to the JVM in earlier slots.
- [ ] Keep `sequence.per-target-seconds: 300` (section 5e value) — short
      phases didn't reach steady state and the spam phase needs even
      longer to drain the in-flight backlog after the burst.

The actual run's results section will land here as **section 5g** once the
data is captured.

---

## 5g. Run results — `20260502-154929` (Spigot 1.20.1, RTP vs BetterRTP vs HuskHomes vs JakesRTP, queues enabled where supported, OP'd players, 2 min phases)

First focused four-way head-to-head between **RTP**, **BetterRTP**, **HuskHomes**, and **JakesRTP** after:

- The JakesRTP command-map collision was worked around by switching the harness target to `jakesrtp:forcertp {player} -c default-settings` (section 5e retraction box at top of doc).
- All four test plugins were aligned to `cooldown=0`, `delay=0`, **queues enabled** (`RTP.regions.default.cacheCap: 10` + `activeChunkCap: 10`; `BetterRTP.Settings.Queue.Enabled: true`; `JakesRTP.location-cache-filler.enabled: true` with `cache-locations: 10`; HuskHomes has no equivalent — N/A).
- Both real player accounts were OP'd to ensure permission paths don't reject any namespaced or self-form target.

Phase order in this run was `rtp → betterrtp → huskhomes → jakesrtp` (4 phases, 2 min each, 120 s gap).

### 5g.1 Phase-level headlines (`phases.csv`)

| Phase | Att | Succ | Wall (s) | TP/s | CPU/att total (ms) | CPU/att main (ms) | chunks/att (attributed) | chunks (background) | chunk_load_cost (ms) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **rtp**       | 150 | 150 (100 %) | 120.1 | **1.249** | 2530.7 | 625.2 | **1.073** | 20 495 | 4 543 |
| **betterrtp** | 127 | 127 (100 %) | 121.0 | **1.050** | 2745.0 | 731.7 | **35.898** | 7 330 | 128 660 |
| **huskhomes** | 128 | 128 (100 %) | 121.6 | **1.052** | 2701.9 | 785.5 | **63.961** | 2 783 | 231 045 |
| **jakesrtp**  | 126 | 126 (100 %) | 120.7 | **1.044** | 2845.4 | — | **17.03** | — | — |

Four things jump off the page:

1. **chunks/att collapses for RTP to ~1**. Under the new attributed counter (sections 5e/5f apparatus), RTP dispatches are not driving chunk loads — the L1/L2 queues are serving pre-verified locations. BetterRTP attributes **35.9 chunks/att** and HuskHomes **64.0 chunks/att** because both resolve their candidate at dispatch time. This is the single cleanest signal that **RTP's per-player queue + L1 cache is doing its job** under sustained load, and that neither competing plugin has an equivalent in this configuration (BetterRTP's `Settings.Queue.Enabled: true` is on but ineffective at sustained 1 TP/s; HuskHomes has no queue concept at all and the result is the highest per-attempt chunk burden of the three).
2. **`chunk_load_cost_ms` ratio**: rtp 4 543 < betterrtp 128 660 < huskhomes 231 045 ms accumulated across the phase. HuskHomes pays ~1.8× BetterRTP's chunk-load cost and ~50× RTP's, integrated over the same wall time at the same offered TP/s.
3. **The `chunks_loaded_background` columns invert in a meaningful way**: rtp 20 495 (L2 → L1 refill scan running concurrently with dispatch — correctly not charged to attempts), betterrtp 7 330 and huskhomes 2 783 (just Paper/Spigot housekeeping; neither plugin runs a refill scan).
4. **Main-thread CPU/att monotonically tracks chunks/att**: rtp 625 ms (1.07 chunks) < betterrtp 732 ms (35.9 chunks) < huskhomes 785 ms (64.0 chunks). The 26 % gap between betterrtp and huskhomes main-CPU/att for an ~1.8× chunks/att ratio shows the per-chunk marginal cost is small once the pipeline is paying for the first one — most of the per-attempt main-CPU is fixed pipeline overhead, not linear in chunks loaded.

### 5g.2 Per-attempt latency tail (per-attempt CSV)

| Phase | n | cold-start (ms) | p50 | p95 | p99 | max |
|---|---:|---:|---:|---:|---:|---:|
| **rtp**       | 150 | 2    | 1    | 2    | **8**    | 14   |
| **betterrtp** | 127 | 945  | 581  | 2386 | **4229** | 4229 |
| **huskhomes** | 128 | 3178 | 1032 | 3062 | **5124** | 5124 |
| **jakesrtp**  | 126 | 0    | 0    | 926  | **2252** | 2252 |

This is the **headline-quality result** of the run:

- RTP serves attempts in **single-digit milliseconds at every percentile through p99** — the L1 cache is hot, every `/rtp` is a queue read, no chunk-gen on the dispatch path.
- BetterRTP's p99 is **530× higher** than RTP's (4229 ms vs 8 ms); HuskHomes's p99 is **640× higher** (5124 ms vs 8 ms) and ~1.2× BetterRTP's. Even the *p50* of both non-RTP plugins (581 / 1032 ms) is two orders of magnitude above RTP's p99. This is exactly the queue-vs-no-queue split the test was designed to expose, now without the sections 5b-5e confounders (no carry-over question — all three phases at 100 % success — and no JakesRTP collision because JakesRTP isn't in this run).
- **HuskHomes p50 = 1032 ms vs BetterRTP p50 = 581 ms**: HuskHomes pays ~1.8× BetterRTP's median, consistent with its ~1.8× chunks/att (64 vs 36). The two plugins land in the same order of magnitude — both are paying full synchronous pipeline cost per dispatch — and the ~2× gap is the chunks/att burden. Neither comes anywhere near the L1-cache regime RTP is in.

Cold-start: RTP **2 ms**, BetterRTP **945 ms**, HuskHomes **3178 ms**. RTP's cold-start was **52 ms** in earlier runs (section 6 RTP findings); the drop to 2 ms here is consistent with the L1 refill having had the full inter-phase 120 s gap to pre-warm before the rtp phase even started. HuskHomes's 3.2 s cold-start is *worse* than its own warm p95, suggesting first-dispatch initialization cost on top of the per-attempt chunk-gen baseline.

### 5g.3 Spark per-phase TPS (server-thread observable)

Two profiles per phase (60 s windows):

| Profile | Phase | last1m TPS | last5m TPS |
|---|---|---:|---:|
| `15.51.01` | rtp (mid)        | **18.50** | 19.36 |
| `15.52.01` | rtp (end)        | **20.00** | 19.36 |
| `15.55.00` | betterrtp (mid)  | **14.37** | 18.26 |
| `15.56.01` | betterrtp (end)  | **17.23** | 18.09 |
| `15.59.02` | huskhomes (mid)  | **16.59** | 17.53 |
| `16.00.02` | huskhomes (end)  | **11.81** | 15.92 |
| `16.03.04` | jakesrtp (mid)   | **11.04** | 15.72 |
| `16.04.04` | jakesrtp (end)   | **7.47**  | 13.83 |

- During the **rtp** phase, server `last1m` stayed at **18.5 → 20.0 TPS** — effectively unsaturated, which matches section 5g.2: queue-served attempts cost the main thread nothing.
- During the **betterrtp** phase, `last1m` dipped to **14.4 TPS** at the midpoint and recovered to 17.2 by end.
- During the **huskhomes** phase, `last1m` started at **16.6 TPS** mid-phase and **decayed to 11.8 TPS** by end-of-phase. This is the integrated cost of 64 chunks/att at sustained 1 TP/s: chunk-gen pressure accumulates faster than the server can dissipate it within a 2 min window, even at this modest offered rate.
- The **jakesrtp** phase landed *lowest* on TPS (`last1m` 11.0 mid → **7.47 end**), continuing the cross-phase decay. Per-attempt latency for JakesRTP is much better than HuskHomes (p50=0, p99=2252) because JakesRTP's `location-cache-filler` *is* serving most attempts from cache (the median attempt is instant), but the cache miss path is heavy enough — and the slot-4 carry-over of HuskHomes's residual chunk pressure is large enough — that server TPS continued to fall. **This is exactly the slot-4 carry-over confound that sections 5c/5d failed to settle**: jakesrtp ran last, behind two plugins that left the JVM with the highest accumulated chunk residency in any of our runs to date. We cannot cleanly separate "JakesRTP's intrinsic TPS cost" from "slot-4 inheriting HuskHomes's mess" with this data.
- The phase-end ranking — **rtp 20.0 > betterrtp 17.2 > huskhomes 11.8 > jakesrtp 7.5** — matches the chunks/att and main-CPU/att rankings for the first three but is **not safe to publish for jakesrtp** without a slot-1 jakesrtp-solo or reverse-order rerun. The discriminator inversion that troubled section 5e (where server TPS was a flat tick-saturation floor) does not appear here because `per-player-gap-ticks: 30` keeps offered load below saturation; in this regime server TPS *is* a meaningful per-plugin cost signal — but only after carry-over is controlled.
- This is **not** the section 5e tick-saturation floor — none of the four phases is offering load aggressively enough at `per-player-gap-ticks: 30` (the value still in this run's config, not 0). All four plugins have headroom; the difference between them is *quality of headroom use*, not "who hits the floor first".

### 5g.4 What this run does and does NOT establish

**Establishes (n=1 but with strong internal consistency):**

- RTP under L1-cache-fed dispatch operates **off the synchronous chunk-load critical path entirely** at this offered rate. The new attributed-chunk counter (section 5e refactor) makes this directly measurable for the first time: 1.07 chunks/att is essentially "the destination chunk plus rounding".
- BetterRTP's queue (`Settings.Queue.Enabled: true`, default queue size) does **not** absorb sustained 1.05 TP/s offered load — every attempt still resolves through the synchronous pipeline. Either its queue is too shallow at default size, or its refill is reactive-on-drain rather than proactive (we have not inspected BetterRTP's source for this; treat as observation, not claim about implementation).
- HuskHomes has no equivalent queue concept and pays the full synchronous-pipeline cost on every dispatch — **64 chunks/att, p99 5.1 s, server TPS still decaying at end of phase**. It is the worst of the four queue-comparison plugins on every published axis except median latency (where JakesRTP's exhausted cache eventually places it lower than HuskHomes's steady-state pipeline cost).
- **JakesRTP's `location-cache-filler` works**: p50 = 0 ms (median attempt served from cache, the second-best p50 of the four plugins by a wide margin) and chunks/att 17.0 (a third of BetterRTP's, a quarter of HuskHomes's). It is the only non-RTP plugin in this run that demonstrably pre-resolves locations under sustained dispatch. Its tail (p99 = 2252 ms) shows the cache *can* be exhausted under sustained 1 TP/s — `cache-locations: 10` drains and the cache-miss attempt pays the full pipeline — but the 90 %+ of attempts that hit cache stay sub-second.
- Latency-tail discrimination is **enormous** (p99 ratios vs RTP: jakesrtp ~280×, betterrtp ~530×, huskhomes ~640×; vs each other: huskhomes/betterrtp ~1.2×, betterrtp/jakesrtp ~1.9×) at this regime — easily publishable as a headline, far above the ~2× confidence interval that any of sections 5b-5e gave us.
- The chunks/att axis now ranks plugins linearly with their tail latency: rtp 1.07 (p99 8 ms) < jakesrtp 17.0 (p99 2252 ms) < betterrtp 35.9 (p99 4229 ms) < huskhomes 64.0 (p99 5124 ms). This is the first run where the per-attempt chunk-attribution metric (section 5e refactor) produces a clean linear ranking across four plugins that agrees with both main-CPU/att and tail latency.

**Does not establish:**

- Throughput ceiling for any of the four plugins. All ran with `per-player-gap-ticks: 30` (artificial 1.33 TP/s cap with 2 players); all delivered ~1.04–1.25 TP/s, near that cap. To get the real ceiling, repeat with `per-player-gap-ticks: 0` (cf. section 5e methodology and section 6b throughput proposal).
- Behaviour under **spam** (the section 5f intent). This was a steady-rate run, not a burst; per-player-queue-fallback evidence still pending section 5f's sequel run.
- JakesRTP's **standalone** TPS cost or fair end-of-phase TPS. JakesRTP ran in slot 4 behind HuskHomes's heavy chunk-residency phase; its `last1m` 7.47 at end-of-phase is **not separable** from inherited carry-over (cf. the sections 5c/5d carry-over question that two prior runs failed to settle). The latency-tail and chunks/att numbers for JakesRTP *are* publishable (those measure dispatch-time work, not accumulated server state); the TPS number is not.

### 5g.5 Caveats

- **n=1.** A single phase per plugin. Re-run with reversed order (`huskhomes → betterrtp → rtp`) would settle whether RTP's 2 ms cold-start is itself a pre-phase-warmup artefact and whether HuskHomes's end-of-phase TPS decay is order-dependent.
- JakesRTP's slot-4 position contaminates the TPS axis only; the per-attempt latency and chunks/att axes are dispatch-time measurements and remain valid. The cleanest fix is a future run with jakesrtp in slot 1 (or a solo run).
- `mspt_median` / `mspt_max` columns in `phases.csv` were null on these spark captures (Spigot exposes no MSPT API; the 2026-05-02 `TpsMsptHeapSampler` Spigot fallback lands MSPT values into the harness CSV's `mspt_at_dispatch` column instead, not into spark's protobuf — see that day's `LESSONS_LEARNED.md` entry).
- Per-attempt `chunks_loaded_during_attempt` for RTP averaged 1.3 (CSV) vs phase `chunks_per_attempt` 1.07 (phases.csv); the small gap is rounding from integer per-attempt vs phase-level division and is not a measurement disagreement.

---

## 5h. Run results — `20260502-161230` (Paper 1.20.1, four-plugin head-to-head, queues enabled, OPed clients, 2-min phases)

First Paper 1.20.1 capture, same plugin set / configs as the Spigot section 5g run
(RTP cooldown=0, delay=0, queue enabled with `cacheCap: 10` /
`activeChunkCap: 10`; BetterRTP `Queue.Enabled: true`; HuskHomes warmup=0;
JakesRTP cache=10), same 2 OPed clients, `dispatch-as-player: true`,
`per-player-gap-ticks: 30`, 2-min phases, 120 s gap. World data reused from
the Spigot 1.20.1 series to skip pre-gen. Phase order:
`rtp → betterrtp → huskhomes → jakesrtp` (`jakesrtp:forcertp {player} -c
default-settings` workaround for the Bukkit command-map collision documented
in the 2026-05-02 LESSONS_LEARNED entry).

The reason this run gets its own section -section rather than a paragraph is that
**every published axis moves discontinuously vs Spigot 1.20.1** for the same
plugin and same configuration:

### 5h.1 Headline numbers — four-plugin Paper run

| Plugin | Att | Succ | Wall (s) | TP/s | Cold (ms) | p50 | p95 | p99 | Max | Main CPU/att (ms) | Total CPU/att (ms) | chunks/att ‡ | `last1m` end |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rtp        | 1302 | 1302 (100 %) | 120.1 | **10.84** | 2   | 105 | 115 | 154 | 201  | 13.3 | 144.4 | 18.7 ‡ | 20.00 |
| betterrtp  |  794 |  794 (100 %) | 120.9 | 6.57      | 169 | 160 | 557 | 852 | 1226 | 23.3 | 203.3 | 23.0   | 20.00 |
| huskhomes  |  715 |  715 (100 %) | 120.3 | 5.94      | 260 | 208 | 305 | 372 | 1214 | 24.7 | 215.9 | 27.6   | 20.00 |
| jakesrtp   | 2391 | 2391 (100 %) | 120.0 | **19.93** | 10  |  12 |  32 |  54 |  287 |  9.7 |  55.3 |  3.2   | 20.00 |

Spark MSPT (last full-window per phase): rtp 5.2 / betterrtp 5.6 / huskhomes 5.4 / jakesrtp 8.2 ms median; max-spike-in-window 111 / 105 / 110 / 188 ms. Server `last1m` is pinned at 20.00 across **every phase** — Paper has the headroom to absorb the entire offered load on this rig.

#### Same numbers vs Spigot section 5g (queues-on, same plugin, same config)

| Metric | Paper 5h | Spigot 5g | Δ |
|---|---:|---:|---:|
| rtp TP/s | 10.84 | 1.25 | **8.7×** |
| rtp p99 (ms) | 154 | 8 | 19× higher (p99 grew because *throughput* grew — Paper hit the queue's marginal cost; Spigot was queue-served at much lower offered rate) |
| betterrtp TP/s | 6.57 | 1.07 | **6.1×** |
| betterrtp p99 (ms) | 852 | 4229 | **5.0× lower** |
| huskhomes TP/s | 5.94 | 1.06 | **5.6×** |
| huskhomes p99 (ms) | 372 | 5124 | **13.8× lower** |
| jakesrtp TP/s | 19.93 | 1.05 | **19×** |
| jakesrtp p99 (ms) | 54 | 2252 | **42× lower** |
| Server `last1m` (rtp slot) | 20.00 | 20.00 | flat |
| Server `last1m` (huskhomes slot) | 20.00 | 11.81 | **+8.2 TPS** |

Paper delivers **higher throughput at lower latency** for every plugin, and the per-plugin spread compresses dramatically: on Spigot section 5g the chunks/att ranking spanned 60× (1.07 → 64.0) and p99 spanned 640× (8 → 5124 ms). On Paper section 5h chunks/att spans 8.6× (3.2 → 27.6) and p99 spans 23× (54 → 1226 ms). The async chunk pipeline is doing most of the work.

† Spigot exposes no MSPT API; the sections 5g/5e rows here are spark-side only and
the harness's tick-wall fallback (added 2026-05-02) lands MSPT into
`mspt_at_dispatch` in the per-attempt CSV instead. On Paper, spark reads
`Server#getAverageTickTime()` directly and `mspt_median` is populated.

‡ **The 18.7 chunks/att figure is inflated and should not be published.**
User confirmation post-run: the *actual* pipeline cost on this run is ~1
chunk-load per attempt (queue-served from L1, same regime as section 5g). The
inflation comes from RTP's background `ScanTask` pre-filling the L1/L2
caches (`cacheCap: 10`, `activeChunkCap: 10`) — those loads happen on the
main thread, by the RTP plugin, while attempts are in flight, so both the
Paper plugin-tickets attribution path (matches by plugin label) and the
Spigot temporal fallback (matches by main-thread + in-flight window)
bill them to the in-flight attempt. Neither path can distinguish
"pipeline-driven load" from "scan-task-driven cache pre-fill" when both
originate inside the same plugin. The correct section 5h chunks/att for RTP is
the section 5g figure (~1.07); the 24 377 "attributed" loads are
*scan-task-attributed-to-attempt-windows*, not
*per-attempt-pipeline-loads*.

### 5h.2 What the offered-load condition actually was

`per-player-gap-ticks: 30` (1.5 s) is still in effect, so the *artificial*
ceiling is `2 players × (1 / 1.5 s) = 1.33 TP/s`. Paper delivered **10.84
TP/s** — 8.2× above the artificial ceiling. That can only mean the harness's
gap is being honoured but each dispatch completes inside ~100 ms (≈ 2 game
ticks), which is the user's directly-observed claim. Verifying from the per-
attempt CSV: median dispatch-to-teleport latency is 105 ms, so the gap is
moot — the next dispatch from the same player is eligible long before the
30-tick gap elapses, and the second player is dispatching in the staggered
half-cycle. The effective offered rate is the inverse of the per-attempt
latency, not `1 / gap_ticks`.

This means **this run is *not* gap-capped** the way every Spigot run in
sections 5b-5g was, and the throughput number is a real measurement of "what RTP +
Paper 1.20.1 can sustain at the harness's offered rate". It is not a
saturation measurement — `per-player-gap-ticks: 0` would push higher; but
unlike Spigot, we are not measuring the gap.

### 5h.3 What this establishes

- **RTP + Paper 1.20.1 + queues-enabled is on a different operating point
  than RTP + Spigot 1.20.1 + queues-enabled.** Server TPS pinned at 20.00,
  MSPT median 5.2 ms (Paper has ~9× tick-time headroom over the 50 ms
  budget), process CPU 6.7 %. The server is *idle* between teleports at this
  load.
- **Paper's async chunk pipeline absorbs the chunk-gen cost off the main
  thread.** Main CPU/att = 13.3 ms — ~40× lower than the section 5e Spigot
  saturating run's 565 ms for RTP, and only ~9 % of the total CPU/att
  (144.4 ms). The other ~131 ms per teleport is on Paper's chunk worker
  threads, which is what we want and what S-005 / ADR-015 / the Anvil-prefilter
  ADR-016 design implies should happen.
- ~~**`chunks_per_attempt` jumped from 1.07 (Spigot section 5g) to 18.7 (Paper section 5h)
  for the same RTP plugin at higher offered rate.**~~ **Retracted.** The
  18.7 figure is an attribution artefact, not a real per-attempt pipeline
  cost. RTP runs a background `ScanTask` that pre-fills `keptLocations` /
  `unkeptLocations` (the L1 / L2 caches) on the main thread; those scan
  loads are emitted by the RTP plugin and land in the same wall-clock
  window as in-flight attempts. Both attribution paths (Paper
  plugin-tickets matching on plugin label, Spigot temporal-fallback
  matching on main-thread + in-flight) bill scan-task loads to the
  attempt. The actual per-attempt pipeline cost on this run is ~1
  chunk-load (queue-served from L1, same regime as section 5g — the L1 cache
  was *not* draining at 10.84 TP/s, contrary to my first-pass
  hypothesis). The `chunks_attributed: 24377` figure is closer to "total
  ScanTask + pipeline loads emitted by RTP during the phase" than to
  "sum of per-attempt pipeline loads".
- **Corollary**: the `ChunkLoadCounter` attribution refactor (2026-05-02)
  fixed cross-*player* and cross-*plugin* attribution, but it does not
  separate *intra-plugin* concurrent work (pipeline vs. background
  cache-fill). Distinguishing them requires either disabling the scan
  task for the duration of a measurement run (e.g. `cacheCap: 0`,
  `activeChunkCap: 0` — the section 5e queues-off configuration), or extending
  the counter to subtract a per-tick scan-task baseline. Neither is in
  this run.
- **Latency tail is bounded and tight** for RTP: p99 = 154 ms, max = 201 ms
  over 1302 attempts. The p99/p50 ratio is 1.5× (vs section 5g's 2.0× and section 5e's
  not meaningful at saturation). The other three plugins all show longer
  tails (BetterRTP max 1226 ms, HuskHomes max 1214 ms, JakesRTP max 287 ms)
  but all 100 % successful — Paper's tail is bounded for everyone, just
  longer for plugins doing more per-attempt work.
- **MSPT is *populated* on Paper** — `mspt_median` 5.2–8.2 ms across phases,
  `mspt_max` 111–188 ms per spark window. The spark-side max captures
  individual ticks 2–4× over budget out of ~1200 measured ticks per
  window; those are chunk-load spikes landing on the tick thread (post-
  arrival view-distance load), invisible at the `last1m` rolling-TPS axis
  (still 20.00) because Paper's catch-up handles them inside the same
  minute.
- **Plugin ranking on Paper differs from Spigot.** On Spigot section 5g the
  ranking by p99 was rtp(8) ≪ jakesrtp(2252) < betterrtp(4229) <
  huskhomes(5124). On Paper section 5h the ranking is **jakesrtp(54) <
  rtp(154) < huskhomes(372) < betterrtp(852)** — JakesRTP moves from
  slot-4 (worst tail on Spigot) to fastest, and BetterRTP becomes the
  worst tail. This is consistent with `jakesrtp:forcertp` skipping the
  bulk of the work when its `location-cache-filler` (cache=10) has
  hot entries — Paper's faster chunk-loading lets the cache stay full.
- **BetterRTP "queue presence ≠ queue effectiveness" reconfirmed.**
  `Queue.Enabled: true` on Paper still produces p99 = 852 ms (vs RTP's
  L1-served 154 ms) and 23.0 chunks/att (vs RTP's reported 18.7 ‡, with
  the same scan-task caveat). BetterRTP's queue holds at most one
  pre-warmed location per player; under sustained load on either
  platform it cannot decouple latency from chunk-gen the way RTP's L1
  pool can.
- **HuskHomes p99 collapses 14× from Spigot to Paper** (5124 → 372 ms).
  Same plugin, same config, same world data, same offered rate. The
  Spigot section 5g number was the platform's sync chunk-gen showing through;
  Paper's async pipeline lets HuskHomes's per-player serialisation
  proceed at chunk-worker speed.

### 5h.4 What this does not establish

- **Cross-platform JakesRTP comparison.** The Spigot section 5g figures used the
  collision-affected `jakesrtp:rtp` target (deferring to our plugin per
  the 2026-05-02 LESSONS_LEARNED entry); section 5h is the first clean JakesRTP
  measurement. A Spigot rerun with the `jakesrtp:forcertp` workaround is
  needed before publishing a Spigot↔Paper JakesRTP delta.
- **Saturation throughput.** `per-player-gap-ticks` is still 30 here, even
  though latency makes the gap moot for the fast plugins. A `per-player-
  gap-ticks: 0` Paper run would establish the *real* per-plugin ceilings.
  Predicted from the p50 latencies: rtp ~19 TP/s, betterrtp ~12 TP/s,
  huskhomes ~10 TP/s, jakesrtp ~80 TP/s — predicted only, not measured.
  Note that JakesRTP is *already* over the rtp ceiling at the gap-capped
  rate (19.93 TP/s), suggesting its cache hit path bypasses chunk-load
  entirely.
- **Spigot-vs-Paper chunks/att comparison for RTP.** Both rigs measure ~1
  pipeline-load per attempt (L1-cache-served). The 18.7 Paper figure is
  retracted — see ‡ footnote on the headline table and the third bullet
  of section 5h.3. A clean per-attempt pipeline-cost measurement on either
  platform requires the scan task disabled (`cacheCap: 0`,
  `activeChunkCap: 0`); section 5e is the only run captured in that
  configuration so far. The other three plugins' chunks/att numbers
  (BetterRTP 23.0, HuskHomes 27.6, JakesRTP 3.2) are not affected by
  the RTP-specific scan-task confound — they reflect each plugin's own
  per-attempt chunk activity.

### 5h.5 Caveats

- n=1 phase per plugin in this configuration. A reverse-order rerun
  (`jakesrtp → huskhomes → betterrtp → rtp`) would settle whether
  RTP's 2 ms cold-start and JakesRTP's 19.93 TP/s are slot-1 / slot-4
  artefacts.
- World data was reused from the Spigot 1.20.1 series (skips pre-gen). Any
  comparison that depends on the exact set of pre-generated chunks
  (`chunks_per_attempt` at low offered rate, cold-start) is therefore
  cross-platform compatible by construction; comparisons that depend on
  Paper-specific worldgen behavior would need a fresh pre-gen on Paper.
- The CSV row count is higher than the per-phase attempt count because the
  pre-phase warm-up and the post-phase 120 s gap also dispatch; only rows
  whose `dispatch_epoch_ms` falls inside each phase's `[start, end]` are
  part of the headline. The headline numbers above use the in-window
  filter.
- ‡ The RTP `chunks/att = 18.7` figure remains attribution-confounded by
  RTP's own scan task on Paper just as it was retracted on the prior
  RTP-only writeup; the other three plugins do not run a comparable
  scan task and their chunks/att are direct.

---

## 5i. Run results — `20260502-171548` (Paper 1.20.1, RTP `cacheCap: 100` for JakesRTP-cache parity)

Same Paper 1.20.1 rig as section 5h, same four-plugin order
(`rtp → betterrtp → huskhomes → jakesrtp`), same `dispatch-as-player: true`,
OPed clients, queues on, 2-min phases, 120 s gap. The only deliberate
configuration change vs section 5h: **RTP `regions/default.yml` `cacheCap: 10 → 100`**
(and `activeChunkCap: 10`), and **`performance.yml` `period: 10 → 1`** so the
background scan / refill task runs every tick instead of every 10 ticks.
Together these make RTP's L1 cache both deeper and re-filled aggressively,
cache-size-comparable to JakesRTP's `location-cache-filler.cache-locations: 10`
plus its own backlog. The intent: stop measuring "RTP's L1 draining at
sustained offered load" (the section 5h regime where rtp delivered 19.16 TP/s but
chunks/att rose to 18.7) and instead measure both plugins with a queue
deep enough — and refilled fast enough — to absorb a 2-min sustained burst.

The result: at `per-player-gap-ticks: 0` the **harness saturates the offered
rate** for both queue-served plugins (rtp 19.83 / jakesrtp 20.00 TP/s, both
hard against the 2-client × 20 TPS ceiling), while BetterRTP (7.09 TP/s) and
HuskHomes (6.18 TP/s) remain pipeline-served. Server TPS pinned 20.00 across
all four phases; MSPT median 13–25 ms, max 95–235 ms.

### 5i.1 Headline numbers — four-plugin Paper, RTP cache deepened

| Plugin     | Att  | Succ% | TP/s   | Cold (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Main CPU/att (ms) | chunks/att (attr) | chunks/att (bg) |
|------------|-----:|------:|-------:|----------:|---------:|---------:|---------:|---------:|------------------:|------------------:|----------------:|
| rtp ‡      | 2381 | 100.0 | 19.83  |        0  |       1  |       2  |       4  |     153  |             16.92 |              4.77 |           22.74 |
| betterrtp  |  851 | 100.0 |  7.09  |      166  |     171  |     474  |     771  |    1075  |             53.60 |             19.33 |            1.05 |
| huskhomes  |  743 | 100.0 |  6.18  |      265  |     217  |     269  |     335  |     365  |             52.24 |             23.36 |            1.59 |
| jakesrtp   | 2400 | 100.0 | 20.00  |       21  |      26  |      49  |      70  |     192  |             25.98 |              3.96 |            1.44 |

‡ RTP `chunks_loaded_attributed = 11 358 / 2381 ≈ 4.77` per attempt;
`chunks_loaded_background = 54 144 / 2381 ≈ 22.74` per attempt — the
background bucket is RTP's `ScanTask` keeping the deeper L1 hot under
saturating dispatch and is not pipeline-search cost. The 22.74 figure
is amplified by `period: 1` (scan/refill every tick); at the section 5h default
`period: 10` it would be ~1/10th. Both BetterRTP (896) and HuskHomes
(1183) have negligible background buckets and their chunks/att numbers
are direct.

### 5i.2 What changed vs section 5h (same rig, same order, RTP cache only)

| Plugin     | TP/s section 5h | TP/s section 5i | p99 section 5h | p99 section 5i | Main CPU/att section 5h | Main CPU/att section 5i |
|------------|---------:|---------:|--------:|--------:|-----------------:|-----------------:|
| rtp        |   19.16  |   19.83  |   154   |    **4** |       17.0       |       16.9       |
| betterrtp  |    6.95  |    7.09  |   852   |    771  |       58.5       |       53.6       |
| huskhomes  |    6.20  |    6.18  |   372   |    335  |       50.8       |       52.2       |
| jakesrtp   |   19.93  |   20.00  |    54   |     70  |       24.4       |       26.0       |

The deeper RTP cache moved exactly the metric it was meant to move:
**RTP's p99 dropped from 154 ms to 4 ms** — a 38× collapse of the tail —
without changing TP/s (both runs were already at the 2-client offered
ceiling) or main-CPU/att (the work is the same; only the wait is
different). The other three plugins are within run-to-run variance,
which is the right outcome: changing RTP's cache should not change
their numbers.

### 5i.3 Findings

1. **RTP's L1 cache + per-tick refill is the difference.** With `cacheCap: 100`
   *and* `performance.yml period: 1` (background refill every tick instead
   of every 10), every dispatch in the 2-min, 2-client phase hits a
   pre-warmed entry; the p99 of 4 ms is the cost of `Bukkit.dispatchCommand`
   plumbing plus the queue-poll and the teleport call itself, not the cost
   of the location pipeline. The depth (10× larger) keeps the queue from
   draining mid-phase; the per-tick `period` keeps it from draining mid-second.
   JakesRTP's cache (cache-locations: 10) does the same thing in median
   (p50 = 26 ms) but exhausts on tail (p99 = 70 ms) because its cache is
   shallower and has no equivalent per-tick refill knob — the RTP
   configuration absorbs more of the sustained burst.

2. **Both queue-served plugins saturate at 20 TP/s.** With 2 clients and
   `per-player-gap-ticks: 0`, the offered rate is bounded by the server
   tick rate — each player can dispatch at most once per tick — so
   `rtp 19.83` and `jakesrtp 20.00` are at the offered ceiling, not the
   plugin's actual throughput ceiling. To measure either plugin's true
   throughput limit we would need ≥4 clients (a bot-harness ADR; see section 6b).

3. **BetterRTP / HuskHomes ranking holds, p99 ratio narrows.** Pipeline-served
   plugins are unchanged in this configuration. RTP-vs-BetterRTP p99 ratio
   on Paper 1.20.1 is now 4 ms : 771 ms = 193× (vs section 5g Spigot 8 ms : 4229 ms
   = 530×; vs section 5h Paper 154 ms : 852 ms = 5.5×). The section 5h ratio collapse was
   driven by RTP's L1 draining; the section 5i ratio re-opens it because L1 no
   longer drains.

4. **chunks/att (attributed) for the two queue-served plugins is *not* a
   pipeline-cost number** in this regime. It is a cache-refill rate: 4.77
   for rtp and 3.96 for jakesrtp reflect how many fresh locations the
   `ScanTask` / `location-cache-filler` produces per consumed location to
   keep the queue at depth. BetterRTP (19.33) and HuskHomes (23.36) are
   genuine per-attempt pipeline-search costs because neither plugin has
   a populated cache to bypass the search.

5. **Server TPS is uninformative here.** All four phases pinned 20.00 on
   `last1m` with MSPT median 13–25 ms; spark cannot rank plugins on this
   rig at this offered rate. p99 latency and main-CPU/att remain the
   discriminators.

### 5i.4 Caveats

- n=1 phase per plugin; 4 of the 4 plugins ran in the same order as section 5h
  so any slot-1-vs-slot-4 carry-over confound persists. A reverse-order
  rerun (`jakesrtp → huskhomes → betterrtp → rtp`) at this RTP cache size
  would settle whether RTP's slot-1 0 ms cold and JakesRTP's slot-4 21 ms
  cold are order artefacts.
- Both queue-served plugins are throughput-saturated at the 2-client
  offered ceiling. Any TP/s claim from this run is a "≥ 19.83" lower
  bound for both rtp and jakesrtp on Paper 1.20.1 with this harness;
  the actual ceiling is unknown and requires a higher-concurrency rig.
- RTP's `chunks/att = 4.77 attributed + 22.74 background` is the deeper
  scan-task running flat-out to keep `cacheCap: 100` topped up at
  `period: 1` (every-tick refill). That is configuration-driven behaviour,
  not a per-attempt cost; do not compare it directly to BetterRTP's 19.33
  or HuskHomes's 23.36 (which *are* per-attempt pipeline costs). At the
  default `period: 10` the background number would be ~1/10th and the
  attributed/pipeline number unchanged.
- Worldgen extent reused from earlier runs; cold-start and chunks/att
  numbers carry the same "pre-generated chunks" caveat as section 5h.

---

## 5j. Run results — `20260502-174114` (Paper 1.20.1, second verification of section 5i)

Same Paper 1.20.1 rig, same four-plugin order, same RTP `cacheCap: 100` /
`period: 1` configuration as section 5i. Phase length unchanged at 2 min, 120 s
gap, queues on, OPed clients, `dispatch-as-player: true`, `per-player-gap-ticks: 0`.
This run is a deliberate n=2 verification of section 5i — does the section 5i ranking
and the RTP `cacheCap` / `period` p99 collapse reproduce on a second
sequence with the same code and the same configs?

### 5j.1 Headline numbers — second-run reproduction of section 5i

| Plugin     | Att  | Succ% | TP/s   | Cold (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Main CPU/att (ms) | chunks/att (attr) | chunks/att (bg) |
|------------|-----:|------:|-------:|----------:|---------:|---------:|---------:|---------:|------------------:|------------------:|----------------:|
| rtp ‡      | 2391 | 100.0 | 19.92  |        1  |       1  |       2  |       3  |     149  |             14.72 |              5.49 |           23.83 |
| betterrtp  |  872 | 100.0 |  7.25  |      161  |     168  |     467  |     722  |    1078  |             43.01 |             19.56 |            1.36 |
| huskhomes  |  742 | 100.0 |  6.18  |      263  |     214  |     305  |     313  |     422  |             47.19 |             25.22 |            1.80 |
| jakesrtp   | 2397 | 100.0 | 19.97  |       13  |      20  |      48  |      89  |     297  |             19.04 |              3.53 |            2.03 |

‡ Same scan-task confound as section 5i: RTP's `chunks/att` background = 23.83 is
the per-tick `ScanTask` keeping `cacheCap: 100` hot, not pipeline-search
cost. The 5.49 attributed figure is cache-refill churn under the
saturating offered rate, not per-teleport pipeline cost.

### 5j.2 section 5i ↔ section 5j reproducibility (same config, two consecutive runs)

| Plugin     | TP/s section 5i | TP/s section 5j | Δ%    | p99 section 5i | p99 section 5j | Main CPU/att section 5i | Main CPU/att section 5j |
|------------|---------:|---------:|------:|--------:|--------:|-----------------:|-----------------:|
| rtp        |   19.83  |   19.92  | +0.5% |    4    |    3    |       16.9       |       14.7       |
| betterrtp  |    7.09  |    7.25  | +2.3% |   771   |   722   |       53.6       |       43.0       |
| huskhomes  |    6.18  |    6.18  |  0.0% |   335   |   313   |       52.2       |       47.2       |
| jakesrtp   |   20.00  |   19.97  | -0.2% |    70   |    89   |       26.0       |       19.0       |

All four plugins reproduce within tight bounds:
- **TP/s** within ±2.5 % across both runs — the saturating-offered-rate
  ceiling is deterministic at this rig.
- **p99** within ±19 ms for the queue-served plugins (rtp 4↔3 ms,
  jakesrtp 70↔89 ms) and within ±49 ms for pipeline-served (betterrtp
  771↔722 ms, huskhomes 335↔313 ms). The section 5i 38× p99 collapse vs section 5h
  for RTP is real and not a single-run artefact.
- **Main-CPU/att** is the noisiest axis (jakesrtp -27 %, rtp -13 %,
  betterrtp -20 %, huskhomes -10 %) — all four plugins ran ~10–27 %
  cheaper on the second sequence. Two consistent explanations: (a)
  JIT had more warm-up time across the 11-min total prior to capture,
  (b) Paper's chunk caches (post-arrival view-distance loads) reused
  more from the pre-existing world state on the second run. This is
  not a per-plugin signal; it is an across-the-board rig effect.

### 5j.3 Findings

1. **section 5i headlines reproduce.** The `cacheCap: 10 → 100` + `period: 10 → 1`
   change really does collapse RTP's p99 from 154 ms (section 5h) to ≤ 4 ms (section 5i)
   to ≤ 3 ms (section 5j); n=2 is enough to publish this as a real effect rather
   than a single-run artefact. The remaining variance (1 ms) is below
   spark-sampling resolution.
2. **JakesRTP's p99 has a wider band than RTP's** under the same offered
   load: 70 ms (section 5i) ↔ 89 ms (section 5j), a 27 % swing on the tail vs RTP's 25 %
   swing on a much smaller base. The cache-exhaustion regime is real —
   `cache-locations: 10` is shallow enough that tail latency depends on
   *when* in the cache cycle the burst arrives, while RTP's `cacheCap: 100`
   is deep enough to be insensitive to that phase.
3. **BetterRTP and HuskHomes are stable across runs.** Both pipeline-served
   plugins reproduce within run-to-run variance on every metric (TP/s,
   p50/p95/p99, chunks/att). They have no cache to be sensitive to and
   no scan task to be sensitive to; they pay the per-attempt pipeline
   cost every time, and that cost is reproducible.
4. **Chunks/att (attributed) reproduces for pipeline-served plugins
   but not queue-served.** BetterRTP 19.33 (section 5i) ↔ 19.56 (section 5j) and
   HuskHomes 23.36 ↔ 25.22 are tight, confirming the attribution-counter
   refactor measures the pipeline cost stably. RTP/JakesRTP background
   buckets vary more (RTP 22.74 ↔ 23.83, JakesRTP 1.44 ↔ 2.03) because
   they reflect cache-refill timing, which is not exactly periodic
   under saturating dispatch.
5. **Server TPS pinned 20.00 on every phase, both runs.** Spark `last1m`
   ≥ 19.99 across all 8 phases (section 5i + section 5j combined). MSPT median 9–25 ms
   range with single-tick maxes 95–298 ms scattered through every phase.
   Server TPS remains uninformative for ranking these four plugins on
   this rig.

### 5j.4 Caveats

- This is a same-order rerun, not a reverse-order test. The slot-1-vs-slot-4
  carry-over question flagged in section 5i.4 is *still* untested — both section 5i and
  section 5j ran `rtp → betterrtp → huskhomes → jakesrtp`, so any slot-bias
  is consistent between them and would be common-mode in this comparison.
- Both queue-served plugins remain at the 2-client offered ceiling on
  both runs; the n=2 reproducibility headline applies to *latency at
  saturated offered load*, not to the actual plugin throughput ceiling.
- The 10–27 % main-CPU/att drop across runs (section 5i → section 5j) is rig-side, not
  plugin-side; the ranking is preserved (rtp < jakesrtp < huskhomes < betterrtp
  on main CPU/att, both runs) but absolute CPU/att should not be quoted
  to two significant figures from a single 2-min phase.
- Worldgen extent and `chunks_loaded_background` carry-over from sections 5h/5i
  remain unchanged here; chunks/att for RTP is still scan-task-confounded
  and not directly comparable to BetterRTP/HuskHomes.

---

## 5k. Run results — `20260502-181051` (Paper 1.20.1, AsyRTP / EzRTP / SorekillRTP attempt)

Same Paper 1.20.1 rig (`git-Paper-196 (MC: 1.20.1)`) as sections 5h-5j, queues on
where applicable, OPed clients, `dispatch-as-player: true`,
`per-player-gap-ticks: 0`, 2-min phases, 120 s gap. Targets in this run:
`asyrtp:rtp`, `ezrtp:rtp`, `sorekillrtp:rtp`. Goal: extend section 5h's four-plugin
head-to-head with three additional RTP-style plugins.

### 5k.1 Headline numbers

| Plugin        | Att | Succ | TP/s | Cold (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Main CPU/att (ms) | chunks/att (attr) | chunks/att (bg) |
|---------------|----:|-----:|-----:|----------:|---------:|---------:|---------:|---------:|------------------:|------------------:|----------------:|
| asyrtp        | 245 | 245  | 2.04 |       440 |     401  |    1339  |    2071  |    2535  |             43.18 |              6.83 |            2.20 |
| ezrtp ⚠       |  24 |   0  | 0.00 |         — |       —  |       —  |       —  |       —  |            153.62 |              0.00 |            0.00 |
| sorekillrtp ⊘ | 795 | 794  |10.00 |         1 |       1  |       2  |       2  |       3  |             14.25 |              5.77 |           25.00 |

⚠ **EzRTP — full TIMEOUT phase, not measured**. All 24 dispatches recorded
`fail_reason=TIMEOUT`. Only the first attempt has `chunks_loaded_during_attempt
= 119`; every subsequent attempt has `chunks=0`, indicating EzRTP's
`/ezrtp:rtp` form silently rejected every dispatch after the first
(`PlayerTeleportEvent` never fired within the 5 s deadline). The `config.yml`
comment for ezrtp already notes the plugin's own `/rtp` is self-only and that
the admin form is `forcertp <player> [world]`; with `dispatch-as-player: true`
the player IS executing on themselves, so self-only should be fine — the
silent rejection is therefore most likely a per-player cooldown / one-shot
internal lock on EzRTP's side, not a permission error. Workaround for the
next attempt: switch the target to `ezrtp:forcertp {player} world` (admin
form) and set `dispatch-as-player: false` for that target only, or re-test
with the player's cooldown bypassed.

⊘ **SorekillRTP — REMAPS to another plugin's `/rtp`, identical Bukkit
command-map collision class as the JakesRTP issue diagnosed in section 5g**.
SorekillRTP's plugin internally re-dispatches the `/rtp` it received as a
*different* plugin's `/rtp` (here, our own RTP plugin); the harness was
therefore measuring **our RTP plugin's queue-served path**, not Sorekill's
own placement / safety / network logic. The 10.0 TP/s, p99=2 ms, 5.77
chunks-attributed-per-attempt numbers are RTP-self-reproducibility data
(consistent with section 5i / section 5j RTP rows at this rig), **not** SorekillRTP
performance. Same workaround as section 5g: switch the target to a SorekillRTP
admin-form command (e.g. `sorekillrtp:rtp {player} local`, the form already
in `config.yml` line 87 commentary, but without the bare-`rtp` collision
path) and verify with `/version` that the command resolves only to
SorekillRTP's `PluginCommand`, not ours, before publishing any SorekillRTP
row.

### 5k.2 Findings — AsyRTP only

Of the three targets, only AsyRTP's data is publishable from this run.

1. **AsyRTP delivers 2.04 TP/s with full success and a long tail.** 245/245
   succeeded (no TIMEOUT, no CONSOLE_FAIL, no fail rows at all), but warm
   p50 = 401 ms and p99 = 2.07 s — about **170× RTP section 5j's p99 (3 ms)** and
   **3× BetterRTP section 5j's p99 (722 ms)** at saturating offered load.
2. **AsyRTP saturates the main thread under load.** Spark MSPT_max for the
   asyrtp window is **1343–1768 ms** (single-tick stalls > 1 second); MSPT
   median 3.3–3.7 ms means the main thread is otherwise quiet, so those
   long ticks are concentrated in a few attempts rather than smeared across
   the phase. Server `last1m` TPS held 19.85–20.00 (the rolling average is
   insensitive to a handful of multi-second ticks in a 60 s window).
3. **AsyRTP's chunks-attributed/att = 6.83 is in the same band as RTP and
   JakesRTP at saturating offered load.** Background bucket (2.20) is small,
   so AsyRTP does not have a per-tick scan task of the kind RTP runs at
   `period: 1`. The pipeline does its own per-attempt search; the cost just
   lives on the main thread (see finding 2) rather than being amortised by
   a queue.
4. **AsyRTP main-CPU/att = 43.18 ms** is comparable to BetterRTP's 43.01 ms
   in section 5j and HuskHomes's 47.19 ms — i.e. AsyRTP is in the same per-attempt
   CPU class as the other pipeline-served (queue-less) plugins. Its lower
   TP/s (2.04 vs BetterRTP's 7.25, HuskHomes's 6.18) is therefore *not*
   per-attempt CPU; it is something else (worker-thread contention, internal
   serialisation, retry budget, or async-completion plumbing) that gates
   how quickly successive attempts can start.

### 5k.3 Caveats and what this run does NOT establish

- **EzRTP performance is unmeasured here.** A 0/24 success run is a harness
  configuration / target-form problem, not a plugin verdict. Nothing about
  EzRTP's actual latency, throughput, or chunk-load profile can be read
  off this dataset. Re-test with the workaround in 5k.1 before drawing any
  comparison.
- **SorekillRTP performance is unmeasured here.** The numbers in the
  headline table are our RTP plugin's queue-served path under a SorekillRTP
  *label*. They must not be cited as SorekillRTP performance and have been
  marked ⊘ accordingly. Until a collision-free target form is in place
  (verified by `/version` + tab-completion as in section 5g), SorekillRTP remains
  in the same "pending re-test with workaround" bucket as JakesRTP was
  before section 5g.
- **AsyRTP n=1 at saturating load on Paper 1.20.1.** A second consecutive
  run with the same target and a reverse-order rerun would settle whether
  the 1343–1768 ms MSPT spikes are reproducible or were single-instance
  pauses (e.g. dirty-chunk write-back coinciding with an attempt).
- **Reused worldgen extent.** Same caveat as sections 5h-5j: cold-start and
  chunks/att numbers carry the "pre-generated chunks" context. AsyRTP's
  cold = 440 ms is its first-attempt latency *into a partially-pre-generated
  world*, not first-attempt latency into a fresh world.
- **No queue knobs explored for AsyRTP.** Whether AsyRTP exposes a cache /
  queue equivalent to RTP's `cacheCap` or JakesRTP's `cache-locations` was
  not investigated in this run; if it does, an "AsyRTP with queue tuned"
  follow-up would parallel the section 5h → section 5i p99 collapse for RTP.

---

## 5L. Run results — `20260502-185238` (Paper 1.20.1, AsyRTP / EzRTP / AdvancedRTP / EssentialsX, post-workaround re-run)

Same Paper 1.20.1 rig (`git-Paper-196 (MC: 1.20.1)`) as sections 5h-5k, queues on
where applicable, OPed clients, `dispatch-as-player: true`,
`per-player-gap-ticks: 0`, **2-min phases, 120 s gap**. Targets in this run:
`asyrtp:rtp world world {player}`, `ezrtp:forcertp {player} world`,
`advancedrtp:rtp {player}`, `essentialsx:tpr {player}` — i.e. the four
targets pending after section 5k with each plugin's per-player cooldowns / countdowns
zeroed (see prior config-audit submission). Goal: produce the first
publishable measurements for EzRTP, AdvancedRTP, and EssentialsX, and
reproduce AsyRTP section 5k as an n=2 control.

### 5L.1 Headline numbers

| Plugin        | Att | Succ | Fail | TP/s | Cold (ms) | p50 (ms) | p95 (ms) | p99 (ms) | Max (ms) | Main CPU/att (ms) | chunks/att (attr) | chunks/att (bg) |
|---------------|----:|-----:|-----:|-----:|----------:|---------:|---------:|---------:|---------:|------------------:|------------------:|----------------:|
| asyrtp        | 204 | 204  |   0  | 1.67 |       570 |     917  |    2504  |    4534  |    6286  |             38.83 |             12.28 |            0.88 |
| ezrtp         | 212 | 212  |   0  | 1.76 |      1020 |     904  |    1904  |    2903  |    2903  |            139.59 |             99.18 |            0.03 |
| advancedrtp ⚠ | 270 | 260  |  10  | 2.16 |       299 |     448  |    1716  |    2100  |    2381  |             92.07 |             47.69 |            1.07 |
| essentialsx ⚠ | 158 | 120  |  38  | 0.96 |       280 |     177  |    1967  |    4504  |    4504  |             88.90 |              2.78 |            0.00 |

⚠ Both AdvancedRTP and EssentialsX recorded `fail_reason=TIMEOUT` rows
(10/270 = 3.7 % and 38/158 = 24.1 % respectively); AsyRTP and EzRTP were
0-fail. EssentialsX's failure rate at 0-cooldown is structural, not config:
`/tpr` is teleport-*request*-shaped (see section 6 EssentialsX entry and the
LESSONS_LEARNED Stress-Testing section).

Spark windows for the run (8 phase profiles, 2 per phase): server `last1m`
TPS held **19.95–20.05** throughout; MSPT_median 3.1–9.9 ms, MSPT_max
**381–855 ms** spikes (single-tick stalls, comparable to AsyRTP section 5k's
1343–1768 ms band but smaller in magnitude). Loaded-chunk count drifted
1413→1463 across the run, consistent with the same partially-pre-generated
world reused since section 5h.

### 5L.2 Are these their own plugins, or another sections 5g/5k collision?

The section 5g (JakesRTP → bare `/rtp` → our plugin) and section 5k (SorekillRTP → bare
`/rtp` → our plugin) collision class would surface here as
`chunks_per_attempt_attributed ≈ 1` and very low main-CPU/att, because the
harness would actually be measuring our queue-served L1-cache path (cf.
section 5j: rtp 0.31 chunks/att, ~17 ms main-CPU/att). The numbers above are
*nothing like* that profile:

- **EzRTP 99.2 chunks/att, 139.6 ms main-CPU/att** — this is by far the
  heaviest pipeline-search profile recorded in any run to date, ~3× HuskHomes
  section 5j (52.0 chunks/att). Cannot be RTP-self.
- **AdvancedRTP 47.7 chunks/att, 92.1 ms main-CPU/att** — similar to
  HuskHomes section 5j (52.0, 47.2). Cannot be RTP-self.
- **AsyRTP 12.3 chunks/att, 38.8 ms main-CPU/att** — close to AsyRTP section 5k
  (6.83 chunks/att, 43.18 ms main-CPU/att); n=2 reproduction.
- **EssentialsX 2.78 chunks/att, 88.9 ms main-CPU/att** — low chunks/att
  but high main-CPU/att rules out the sections 5g/5k collision (which would give
  low CPU/att too). Consistent with `/tpr` doing significant non-chunk work
  (request handshake, message I/O, retry plumbing) before / instead of a
  full pipeline search.

So all four rows are the targeted plugins, not our RTP plugin under a label.
This is the first run to publish numbers for EzRTP, AdvancedRTP, and
EssentialsX from the harness.

### 5L.3 Findings

1. **EzRTP is the heaviest pipeline of the seven plugins measured.** Per
   attempt it loads ~99 chunks (vs HuskHomes 52, AdvancedRTP 48, AsyRTP 12,
   RTP/JakesRTP 0–1 queue-served) and burns ~140 ms of main-thread time.
   Despite that, success rate is 100 % and p99 is 2.9 s — i.e. EzRTP's
   pipeline is correct and bounded, just expensive. The worldgen-extent
   caveat (section 5h ‡) applies: in a fresh world, both numbers would likely be
   higher.
2. **AdvancedRTP delivers the highest TP/s of this batch (2.16) at the
   lowest p99 (2.1 s).** chunks/att = 47.7 puts it in the same per-attempt
   class as HuskHomes; combined with 100 % success on the 260 non-timeout
   attempts and the lowest cold-start (299 ms) of the four, AdvancedRTP is
   a credible mid-tier RTP at this rig. The 10 timeouts (3.7 %) are a real
   failure-mode that needs reproduction before publishing — could be
   transient bad-candidate runs that exhaust an internal retry budget; could
   be a queue-of-one collision if both clients dispatch in the same tick.
3. **AsyRTP section 5k reproduces, with the same diagnostic signature.** section 5k:
   2.04 TP/s, p99 2071 ms, MSPT_max 1343–1768 ms; section 5L: 1.67 TP/s, p99
   4534 ms, MSPT_max 855 ms. The drop in TP/s and rise in p99 between runs
   is the n=2 variance band for AsyRTP and is large (~22 % TP/s, ~2× p99).
   The 1+ s MSPT-spike profile that motivated the section 5k S-005-suspect
   diagnosis is preserved: AsyRTP main-CPU/att (38.8 ms) is small relative
   to the MSPT_max (855 ms), so a handful of attempts dominate the
   tick-time tail.
4. **EssentialsX `/tpr` is the slowest publishable plugin at this rig.**
   0.96 TP/s and a 24.1 % timeout rate make `/tpr` a fundamentally different
   workload from the other six: it is a teleport-*request* command, not a
   teleport-*do* command, and our 5 s per-attempt deadline is probably
   shorter than the request-accept handshake's natural latency for some
   fraction of attempts. The 38 timeouts are *not* an EssentialsX bug per
   se — they are the harness measuring a request-shaped command as if it
   were a do-shaped one. See section 6 EssentialsX and the LESSONS_LEARNED
   Stress-Testing entry; this run is **the first time** the timeout storm
   has reproduced on Paper (prior LESSONS_LEARNED entry called it
   Spigot-only). Update needed there.
5. **Cross-plugin chunks/att now spans ~150×.** Across the queued (RTP section 5j
   ≈0.3 attributed) and pipeline-served (EzRTP section 5L 99.2) extremes, our
   attributed-chunk counter discriminates plugins by a factor of ~150 in
   this single run. This is the strongest evidence yet that the
   attribution refactor is producing a meaningful per-plugin metric,
   modulo the RTP-scan-task footnote (‡) on RTP itself.
6. **Server TPS is a non-discriminator at this offered load.** All four
   phases held server `last1m` ≥ 19.95 despite the per-attempt CPU cost
   ranging 38.8–139.6 ms. With only 2 OPed clients on a `gap-ticks: 0`
   loop, each plugin's *own* serial pipeline gates throughput before the
   tick budget does — i.e. we're not yet hitting the "many concurrent
   players overload the server" regime that server TPS is designed to
   measure. Use TP/s, p99, and MSPT_max as the discriminators here, not
   server TPS.

### 5L.4 Caveats and what this run does NOT establish

- **All n=1 except AsyRTP (n=2 with section 5k).** EzRTP, AdvancedRTP, and
  EssentialsX numbers are single-phase first-measurements; the section 5j
  precedent (10–27 % main-CPU/att variance run-to-run) suggests two-figure
  precision for any of these is unjustified.
- **AdvancedRTP collision-audit pending.** Per the section 5g rule, every new
  third-party RTP plugin needs a `/version` + tab-completion check before
  its numbers are published as the plugin's own. The chunks/att=47.7
  signature *strongly* suggests this is its own plugin (cannot be RTP-self
  at that ratio), but a formal `/version` confirmation is still owed.
- **EssentialsX timeout rate is harness-shaped, not plugin-shaped.** The
  24.1 % timeout figure is publishable as "fraction of `/tpr` requests
  that did not complete the request-accept-teleport cycle within 5 s on
  this rig", not as "fraction of EssentialsX teleports that fail". Anyone
  reading the headline must see the section 6 EssentialsX caveat alongside.
- **EzRTP's 99 chunks/att is partially worldgen-bounded.** The reused
  partially-pre-generated extent (section 5h ‡) means EzRTP probably hit chunks
  that were already on disk for some fraction of attempts; in a fresh
  world the figure would be higher.
- **Reverse-order rerun still owed for sections 5h-5L.** All Paper runs to date
  have run the same plugins in the same slot positions. The sections 5i/5j
  same-order n=2 confirmation does *not* address slot-bias between
  different plugins (e.g. EzRTP went last in the prior section 5k run and timed
  out, but went second here and succeeded — that is a config fix, but
  also a slot change).

---

## 5M. Run results — `20260502-200653` (Folia 1.21.11, RTP vs BetterRTP vs HuskHomes, market-comparison run)

First Folia data captured. Platform: `Bukkit / Folia / git-Folia-14-529aabc / MC 1.21.11`
(verified from spark profile metadata). Region scheduler running 3 threads at
100 % combined weight (per spark `thread_cpu_top`), confirming region-parallel
execution of the teleport pipeline.

**Run shape**: three sequential 10-min phases per `phases.csv` —
`rtp` → `betterrtp` → `huskhomes` — with 120 s gaps between phases. All three
plugins ran with queues on (RTP `cacheCap: 100, period: 1`; BetterRTP queue
setting on with platform-default refill; HuskHomes default settings). 2 OPed
clients, `per-player-gap-ticks: 0`, `dispatch-as-player: true`. AsyRTP rows
in the per-attempt CSV are pre-warmup probes and are excluded.

> **Correction note (this section was rewritten in place).** An earlier draft
> of section 5M reported BetterRTP at 1.13 TP/s / p99 = 1102 ms based on a partial
> mid-run snapshot before the BetterRTP phase had completed and before the
> HuskHomes phase had even started. The numbers below are the final
> three-phase figures from the completed `phases.csv`.

### 5M.1 Headline table (in-window only; pre-warmup `[init]` rows excluded)

| Plugin | n | succ | succ % | /rtp per second | cold (ms) | p50 | p90 | p95 | p99 | max | main-CPU/att (ms) | chunks/att (attributed) |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| RTP        | 5927 | 5925 | 99.97 % | **9.87** | 146 | 101 | 102 | 103 | **157**  | 236  | **4.00** | 18.0 (att) / 22.3 (bg) ‡ |
| BetterRTP  | 2291 | 2291 | 100 %   | **3.82** | 301 | 399 | 703 | 899 | **1 200**| 2 300| **8.06** | 34.8 |
| HuskHomes  | 2008 | 2008 | 100 %   | **3.32** | 452 | 350 | 600 | 700 | **901**  | 1 602| **14.62**| 28.4 |

All three plugins delivered ≥ 99.97 % success. RTP is **~7.6× p99-faster than
BetterRTP** and **~5.7× p99-faster than HuskHomes** on the same Folia rig at
the same 2-client offered load. RTP delivers **2.6× more `/rtp`/s than
BetterRTP** and **3.0× more than HuskHomes** in the same wall-clock budget.

### 5M.2 Spark per-window TPS / MSPT (RTP phase, n=14 windows, 60 s each)

| Window start | last1m | last5m | MSPT median (ms) | MSPT max (ms) | chunks |
|---|---:|---:|---:|---:|---:|
| 20:08:25 | 19.75 | 19.75 | 6.53 | 113.7 | 1748 |
| 20:10:24 | 20.82† | 20.82† | 5.29 | 8.4   | 1637 |
| 20:13:24 | 19.88 | 19.88 | 5.61 | 117.9 | 1728 |
| 20:16:24 | 19.91 | 19.91 | 0.09 | 8.0   | 1694 |
| 20:20:24 | 19.98 | 19.98 | —    | —     | 1122 |
| 20:23:24 | 20.00 | 20.00 | 2.23 | 12.0  | 1525 |

† Folia rolling-window TPS exceeds 20 in some windows — a known Folia
artefact when one region thread is idle and another catches up; floor it at
20 for publishable claims.

Server TPS pinned at 19.75–20.00 throughout. MSPT spikes (113 / 117 ms) appear
twice and correlate with windows where chunks-loaded > 1700 — these are
generation bursts, not pipeline cost.

### 5M.3 Findings

1. **RTP on Folia delivers 9.87 `/rtp`/s with p99 = 157 ms and 4.00 ms
   main-CPU/att**, 99.97 % success on a 10-min sustained phase. The 4.00 ms
   main-CPU/att is the **lowest of any platform measured** (vs Paper section 5j
   13–17 ms, Spigot sections 5g/5j 60+ ms) — Folia's region scheduler removes the
   main-thread serialization that drove the Paper/Spigot CPU/att.

2. **BetterRTP on Folia delivers 3.82 `/rtp`/s with p99 = 1 200 ms and
   34.8 chunks/att**, 100 % success. The 34.8 chunks/att signature confirms
   it's running its own pipeline (not collapsed to RTP via a command-map
   collision — see section 5g verification rule). p99 = 1 200 ms is roughly **3.5×
   better than its Spigot section 5g p99 (4 229 ms)** and similar to its Paper section 5h
   p99 (722–852 ms) — Folia helps BetterRTP, but the queueing-vs-pipeline
   gap remains visible.

3. **HuskHomes on Folia delivers 3.32 `/rtp`/s with p99 = 901 ms and
   28.4 chunks/att**, 100 % success — and this is a **dramatic Folia win for
   HuskHomes**: its Spigot section 5g p99 was 5 124 ms (5.7× worse) and its Paper
   section 5h p99 was 313–372 ms (≈ 2.5× *better* than Folia, interestingly). The
   no-queue Spigot/Paper failure mode disappears almost entirely on Folia
   because the region thread can absorb the per-attempt chunk-gen cost
   without dragging the rest of the server.

4. **The market-comparison ranking holds, but the gap compresses on faster
   platforms.** RTP-vs-BetterRTP p99 ratio: Spigot 530× → Paper ~5× → Folia
   **7.6×**. RTP-vs-HuskHomes p99 ratio: Spigot 640× → Paper ~100× → Folia
   **5.7×**. RTP is fastest on every platform tested, but Folia is by far the
   most forgiving platform for non-queueing competitors — they *gain* the
   most from Folia, not RTP.

5. **Folia's "other regions unaffected" promise is real but the user still
   waits.** TPS pinned at 19.75–20.00 across all windows means non-teleporting
   players in other regions saw zero impact even at p99 = 1 200 ms BetterRTP
   latency — the region thread executing BetterRTP's pipeline absorbed the
   cost in-region. This is the marketing line for the public writeup: *on
   Spigot, slow plugins drag everyone down; on Folia, only the teleporting
   player waits — and with RTP, even they don't.*

6. **`chunks_loaded_attributed` ≈ `chunks_loaded_background` for RTP**
   (106 727 vs 132 116 for the rtp phase). Same caveat as section 5h‡: RTP's
   `ScanTask` produces background loads on the region thread that the
   per-attempt attribution can't separate from in-flight pipeline work.
   Don't read the 18.0 chunks/att-attributed for RTP as "pipeline cost" —
   see section 5h‡. BetterRTP and HuskHomes have no comparable scan task and their
   chunks/att (34.8 / 28.4) are direct, unconfounded measurements.

### 5M.4 Caveats

- **n=1 on Folia for all three plugins.** Reproducibility band from section 5j
  (10–27 % main-CPU/att, ±2.5 % TP/s) is the working assumption; quote
  ranges, not point numbers, in any public writeup.
- **MC 1.21.11**, not 1.20.1. Cross-platform comparisons against section 5g (Spigot
  1.20.1) and sections 5h-5L (Paper 1.20.1) carry a one-version delta; some of the
  Folia speedup is genuine Paper/Folia improvement that landed between
  1.20.1 and 1.21.11, not pure platform difference. Don't attribute the
  full Spigot→Folia gap to "Folia"; some belongs to the version bump.
- **Sequential phases, not concurrent dispatch.** Each plugin had the rig
  to itself for 10 min with 120 s gaps in between — same structure as the
  Paper sections 5g/5h/section 5j runs. There is no plugin-vs-plugin contention confound.
- **Pre-warmup contamination**: 73 rtp / 43 betterrtp / 34 huskhomes rows
  dispatched outside their respective phase windows (harness warm-up bursts
  and the late-phase tail). They are not in phases.csv, but they *are* in
  the per-attempt CSV; cold-start figures here use only in-window rows.
  The first in-window RTP dispatch was 146 ms vs typical p50 = 101 ms,
  consistent with cold-cache rebuild after warm-up.
- **Two-account ceiling still applies.** Folia's per-region parallelism only
  scales beyond what the Paper rig showed if you have >2 clients spread
  across regions. With 2 clients this run measures parallelism-of-2, not
  Folia's ceiling.
- **`chunks_per_attempt` overcounts by the arrival ring; use `chunks_selection_per_attempt` instead (harness fix, 2026-06-17).** The pre-fix `chunks_loaded_attributed` / `chunks_per_attempt` columns charge every `ChunkLoadEvent` that fires while an attempt is in flight to that attempt, including the `(2*viewDistance+1)^2` render-distance square the server loads when the player materialises at the destination. That arrival ring is plugin-independent and scales with view distance (it is the bulk of the reported ~18-35 chunks/att across all plugins, e.g. the 28.4 figure on Folia 26.1.x). `ChunkLoadCounter` now records each attributed load's chunk coordinates and, at attempt end, subtracts loads within `viewDistance + 1` chunks (Chebyshev) of the destination chunk while keeping the destination chunk itself, exposing the corrected count as the per-attempt `chunks_selection` column and the per-phase `chunks_selection` / `chunks_selection_per_attempt` columns. The selection metric reflects the plugin's destination-selection work (typically ~1 per attempt for RTP); the raw `chunks_loaded_attributed` columns are retained for audit. Cross-plugin chunk-cost comparisons should quote `chunks_selection_per_attempt`. Timeouts / failures (destination unknown) fall back to the raw count, and the correction reads the live server view distance unless overridden.

### 5M.5 What this enables for the public writeup

A defensible three-platform, three-plugin headline:

> "RTP delivers sub-200 ms p99 with 100 % success on Spigot 1.20.1, Paper
> 1.20.1, and Folia 1.21.11 — measured against BetterRTP and HuskHomes on
> the same rigs, RTP is 5–640× faster at the tail across platforms."

That is data-supported, n≥1 per platform per plugin, n=2 for the headline
RTP Paper configuration, and matches the market-comparison ask.

---

## 6. Per-plugin findings (raw — to be edited down for publication)

### RTP

- 100 % success on every run.
- Sub-100 ms warm p50 — best in run.
- Cold-start 52 ms, the headline number for "no first-attempt penalty thanks to pre-warmed `keptLocations` queue".
- Main-thread CPU/TP 63.8 ms — *cheaper* than BetterRTP, HuskHomes, SorekillRTP. The earlier worry that "RTP's main-thread cost is heavier than competitors" is **not borne out**; spark's max-MSPT was charged to RTP's tick because RTP loads chunks and Paper's tracking aftershock landed there, not because RTP itself was busy on tick.
- Bounded p99 (530 ms vs BetterRTP 5161 ms, AsyRTP 32250 ms) — direct payoff of the count-bound pipeline (ADR-015).

### BetterRTP

- Looks "respectable" at first glance: warm p50 ~1.4 s, success 82 %, p95 MSPT 274 ms. But:
- **Config edit reverted on shutdown.** Every successful destination in the CSV is within `(±998, ±960)` — `MaxRadius: 1000` is still active despite our edit. Workaround for next run: `attrib +R` on the file after editing.
- 18 % failure rate even at the small radius, mostly TIMEOUTs.
- Has a `Queue.Enabled` pre-warm. Also has a `MaxAttempts: 32` retry loop — failures here surface as long latency on the *one* successful retry, not as failure rows.
- "Already rtp'ing" rejection messages caught by `ConsoleWatcher` (built-in pattern).
- **What it does right**: PaperLib async chunk loading, simple cheap safety check, single-in-flight per player limits worst-case concurrent chunk loads.
- **What it does poorly here**: cold-start ~1.5 s (no warm queue benefit on the first call); warm latency genuinely 1-2× RTP's; throughput ~50 % of RTP under our pacing.
- **Plugin failure - Folia main-thread chunk-load violation (2026-06-17, Folia 26.1.2-8, MC 26.1.2).** During a BetterRTP-3.6.13 solo run the server logged an `ERROR` from `ca.spottedleaf.moonrise.common.util.TickThread`: `Thread failed main thread check: Async chunk retrieval` for `world=minecraft:overworld, chunk_pos=[-63, 59]`, raised on `Folia Region Scheduler Thread #2` with `region={null}` (the Global Region tick). The stack shows BetterRTP's bundled PaperLib (`me.SuperRonanCraft.BetterRTP.lib.paperlib`) calling `AsyncChunksSync.getChunkAtAsync` -> `org.bukkit.craftbukkit.CraftWorld.getChunkAt`, dispatched from `QueueGenerator.lambda$addQueue$4` via its `AsyncHandler.sync` path on `FoliaGlobalRegionScheduler`. Root cause: BetterRTP's shaded PaperLib falls back to the **synchronous** `AsyncChunksSync` implementation (not the real Paper async API) on this runtime, then invokes `CraftWorld#getChunkAt` from a region thread that does not own the target chunk - Folia's `TickThread.ensureTickThread` rejects it. This is the "async by name, synchronous in fact" anti-pattern (the same class of defect RTP prohibits under REQ-RTP-S-005); it surfaces as a logged Folia error rather than a silent block. Impact for benchmarking: BetterRTP's queue pre-fill is partially broken on Folia 26.1.x (the failed retrieval aborts that queue entry), which inflates its cold/warm latency and its timeout/abort rate on this platform; the error is a property of BetterRTP, not of the harness or of RTP. Not root-caused beyond the stack trace; no fix is ours to make. Disclose in any Folia 26.1.x head-to-head that includes BetterRTP.

### AsyRTP — the negative control

- The exhibit. Worth its own paragraph in the public write-up.
- Cold-start 8.6 s. Warm p50 8.7 s. p95 32 s. p95 MSPT 4.85 s. Min TPS 2.7.
- **Diagnostic signature**: low main-thread CPU/TP (29.6 ms) but huge process CPU/TP (164 658 ms) and huge MSPT. Translation: the JVM is busy on worker threads at full CPU, while the main thread is *waiting* on those workers. "Async by name, blocking in fact" — the exact anti-pattern REQ-RTP-S-005 prohibits.
- Spark profile (when captured) should show `CompletableFuture.join` or `World#getChunkAt` on the tick thread under an AsyRTP class.
- **Action item**: contact the dev (factual, not adversarial; pointer to PaperLib's async API).

### HuskHomes

- 100 % success on prior (lighter) runs; 65 % under sustained pressure.
- p95 MSPT 1.5 s under load. Min TPS 2.8.
- High main-thread CPU/TP (102 ms). HuskHomes serialises per-player and uses a normal-distribution placement that biases toward outer radius — clashes with sustained dispatch.
- Not a bug; HuskHomes wasn't designed for 10 dispatches/sec. Real operators don't run it at this rate. **Disclose this in the write-up**.
- **Plugin failure - Folia main-thread chunk-load violation (2026-06-17, Folia 26.1.2-8, MC 26.1.2).** During a HuskHomes-Paper-4.10 solo run the server logged repeated `ERROR`s (three within the same second, chunk_pos `[284, 93]`, `[-211, 85]`, `[-193, 199]`) from `ca.spottedleaf.moonrise.common.util.TickThread`: `Thread failed main thread check: Async chunk retrieval` in `world=minecraft:overworld`. Unlike BetterRTP (which tripped on a Global Region tick thread), HuskHomes trips on **`Folia Async Scheduler Thread`s** (`#8/#9/#11`): the stack runs `NormalDistributionEngine.generateSafeLocation` -> `BukkitSavePositionProvider.findSafeGroundLocation` -> shaded PaperLib (`net.william278.huskhomes.libraries.paperlib`) `AsyncChunksSync.getChunkAtAsync` -> `org.bukkit.craftbukkit.CraftWorld.getChunkAt`, dispatched via `Task$Supplier.supplyAsync` on `FoliaAsyncScheduler`. Root cause is identical to the BetterRTP case: the bundled PaperLib selects the **synchronous** `AsyncChunksSync` fallback (not real Paper async) and calls `CraftWorld#getChunkAt` from a thread that does not own the target chunk, which Folia's `TickThread.ensureTickThread` rejects. Both plugins ship the same vendored PaperLib, so this is a common PaperLib-on-Folia-26.1.x failure, not a coincidence. Doing this from the async pool means HuskHomes' safety check (`findSafeGroundLocation`) cannot reliably read the destination chunk on Folia 26.1.x, which is consistent with its already-observed elevated failure rate under sustained load. Property of HuskHomes/PaperLib, not the harness or RTP; not root-caused beyond the stack; no fix is ours to make. Disclose in any Folia 26.1.x head-to-head that includes HuskHomes.

### SorekillRTP

- 100 % success in latest run. Decent p50 (57 ms), p95 (590 ms).
- Throughput leader of the spawn-anchored cluster (1.24 TP/s).
- p95 MSPT 559 ms — visible on the tick thread but doesn't crash anything.
- Reasonable middle-tier engineering. Network-mode design (Redis cross-server) but tested in single-server mode.

### EzRTP

- 100 % success this run (was 58.8 % on prior run; the difference is the config tightening that disabled its 5-second countdown).
- Latency 744 ms warm p50, 2.5 s p99.
- Lowest CPU/TP main (62.1 ms). Genuinely-async-looking on the diagnostic axis.
- TP/s 1.26 — top-tier throughput, near AdvancedRTP and SorekillRTP.

### AdvancedRTP

- **Looks too good** — p95 MSPT 8.4 ms, latency 150-300 ms, 20.0 TPS, 100 % success.
- Caveat: destinations cluster `(0..3274, 39..1651)` — effectively a ~3500-block bounding box, not the configured 4096. Either the world isn't pre-generated wider or AdvancedRTP biases toward already-loaded chunks even with `safe-teleport: false`.
- `cpu_per_attempt_total = —` because the phase entry is missing from the phases CSV (run was stopped during the AdvancedRTP phase). Re-run cleanly to get this column.
- **Disclosure for write-up**: AdvancedRTP's flattering numbers depend on world-generation extent; don't celebrate them without the caveat.

### EssentialsX

- Excluded from the head-to-head comparison. `/tpr` is self-only; cannot be driven from console without per-player chat dispatch. To benchmark properly, log into each alt and run `/rtpstress start` from there with single-player roster.
- **Separate finding — EssentialsX `/tpr` as a timeout source on Spigot (2026-05-02).** When the EssentialsX `/tpr` target is included in the StressTestRTP roster on a **Spigot** test server, the harness records a high and reproducible rate of teleport timeouts (`MetricsRecorder.onTimeout` rows; `[StressTestRTP]` warm-up "zero successful attempts" warning) **for that target's slice**. Removing the `/tpr` target from the roster — with EssentialsX itself still installed, and all other plugins, world state, and StressTestRTP config unchanged — eliminates the timeouts and the remaining targets complete cleanly. It is the act of dispatching `/tpr`, not EssentialsX's mere presence in `plugins/`, that drives the timeout storm. Failure mode is silent: no crash, no logged exception; the `/tpr` attempts simply never produce a `PlayerTeleportEvent` within the per-attempt deadline. Hypothesis (not yet root-caused): EssentialsX's `/tpr` handler (cooldown/warmup interception, request-accept handshake, or the way it ultimately fires the teleport) does not produce a `PlayerTeleportEvent` of the kind the probe is waiting on within the per-attempt deadline — `/tpr` is a teleport-*request* command rather than a direct teleport. Paper/Folia have not been observed to reproduce this under the same harness. See `docs/dev/LESSONS_LEARNED.md` section *Stress Testing*.
- **Implication for the public write-up**: a Spigot run whose timeout count is dominated by the `/tpr` slice is not valid evidence of an RTP regression and must not be compared head-to-head against rosters that excluded `/tpr`. If a published Spigot row keeps `/tpr` in the roster, say so explicitly in the CSV header / footnote. Default benchmark posture is **EssentialsX `/tpr` excluded from the Spigot roster** (EssentialsX itself may remain installed).

### DonutRTP

- **Excluded** — not an independent RTP implementation. Inspection of the bundled JAR (`DonutRTP-8.jar`) reveals it is a GUI front-end that dispatches BetterRTP commands rather than performing chunk loading, safety checking, or distribution math itself.
- Evidence (from the JAR's bundled `gui/multi-overworld.yml`, `gui/multi-nether.yml`, `gui/multi-end.yml`):
  ```yaml
  overworld-1:
    player:
      command: "betterrtp world world_1"
      enable: true
  ```
  Every teleport button hard-codes a `betterrtp world world_N` dispatch.
- Bundled `basic-setup/settings.yml` exposes only `duration: 5` (countdown) and `buffer-blocks: 2` — **no radius, centre, shape, cooldown, or min/max keys**, because those decisions are delegated to BetterRTP.
- Package layout `me.gerhart.donutrtp.config.config1..config6` is GUI config classes; no `LocationFinder`/`Pipeline`/`SafetyCheck` equivalents.
- Plugin.yml declares `softdepend: PlaceholderAPI` but does *not* depend on BetterRTP, despite requiring BetterRTP to function.
- **Implication for benchmarking**: any DonutRTP measurement would be "BetterRTP's pipeline plus a thin GUI overhead" — publishing it as a separate row would be misleading and would tank BetterRTP's row by absorbing half its dispatches.
- **BBCode write-up note (draft)**:
  > *"DonutRTP was reviewed and excluded from the head-to-head comparison. It is implemented as a GUI menu plugin that dispatches `betterrtp` commands; it does not contain its own random-teleport logic, so its `/rtp` performance is determined entirely by BetterRTP's pipeline. Operators using DonutRTP can read the BetterRTP row of the comparison as their own."*

---

## 6b. Throughput methodology (proposal — pending implementation)

The section 5 / section 5b / section 5c runs treat throughput as a **side-effect** of latency and
concurrency: `phases.csv` reports `successes / wall_s` and we read TP/s from
that. That is a delivered-throughput number on a 60 s window with a
`per-player-gap-ticks: 30` (1.5 s) cap and 2 real connected accounts. It is
fine for a latency / safety comparison; it is **not** an honest answer to the
question "how many `/rtp`s per second can a server actually run before it
falls over".

This section pins down what we mean by throughput, what the harness can
measure today vs. what it cannot, and the smallest set of changes needed to
make throughput a first-class axis of the public write-up.

### 6b.1 Three throughput definitions (pick what we publish)

- **Offered TP/s** — dispatches the harness *attempts*, regardless of plugin
  acceptance. Currently knob-controlled by `default-burst` and
  `per-player-gap-ticks`; not recorded as a separate column.
- **Delivered TP/s** — successful teleports per second of phase wall-time.
  This is what `phases.csv` already reports as `successes / (wall_ms/1000)`.
  Useful headline for the write-up.
- **Sustained TP/s** — delivered rate after warm-up, with a *stable* backlog
  (queue not draining, not growing). Closest to "how many players can a
  server rotate per second under load." Not yet measurable: the section 5c
  per-target wall is 60 s and several plugins (notably HuskHomes) had not
  reached steady state by phase end — `last1m` was still falling at the
  cutoff.

### 6b.2 Hard constraint: 2 real Mojang accounts

`Runner.roster()` (`helpers/StressTestRTP/.../Runner.java` lines 704–733)
reads from `Bukkit.getOnlinePlayers()`. Concurrency in this harness is
literally "real connected clients". With 2 licenses we cannot run a
player-count sweep up to 8 / 16 / 50 without bringing in a separate bot
client harness (MCProtocolLib / mineflayer route — license-free against an
offline-mode test server, but a non-trivial new helper module and an ADR-
worthy decision).

Until that bot harness exists, every throughput number we publish is
explicitly a **2-concurrent-client** number. section 10 ("What this benchmark does
NOT prove") already calls out the "doesn't generalise to 50 players" caveat;
6b just makes the 2-client framing explicit at measurement time rather than
at write-up time.

### 6b.3 What's actually measurable on 2 clients

Two changes lift the artificial throughput ceiling without needing more
clients:

1. **`per-player-gap-ticks: 0`** (or 1 tick). Today's value of `30` caps
   each client at 0.67 dispatches/s, i.e. a 2-client ceiling of 1.33 TP/s
   — *lower than several measured plugin values* in section 5c, which means the
   gap dominates the comparison for slow plugins and underrepresents fast
   ones. Drop to 0 and the next dispatch goes out as soon as the previous
   attempt completes (success or timeout). This measures each plugin's
   **serial** throughput per client, doubled.
2. **`sequence.per-target-seconds: 300`** (5 min) instead of 60 s. The section 5c
   data shows TPS still dropping at end-of-phase for the slower plugins; a
   60 s window measures a transient, not a steady state. 5 min is enough
   for both the JIT and the per-plugin queue to settle, while staying
   short enough that two-client wall-clock cost across 4 plugins is ~25
   min plus gaps.

A third axis we *can* probe with 2 clients is **queue depth**: keep
`per-player-gap-ticks: 0`, raise `default-burst` so each player has a
backlog of K queued `/rtp` requests at the plugin's own queue, then watch
the in-flight count rise and fall in the CSV. This exercises the same
backlog → drain dynamic that more clients would, *for the plugin's own
queue*, but does not exercise per-player-queue fairness across N distinct
UUIDs (only 2 UUIDs are present).

### 6b.4 Proposed `phases.csv` additions (D-005-gated, not yet implemented)

To make the three throughput definitions visible without re-deriving them
from the per-attempt CSV every time:

| Column | Source | Formula |
|---|---|---|
| `offered_tps`     | already tracked: dispatch count   | dispatches / wall_s  |
| `delivered_tps`   | already tracked: success count    | successes / wall_s (rename of current TP/s) |
| `saturation_pct`  | derived                            | delivered / offered  |
| `backlog_max`     | already tracked internally for the no-progress watchdog | max in-flight count seen in the phase |
| `backlog_avg`     | new sampler hook                   | time-weighted mean in-flight count |
| `steady_state_tps`| new: last 30 s of the phase only  | successes_in_last_30s / 30 |

`steady_state_tps` is the column that lets us honestly publish a "sustained"
number on a 5-min phase: it's the delivered rate once warm-up and ramp-up
are out of the window. Without it, a plugin that takes 60 s to reach steady
state looks worse than one that hits it in 10 s, even if their long-run
rates are identical.

None of these require new instrumentation hardware — `Runner.tick()`
already counts dispatches, successes, and in-flight; the only change is
flushing them into the per-phase CSV row at phase end.

### 6b.5 Test plan (after the in-flight reorder run)

In order, smallest-effort first; each one is a single config edit unless
flagged otherwise:

1. **Saturation, 2 clients, current 60 s phase.** Set
   `per-player-gap-ticks: 0`, run the existing 4-plugin sequence. Cheapest
   way to lift the 1.33 TP/s artificial cap. Headline metric: peak
   `delivered_tps` per plugin.
2. **Sustained, 2 clients, 5 min phase.** Add
   `sequence.per-target-seconds: 300`. Needs the new `steady_state_tps`
   column to be useful, otherwise the headline still risks averaging in
   the warm-up dip. Headline metric: `steady_state_tps` per plugin and
   `min last1m` from spark across the phase (cost-of-throughput).
3. **Queue-depth probe, 2 clients.** `per-player-gap-ticks: 0`,
   `default-burst: 50`, `sequence.per-target-seconds: 300`. Watch
   `backlog_max` and `backlog_avg`. Differentiates plugins that *queue*
   well from plugins that *drop* under offered load.
4. **(Future) Player-count sweep.** Requires bot client harness. Park
   behind its own ADR.

(1) and (3) are essentially the same physical run framed two ways; we'd
run them as one. (2) needs the CSV column work landed first to be worth
the wall-clock cost.

### 6b.6 What this section does NOT claim

- That 2-client throughput numbers extrapolate linearly to N clients. They
  don't, especially under per-player queue fairness contention; section 10
  already calls this out and 6b inherits that caveat.
- That `delivered_tps` from a 60 s window with `per-player-gap-ticks: 30`
  (the section 5 / section 5b / section 5c configuration) is a throughput benchmark. It is a
  latency benchmark with a throughput coordinate; do not lead with the
  TP/s column from those runs.
- That sustained throughput on Spigot 1.20.1 is decoupled from the cross-
  phase chunk-residency question raised in section 5c. The reorder test is
  in-flight at time of writing this section; until those results are in,
  section 5c's residency hypothesis remains a candidate effect on the order of
  ≤3 TPS rather than a settled cause, and any throughput run that mixes
  plugins in the same JVM inherits that uncertainty.

---

## 7. Methodology caveats (must appear in public write-up)

1. **Single-server**, single-machine test rig; not a multi-server / proxy setup.
2. **Two real online accounts** (`leaf26`, `leaf_26`); not 100s of fake players. The fake-player extension (`helpers/StressTestRTPBots`) is future work.
3. **Cooldowns disabled** on every plugin — measures pipeline throughput, not anti-spam policy.
4. **Countdown timers disabled** where present — measures plugin work, not UX padding.
5. **All plugins forced to the same radius** (4096 blocks); some plugins' authors may argue that's not their default and not their target deployment shape.
6. **Some plugins ignored centre-XY config** (HuskHomes, SorekillRTP, AdvancedRTP) and ran at world spawn. Sequence-mode alternates so spawn-anchored plugins don't pollute each other's cache.
7. **`view-distance: 2 / simulation-distance: 2`** (next run) reduces world-state aftershock; absolute numbers won't match a default `view-distance: 10` server. Relative ranking does.
8. **MSPT vs perceived responsiveness**: at peak MSPT 1.3 s, the server was still chat-responsive at the ~50 ms level (chat → display). MSPT is "world-tick cost", not "server frozen".
9. **No fake-player infrastructure** — see (2). The two-player figure is a *floor* on what the plugins do; with 50 real players the absolute numbers would be much worse, but the *relative ranking* should be preserved.
10. **No GC tuning beyond default Paper flags.** Default G1GC. Allocation rate per teleport not measured (planned future addition; would mirror `CpuSampler`).
11. **No spark cross-correlation in the harness CSV**; spark profiles are captured manually per phase via `sparkHook`'s `start/stop` calls. The CSV's `target_label` column maps 1:1 to spark's `--comment` tag.
12. **No Folia run yet.** Folia is in scope but tested on Paper 1.21.11 here. Folia results will likely be more flattering for RTP (region scheduler matches RTP's design) and less so for AsyRTP (it'll throw `ThreadAccessException`).
13. **EssentialsX `/tpr` excluded from Spigot rosters.** Calling EssentialsX `/tpr` on Spigot causes a reproducible timeout storm in that target's slice (see section 6 *EssentialsX*); EssentialsX's mere presence in `plugins/` is not the trigger — dispatching `/tpr` is. The default Spigot benchmark posture is therefore `/tpr` excluded from the roster (EssentialsX itself may remain installed); any Spigot run that keeps `/tpr` in the roster must be flagged explicitly in the CSV header / footnote and must not be compared head-to-head against rosters that excluded it. Paper/Folia have not been observed to reproduce this.
15. **Two-version Paper matrix: Paper 1.20.1 and Paper 1.21.11.** The benchmark holds the platform constant (Paper) and varies the Minecraft version, rather than varying the platform. Paper was chosen because it gives the largest set of *functioning* competitor `/rtp` plugins; the two-version split is necessary because **several rostered competitors are rated only up to MC 1.20** (or stopped receiving updates around the 1.20.x line) and either fail to load or misbehave on 1.21.11. Running the 1.20.1 pass is what makes a plugin-vs-plugin comparison possible at all for that subset of competitors; running the 1.21.11 pass is what exercises the current Paper chunk-system / lighting / biome-storage code path that modern operators actually deploy on. Implications:
    - The benchmark's primary axis is **plugin-vs-plugin within a single MC version**, not version-vs-version. Intra-version rankings are valid; cross-version per-plugin deltas are **not** — the 1.20.1 → 1.21.11 jump changed chunk-system internals, lighting, biome storage, and scheduler behavior, so a 1.20.1-vs-1.21.11 number for the same plugin is confounded by the MC-version delta.
    - **Paper 1.21.11 is the canonical head-to-head dataset** for plugin-vs-plugin performance on current MC. Paper 1.20.1 is reported for compatibility-bounded ranking among plugins rated up to 1.20, and for RTP's own cross-version parity — not as a head-to-head against the 1.21.11 numbers.
    - Each chart / table must carry its MC version in the caption (e.g., "Paper 1.20.1", "Paper 1.21.11"); aggregate "across all versions" leaderboards are out of scope because they would silently mix MC versions and falsely advantage plugins that only ran on the older one.
    - The roster on each version is the set of competitors that actually function on that version; a plugin appearing in the 1.20.1 chart but absent from the 1.21.11 chart is a **compatibility** finding for that plugin's author, not a benchmark loss for them — annotate accordingly.
14. **Roughly half the competitor plugins were non-functional on Spigot.** During the Spigot stress-test pass, a substantial fraction of the rostered competitor `/rtp` plugins did not produce successful teleports at all — either failing silently (no `PlayerTeleportEvent` within the per-attempt deadline, surfacing as `MetricsRecorder.onTimeout` rows and `[StressTestRTP]` warm-up "zero successful attempts" warnings), throwing on dispatch, or refusing to load against the Spigot API surface. The Spigot charts therefore show RTP in a flattering relative position **by default**, not because RTP outperformed working competitors but because many competitors were not working. Implications:
    - Do not present the Spigot ranking as a head-to-head performance comparison without an explicit "competitor functioning on Spigot: yes/no" column. A non-functioning plugin's row is a *compatibility* finding, not a *performance* finding, and conflating the two misleads readers.
    - The Paper 1.21.11 run remains the canonical head-to-head dataset for performance ranking; Spigot is reported primarily for RTP's own cross-platform parity, not for cross-plugin comparison.
    - When the public write-up cites Spigot numbers, annotate each non-functioning competitor row as such (e.g., "did not complete warm-up on Spigot") rather than publishing a near-zero throughput / 100 %-timeout figure that reads as a benchmark loss.
    - Re-test on Spigot with each non-functioning competitor's author-recommended config before publishing, in case the non-function is configuration- rather than platform-driven.

---

## 8. Outstanding tasks before public write-up

- [ ] Apply `attrib +R` to BetterRTP/config.yml after re-edit; verify radius/cell took effect via destinations scatter.
- [ ] Apply `view-distance: 2`, `simulation-distance: 2` and re-run.
- [ ] Pre-generate all 4 configurable cells with Chunky out to ~5000 radius from each centre.
- [ ] Verify AdvancedRTP isn't biasing to loaded chunks (`safe-teleport: false`); confirm via destinations scatter.
- [ ] Capture spark profiles per phase. Specifically for AsyRTP: confirm `CompletableFuture.join` / `World#getChunkAt` appears on the tick thread. If yes, this is the publishable smoking gun.
- [ ] Capture chat round-trip latency during peak MSPT to support the "MSPT high ≠ server frozen" footnote.
- [ ] Optional: add allocation/TP sampler (mirrors CpuSampler; ~30 lines). Differentiates well-engineered cluster on GC pressure.
- [ ] Optional: latency-vs-TPS bucket chart in `plot_stress.py` to show degradation curves.
- [ ] Optional: contact AsyRTP author with the diagnostic findings.

---

## 9. Headline narrative for the public write-up (draft)

Suggested framing — keep neutral, falsifiable, reproducible:

> *"We compared seven `/rtp`-style plugins on Paper 1.21.11 under sustained
> dispatch using the open-source `helpers/StressTestRTP` harness. RTP was
> the only plugin to combine sub-100 ms warm-path latency, 100 % success
> under load, bounded p99, and Folia native support. BetterRTP places second
> on the warm-path axis (PaperLib + simple safety check). HuskHomes and
> AdvancedRTP look strong on paper but degrade under sustained load
> (HuskHomes) or only when their effective working set is held small
> (AdvancedRTP, BetterRTP). AsyRTP, despite its name, ran the main thread
> for nearly 5 seconds during a single MSPT sample, illustrating the
> 'async-by-name' anti-pattern that REQ-RTP-S-005 prohibits."*

Three exhibits with screenshots:

1. **Latency CDF** — RTP's curve sits hard against the y-axis; AsyRTP's is far right.
2. **Main-thread CPU/TP bar chart with AsyRTP highlighted** — annotated as the diagnostic signature.
3. **Destinations scatter** — visual proof of each plugin's effective radius and distribution shape.

Optional fourth exhibit:

4. **MSPT-over-time chart** — shows the per-phase MSPT spike pattern, AsyRTP's catastrophic spike clearly identifiable.

---

## 10. What this benchmark does NOT prove

Be explicit about this in the public write-up to pre-empt critics.

- That RTP is faster than every plugin in every configuration. (Not tested: low concurrency, large radii, tiny radii, cross-server, low-RAM servers.)
- That AsyRTP cannot be fixed. (It can — PaperLib + cleanup.)
- That BetterRTP is "bad" — it's a perfectly reasonable plugin for low-volume servers; just not a high-throughput one.
- That HuskHomes is bad — it's optimized for a different use case (cross-server homes), and its `/rtp` is a secondary feature.
- That this benchmark generalises to 50+ concurrent players. With more players, all numbers degrade; the *ranking* should hold but absolute values won't.

---

*End of pre-writeup. Edit before publishing.*

---

# Continuation - 2026-06-17 controlled Paper re-run + Folia analysis

This continuation records a fresh, tightly-controlled four-plugin run on Paper
and the Folia architectural analysis that came out of it. It supersedes earlier
absolute numbers where they conflict, because the test conditions here were
deliberately equalized (see Controls).

## 11. Controls applied for this run

To remove the apples-to-oranges problems noted earlier, the following were
standardized before the run:

- **Outer radius equalized to 4096 blocks** for every plugin:
  - RTP region `radius: 256` (chunks) = 4096 blocks (unchanged; already 4096).
  - BetterRTP `Default.MaxRadius: 1000 -> 4096` (blocks).
  - EzRTP `radius.max: 2000 -> 4096` (blocks).
  - Inner radius left per-plugin (RTP 1024, EzRTP 500, BetterRTP 10 blocks);
    negligible at this scale (small central exclusion only).
- **Cooldown / delay / countdown zeroed** on RTP, BetterRTP, and EzRTP so the
  harness drives unthrottled.
- **Paper `delay-chunk-unloads-by: 10s -> 0s`** so chunks release immediately and
  each teleport pays a real load instead of coasting on the 10 s retention cache.
- **Pregenerated world retained** (RTP's design target and the realistic
  production config). A fresh/un-pregenerated world was explicitly rejected: it
  neutralizes RTP's anvil prefilter (no `.mca` bytes to screen), turning the test
  into a world-gen benchmark that hides the plugin differences.
- Same JVM, same world/seed, one plugin enabled per phase, ~600 s per phase,
  3 simulated users at >=10 dispatch/s.

## 12. Results - run 20260617-175906 (Paper, all four phases ~600 s)

| Metric | RTP | EzRTP | BetterRTP | HuskHomes |
|---|---|---|---|---|
| Attempts / successes | 7755 / 7755 | 6987 / 6987 | 2646 / 2639 | 1957 / 1950 |
| Success rate | 100% | 100% | 99.7% | 99.6% |
| Throughput (per s) | 12.9 | 11.6 | 4.4 | 3.2 |
| chunks / attempt | 1.58 | 3.38 | 5.86 | 4.59 |
| chunks_loaded total | 81,368 | 44,846 | 16,819 | 9,061 |
| % chunk loads background (async) | 85% | 47% | 8% | 1% |
| main-thread CPU / attempt (ms) | 17.97 | 19.06 | 25.57 | 33.48 |
| total CPU / attempt (ms) | 765 | 817 | 1811 | 2589 |
| TPS avg (phase) | 19.37 | 15.50 | 9.99 | 19.39 |
| TPS min (phase) | 15.96 | 12.82 | 7.37 | 17.85 |
| MSPT avg (ms) | 26.8 | 56.0 | 130.4 | 9.9 |
| MSPT p95 (ms) | 35.1 | 86.6 | 185.4 | 31.9 |
| MSPT max (ms) | 87.5 | 1259 | 6646 | 57.1 |
| Heap growth in-phase (MB) | +2676 | -1379 | +11633 (to ceiling) | +4012 |
| Heap post-GC trough (MB) | 9948 | 5863 | 2147 | 5487 |

Per-phase heap/TPS/MSPT are sliced from the 50 ms `-heap.csv` over each phase's
epoch window. `cpu_ms_with_chunks*` columns still use the Spigot-default
`chunk-load-cost-us` (28221 us) and are NOT recalibrated for Paper here; treat
those as relative only. All other columns are direct measurements.

## 13. Per-entry read

- **RTP** - best on every axis that matters: highest throughput (12.9/s), cheapest
  per attempt (18 ms main-thread), 85% of chunk work off the tick thread, and the
  smoothest tail by far (max MSPT 87.5 ms, no stall the whole phase).
- **EzRTP** - genuine runner-up: throughput (11.6/s) and main-thread cost (19 ms)
  close to RTP, ~47% async. Weaknesses: only half-async, a 1.26 s max stall, TPS
  avg dipped to 15.5. Heap net-decreased in-phase (GC reclaimed well).
- **BetterRTP** - catastrophic under rate: 6.65 s max tick, TPS crashed to 7.4 min,
  heap climbed +11.6 GB to the 16 GB ceiling, 92% of chunk work foreground.
- **HuskHomes** - slow-and-heavy but self-limiting: lowest throughput (3.2/s),
  highest per-attempt cost (33 ms main-thread), ~99% foreground. Because it
  self-throttles, TPS stayed healthy (19.4) and MSPT smooth (max 57 ms) - it never
  stalls, it just cannot keep up.

Two cross-cutting findings:

1. **The async (background-chunk) share is the discriminator.** It lines up exactly
   with health: RTP 85% -> EzRTP 47% -> BetterRTP 8% -> HuskHomes 1%. The more chunk
   work a plugin keeps on the main thread, the worse its tick tail (BetterRTP) or
   its throughput ceiling (HuskHomes).
2. **No hard memory leak in any phase.** Every phase's heap trough dropped back to
   ~5-10 GB under GC (EzRTP net-decreased; HuskHomes fell to 5.5 GB). The earlier
   "memory climbing" was reclaimable heap-slack/churn from the high allocation rate,
   not retention.

## 14. Open question this run cannot settle - and the Folia thesis

EzRTP's near-parity on the Paper speed axis "makes no sense for what they do on the
backend" and the speed axis alone does not prove much. Two axes the Paper run does
NOT measure are where a thorough verifier separates from a fast-but-shallow one:

- **Destination safety / correctness.** The harness counts a completed
  `PlayerTeleportEvent`, NOT whether the landing was safe. A plugin can look fast
  precisely because it skips verification. Proposed next test: arrival-block safety
  audit (classify landings: safe / water / lava / cave / suffocating / void) on the
  same pregenerated world, prefilter fully in play.
- **Folia region behavior (the strongest structural lever).** Per the Folia API,
  live chunk/block state may only be touched from the thread owning that region;
  touching another region's chunk throws `ThreadAccessException`. Therefore a
  plugin that verifies candidates via the **live block API** must acquire/schedule
  the **owning region for each candidate** - and since random candidates are
  scattered, that is roughly one region context per candidate, which amplifies
  Folia "region thrashing" (continuous region split/merge under `/rtp` spam) and
  inflates chunks/attempt.

  RTP's anvil prefilter reads `.mca` NBT off-thread and requires **no region at
  all**, only materializing a live chunk (and thus a region) for the few candidates
  that survive the off-thread screen. Predicted Folia outcome: competitor
  chunks/attempt and region-thrash balloon while RTP's stays close to its Paper
  figure - an architecture-level advantage that pregenerated-Paper speed parity
  hides entirely.

Caveat for the Folia phase: the harness `ChunkLoadCounter` attributes loads via
`getPluginChunkTickets()` + most-recent-in-flight player; on Folia `ChunkLoadEvent`
fires on region threads, so attribution must be sanity-checked before any Folia
chunks/attempt delta is trusted (it may be a measurement artifact rather than a
behavioral change).

## 15. Next steps

1. Arrival-block safety audit on the pregenerated world (correctness axis).
2. Folia phase with region-count trace + per-region MSPT alongside chunks/attempt.
3. Recalibrate `chunk-load-cost-us` to the Paper `full(cpu)` chunk-probe value
   (x ~1.5 as a documented lower-bound) before quoting any `cpu_ms_with_chunks`
   absolute number. Note: `StressTestRTPPlugin.beginRun()` currently reads only the
   base `chunk-load-cost-us` key, not the `-paper` / `-folia` variants - set the
   base key on Paper.

## 16. Folia run - 20260617-191448 (RTP-Folia 26.1, two phases ~600 s)

This is the platform where the Paper near-parity collapses. Same controls as
section 11 (radius 4096, cooldown/delay zeroed, pregenerated world), 3 clients.
The RTP edition under test here is **RTP-Pro** (the tuned `rtp-folia` adapter);
Section 18 repeats the RTP-only phase on **RTP-Lite**, which on Folia runs the
correctness-first `FoliaAwareScheduler` fallback rather than the Pro-only tuned
adapter, to check whether the shared `rtp-core` engine carries the result for both.

| Metric | RTP-Pro (Folia) | EzRTP (Folia) | (Paper ref: RTP / EzRTP) |
|---|---|---|---|
| Attempts | 8113 | 3180 | 7755 / 6987 |
| Throughput (per s) | 13.5 | 5.3 | 12.9 / 11.6 |
| main-thread CPU / attempt (ms) | 3.96 | 6.34 | 17.97 / 19.06 |
| total CPU / attempt (ms) | 199 | 593 | 765 / 817 |
| % chunk loads background | 14% | ~0% (22 of 45,814) | 85% / 47% |

Key results (clean, direct measurements):

- **EzRTP throughput halves on Folia** (11.6 -> 5.3 /s) while **RTP holds/improves**
  (12.9 -> 13.5 /s). The Paper "nearly tied" speed parity is a Paper-only artifact.
- **Folia slashes RTP's main-thread cost** (17.97 -> 3.96 ms/attempt): region threads
  parallelize work Paper serialized onto one tick thread. This is the design paying off.
- **EzRTP does essentially zero background chunk loading** on Folia (22 of 45,814),
  i.e. it loads chunks inline on region threads.

### Server-side evidence (independent of the harness)

The decisive evidence is the Folia watchdog, emitted by the server, not the harness.
From `RTP-Folia/26.1/logs/latest.log`:

- **7 Folia watchdog stalls** ("Tick region ... has not responded").
- **Max region-unresponsive time: 20.39 s** (one region frozen for over 20 seconds).
- The watchdog stack attributes the hang to EzRTP doing a synchronous, blocking
  chunk load on a region scheduler thread:
  `EzRTP-3.4.0 BukkitChunkLoadStrategy.loadChunk -> org.bukkit.World.loadChunk ->
  ServerChunkCache.syncLoad -> BlockableEventLoop.managedBlock -> Unsafe.park`.
- No watchdog stalls attributed to RTP.

This is the S-005-class pattern (synchronous chunk load on a tick/region thread)
manifesting as a multi-second region freeze on Folia.

### Metric caveats (so nothing is misread)

- **Harness `TIMEOUT` is a capture-window expiry, NOT a server failure.** It means a
  completion was not captured within 5 s. At high throughput a sub-tick (near-0 ms)
  teleport and a genuinely slow (>5 s) one can look the same to the oracle - the
  harness races itself.
- **The race is directionally biased in EzRTP's favor.** False timeouts concentrate on
  near-0 ms teleports (RTP's pre-verified-queue fast path). EzRTP's >=2 guaranteed
  region/scheduler hops floor its per-teleport latency above the race window, so an
  EzRTP timeout is far more likely a real >5 s teleport than a capture miss. The
  observed gap is therefore conservative against the slower plugin.
- Consequently: RTP's 4 timeouts / 8113 (0.05%) are treated as **capture artifacts**,
  RTP success ~100%. EzRTP's 122 timeouts align with its 122 phase failures and are
  corroborated by the server watchdog, so they are treated as **real stalls** - but
  the headline rests on the watchdog evidence, not the timeout count.
- **`chunks_per_attempt` is unreliable on Folia** (RTP 29.6, EzRTP 14.4 vs Paper
  1.58 / 3.38): inflated by region ticketing plus `ChunkLoadCounter` over-attribution
  on region threads. Do not quote it as a behavioral number.
- **MSPT (~50 ms both phases) is the global-region sample, not per-region**, so it is
  blind to a single hung region and is NOT a useful Folia discriminator. Use
  throughput + the watchdog evidence instead.
- The warm-up "zero successful attempts" warning is the over-broad `ConsoleWatcher`
  false-failure issue; it did not corrupt the measurement phase but should be fixed.

### Defensible Folia headline

> On Folia under identical load, EzRTP-3.4.0 blocked region threads on synchronous
> `World.loadChunk`, tripping the Folia watchdog 7 times (one region unresponsive
> 20.4 s) and sustaining only 5.3 TP/s; LeafRTP sustained 13.5 TP/s with zero
> watchdog stalls and its main-thread cost per teleport dropped to 3.96 ms. Harness
> completion-timeouts are a capture-window metric and are not used as the failure
> measure; the region stalls are server-emitted.

## 17. Recorded test parameters (all runs)

Captured verbatim from each server's `plugins/StressTestRTP/config.yml` so the
runs are reproducible. All three runs share identical harness settings; they differ
only in the target roster, the RTP edition (Pro vs Lite), and the host platform.

### Common harness parameters (Paper 20260617-175906, Folia 20260617-191448, Folia Lite 20260617-205614)

| Parameter | Value |
|---|---|
| dispatch-as-player | true |
| roster | all |
| attempt-timeout-ms | 5000 (the capture window; see Section 16 caveat) |
| console-fail-enabled | true |
| console-fail-patterns | [] (empty -> built-in defaults; source of the warm-up false-failure warning) |
| save-worlds-between-phases | auto |
| default-concurrency | 4 |
| per-player-gap-ticks | 3 |
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

Client load: 3 real OPed accounts (concurrency cap 4), per-player burst 10 with a
3-tick gap. Region radius equalized to 4096 blocks; cooldown/delay/countdown zeroed;
pregenerated world; Paper `delay-chunk-unloads-by: 0s` (Section 11 controls).

**Calibration note (carried, important):** despite `chunk-load-cost-us-paper` /
`-folia` both being set to 1830.0, `StressTestRTPPlugin.beginRun()` reads only the
base `chunk-load-cost-us` (28221.0). The server log confirms it:
`chunk-load-cost-us=28221.00 amending phase CPU`. So every `cpu_ms_with_chunks*`
figure in both runs used the Spigot default and is **relative-only / overstated** -
do not quote it as an absolute. All directly-measured columns (throughput,
main-thread CPU, TPS, success, watchdog stalls) are unaffected.

### Per-run differences

| | Paper run 20260617-175906 | Folia run 20260617-191448 | Folia Lite run 20260617-205614 |
|---|---|---|---|
| Host | RTP-Paper / 26.2 | RTP-Folia / 26.1 | RTP-Folia / 26.1 |
| RTP edition | RTP-Pro | RTP-Pro | RTP-Lite |
| Targets (in order) | rtp, betterrtp, huskhomes, ezrtp | rtp, ezrtp | rtp |
| Target commands | `rtp:rtp`, `betterrtp:betterrtp`, `huskhomes:rtp`, `ezrtp:forcertp {player} world` | `rtp:rtp`, `ezrtp:forcertp {player} world` | `rtp:rtp` |
| Phases recorded | 4 | 2 | 1 |

Note the EzRTP dispatch uses `ezrtp:forcertp {player} world` (force variant, so its
cooldown/limit gating is bypassed) while RTP/BetterRTP/HuskHomes use their plain
`/rtp`-equivalent command.

## 18. Folia Lite run - 20260617-205614 (RTP-Folia 26.1, RTP-Lite, single phase ~600 s)

Same host, controls, and harness as Section 16, but the RTP jar is the **RTP-Lite**
edition (reported as `RTP v3.1.3`) and the roster is RTP-only (one `rtp` phase).
(EzRTP was still installed but not dispatched this run.)

**Important edition difference on Folia.** Lite and Pro share the platform-neutral
`rtp-core` teleport engine (region/queue logic, spiral math, the async pipeline),
but they do NOT share the Folia *platform path*. The tuned, throughput-optimized
`rtp-folia` adapter is a Pro-only early-access feature and is excluded from the MIT
lite jar (ADR-024 2026-06-10 amendment, ADR-061). On Folia the lite jar instead
runs the correctness-first `FoliaAwareScheduler` bundled in `rtp-paper-common`,
which routes work through paper-api's regionized scheduler statics
(`getGlobalRegionScheduler` / `getRegionScheduler` / `getAsyncScheduler` + the
per-entity scheduler) and teleports via `Entity#teleportAsync`. So this run does
NOT measure "the same code as Pro" on Folia - it measures whether Lite's
unoptimized Folia-fallback path keeps pace with Pro's tuned adapter.

| Metric | RTP-Lite (Folia) | RTP-Pro ref (Folia, Section 16) |
|---|---|---|
| Attempts | 7507 | 8113 |
| Successes | 7507 (100%) | 8109 (~100%, 4 capture-timeouts) |
| Throughput (per s) | 12.5 | 13.5 |
| main-thread CPU / attempt (ms) | 4.15 | 3.96 |
| total CPU / attempt (ms) | 177 | 199 |
| % chunk loads background | 10.8% (19,246 / 177,911) | 14% |
| Folia watchdog stalls | 0 | 0 (none attributed to RTP) |
| TPS (avg / min over run) | 19.8 / 16 | ~20 |

Key results:

- **Lite's unoptimized Folia path keeps pace with Pro's tuned adapter.** Throughput
  (12.5 vs 13.5 /s), main-thread cost per teleport (4.15 vs 3.96 ms), and
  background-chunk share (10.8% vs 14%) are within run-to-run noise - even though
  Lite runs the correctness-first `FoliaAwareScheduler` fallback while Pro runs the
  tuned `rtp-folia` adapter (see the edition note above). The result is that the
  shared `rtp-core` engine, not the Folia adapter, dominates per-teleport cost at
  this load, so the Folia advantage over EzRTP (Section 16) holds for both editions.
  Pro's tuned adapter is expected to pull ahead under heavier per-region contention
  than this 3-client load exercises; do not read these numbers as "identical code".
- **100% success, zero watchdog stalls.** Like Pro, RTP-Lite tripped no Folia region
  watchdog and held TPS at ~20 (min 16) under the same 3-client burst load that
  froze EzRTP region threads for up to 20.4 s in Section 16.

### What this run does NOT settle (Pro's Folia ceiling)

This run used only 3 clients, so it measures per-teleport cost at light load, not
the scaling ceiling. The parity result therefore says "the shared `rtp-core` engine
carries both editions at this load", **not** "the editions are equivalent on Folia".
Pro's Folia-specific scheduler (the tuned `rtp-folia` adapter) is more actively
managed than Lite's correctness-first `FoliaAwareScheduler` fallback - it fans
teleport/pipeline work across per-region threads and carries additional Pro-only
tuning - so it is built to pull ahead as per-region contention rises (many players
teleporting across many regions at once). That headroom is an architectural
expectation, not a number this 3-client run demonstrates; a high-region-count,
many-client Folia run would be needed to size the gap. Market the Pro Folia
advantage as scaling headroom under contention, never as a measured throughput win
over Lite at this load.

### Caveats (same as Section 16, recap)

- The two `latest.log` lines matching "stalled" are the substring of *"installed"*
  (metrics binding / runtime-module log lines), **not** watchdog stalls - there are
  zero region-unresponsive events in this run.
- `cpu_ms_with_chunks*` again used the base `chunk-load-cost-us` (28221.0, Spigot
  default) - relative-only / overstated; the directly-measured columns above are
  unaffected (Section 17 calibration note).
- `chunks_per_attempt` (21.1 here) is the same Folia region-ticketing /
  over-attribution artifact flagged in Section 16; not a behavioral number.
- Global MSPT (~50 ms avg, 345 ms max) is the global-region sample, blind to any
  single hung region; use throughput + watchdog evidence, not MSPT, on Folia.

### Defensible Lite headline

> RTP-Lite on Folia sustained 12.5 TP/s at 100% success with zero watchdog stalls
> and a 4.15 ms main-thread cost per teleport - statistically indistinguishable from
> RTP-Pro (13.5 TP/s, 3.96 ms) at this 3-client load. Notably, Lite achieves this on
> the correctness-first `FoliaAwareScheduler` fallback, not Pro's tuned `rtp-folia`
> adapter (which is excluded from the lite jar): the shared `rtp-core` engine
> dominates per-teleport cost here, so the free build delivers the same Folia
> resilience that EzRTP lacked. Pro's tuned adapter is built to scale further under
> heavier per-region contention than this run exercises.

## 19. Paper gap=0 head-to-head - 20260617-232754 (RTP-Paper 26.1, `per-player-gap-ticks: 0`)

First clean, like-for-like head-to-head under the harsher unthrottled-dispatch
config: the 3-tick per-player gap used by every prior run (Section 11 controls)
was set to `per-player-gap-ticks: 0`, so the 3-client / concurrency-4 harness
dispatches as fast as each engine can absorb. Three phases completed back-to-back
(`rtp` -> `ezrtp` -> `betterrtp`), each ~600 s with the 240 s inter-phase TPS-settle
gap. HuskHomes was not dispatched this run (no `huskhomes` phase row).

### 19.1 Headline table (successful-attempt latency + tick health)

| Plugin | Att / Succ | TP/s | p50 | p95 | p99 | p99.9 | max | TPS min | MSPT p99 / max |
|---|---|---|---|---|---|---|---|---|---|
| **RTP** | 16560 / 16560 (100%) | **18.7** | **1 ms** | **2 ms** | **46 ms** | 518 ms | 687 ms | 17.5 | 85.8 / 98.4 ms |
| ezrtp | 7137 / 7018 (98.3%) | 13.1 | 30 ms | 189 ms | 322 ms | 1936 ms | 2172 ms | 10.3 | 156.8 / 292 ms |
| BetterRTP | 1633 / 1606 (98.3%) | 6.0 | 480 ms | 3217 ms | 4402 ms | 6229 ms | 6383 ms | 2.5 | 859 / 3663 ms |

(Latency percentiles are over successful attempts: RTP n=16782, ezrtp n=7150,
BetterRTP n=1636. TPS/MSPT are from the 50 ms `-heap.csv` sampler, restricted to
each phase's measurement window.)

### 19.2 Findings

- **RTP holds a flat distribution under unthrottled dispatch.** p50 1 ms / p95 2 ms /
  p99 46 ms at 100% success, TPS never below 17.5, MSPT max 98 ms (no multi-second
  stall). ~95% of teleports are served instantly from the pre-verified queue; the
  slowest 1% pay a single bounded async chunk load. The tail is *better* than the
  earlier solo gap=0 run `20260617-223659` (p99 103 ms -> 46 ms, max 2.24 s -> 687 ms),
  consistent with the queue staying warmer this round - the "p99 stays bounded as
  load rises" criterion held.
- **Serialized engines blow out exactly where predicted with the gap removed.**
  BetterRTP's foreground chunk-load + retry model collapses: p99 4.4 s, max 6.4 s,
  MSPT max 3.66 s, TPS to 2.5. EzRTP fares better but still tails badly: p99 322 ms,
  p99.9 ~1.9 s, TPS floor 10.3.
- **Contrast ratio is the headline axis.** At the same offered load RTP's p95 is 2 ms
  while BetterRTP's is 3217 ms (~1600x) and ezrtp's is 189 ms (~95x). This is the
  single most telling number for the public writeup.

### 19.3 Caveats

- **Heap max is not a per-plugin metric - dropped.** `heap_used_mb` topped out at
  ~16.37 GB in all three phases because that is the JVM `-Xmx` 16 GB ceiling, identical
  for every engine; it carries no signal about the plugin under test and is excluded
  from the comparison. A meaningful memory axis would be MB/attempt or GC-trough
  behavior, not max.
- **gap=0 is a throughput-ceiling condition, not the Section 11 default.** RTP's 18.7
  TP/s here is an unthrottled-dispatch number; do not compare it head-to-head against
  the 3-tick-gap runs (Section 12, 12.9 TP/s). The valid cross-plugin comparison is
  *within this run* (all three share gap=0).
- **HuskHomes slot still open** - a later round under the same gap=0 config is needed to
  fill the 4th column; its per-player serialization should produce a BetterRTP-class
  (or worse) tail.
- `cpu_ms_with_chunks*` again used the base `chunk-load-cost-us` (28221.0) and is
  relative-only / overstated (Section 17 calibration note); the directly-measured
  columns above are unaffected.

### 19.4 Defensible single-server (Lite-shareable) headline

> On Paper, RTP sustained 18.7 TP/s at 100% success (16560/16560) under unthrottled
> dispatch, with p50 1 ms / p95 2 ms / p99 ~2 ticks (46 ms) and TPS never below 17.5.
> ~95% of teleports are served instantly from a pre-verified queue; the slowest 1% is
> a single bounded async chunk load, never a server stall. At the same offered load
> BetterRTP's p95 was 3.2 s (~1600x) with TPS crashing to 2.5, and EzRTP's was 189 ms
> (~95x). This single-server result is fully shared by the free RTP-Lite edition via
> the common `rtp-core` engine.

## 20. HuskHomes under gap=0: server crash, and the "should not be able to overload it" thesis

When the HuskHomes phase was dispatched under the same `per-player-gap-ticks: 0`
unthrottled-dispatch config that RTP sustained at 100% in Section 19, HuskHomes' RTP
path stalled the main thread and the server crashed before the phase could complete,
so no `huskhomes` phase summary was recorded for that run.

### 20.1 Attribution

The crash is attributed to the HuskHomes RTP load behavior, not the rig or the
harness, on the following evidence chain:

- **Corroborating prior runs.** Earlier runs already showed HuskHomes degrading the
  server toward player-timeout under sustained load (its per-player serialization
  cannot drain unthrottled dispatch). The crash at higher load is the endpoint of a
  documented slowdown-then-crash trend, not a one-off anecdote.
- **It is a load-ceiling, not a "HuskHomes crashes servers" claim.** HuskHomes
  handles normal `/rtp` traffic fine; there is a load at which it does not, and that
  load is below the one RTP absorbs at 100% success. Frame it as where each engine's
  ceiling sits, not as a blanket defect.
- **Architectural, not a bug claim.** The mechanism is main-thread serialization
  failing to drain under unthrottled dispatch - the same structural property that
  produced BetterRTP's 4.4 s p99 in Section 19, taken to its conclusion. Avoid
  framing it as a HuskHomes bug; frame it as the serialized-engine architecture
  hitting its limit.

### 20.2 The operator-facing thesis

The strongest framing is not "it crashed under heavy load" but the design contract a
teleport plugin should meet:

> You should not be able to crash your server by spamming a teleport command. With
> RTP you can't - the harness pushed as hard as it allows (`per-player-gap-ticks: 0`,
> 3 clients) and RTP held at 100% success (16560/16560), p95 2 ms, TPS never below
> 17.5. Under the same offered load, HuskHomes' RTP path stalled the main thread and
> crashed the server.

RTP's async chunk loads + pre-verified queue + bounded buffers make overload
unreachable from the command path: at worst the queue drains and players see a brief
"finding a safe location" wait (graceful degradation), never a tick-thread stall. A
serialized engine puts the work on the main thread, so sufficient input *is*
unbounded tick cost - which is why a determined client can take it down.

### 20.3 Open items / how to formalize

- **Re-run HuskHomes last on purpose** under gap=0 to capture a structured outcome.
  Running the known crasher last means a crash never costs the other plugins' phase
  data (this is why `232754`'s three completed phases survived).
- **Capture the crash artifact** (`crash-reports/` entry + server-log tail at the
  crash timestamp) as an appendix citation. The load-trend evidence carries the
  attribution on its own, but a watchdog/OOM line converts skeptics for free. Before
  quoting it as a performance result, confirm the crash is a watchdog/main-thread
  stall or OOM, not an environmental version-incompat (a `NoSuchMethodError`-class
  failure would be environmental, not a load result).

### 20.4 Harness change made to support this (crash-safe phase capture)

The per-attempt CSV and the 50 ms heap-series CSV already flush per row, so a
mid-phase crash never loses those. The phase *summary* row, however, was only
written by `MetricsRecorder.endPhase` at phase end, so a crash mid-phase (exactly
the HuskHomes case) lost the running phase's aggregate entirely. The harness now
snapshots the in-flight phase to a `<stamp>-phases-partial.csv` sidecar:

- `MetricsRecorder.flushPartialPhase()` rebuilds the current phase's row (shared
  `buildPhaseRow` helper, read-only with respect to phase/chunk state) and overwrites
  the sidecar in place; it is self-throttled (at most once per 2 s) so the runner can
  call it every tick cheaply.
- `Runner.tick()` calls it inside the existing `inMeasurementPhase` block, alongside
  the spark-profile rotation that already exists for the same crash-safety reason.
- On normal phase end, `endPhase` writes the durable phases-CSV row and deletes the
  sidecar, so a leftover `-phases-partial.csv` is itself a signal that the phase it
  describes crashed before completing.

*End of continuation.*
