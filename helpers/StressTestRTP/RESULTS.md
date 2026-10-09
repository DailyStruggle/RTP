# RTP - Performance Results

> **Headline.** On Paper, RTP sustained 18.7 teleports/s at 100 % success under unpaced dispatch with p95 2 ms and TPS never below 17.5. On Folia it held 13.5 TP/s with zero watchdog stalls while EzRTP froze a region for 20.4 s.

Measured against currently-shipping alternatives with the same harness, world, and hardware, using real connected clients. Methodology, per-run analyses, retractions, and known confounds: [`PRE_WRITEUP.md`](./PRE_WRITEUP.md). Harness source: [`helpers/StressTestRTP/`](./). Per-attempt CSVs are kept locally under `runs/` (gitignored) and are not published in this repository.

Only runs against competitor versions that are still current are listed. The earlier Spigot / Paper 1.20.1 passes were dropped once every competitor in them had shipped a newer release, and the 1.07 / 35.9 / 64.0 chunks-per-attempt figures from those runs are retracted (worldgen artefacts; see ADR-080).

---

## Cross-platform summary

| Run | Platform | Plugin | TP/s | p95 | Min TPS | Success |
|---|---|---|---:|---:|---:|---:|
| `20261008-003310` | Paper 26.2, unpaced (Test A) | **LeafRTP** | **42.2** | **7 ms** | **16.2** | 100 % (4,096 / 4,096) |
| `20261008-003310` | Paper 26.2, unpaced (Test A) | JakesRTP | 23.2 | 73 ms | 12.1 | 100 % (4,096 / 4,096) |
| `20261008-003310` | Paper 26.2, unpaced (Test A) | BetterRTP | 9.2 | 628 ms | 13.2 | 100 % (4,096 / 4,096) |
| `20261008-003310` | Paper 26.2, unpaced (Test A) | HuskHomes | 8.4 | 411 ms | 19.9 | 100 % (4,096 / 4,096) |
| `20261008-003310` | Paper 26.2, unpaced (Test A) | EzRTP | 7.9 | 748 ms | 18.0 | 100 % (4,096 / 4,096) |
| `20261008-003310` | Paper 26.2, unpaced (Test A) | JustRTP | 4.4 | 1 724 ms | 19.8 | 99.8 % (4,088 / 4,096) |
| `20261008-052141` | Folia 26.2, paced (Test B) | **LeafRTP** | **4.8** | **203 ms** | **19.9** | 100 % (4,096 / 4,096) |
| `20261008-052141` | Folia 26.2, paced (Test B) | HuskHomes | 4.6 | 785 ms | 20.0 | 99.98 % (4,095 / 4,096) |
| `20261008-052141` | Folia 26.2, paced (Test B) | JustRTP | 2.3 | 3 050 ms | 19.8 | 99.2 % (4,056 / 4,087) |
| `20261008-052141` | Folia 26.2, paced (Test B) | EzRTP | 0.8 | 4 995 ms | 20.0 | 59.9 % (1,415 / 2,364) |
| `20260617-232754` | Paper 26.1, unpaced | **RTP (Pro)** | **18.7** | **2 ms** | **17.5** | 100 % |
| `20260617-232754` | Paper 26.1, unpaced | EzRTP | 13.1 | 189 ms | 10.3 | 98.3 % |
| `20260617-232754` | Paper 26.1, unpaced | BetterRTP | 6.0 | 3 217 ms | 2.5 | 98.3 % |
| `20260617-191448` | Folia 26.1 | **RTP (Pro)** | **13.5** | - | - | ~100 % |
| `20260617-205614` | Folia 26.1 | **RTP (Lite)** | **12.5** | - | 16 | 100 % |
| `20260617-191448` | Folia 26.1 | EzRTP | 5.3 | - | - | 96.2 % |

All rows are n=1 on a single rig except the Folia watchdog-stall count, which reproduced across two runs. Read them as "this rig, this version, this configuration", not as universal claims.

