# ADR-120 - Mountainside and Cliff-Edge Check over the Resident 3x3 Square

**Status:** Accepted
**Date:** 2026-10-21
**Extends:** [ADR-119](ADR-119-slope-detection-and-elevation-memory.md) (takes up its "most promising next step"), [ADR-016](ADR-016-anvil-subsystem.md) (one definition for probe and live). **Relates to:** [ADR-109](ADR-109-measured-cost-probe-first-gate.md), [ADR-110](ADR-110-purpose-gated-live-loads.md), [ADR-052](ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md).

## Context

ADR-119's `maxSlope` measures block-scale bumps inside the landing chunk. Admins want to refuse mountainsides and cliff edges, which are slopes over 16-32 blocks. The design must:

- **Read blocks only, never generator noise.** Custom generators (Iris, Terra, plugin generators) do not provide noise hooks, so a block-based check is the only one that behaves the same on every world.
- **Add no chunk I/O (S-005).** One RTP ticket keeps the square around a pinned chunk resident: Bukkit / Paper / Folia `addPluginChunkTicket` is level 31, so the 5x5 square is FULL. Fabric / NeoForge use `RTP_TICKET_DISTANCE = 1` (level 32), so only the 3x3 is FULL.
- **Work with the probe pre-compute.** A coalesced probe drain (`ColumnProbeCoalescer`) leases one region file's bytes, so it can decode any chunk of that file and none of the others.
- **Leave the reject procedure unchanged.** Rejects use the existing fail-and-reschedule paths (S-004).

## Decision

1. **Vert key `maxHillAngle`** (degrees) on `linear` and `jump` (`GenericVerticalAdjustorKeys`, `JumpAdjustorKeys`). `config.yml` `defaults.vert` ships `maxHillAngle: "off"` with `@options: ["off", 30, 45, 60]`, so the menu offers the measured settings while any number 0-89 still parses. `"off"`, YAML `off`/`false`, absent, negative or unparsable values turn it off (`AbstractVerticalAdjustor.hillAngleOf`; an unparsable string logs one WARNING); values are clamped to 89. It has no code defaults entry, and `RegionCacheKey` drops an off value and hashes the parsed angle, so `"off"` and an absent key keep the same key (ADR-119 item 8) and `"45"` hashes like `45`. Off is the default because the check multiplies probe cost (measured below). The name says what an admin controls; it avoids "grade" (read as a percent) and stays distinct from `maxSlope` (a block delta). `fixed` does not extend `AbstractVerticalAdjustor` and ignores it.
2. **One pure check, `TerrainShapeCheck`**, read through a `SquareReader` over block coordinates of the 5x5 square. Readers: `probeSquare` (probe + same-file neighbours), `chunkSquare` (resident `RTPChunk`s), and the measurement harness. Floor test: non-air at `y-1`, air at `y`, searched nearest `y` within +-48 blocks (layer-following, so caves and the Nether work).
   - **Rule:** 8 directions, floors at 16 and 32 blocks (the shipped 3x3 reads only the 16-block ones; see item 4). A direction is steep when either sample differs by `>= tan(maxHillAngle) * distance` or has no floor in the window. Reject when `steep * 2 >= measured`, `measured >= 1`.
   - **Plane fit:** least squares over floors within +-8 blocks, step 2 (81 points). Reject at gradient `>= tan(maxHillAngle)`, or when over a quarter of the readable points have no floor (a layer break).
