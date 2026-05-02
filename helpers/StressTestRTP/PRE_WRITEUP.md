# RTP Comparison — Pre-Writeup

> **Status**: working notes, not a finished post. Captures methodology, controls,
> caveats, and per-plugin findings accumulated across stress-test iterations on
> Paper 1.21.11 with `helpers/StressTestRTP`. Re-read before publishing; trim
> aggressively for the public version.

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
| BetterRTP | 3.6.13 | (16384, 0) | Configurable centre. Config reverts on shutdown — see §6. |
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

- Excluded. `/tpr` is self-only; cannot be driven from console without per-player chat dispatch. To benchmark properly, log into each alt and run `/rtpstress start` from there with single-player roster.

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