Test B (`20261008-024412`) holds every plugin to the same offered rate (one `/rtp` per 200 ms), so it compares server cost at equal work rather than throughput; it has its own section below.

---

## Paper 26.2 - unpaced dispatch (Test A: `20261008-003310`, 4,096 teleports per plugin, 16k radius)

> Full 6-plugin unpaced saturation run on Paper 26.2 (Ryzen 9 3900X, 16 GB heap). 3 OPed clients, concurrency 4, `immediate-redispatch: true`, `dispatch-interval-ms: 0`, `per-player-gap-ticks: 0`. Outer radius 16,384 blocks, inner radius 1,024 blocks. EzRTP tested with biome verification active.

| Plugin | Success / Attempts | Wall Time | Throughput | Latency (p50 / p95 / p99) | Chunks / att (attr / inc) | Main CPU / att | Min TPS (Avg) | MSPT p99 | Peak Heap |
|---|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | **4,096 / 4,096 (100 %)** | **97.2 s (1.6 min)** | **42.15 TP/s** | **5.0 ms / 7.0 ms / 9.0 ms** | **0.18 / 2.92** | **9.92 ms** | 16.19 (17.82) | 80.6 ms | 16,296 MB |
| **JakesRTP** | 4,096 / 4,096 (100 %) | 176.5 s (2.9 min) | 23.20 TP/s | 21.0 ms / 73.0 ms / 114.0 ms | 7.14 / 8.46 | 13.17 ms | 12.14 (15.02) | 104.8 ms | 14,511 MB |
| **BetterRTP** | 4,096 / 4,096 (100 %) | 444.2 s (7.4 min) | 9.22 TP/s | 223.0 ms / 628.0 ms / 1,013.0 ms | 24.96 / 26.06 | 24.86 ms | 13.23 (19.16) | 120.2 ms | 15,003 MB |
| **HuskHomes** | 4,096 / 4,096 (100 %) | 489.3 s (8.2 min) | 8.37 TP/s | 258.0 ms / 411.0 ms / 748.0 ms | 31.66 / 32.22 | 30.72 ms | 19.92 (20.00) | 17.8 ms | 14,339 MB |
| **EzRTP** | 4,096 / 4,096 (100 %) | 517.7 s (8.6 min) | 7.91 TP/s | 240.0 ms / 748.0 ms / 1,125.0 ms | 30.94 / 32.07 | 34.35 ms | 18.01 (19.57) | 88.3 ms | 14,157 MB |
| **JustRTP** | 4,088 / 4,096 (99.8 %) | 923.8 s (15.4 min) | 4.43 TP/s | 418.0 ms / 1,724.0 ms / 2,721.0 ms | 89.53 / 91.79 | 82.79 ms | 19.83 (19.99) | 26.9 ms | 13,459 MB |

---

## Paper 26.2 - paced dispatch at 5 TP/s (Test B: `20261008-024412`, 4,096 teleports per plugin, 16k radius)

> Same server, world, radius, clients and plugin configs as Test A. One `/rtp` dispatched every 200 ms (`immediate-redispatch: false`, `dispatch-interval-ms: 200`, `per-player-gap-ticks: 4`), 180 s settle gap, spark on for every phase. Every plugin is asked for the same rate, so the MSPT and CPU columns compare cost at equal work. Achieved rate is attempts / phase wall time: five plugins held 4.50-4.63 TP/s; JustRTP fell behind at 3.84 TP/s.