3. **Readable samples only.** A sample the reader cannot supply is left out, never guessed. An unreadable 32-block sample falls back to the 16-block one; a direction with neither is unmeasured; 0 measured directions abstain. The plane fit abstains when fewer than half of its points are readable.
4. **Provisional probe verdict, square fetched on demand.** The probe request stays centre-only. Only a candidate that passes `adjustFromProbe` and the biome and block prefilters gets a terrain job (`PregenTask.startProbeTerrain` -> `RTPWorld.probeChunkSquare` -> `AnvilPrefilter.squareJobAsync`). The job joins the region file's coalesced drain and runs while the drain holds the file. Its `ChunkSquare` decodes a same-file chunk only when the check first reads a column in it, at most once per drain and Y window; chunks outside the file, ungenerated or undecodable read as unmeasured. A reject records `FailTypes.prefilterTerrain` and reschedules like the other probe rejects; an accept or a failed job continues to the live path. The governor sample includes the job's time.
   - **Staged evaluation.** `TerrainShapeCheck.evaluate` runs the plane fit first (centre plus at most 3 neighbours), then the 16-block samples (3x3), then a 32-block sample only while it can still change the rule, and stops once the verdict is decided. Its reject verdict equals the eager rule + plane fit (asserted per landing in the harness and on random terrain in `TerrainShapeCheckTest`).
   - **3x3 at every limit.** `TerrainShapeCheck.SQUARE_RADIUS = 1` on both the probe and the live path, so the rule uses the 16-block samples only. The 3x3 catches 94-98% of the 5x5's rejects and never rejects a landing the 5x5 accepts, at about half the neighbour decodes (measured below); the cost/benefit favours it at 30 degrees too. An earlier revision kept the 5x5 below 45 degrees.
   - **Lean neighbour decode.** Neighbours use `AnvilReader.readTerrainProbe`: block states of the sections in the floor window (landing Y +-48) only, with biomes and heightmaps skipped.
5. **Authoritative live recheck.** In `PregenTask.evaluateSafety`, after the block-safety scan passes and before `GlobalRegionVerifiers`, `TerrainShapeCheck.residentSquare` fills the square from the safety grid, then from chunks `isChunkLoaded` reports, through the read-only `getChunkIfReadable` (ADR-110) and `getCachedChunk`. It never calls `getChunkAt`. A reject reuses the safety-reject block (`recordOutcome`, `closeIfPresent`, `rescheduleNextAttempt`) with `FailTypes.terrain`. A read that throws mid-check rejects that attempt with a WARNING, never accepts it.
6. **Spatial memory only for full verdicts.** Rejects that measured all 8 directions call `MemoryShape.addBadChunk(l, FailTypes.terrain)` (static TTL, like `safety`), for the same reason as the safety mark: the column order within a chunk is fixed, so the same verdict repeats. A partial square never retires a chunk.
7. **No neighbour cache.** An earlier design decoded all 24 neighbours in the drain and kept a 128-probe cache. It hit 0-2.4% of the time, because random candidates in a 1,024-chunk file rarely share neighbours, so it was removed.

## Measured (2026-10-21)

Reference save: 26.3 Paper overworld, 484 region files. Host: Ryzen 9 3900X, OpenJDK 21.0.12. Timed loops, not JMH.

**Detection** (`LandingTerrainCheckMeasurementTest`, 1,962 land landings plus 1,168 oversampled edge/corner landings, through `TerrainShapeCheck`; matches the earlier private copy):

| Check | Reject | Mountainside recall / FR | Cliff-edge recall / FR |
|---|---|---|---|
| Rule, full square | 9.1% | 89.8% / 0.2% | 27.3% / 2.0% |
| Rule + plane, full square | 22.5% | 96.4% / 14.3% | 66.0% / 5.4% |
| Rule (scaled) + plane, one region file | 22.5% | 95.9% / 14.3% | 65.8% / 5.5% |
| Same, shipped floor test (non-air below) | 20.5% | 92.3% / 12.6% | 61.1% / 4.6% |

The shipped floor test counts leaves and plants as floor, where the harness's truths treat trees as passable; the loss is about 4 points of recall. On interior chunks (76.6% of a file) the one-file verdict equals the full square; edge chunks measure 5 of 8 directions at local coordinate 0, corner chunks 3.

**Angle study** (same harness, full square, shipped floor test; `nbr` = neighbour chunks read per check):

| Limit | Reject | `nbr` all at once | `nbr` staged | `nbr` 3x3 only | Staged rejects decided in 3x3 | 3x3-only agrees with 5x5 | 5x5 rejects 3x3 misses | 5x5 accepts 3x3 rejects |
|---|---|---|---|---|---|---|---|---|
| 30 deg | 20.6% | 15.3 | 11.2 | 5.7 | 94.1% | 98.78% | 5.9% | 0 |
| 45 deg | 8.2% | 15.7 | 12.3 | 6.0 | 98.1% | 99.85% | 1.9% | 0 |
| 60 deg | 1.7% | 15.9 | 12.9 | 6.1 | 97.1% | 99.95% | 2.9% | 0 |

