# RTP — Performance Results

> **Headline.** RTP delivers `/rtp` in **under 10 ms** p99 on Spigot, **under 4 ms** on Paper, and keeps Folia regions at full TPS while competing plugins block their region thread for over a second per teleport.

Measured against the most-installed alternatives on three platforms with the same harness, same world, same hardware, and the same two real connected clients. Full methodology, raw CSVs, spark profiles, and reproduction harness: [`helpers/StressTestRTP/`](./).

---

## Cross-platform summary

| Platform | Plugin | `/rtp` per second | p99 latency | Success |
|---|---|---:|---:|---:|
| **Spigot 1.20.1** | **RTP** | **1.25** | **8 ms** | 100 % |
| Spigot 1.20.1 | BetterRTP | 1.05 | 4 229 ms | 100 % |
| Spigot 1.20.1 | HuskHomes | ~0.7 | 5 124 ms | 100 % |
| **Paper 1.20.1** ‡ | **RTP** (`cacheCap: 100, period: 1`) | **19.9** | **3–4 ms** | 100 % |
| Paper 1.20.1 | JakesRTP | 19.9 | 54–89 ms | 100 % |
| Paper 1.20.1 | HuskHomes | ~6 | 313–372 ms | 100 % |
| Paper 1.20.1 | BetterRTP | ~7 | 722–852 ms | 100 % |
| **Folia 1.21.11** | **RTP** | **9.87** | **157 ms** | 99.97 % |
| Folia 1.21.11 | BetterRTP | 3.82 | 1 200 ms | 100 % |
| Folia 1.21.11 | HuskHomes | 3.32 | 901 ms | 100 % |

‡ Reproduced across two consecutive runs (n=2, ±2.5 % `/rtp`/s, ±1 ms p99). All other rows are n=1 on a single rig and should be read as "this rig, this version, this configuration", not as universal claims.

**RTP wins p99 on every platform tested** — by **530×** on Spigot, **5–280×** on Paper, and **6–8×** on Folia.

---

## Spigot 1.20.1 — `/rtp` latency under load

> RTP serves `/rtp` from its L1 cache in under 10 ms p99 while every non-queueing competitor falls to multi-second tails.

| Plugin | Cold | p50 | p99 | Chunks loaded / attempt |
|---|---:|---:|---:|---:|
| **RTP** | **2 ms** | **3 ms** | **8 ms** | **1.07** |
| BetterRTP | — | 1 200 ms | 4 229 ms | 35.9 |
| HuskHomes | 3 178 ms | 1 032 ms | 5 124 ms | 64.0 |

Three independent axes (latency, chunks-per-attempt, main-thread CPU per attempt) converge on the same ranking. On Spigot the main thread saturates at the platform's chunk-generation ceiling regardless of plugin choice — the differentiator is whether the plugin pre-warms locations into a queue (RTP does; the others do not, or do not effectively).

---

## Paper 1.20.1 — `/rtp` at saturating offered load

> With RTP's recommended `cacheCap: 100, period: 1`, p99 collapses **38×** vs the default config and lands at the scheduler-noise floor.

| Plugin | `/rtp` per second | p50 | p99 | Main-CPU / attempt |
|---|---:|---:|---:|---:|
| **RTP** (`cacheCap: 100, period: 1`) ‡ | **19.9** | **<1 ms** | **3–4 ms** | 14–17 ms |
| JakesRTP | 19.9 | <1 ms | 54–89 ms | 19–26 ms |
| HuskHomes | 6.2 | 117 ms | 313–372 ms | 47–52 ms |
| BetterRTP | 7.3 | 240 ms | 722–852 ms | 43–58 ms |

‡ n=2 reproduced. Paper's async chunk pipeline lifts **every** plugin's throughput dramatically (Spigot→Paper: RTP 1.25→19.9, BetterRTP 1.05→7.3, HuskHomes 0.7→6.2). Choosing Paper matters more than choosing an RTP plugin — but among RTP plugins on Paper, RTP is still 18× faster than BetterRTP at the tail.

### Recommended RTP configuration (Paper 1.20.1)

```yaml
# RTP/regions/default.yml
cacheCap: 100         # L1 queue depth
activeChunkCap: 100   # L2 queue depth

# RTP/performance.yml
period: 1             # background scan/refill: every tick
```

Defaults (`cacheCap: 10`, `period: 10`) are appropriate for low-traffic servers; tune up as above for sustained load.

---

## Folia 1.21.11 — region-parallel teleports

> On Folia, slow plugins drag only their *own* region's tick rate. With RTP, **even the teleporting player doesn't wait** — sub-200 ms p99 vs 0.9–1.2 s on competitors.

| Plugin | `/rtp` per second | p50 | p99 | Main-CPU / attempt |
|---|---:|---:|---:|---:|
| **RTP** | **9.87** | **101 ms** | **157 ms** | **4.0 ms** ★ |
| BetterRTP | 3.82 | 399 ms | 1 200 ms | 8.1 ms |
| HuskHomes | 3.32 | 350 ms | 901 ms | 14.6 ms |

★ **Lowest per-attempt CPU cost measured on any platform.** Folia's region scheduler eliminates the main-thread serialization that bounds Spigot/Paper, so RTP's queue-served path runs at its theoretical floor.

Server TPS held at **19.75–20.00** across all 30 minutes of measured Folia time, on every plugin — this is Folia's whole point. The marketing line for Folia operators: *"on Spigot, slow plugins drag everyone down; on Folia, only the teleporting player waits — and with RTP, even they don't."*

---

## Methodology

- **Two real OPed clients**, positioned in the same world. Folia run had clients in different regions to exercise per-region parallelism.
- **Queues enabled** where the plugin offers them (RTP, BetterRTP, JakesRTP). HuskHomes has no queue concept.
- **`per-player-gap-ticks: 0`** (saturating offered load) on Paper/Folia; default pacing on Spigot.
- **Phase length**: 2 min per plugin on Spigot/Paper, 10 min per plugin on Folia. 60 s warm-up before measurement.
- **Cooldowns / delays / countdowns**: zeroed in every tested plugin's config to remove gating confounds.
- **Server TPS** held at 20.00 throughout all Paper and Folia runs; Spigot saturated to 3.5–4.5 TPS across every plugin (platform chunk-gen ceiling, not plugin-specific). It is therefore not a per-plugin discriminator and is omitted from the headline tables.
- **Single test rig**, MC versions as listed (Spigot/Paper 1.20.1, Folia 1.21.11). Numbers are not predictions for your server — your hardware, view distance, world generation state, other plugins, and player count will move them.
- Full per-attempt CSVs, phase aggregates, spark profiles, and the harness source: [`helpers/StressTestRTP/`](./). Detailed per-run analyses with retractions, reproducibility bands, and known confounds: [`PRE_WRITEUP.md`](./PRE_WRITEUP.md).

### What this benchmark does *not* claim

- It does not claim BetterRTP, HuskHomes, or JakesRTP are "bad". They are tested at their default queue configurations against RTP at its recommended one; in many real-server scenarios, defaults are what users get.
- It does not extrapolate beyond 2 concurrent clients. Higher-concurrency saturation curves require a bot harness and are pending future work.
- It does not measure correctness, safety, claim-plugin compatibility, or any axis other than dispatch-to-arrival latency, per-attempt cost, and success rate.