| Plugin | Success / Attempts | Achieved rate | Latency (p50 / p95 / p99 / max) | Chunks / att (attr / inc) | Main CPU / att | Process CPU / att | MSPT (p50 / p99 / max) | Ticks > 50 ms | Min TPS (Avg) |
|---|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | **4,096 / 4,096 (100 %)** | **4.63 TP/s** | 6 / **9** / **14** / **34 ms** | 9.44 / **58.87** | **55.49 ms** | **537.1 ms** | **16.4 / 22.6 / 24.5 ms** | **0** | 19.89 (20.00) |
| **JakesRTP** | 4,096 / 4,096 (100 %) | 4.56 TP/s | **0** / 18 / 76 / 710 ms | **3.88** / 64.58 | 69.50 ms | 581.6 ms | 28.8 / 54.5 / 92.1 ms | 271 (1.52 %) | 18.55 (19.88) |
| **BetterRTP** | 4,096 / 4,096 (100 %) | 4.50 TP/s | 203 / 562 / 898 / 2,151 ms | 27.89 / 64.25 | 60.42 ms | 566.4 ms | 27.9 / 54.6 / 166.6 ms | 329 (1.83 %) | 16.86 (19.80) |
| **HuskHomes** | 4,096 / 4,096 (100 %) | 4.58 TP/s | 264 / 419 / 626 / 3,995 ms | 38.63 / 62.48 | 72.83 ms | 585.7 ms | 21.1 / 32.3 / 37.8 ms | 0 | 19.90 (20.00) |
| **EzRTP** | 4,096 / 4,096 (100 %) | 4.55 TP/s | 171 / 608 / 923 / 1,649 ms | 25.17 / 63.48 | 63.74 ms | 555.6 ms | 25.7 / 42.8 / 49.4 ms | 0 | 19.70 (19.98) |
| **JustRTP** | 4,094 / 4,096 (99.95 %) | 3.84 TP/s | 345 / 1,739 / 2,729 / 5,000 ms | 89.42 / 102.46 | 96.42 ms | 751.2 ms | 23.4 / 34.3 / 36.3 ms | 0 | **19.92** (20.00) |

MSPT against the median of the last 120 s of each settle gap: LeafRTP +4.3 ms (12.2 -> 16.4), JakesRTP +15.5, BetterRTP +17.1, EzRTP +13.1, JustRTP +10.5, HuskHomes +3.4 (17.6 -> 21.1; its baseline followed the JakesRTP phase and sat 4-7 ms above the other five). CPU per attempt includes the server's own tick work over a phase of near-equal length. Analysis: [`PRE_WRITEUP.md`](./PRE_WRITEUP.md) section 5.8.

---

## Folia 26.2 - paced dispatch at 5 TP/s (Test B: `20261008-052141`, 4,096 teleports per plugin, 16k radius)

> Same world, seed, radius, clients, and harness settings as Paper 26.2 Test B on Folia 26.2 (16 GB heap). JakesRTP was omitted (no Folia support declared). BetterRTP threw `Cannot retrieve chunk asynchronously` (`region={null}`) during warm-up and was pruned. EzRTP threw `getHighestBlockYAt` off-thread exceptions locking out 2 of 3 player accounts and capped out at 1,800 s with 949 timeouts. JustRTP struggled with heavy chunk loading and also reached the 1,800 s phase cap. HuskHomes and LeafRTP completed all attempts cleanly.

| Plugin | Success / Attempts | Achieved rate | Latency (p50 / p95 / p99 / max) | Chunks / att (attr / inc) | Region CPU / att | Process CPU / att | Regional TPS (Min / Avg) | Ticks < 20 TPS | GC Pause (Phase) |
|---|---|---|---|---|---|---|---|---|---|
| **LeafRTP** | **4,096 / 4,096 (100 %)** | **4.83 TP/s** | **137 / 203 / 206 / 399 ms** | 32.40 / 69.27 | **60.38 ms** | **368.36 ms** | 8.52 (19.89) | **0.88 %** | 7.3 s (102) |
| **HuskHomes** | 4,095 / 4,096 (99.98 %) | 4.63 TP/s | 404 / 785 / 1,065 / 4,928 ms | 36.08 / **42.40** | 109.31 ms | 407.94 ms | 15.85 (19.97) | 0.63 % | **6.5 s** (159) |
| **JustRTP** | 4,056 / 4,087 (99.24 %) | 2.25 TP/s | 901 / 3,050 / 4,746 / 5,004 ms | 136.62 / 138.47 | 287.32 ms | 1,149.74 ms | 6.33 (19.81) | 5.17 % | 16.8 s (301) |
| **EzRTP** | 1,415 / 2,364 (59.86 %) | 0.78 TP/s | 455 / 4,995 / 5,010 / 5,151 ms | **23.87** / 25.28 | 291.03 ms | 580.37 ms | **6.09** (19.98) | **0.32 %** | 1.6 s (89) |