Steeper limits are decided from the 3x3: almost every reject shows within 16 blocks, and the 3x3 never rejects a landing the 5x5 accepts. An accept still has to check the outer ring, so the staged check alone saves only about a quarter of the reads. At 30 degrees the 3x3 loses 5.9% of the 5x5's rejects (about 1.2% of landings get through) for half the reads; the shipped check accepts that trade at every limit.

**Chunk decode** (`ChunkDecodeCostBenchmarkTest`, 4,000 random chunks): decompression (zlib / LZ4) about 116 us, NBT and palette parse the rest, 215-221 us per chunk in total. Narrowing the Y window or skipping biomes and heightmaps saves about 3%, so a cheaper decode cannot remove the cost; fewer decodes do. Bulk NBT array reads and a block-copy LZ4 overlap were kept as plain wins.

**Probe pre-compute cost** (`TerrainSquareProbeBenchmarkTest`, groups of 16 random generated chunks of one file on the shipped pool, bytes resident; per candidate over all candidates, of which 48.7% passed a dry-landing stand-in prefilter):

| Mode | us per candidate | Neighbour decodes per candidate | Per square job | Terrain reject of passed |
|---|---|---|---|---|
| Check off | 280 | 0 | - | - |
| All 24 neighbours (earlier design, in this harness) | 2,829 | 10.83 | 22.3 | - |
| On demand, 30 deg, 5x5 (earlier revision) | 1,484 | 4.94 | 10.1 | 23.8% |
| On demand, 30 deg, 3x3 (shipped) | 822 | 2.25 | 4.6 | 22.5% |
| On demand, 45 deg, 3x3 (shipped) | 851 | 2.31 | 4.8 | 7.8% |
| On demand, 60 deg, 3x3 (shipped) | 846 | 2.32 | 4.8 | 2.2% |

Re-run after the 3x3 change (same seed and host, one run). The earlier design measured 3.9 ms per probe against 0.22 ms off (about 17x). Fetching after the prefilters and on demand over the 3x3 brings every shipped limit to about 3x the probe-only cost; the 5x5 at 30 degrees was about 5.3x in this run (6.6x in the first). The ADR-109 governor sees the job time and may move a region off probe-first; the live recheck still applies there.

**Devstack A/B (2026-10-21).** Paper 26.3 (`itzg/minecraft-server:java25`, 3 GiB heap, no players) on a copy of the reference save, shipped config except `cacheCap: 5000`, fresh memory per run. `/rtp info` sampled every 15 s; one run per row.

| `period` | `maxHillAngle` | Run | Cached at end | Candidates tried | Accepted of tried | `terrain` (live) | `prefilterTerrain` | RTP chunk loads | Server MSPT after warm-up |
|---|---|---|---|---|---|---|---|---|---|
| 20 (shipped) | off | 292 s | 287 | 491 | 58.5% | - | - | - | 0.17-0.38 |
| 20 | 30 | 291 s | 288 | 716 | 40.2% | 49 | 29 | 40 | 0.08-0.21 |
| 20 | 45 | 290 s | 286 | 634 | 45.1% | 15 | 15 | - | 0.08-0.24 |
| 1 | off | 170 s | 3,188 | 6,051 | 52.7% | - | - | 26 | 0.21-0.51 |
| 1 | 30 | 170 s | 3,163 | 7,149 | 44.2% | 542 | 85 | 267 | 0.24-0.35 |
| 1 | 45 | 169 s | 3,188 | 6,464 | 49.3% | 194 | 26 | 70 | 0.12-0.29 |

