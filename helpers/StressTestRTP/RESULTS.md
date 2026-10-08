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
| `20260617-232754` | Paper 26.1, unpaced | **RTP (Pro)** | **18.7** | **2 ms** | **17.5** | 100 % |
| `20260617-232754` | Paper 26.1, unpaced | EzRTP | 13.1 | 189 ms | 10.3 | 98.3 % |
| `20260617-232754` | Paper 26.1, unpaced | BetterRTP | 6.0 | 3 217 ms | 2.5 | 98.3 % |
| `20260617-191448` | Folia 26.1 | **RTP (Pro)** | **13.5** | - | - | ~100 % |
| `20260617-205614` | Folia 26.1 | **RTP (Lite)** | **12.5** | - | 16 | 100 % |
| `20260617-191448` | Folia 26.1 | EzRTP | 5.3 | - | - | 96.2 % |

All rows are n=1 on a single rig except the Folia watchdog-stall count, which reproduced across two runs. Read them as "this rig, this version, this configuration", not as universal claims.

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