LeafRTP reduced gross process CPU by 31.5% compared to Paper (368.4 ms vs 537.1 ms) by utilizing Folia's parallel regional threads without thread locks. JustRTP CPU surged to 1,149.7 ms (+53% vs Paper) loading 136.6 chunks per attempt. EzRTP's low GC and tick degradation reflects its idle state during 949 consecutive 5-second timeouts on locked accounts. Analysis: [`PRE_WRITEUP.md`](./PRE_WRITEUP.md) section 5.9.

---

## Paper 26.1 - unpaced dispatch (`20260617-232754`)

> The per-player gap set to 0: 3 clients, up to 4 teleports in flight, each client sends the next `/rtp` as soon as the last one lands. ~600 s per plugin.

| Plugin | Att / Succ | TP/s | p50 | p95 | p99 | Min TPS | MSPT p99 / max |
|---|---|---:|---:|---:|---:|---:|---:|
| **RTP (Pro)** | 16560 / 16560 | **18.7** | **1 ms** | **2 ms** | **46 ms** | **17.5** | 85.8 / 98.4 ms |
| EzRTP | 7137 / 7018 | 13.1 | 30 ms | 189 ms | 322 ms | 10.3 | 156.8 / 292 ms |
| BetterRTP | 1633 / 1606 | 6.0 | 480 ms | 3 217 ms | 4 402 ms | 2.5 | 859 / 3 663 ms |

About 95 % of RTP teleports are served from the pre-verified queue; the slowest 1 % pay one bounded async chunk load. The Paper adapter and engine are identical in Lite.

---

## Folia 26.1 - region safety (`20260617-191448`, Lite: `20260617-205614`)

| Metric | RTP-Pro | RTP-Lite | EzRTP |
|---|---:|---:|---:|
| Throughput (TP/s) | **13.5** | 12.5 | 5.3 |
| Main-thread CPU / attempt | **3.96 ms** | 4.15 ms | 6.34 ms |
| Process CPU / attempt | 199 ms | 177 ms | 593 ms |
| Folia watchdog stalls | **0** | **0** | 7 (worst region 20.4 s) |

The stalls are from the server's own `latest.log`, independent of the harness: EzRTP calls synchronous `World.loadChunk` on region threads. The stall count reproduced at 7 across two runs (worst 21.0 s and 20.4 s).

---

## Chunk loads and CPU per teleport - Folia 26.1, 6 GB pinned heap (`20260921-134507`, 4,096 teleports per plugin)

Chunk loads are counted from the server's chunk-load events, so they include the area the server loads around every arrival (a 5x5 block, up to 25 chunks) on top of whatever the plugin loads while searching. Depending on the world, close to half of random candidates are unsafe, so a plugin that loads a chunk to check it pays for the rejects too. RTP checks candidates against region files first, so under 5 % of them ever get loaded, and it pins one chunk: the destination.

| Metric | RTP | EzRTP | JustRTP |
|---|---:|---:|---:|
| Successes | **4,096 / 4,096** | 4,030 / 4,096 | 4,020 / 4,096 |
| Throughput (TP/s) | **13.9** | 6.7 | 2.5 |
| Chunks loaded / teleport | 26.1 | 22.1 | 75.1 (2.9x RTP) |
| Process CPU / teleport | **182.4 ms** | 208.9 ms | 729.0 ms (4.0x) |
| Region-thread CPU / teleport | **7.9 ms** | 21.4 ms (2.7x) | 79.6 ms (10x) |
| JVM allocation / teleport | **85.3 MB** | 190.4 MB | 569.0 MB |
| Total GC pause (phase) | **21.7 s** | 47.9 s | 138.8 s |