- Queue fill is paced by `period` (about 1 accepted spot per second at 20, about 18.7 per second at 1), and every variant kept that pace. The extra work per accepted spot fits inside the pacing on this host, so no throughput loss shows.
- Candidates tried per accepted spot rose by 46% (30 degrees) and 29% (45 degrees) at `period: 20`, and by 18% and 7% at `period: 1`.
- Most terrain rejects come from the live recheck (`terrain`), not the probe (`prefilterTerrain`): candidates that skip probe-first (ADR-109) or sit on region edges reach the live check. Each live reject was read or loaded first, which is why RTP chunk loads rose from 26 to 267 at 30 degrees. The check itself loads nothing.
- No warnings, errors or exceptions beyond server boilerplate; MSPT stayed below 0.6 ms after warm-up in every run.
- Not measured: a host where the pacing is not the limit (slower CPU, many regions), probe drain nanoseconds from the live server, and landings by a player.

## Consequences

- **Positive:**
  - Mountainsides and cliff edges are refused on any generator; the check itself loads no chunks.
  - Unset regions behave and hash as before, and the probe requests no neighbours (one compare per path).
  - Terrain rejects are counted apart in the fail map (`terrain`, `prefilterTerrain`) and drawn in the `vert` colour on the bad-locations map.
- **Negative / Trade-offs:**
  - About 1 in 5 land candidates is rejected at 30 degrees, and the probe pre-compute costs about 3x per candidate at any shipped limit. Queue filling slows in hilly worlds, which is why the shipped default is off.
  - The 3x3 misses mountainsides whose steep part only shows at 32 blocks: 5.9% of the 5x5's rejects at 30 degrees, 2-3% at 45 and 60.
  - The devstack A/B below ran on the earlier revision (5x5 at 30 degrees) and was not repeated for the 3x3.
  - Every platform keeps at least the 3x3 FULL around a ticketed chunk, so Bukkit-family and Fabric / NeoForge rechecks read the same square.
  - Probe and live verdicts can differ on region-file edge chunks (provisional vs authoritative). They agree on squares where every chunk is readable (`TerrainShapeCheckReadersTest`).
  - Live neighbours are read on the thread that ran the safety scan, under the same assumption as the existing safety grid (see the open Folia entry in `POTENTIAL_BUGS.md`).
- **Future work:**
  - Cut parse cost further with a column-only NBT reader that unpacks just the sampled columns' palette indices.
  - Keep per-chunk floors at the 5 stencil columns from earlier drains so the rule can run without decoding again.
  - Repeat the devstack A/B on a host where queue pacing is not the limit, and with a player bot landing.

## References

- REQ-RTP-F-015; S-001, S-004, S-005.
- `rtp-core/.../verticalAdjustors/TerrainShapeCheck.java` (`evaluate`, `SQUARE_RADIUS`, `within`, `probeSquare`), `AbstractVerticalAdjustor.maxHillAngle` / `hillAngleOf`, `RegionCacheKey`, `config.yml` `defaults.vert.maxHillAngle`, `selection/region/PregenTask.java` (`startProbeTerrain`, `recheckTerrain`, `evaluateTerrain`), `selection/model/FailTypes.java` (`terrain`, `prefilterTerrain`).
- `api/anvil-api/.../ChunkSquare.java`, `ColumnProbeCoalescer.submitSquare`, `AnvilPrefilter.squareJobAsync`, `AnvilReader.readTerrainProbe`, `AnvilSectorReader.Session.readTerrainProbe`; `rtp-api` `RTPWorld.probeChunkSquare`.
- Tests: `TerrainShapeCheckTest` (`stagedMatchesEager`, `stagedSkipsOuterRing`), `TerrainShapeCheckReadersTest`, `PregenTaskTerrainRecheckTest` (`liveRecheckReadsInnerSquareOnly`), `RegionCacheKeyTest.maxHillAngle_changesCacheKeyOnlyWhenSet` / `maxHillAngle_offMatchesAbsent`, `AnvilSectorReaderTest.squareJobs_decodeOnDemand` / `terrainProbe_matchesColumnProbeInWindow`; evidence: `LandingTerrainCheckMeasurementTest`, `ChunkDecodeCostBenchmarkTest`, `TerrainSquareProbeBenchmarkTest`.