EzRTP loads slightly fewer chunks per teleport than RTP; its cost shows up on the region thread instead. In the unpaced 4,096-teleport Paper 26.1 run on the storefront, the ratio against JustRTP was larger (about 17 vs 76 chunks per teleport).

---

## Where players landed - Paper 26.2 (`20261008-003310`, 4,096 teleports per plugin, 16k radius)

> Full landing analysis across all 6 contenders on Paper 26.2. 16,384-block radius, 1,024-block void around spawn. Generated from destination scatter logs via `CrossPluginDestinationScatterVisualizerTest`.

| Plugin | Landed / Unique | Exact duplicates | Pairs within 48 blocks | Nearest pair | Clark-Evans R | Chunks reused | Landing block check (safe share) |
|---|---|---:|---:|---:|---:|---:|---|
| **LeafRTP** | 4,096 / 4,096 | **0 (0.0 %)** | **0** (random: 82) | **75.2 blocks** | **0.977** | **0** | **99.5 %** (547 / 550 safe, 3 noFloor) |
| **JakesRTP** | 4,096 / 4,096 | 0 (0.0 %) | 108 (random: 82) | 6.7 blocks | 0.919 | 1 | 78.8 % (2,745 / 3,484 safe, 730 noFloor, 9 hazard) |
| **BetterRTP** | 4,096 / 4,094 | 2 (0.05 %) | 123 (random: 78) | 0.0 blocks | 0.906 | 5 | 99.7 % (4,085 / 4,096 safe, 3 noFloor, 6 hazard) |
| **HuskHomes** | 4,096 / 4,096 | 0 (0.0 %) | 136 (random: 85) | 2.2 blocks | 0.907 | 4 | 98.7 % (2,429 / 2,462 safe, 32 noFloor, 1 water) |
| **EzRTP** | 4,096 / 4,096 | 0 (0.0 %) | 153 (random: 125) | 3.6 blocks | 0.914 | 4 | 87.9 % (3,599 / 4,096 safe, 441 water, 49 blocked, 7 hazard) |
| **JustRTP** | 4,088 / 4,088 | 0 (0.0 %) | 115 (random: 78) | 4.1 blocks | 0.897 | 1 | 100.0 % (8 / 8 safe) |

---

## Where players landed - Paper 26.2, paced (Test B: `20261008-024412`, 4,096 teleports per plugin, 16k radius)

> Successful landings only. "Random" is the near-pair count of a random scatter with each plugin's own landing intensity over terrain class and radius, so water and biome rejection are already in the expectation.

| Plugin | Landed / Unique | Exact duplicates | Pairs within 48 blocks | Nearest pair | Clark-Evans R | Chunks reused | Landing block check (safe share) |
|---|---|---:|---:|---:|---:|---:|---|
| **LeafRTP** | 4,096 / 4,096 | **0** | **0** (random: 81) | **70.0 blocks** | **0.978** | **0** | 99.7 % (4,083 / 4,096 safe, 6 noFloor, 5 water, 2 blocked) |
| **JakesRTP** | 4,096 / 4,096 | 0 | 127 (random: 84) | 4.5 blocks | 0.897 | 2 | 77.2 % (186 / 241 safe, 55 noFloor) |
| **BetterRTP** | 4,096 / 4,096 | 0 | 126 (random: 84) | 2.2 blocks | 0.899 | 7 | 99.7 % (4,082 / 4,096 safe, 12 hazard, 2 noFloor) |
| **HuskHomes** | 4,096 / 4,096 | 0 | 105 (random: 82) | 2.2 blocks | 0.920 | 5 | 98.9 % (2,275 / 2,301 safe, 26 noFloor) |
| **EzRTP** | 4,096 / 4,096 | 0 | 142 (random: 129) | 4.1 blocks | 0.917 | 3 | 88.5 % (3,625 / 4,096 safe, 424 water, 37 blocked, 10 hazard) |
| **JustRTP** | 4,094 / 4,094 | 0 | 129 (random: 85) | 4.0 blocks | 0.901 | 7 | 100.0 % (8 / 8 safe) |

---

## Where players landed - Folia 26.2, paced (Test B: `20261008-052141`, 4,096 teleports per plugin, 16k radius)

> Successful landings only. 16,384-block radius, 1,024-block void around spawn.

| Plugin | Landed / Unique | Exact duplicates | Pairs within 48 blocks | Nearest pair | Chunks reused | Landing block check (safe share) |
|---|---|---:|---:|---:|---:|---|
| **LeafRTP** | 4,096 / 4,095 | **1 (0 in live)** | **16 (0 in live)** | **50.8 blocks (live)** | **1 (0 in live)** | **99.9 %** (4,093 safe, 2 noFloor, 1 suffocating) |
| **HuskHomes** | 4,095 / 4,095 | 0 | 108 | 4.5 blocks | 5 | 98.9 % (4,049 safe, 46 noFloor) |
| **JustRTP** | 4,056 / 4,056 | 0 | 96 | 1.4 blocks | 7 | 99.7 % (4,044 safe, 12 hazard) |
| **EzRTP** | 1,415 / 1,415 | 0 | 15 | 14.3 blocks | 0 | 87.8 % (1,242 safe, 154 water, 17 suffocating, 2 hazard) |

Note on LeafRTP: All 16 close pairs occurred during startup queue synchronization (the first 100 attempts); across all 3,996 post-startup attempts, there were 0 pairs within 48 blocks and the nearest pair was 50.8 blocks. EzRTP landed 10.9 % of all successful teleports directly into ocean/river water surfaces (154 water landings).

---

## Where players landed - Paper 26.1 (`20260923-000854`, 4,096 teleports per plugin)

| Plugin | Exact duplicates | Pairs within 48 blocks | Nearest pair | Clark-Evans R |
|---|---:|---:|---:|---:|
| **RTP** | **0** | **0** | **78.4 blocks** | **1.04** (uniform) |
| EzRTP | - | 107 | 0 blocks | - |
| JustRTP | 167 (4.1 %) | 324 | 0 blocks | 0.83 (clustered) |

---

## Methodology

- **3 real OPed clients**, concurrency 4, cooldowns / delays / countdowns zeroed in every plugin, queues enabled where the plugin offers them.
- **Phase length** ~600 s per plugin with a 240 s settle gap, 30 s warm-up; the 4,096-teleport runs use a fixed quota instead.
- **Rig**: Ryzen 9 3900X, one rig for every row. Heap 16 GiB except the pinned 6 GB run.
- **CPU** is measured directly from the JVM (`CpuSampler`: process CPU and main/region-thread CPU per phase, divided by attempts). The `cpu_ms_with_chunks*` columns use an uncalibrated chunk-cost constant and are not quoted.
- Numbers are not predictions for your server - hardware, view distance, how much of your world is unsafe, whether it is pregenerated, other plugins, and player count will move them.

### What this benchmark does *not* claim

- It does not claim EzRTP, BetterRTP, or JustRTP are "bad". They are tested at their default queue configurations against RTP at its recommended one.
- It does not extrapolate beyond 3-4 concurrent clients.
- It does not measure correctness, safety, or claim-plugin compatibility - only dispatch-to-arrival latency, per-attempt cost, success rate, and landing spread.
- The region-file precheck needs terrain that already exists; chunks that have never been generated have to be generated whichever plugin asks.
