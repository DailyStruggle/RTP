# ADR-080 — Opt-In Simulation Benchmark Tier (Measurement vs Model Separation)

**Status:** Accepted
**Date:** 2026-09-04 (Accepted 2026-09-04)

## Context

Performance claims for RTP are currently produced by one instrument: the real-server harness in
[`helpers/StressTestRTP`](../../helpers/StressTestRTP/). It is authoritative — real Spigot / Paper / Folia,
real competitor plugins, real clients — and it is expensive: 30–60 minutes per data point once setup,
startup, execution, collection, analysis and logging are counted. That cost has three consequences:

1. **No iteration.** A design question cannot be explored with a 45-minute instrument; the harness can
   only *confirm* a hypothesis, never help form one.
2. **Structural blind spots.** `RESULTS.md` states them: it "does not extrapolate beyond 2 concurrent
   clients", and higher-concurrency saturation curves are named as pending work. Phase lengths of 2–10
   minutes also cannot reach behaviour that only appears over hours — most importantly, spatial-memory
   convergence and TTL expiry cycles.
3. **Some quantities are not measurable there at all.** Plugin-side allocation is buried under the
   platform's; retained footprint of a specific data structure cannot be isolated from a live server heap.

Conversely, some quantities are *only* honestly measurable on a real server: chunk-load and worldgen cost,
whole-JVM heap and GC under load. Those are platform-owned. Allocating a stand-in array "shaped like a
chunk" and reporting the result would be arithmetic dressed as measurement.

There is also a publication hazard. Results from this project are used in user-facing material
(`docs/FRONT_PAGE.md`, storefront listings). A benchmark authored by the maintainer of the winning
plugin, whose cost constants are tunable, can trivially become marketing with a table attached. Two live
examples of the failure mode already exist in-repo: chunks-per-attempt figures of 35.9 / 64.0 that were
worldgen-bounded artefacts and collapse to 5.86 / 4.59 in a pregenerated world; and a
"lower heap usage than reroll-based plugins" claim contradicted by our own best run at the time, in
which LeafRTP held the *highest* post-GC trough (9 948 MB) because it is a cache. That run itself has
since gone stale — it predates the allocation and staged-expiry work of ADR-078 / ADR-079 and the
competitor releases it measured — which is why the currency rule below exists and why the replacement
front-page answer quotes no cross-plugin heap figure at all.

## Decision

1. **A third instrument tier, opt-in and off by default.**
   Benchmarks live in `rtp-core/src/test/java/.../common/benchmark/`, carry `@Tag("simulation")`, and are
   excluded from `test`. They run only via `./gradlew :rtp-core:simulationBenchmark`.
   - Gating is implemented with `Test.include`/`Test.exclude` class-file patterns on `**/benchmark/**`,
     **not** JUnit tag filters: the root build applies `tasks.withType(Test).configureEach { useJUnitPlatform() }`,
     which is realized after the subproject script and resets any tag filter set through that DSL. The
     `@Tag` is retained so IDE run configurations and future tag-based tooling agree with the build.
   - The task clears the root build's `finalizedBy jacocoTestReport` (which `dependsOn test`), otherwise a
     benchmark run drags in the whole unit-test suite. Coverage of a benchmark tier is meaningless.
   - The task does **not** override heap or collector. The root build already pins every `Test` task to
     `maxHeapSize = 1024m` and `-XX:+UseG1GC`; a collector flag set in the subproject would be *appended*
     to the root's and HotSpot rejects two collectors on one command line.

2. **Division of labour between the three instruments.** Each tier answers only what it can answer honestly.

   | Question | Instrument |
   |---|---|
   | Cost of one chunk load / generation; whole-JVM heap and GC under load | `helpers/StressTestRTP` (real server) |
   | Allocation and GC pressure of our own code paths; retained footprint of a specific structure; measured `.mca` read cost | this tier (server-free, real classes) |
   | Behaviour over hours, large radii, and high concurrency | this tier (behavioural model, priced by the above) |

3. **Mandatory provenance on every emitted number.** Each report row carries one of:
   - `MEASURED` — timed or counted on this machine, this run, against shipped code.
   - `DERIVED` — arithmetic over `MEASURED` inputs, no behavioural assumption added.
   - `MODELED` — behavioural cost-model output; assumption-dependent.

   Measurement rows and model rows shall not be blended into one published table.

4. **Shared cost oracle with common random numbers.** All simulated strategies draw operation costs from
   the same measured sample distributions with the same seed, so that identical operations at identical
   positions in a request stream draw identical samples. Differences between strategies are then
   attributable only to the operations each strategy chooses to perform, not to draw luck. Distributions
   are retained as samples, not collapsed to means: a mean-based model cannot produce a credible p99.

5. **Reporting.** Markdown + CSV under `build/reports/rtp-simulation/`, with the JVM's *actual* input
   arguments (via `ManagementFactory`) in the header rather than what the build script asserts. Absolute
   values are machine-relative and shall be labelled as such.

6. **Assertions are self-consistency only.** Calibration completed, rows emitted, seeded runs
   deterministic. No assertion shall encode a competitive outcome: a test that fails when a competitor
   wins is not a measurement. Consequently this tier is never a merge gate.

7. **Publication rules.** Before any figure from this tier appears in user-facing material:
   - Its provenance tier shall be stated, with a link to the method.
   - Pregenerated-world figures supersede worldgen-bounded ones; a superseded figure shall not be quoted.
   - **Currency.** A published figure shall describe the *current* state of every subject it names. A
     figure is stale, and shall not be published, once the code it measures has changed materially -
     on our side or a competitor's. Concretely: whole-JVM heap figures predating the allocation and
     staged-expiry work (ADR-078 / ADR-079) describe neither this plugin nor any competitor's current
     release, so cross-plugin heap comparisons are withheld until re-measured. Where a claim cannot be
     made current, the honest publication is to state that it is withheld and why - not to quote the
     old number with a caveat.
   - **Validation gates must pass**, fixed before results are read, and each gate governs only the class
     of figure it validates. Failure blocks publication, not the test.
     - *Operation-count figures* (chunks loaded, region contexts, coverage): the model shall reproduce
       the between-profile ratio of `PRE_WRITEUP.md` section 12 chunks-per-attempt within 2x. The ratio
       rather than the absolute, because the measured figure is per attempt and that run did not record
       attempts-per-teleport. **Implemented and passing** (measured 3.71, modelled 1.90, deviation
       0.513x).
     - *Latency figures* (p99, p999, stall counts) and *throughput figures*: the model shall reproduce
       JakesRTP's measured Paper p99 within 2x and the section 16 Folia throughput split. **Not
       implemented** - these require a latency model over the cost oracle, which the current
       operation-count model deliberately does not attempt. Until it exists, no latency or throughput
       figure from this tier may be published.
   - **Parity shall be disclosed.** Where a competitor is at or near parity on an axis, the report and any
     published derivative shall say so on that axis. Two well-evidenced differentiators plus an explicit
     parity statement is both more credible and harder to refute than five soft ones.
   - **Unmeasured plugins ship as strategy classes, not names.** A competitor with no measured anchor is
     published as its published-config-derived strategy (for example "TTL-bounded 2D coarse memory +
     warm-chunk cache + local rescan"), never as a named plugin with modelled numbers attached.
   - **No grievance framing.** Published derivatives shall be technical and reproducible, and shall not
     reference disputes with competitor authors or communities.
   - **Allocation figures carry their omitted term.** Plugin-side allocation excludes chunk-load and
     worldgen allocation, which is platform-owned. Any published allocation figure shall be
     accompanied by the chunk-materialisations-per-teleport figure for the same run, because the
     omitted term scales with it: without that pairing, a design that avoids chunk work looks
     *worse* than one that does not.
   - **Allocation shall be published by load class, never as one total.** Total bytes per teleport
     mixes four costs that are not interchangeable, and a ratio taken across it compares all four at
     once. The classes are: (1) *one-time construction*, paid once per world and not GC pressure at
     all - a design that pre-allocates bounded arrays will always post a larger figure here than one
     that allocates lazily, and at steady state that difference costs nothing; (2) *steady transient*,
     the per-request trickle, taken as the median sampling bucket so bursts cannot inflate it, and the
     cheapest class per byte because young-dying objects are reclaimed at a cost proportional to
     survivors rather than to bytes allocated; (3) *burst*, allocation above the steady rate from
     periodic bulk work, the most expensive class because large arrays raise pause time and can bypass
     the young generation; (4) *retained live set*, which occupies heap and costs marking work but is
     not churn. A published figure shall name its class, and instantaneous pressure (p99 and peak
     bucket rate, plus peak-over-steady burstiness) shall accompany any steady-rate claim, because a
     design can win on the mean and lose on the tail.
   - **Serve rate shall be published beside any cost figure.** A starved cache allocates almost
     nothing, so a cost figure without an unserved-request percentage is unreadable in principle.
     This is not hypothetical: the first run of the allocation benchmark reported the best figure in
     the table while serving 0.3 % of requests, and only the serve-rate row exposed it.

## Primary sources for the design rationale

The 1D-over-2D spatial-memory decision predates this repository and is documented publicly:

- r/admincraft, **"too much math"** (`/comments/owgvzz`, Aug 2021) — the original analysis. States the
  clustering-versus-preloaded-space trade-off ("this makes it harder to fully utilize pre-loaded space,
  and users end up having to teleport more often"), the reroll scaling thesis ("rerolling ... gives you a
  much more non-deterministic computation time, getting less stable and statistically worse as you add
  more space to avoid"), the 1D spiral bijection, a measured ~13 % selection-cost overhead versus naive
  sampling, and — five years before it shipped — the `MemoryShape` design: "subtracting missing space from
  the total before randomizing and shifting the random point by the missing area under the curve (probably
  by keeping running tabs on each 'bad sector' on the disc)".
- r/admincraft, **"[info] testing different teleportation optimizations"** (`/comments/p0vu0d`) — follow-up.
- [`Python Test Scripts/randomDonutFixed - Spatial Indexing.py`](../../Python%20Test%20Scripts/) — surviving
  script from that period implementing the 1D-area to (radius, rotation) bijection and indexing a region
  into 1D occupancy spans; the direct ancestor of `MemoryShape`.

The accompanying 2D coarse-grid footprint analysis from that period was not persisted. It is
reconstructed in this tier by `RetainedFootprintBenchmarkTest` (measured footprint of the shipped
run-length representation against a chunk-keyed TTL map) and `SpatialMemoryScalingBenchmarkTest`
(coverage convergence under blanket TTL versus cause-typed persistence). Both **partially contradict**
the remembered conclusion; see Consequences.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Extend `helpers/StressTestRTP` only | Cannot reach hour-scale horizons or isolate plugin-side allocation, and 30–60 min per data point forbids iteration. Retained as the authoritative tier for platform-owned costs. |
| Python analysis scripts | Cannot allocate or measure JVM heap, and would measure a reimplementation rather than the shipped `MemoryShape` / `AnvilRegionByteCache`. Java gives parity with production code. |
| A new Gradle module (`helpers/RtpSimulation`) | `rtp-core` already declares `api project(':anvil-api')`, so the measured `.mca` calibration needs no new module or dependency edge. Reconsider only if the tier grows platform dependencies. |
| Run benchmarks as part of `test` behind a system property | A property switch cannot pin JVM args per run, and allocation/heap figures are not comparable across differing heap sizes or collectors. A separate `Test` task can. |
| Model chunk-load allocation with stand-in arrays | Chunk objects, palettes, heightmaps and NBT buffers are platform-owned; a synthetic array measures our stand-in, not the platform. Left to the real harness. |
| Assert competitor-relative thresholds | A benchmark that fails when we lose is not a measurement. Self-consistency assertions only. |
| Publish modelled figures without provenance labels | Indistinguishable from marketing, and unfalsifiable by a reader. Labels plus a validation gate are what make the tier publishable at all. |

## Consequences

- **Positive:**
  - Design questions become iterable: seconds per run instead of tens of minutes.
  - Allocation and GC-pressure changes to our own code become measurable and regression-visible. The first
    run already quantified the `AnvilRegionByteCache` buffer pool at ~2.6 KB allocated per region read
    versus ~4.2 MB when reallocating per probe (0 GC collections versus 3), and surfaced that two staleness
    `stat` syscalls account for essentially the entire warm-hit cost.
  - Long-horizon and high-concurrency behaviour — the gap `RESULTS.md` names as pending — becomes
    addressable without a bot harness.
  - Provenance labelling plus fixed validation gates make published figures falsifiable by a reader.
  - **The tier immediately corrected two of the maintainer's own working assumptions**, which is the
    clearest evidence that it is a measurement instrument and not a marketing one:
    - The spatial-memory footprint advantage is **~4x, not ~1000x** (25.9 B per known-bad chunk versus
      111.3 B for a chunk-keyed TTL entry). The ~1000x estimate assumed dense bit-per-chunk coverage;
      at the sparse learned densities that actually occur, both representations are per-entry.
      Footprint alone therefore does *not* establish that a coarse 2D map must expire entries.
    - **Verification order dominates spatial-memory durability.** At a 10 000-block radius the model
      attributes nearly the whole cost gap to prefilter-before-load (1.94 -> 1.00 chunk loads per
      served teleport), while durable memory moves load-then-check only 2.01 -> 1.94. Spatial memory
      pays in proportion to coverage, and coverage of a production-sized world is low for every
      strategy. Published claims shall lead with verification order and present memory durability as
      the long-horizon second-order effect it measures as.
    - Also corrected: run-length compression is a *within-ring* property of the 1D index, so clustered
      and scattered bad sets cost the same. The footprint result is distribution-independent rather
      than a clustering win.
    - **The undifferentiated allocation total was itself the wrong measurement, and it inverted the
      result.** Read as one mean, durable 1D memory looked like the most allocating design of five
      (485 B per served teleport at the 300 s rebuild cadence, against 315 B stateless reroll, 381 B
      clustered reroll, 176 B warm queue, 471 B TTL coarse-2D memory). Classified, the ordering
      changes: its *steady transient* churn is **124.8 B per teleport, the second lowest of the five**
      (stateless reroll 308.7 B, clustered reroll 379.1 B, TTL coarse-2D 455.7 B, warm queue 176.0 B),
      because a candidate rejected by spatial memory allocates nothing at all. 75 % of its allocation
      is *burst* from the periodic span-array rebuild, whose cost scales with knowledge retained
      rather than with the triggering request - a cadence parameter, not a property of the
      representation: burst falls from 1 259 B per teleport at a 60 s cadence to 55 B at 3 600 s while
      steady transient stays flat at 130 -> 121 B and chunk materialisations stay flat at ~1.36. Its
      *one-time* construction cost is 18 192 B, which amortises to 0.09 B per teleport and is not GC
      pressure.
    - **Recomputation is dirtiness-gated, so the burst class is work-proportional rather than
      periodic.** The shipped guard (`MemoryShape.maybeFlushAndRebuild`) returns unless a mark is
      pending and requires `min(256, runs/8)` pending marks, so a cadence deadline that comes due
      with nothing newly learned performs no merge at all - at a 60 s cadence the model performs 230
      rebuilds and declines 62 000 deadlines. Burst therefore decays as the memory converges instead
      of recurring forever on the clock, and a model that rebuilds unconditionally on a timer
      overstates it. Any simulated design shall reproduce its subject's recomputation *trigger*, not
      merely its period.
    - **The honest remaining cost is burstiness, not volume.** Peak-over-steady is **~45x** for
      durable memory against ~1.06-1.16x for every memoryless arm: rerolling spreads its plugin-side
      bytes thinly across attempts and forgets, remembering allocates in bursts and keeps. Bulk
      allocation is the class that produces pauses rather than merely collection counts, so this is
      a real cost and it is
      what the rebuild cadence trades against. What it buys is the chunk work the same run shows
      avoided, and this tier does not price chunk work. No allocation figure from this tier is
      publishable without its class label and that pairing.
    - **"Rerolling allocates a little per attempt" is true only of the bytes this tier can measure,
      and shall not be written without that qualifier.** A retry's dominant allocation is the chunk
      it materialises - chunk objects, palettes, sections, heightmaps, NBT decode buffers - which is
      platform-owned, is not fabricated here, and is far larger than any bookkeeping measured. The
      memoryless arms materialise ~1.79 chunks per served teleport against ~1.36 for durable memory,
      so the omitted term is both the largest and the one that most favours remembering. No row from
      this tier is a total cost of a design.
    - **The durable arm's extra allocation is extra work, not a confound - and it is auditable.** The
      question "is this arm allocating more because it does more?" is answered by rows, not by
      argument: it admits 1.005 real `RTPLocation`/`RTPCoords` objects per teleport where the
      strategy classes hold one flat array, and it records 0.357 learning marks per teleport where a
      memoryless design records none. A depth-parity arm settles the other candidate explanation -
      hot-tier capacity 20 allocates 483.9 B per teleport against 485.4 B at capacity 1024, a 0.3 %
      difference, so cache depth is free at steady state and is not the cause. Any arm whose figure
      differs from a comparator shall report the operation counts that explain the difference.
    - **The pre-scanned configuration is the one this design is built for, and it inverts the
      table.** With spatial memory pre-populated by an off-tick crawl (`/rtp scan`), the durable arm
      becomes the cheapest arm in *every* load class: burst falls 364.7 -> 1.2 B per teleport,
      burstiness 45.5x -> 1.17x, steady transient 124.8 -> 107.0 B, and chunk materialisations
      1.362 -> 1.005 - the floor, one chunk per teleport. Zero span-array rebuilds are performed and
      all 200 004 cadence deadlines are declined, because a converged memory is never dirty. The
      crawl itself is charged as class 1: 15.5 MB one-time for full coverage of a 262 144-cell
      region, amortising to 77.4 B per teleport over the run and to nothing over a server's life.
      Partial scans interpolate monotonically (25 % of radius = 6.25 % of area -> 1.339 chunks per
      teleport; 50 % -> 1.272). Publication rule: a pre-scanned figure shall never appear without
      its coverage row and its class-1 crawl cost, and shall never be compared against a
      cold-start row of another design.
    - **Operation counting cannot produce a latency spread, and a queue can.** The count model's
      between-arm range was ~1.9x where the rig measures ~417x on mean latency. The missing factor is
      contention, not a cost constant: a multi-second stall is a queue length. The latency tier is
      therefore a discrete-event model with closed-loop clients, a paced dispatch gate, a serial tick
      thread (per-region threads on Folia), a contended async pool, and a tick duty cycle - so
      throughput and the tail are outputs rather than inputs. Rule: **a latency or throughput figure
      shall not be produced by summing per-request operation costs.**
    - **Calibrate on one arm, validate on the rest, and report signed residuals.** Foreground
      chunk-load cost is *derived* from the calibration run by arithmetic
      (`mainCpu / (chunks * (1 - asyncShare))` = 75.8 ms); an independent search that included it
      selected 73.8 ms, a 3 % agreement between a fit and a derivation sharing no inputs. Only three
      scheduling parameters are fitted, against this plugin on Paper alone, then applied unchanged to
      every other arm. Rule: **no parameter shall be fitted to a competitor row**, and residuals
      shall be reported signed, because the count model passed a 2x absolute gate while being wrong
      in the flattering direction on every arm.
    - **Current verdict: latency and throughput figures from this tier are NOT publishable.** The
      calibration arm reproduces its measured p50 within 16 % and the uniform live-verify arm its
      p95/p99/max within 3-12 %, but only 64 % of validation comparisons are within 2x (threshold
      0.80) and the mean signed residual is +1.55 (threshold +-0.50). Positive bias means the model
      over-states competitor cost, which flatters this plugin - the exact direction a published
      figure must not be wrong in. The remaining residual is a competitor arm whose modelled
      cache-hit fraction sits on the 0.5 boundary where p50 is maximally sensitive; closing it needs
      a measurement (per-plugin cache-hit rate) rather than a knob.
    - **Four modelling errors found, each of which had turned a harness artefact into a reported
      property of a design.** Recorded because all four read as plausible results: (1) background
      refill and player requests sharing one FIFO made every cached serve wait behind the refill
      backlog (p50 103 ms modelled against 1 ms measured); (2) a pulse cursor advanced per request
      drifted ahead of the clock and starved refill entirely; (3) a strictly sequential refiller
      waited out each async chunk load in turn, capping production at ~8/s against 18/s demand and
      pinning cache-hit rate near 0.5; (4) a 40-item once-a-second refill burst produced a 19x p50
      over-prediction at the same average rate. Rule: **pulse width, queue discipline and pipelining
      are behaviour, not implementation detail** - an arm's scheduling must be reproduced, not just
      its rates.
- **Negative / Trade-offs:**
  - A second results surface exists, so superseded figures can be quoted by mistake; mitigated by the
    supersession rule and by keeping model rows out of measurement tables.
  - Modelled rows depend on assumptions and will attract scrutiny; that is the intended cost of publishing
    them at all.
  - Absolute values are machine-relative and not comparable across rigs.
  - Figures age out. Competitor releases and our own optimisation work both invalidate published
    comparisons, so the currency rule guarantees a recurring re-measurement cost or a withheld claim.
    The front-page memory answer is currently the withheld case: it publishes only the per-entry costs
    measured against current code and explicitly declines a cross-plugin heap comparison.

## References

- `docs/adr/ADR-001-archimedean-spiral-1d-mapping.md` (1D spiral mapping).
- `docs/adr/ADR-016-anvil-subsystem.md`, `docs/adr/ADR-077-multi-format-region-support.md` (off-tick prefilter).
- `docs/adr/ADR-028-l3-backlog-cache.md` (L3 backlog screening, the path priced by the allocation tier).
- `docs/adr/ADR-052-outcome-metrics-and-cause-tagged-bad-locations.md`,
  `docs/adr/ADR-079-cause-based-ttl-and-staged-expiration.md` (cause-typed retention: static terrain is
  permanent, dynamic claim state expires — the axis the behavioural tier models).
- `helpers/StressTestRTP/RESULTS.md`, `helpers/StressTestRTP/PRE_WRITEUP.md` (measured ground truth).
- `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/benchmark/` (this tier).


## Addendum: per-attempt teleport-mode classification in the measurement harness

Latency populations produced by RTP-style plugins are bimodal: a request is either served from a
pre-computed location or it selects and verifies a destination on demand. A p50 taken across a
near-50/50 mixture sits on the mode boundary and is maximally unstable, and a mean over such a
population describes neither mode.

`StressTestRTP` therefore classifies each attempt and reports the two modes separately.

- The classification uses **observable signals only**: the attempt latency and the attributed
  selection chunk loads, both already columns in the per-attempt CSV. A competitor's internal
  cache state is not observable from outside the plugin and shall not be reconstructed, guessed,
  or published as if it were read.
- The fast/cold boundary is **derived from the phase's own attempt population** (Otsu two-class
  split over log10 latency) and written into the phases CSV as `mode_threshold_ms` alongside
  `mode_threshold_method`, so any reader can re-derive every row. No millisecond constant is
  hardcoded and no threshold is chosen after looking at a result.
- This plugin's own serving mode is knowable directly; a competitor's is not. The direct reading
  has its own columns (`served_mode_direct`, `direct_fast_mode_fraction`) and shall never feed the
  inferred classifier's threshold or its per-mode percentiles. The two readings are reported side
  by side and labelled, never merged.
- Every new numeric column uses `-1` for "not measured" and every new string column uses empty.
  A competitor arm's blanks shall never be readable as zeros. The sentinels are documented in the
  run's `<stamp>-schema.txt` sidecar.
- Phase rows publish `fast_mode_fraction` and p50/p95/p99 **within each mode**. A bare mean over
  the combined population shall not be published.

## Addendum: Folia region-context accounting and region-freeze detection in the measurement harness

The strongest validation target in the measured dataset is not a percentile but a discrete integer:
7 region freezes against 0, reproducing at exactly 7 across two independent 600 s runs, worst
freeze 21.0 s. An integer that reproduces is a harder target than any tail statistic, and 21 s sits
two orders of magnitude past every entry in the latency table - so it is a distinct mechanism
(scheduler churn, region prepare / tick / GC), not the same distribution's tail, and it shall not
be reported as one.

`StressTestRTP` therefore accounts for Folia region context per attempt and detects region freezes
per phase.

- **The detection criterion is fixed before any run is read, and travels with the data.** A region
  freeze is a wall-clock stall of at least a compile-time threshold (5000 ms) on a Folia region
  thread. The threshold is 100x the nominal tick and two orders above the measured latency table,
  so a freeze can be neither tick jitter nor a latency tail. It is written into every phase row as
  `region_freeze_threshold_ms` so no reader has to assume it. A detector that fails to reproduce an
  archived freeze count shall be reported as a broken detector; the threshold **shall not** be
  adjusted until the result agrees.
- **Exactly two channels, each reported separately.** Tick stall - the wall gap between consecutive
  invocations of a 1-tick global-region-scheduler timer. Hop stall - the wall wait for a
  player-owning region's entity scheduler to execute a dispatch. `region_freezes` is their union;
  `region_freezes_tick_stall` and `region_freezes_hop_stall` keep the mechanism visible, because a
  single fused count cannot distinguish "the region stopped ticking" from "the region stopped
  draining its task queue".
- **Runtime detection only.** The columns are gated on the existing `Sched.isFolia()` /
  `Runner.isFoliaFamily()` probes. There is no build variant and no config toggle for something the
  JVM can be asked directly.
- **The schema is platform-independent.** Off Folia the columns are still emitted, carrying `-1`
  for NOT MEASURED. A Folia phase that genuinely never froze writes `0`, which is a measurement.
  A non-Folia arm's `-1` shall never be readable as "zero freezes".
- **The apparatus stays off the measured path.** Primitive and `AtomicLong` state only, no
  allocation per attempt or per tick, no snapshot copy, no blocking on a tick thread. This tier has
  twice had its own instrumentation become the headline - a snapshot copy that inflated allocation
  24x, and a shared FIFO that reported its own queue discipline as the design's latency - so a new
  counter's cost is a design constraint, not an afterthought.
- **No freeze figure is publishable until the detector is validated.** Until it reproduces the
  archived 0-versus-7 split with its worst freeze, these columns carry data and no claim.


## Addendum: GC and residency accounting in the measurement harness

Heap-used sampled every 50 ms cannot distinguish memory that is **retained** from memory that is
**churned**: two designs with identical heap curves can differ entirely in whether the bytes are
live at the end of a teleport. That gap is why the cross-plugin heap claim is withheld from the
front page under the currency rule, and restoring any such claim is gated on the accounting below
being present for every phase compared.

- GC is recorded as **per-phase deltas of `GarbageCollectorMXBean` collection count and collection
  time**, split young/old by collector name, with beans whose generation cannot be identified
  charged only to the totals and counted in their own column. Tick-thread allocation rate comes
  from `getThreadAllocatedBytes` on the recorded tick thread, as a phase delta.
- Residency is recorded as **peak resident chunks** and **peak plugin chunk tickets held** per
  phase. This is the honest observable proxy for the platform-owned retained term the simulation
  tier cannot weigh, and it is the row on which the designs actually differ: the axis is residency
  policy - coordinate tuples plus tracked-and-released tickets against resident chunk
  neighbourhoods - not the presence or absence of a cache. Peaks, not averages, because a peak is
  what exposes retention.
- Peak tickets depend on Paper's `World#getPluginChunkTickets()`. Where the platform does not
  expose it the column is `-1` (not measured) and never `0`, per the no-data sentinel rule.
- Heap-pressure control loops are **recorded, not modelled**. Where a plugin observably changes
  behaviour under heap pressure (one competitor reduces max attempts and switches to a cache-only
  mode under a heap limit), the harness captures the matching log line and the heap-used level at
  which it fired. The trigger is evidence; no behavioural response is inferred, simulated, or
  attributed from a match.
- Instrumentation shall stay off the measured path: primitive counters, no per-attempt allocation,
  no blocking call on a tick thread. This tier has twice had its own apparatus become the
  headline - a snapshot copy that inflated allocation 24x, and a shared FIFO that reported its own
  queue discipline as a design's latency - so a new counter that allocates or blocks is a
  measurement error, not a cost.
- Folia-specific interpretation is gated at **runtime** on Folia detection; there is no build
  variant and no config toggle for a detectable property. Folia has no single tick thread, so the
  allocation column carries a scope label identifying it as one region thread's share.
- The output of this accounting is **columns**. No comparative heap or GC conclusion is written
  from it here; an uninstrumented reading already produced one inverted published claim, and
  interpretation is a later, separate decision under the currency rule.


## Addendum: per-attempt foreground/async chunk split and tick occupancy as intervals

The foreground share of chunk work is the single strongest health discriminator in the dataset -
across four plugins the async share spread 85 / 47 / 8 / 1 %, and foreground chunk work either
wrecks the tick tail or caps throughput. It was nonetheless only ever recorded **per phase**, as an
average. An average cannot answer the question the queueing model actually asks, because the tail
is produced by *when* foreground work lands relative to the tick, not by how much of it there is on
aggregate.

- The split is recorded **per attempt**: chunk loads attributed to a teleport are divided into
  on-tick and off-tick counts. The decision is made once per `ChunkLoadEvent` from thread identity
  and reused for both the per-attempt split and the per-phase counters, so the two can never
  disagree with each other.
- Thread identity is **runtime-detected**. On a single-tick-thread platform the primary-thread
  check is exact. On Folia the question is region-scoped and is asked as "is this the region thread
  that owns the chunk that just loaded", because Folia has no single tick thread and a
  primary-thread check is not meaningful there. There is no build variant and no config toggle for
  a detectable property, and each row records which oracle classified it so the columns are never
  silently compared across platforms.
- Tick-thread occupancy is recorded as **intervals**, summarised to count, total and max - never a
  total alone. One 8 ms stall and sixteen 0.5 ms stalls have the same total and completely
  different tick tails, so a total cannot validate queue discipline and an interval list can.
  Only spans the harness can genuinely observe are recorded: the synchronous span inside the
  dispatched command, and runs of on-tick loads for one attempt coalesced at a gap fixed well below
  a tick so work on two different ticks can never merge into one interval.
- The pre-existing per-phase aggregate columns are **left exactly as they are** and the new columns
  are appended alongside. This is not tidiness: the derived foreground chunk-load cost is the
  model's only unfitted dominant term, and moving or redefining a column it reads would break the
  one term that was independently corroborated rather than fitted. Prior runs also stay comparable.
- The measured async share is reported **alongside** the derived one, never in place of it. A
  measurement and a derivation of the same quantity are kept in separate columns so neither can be
  quietly substituted for the other, and agreement between them becomes checkable evidence instead
  of an assumption.
- Every new count and duration uses `-1` for "not measured". `-1` interval count means no interval
  was ever observed and is explicitly **not** the claim that an arm consumed no tick time; a
  competitor arm's blanks must never be readable as zeros.
- The apparatus stays off the measured path: the per-attempt split reuses the existing per-attempt
  tally through a static field updater, adding **zero** allocation per attempt, and the interval
  accumulator is plain primitive fields under an uncontended per-attempt monitor. On a tick thread
  the added work is two monotonic clock reads. This tier has twice had its own apparatus become the
  headline, so a counter that allocates or blocks is a measurement error rather than a cost.
- The output is **columns**. No foreground or async-share conclusion for any arm is written from
  them here; interpretation is a later, separate decision under the currency rule.


## Addendum: storage-class characterisation and region-file read accounting

The leverage argument for answering safety from region bytes before materialising a chunk is a
ratio between a cold region-file read and a warm in-memory hit. A ratio of that size is dominated
by the medium it was measured on, so quoting it without the medium is quoting a property of one
machine as a property of a design.

- **A read-cost figure shall carry its device class.** Every run records the world directory's
  storage class (`NVME | SATA_SSD | SPINNING | NETWORK | UNKNOWN`), the method that produced the
  verdict, and a measured read-latency distribution (p50/p90/p99/max) taken from the actual
  device, as a per-phase header block and as phase columns. A downstream model then selects a cost
  distribution from a recorded histogram instead of assuming one. `UNKNOWN` is written literally
  and never means "fast".
- **The classification thresholds are fixed before any result is read.** They are compile-time
  constants applied to the p50 of a random 4 KiB read, chosen as device-physics boundaries rather
  than tuned to output. A network mount is decided by filesystem type and is never overridden by
  latency, because latency on a network store describes the link rather than the medium.
- **A probe that warms the pages it then measures shall say so.** No page-cache drop is portable
  from a JVM, so the profiler does not pretend to one. It instead times only first touches of
  distinct region files, never re-reads a file it has already probed, and selects probe files
  farthest from the origin the benchmark radius is centred on - so no sample can measure pages the
  profiler itself warmed. Prior warmth from the server or an earlier run remains uncontrolled and
  indistinguishable, so the distribution is published under the label
  `FIRST_TOUCH_UNKNOWN_PAGE_CACHE` and treated as a **lower bound**. A silent conflation would not
  be acceptable; a labelled one is.
- **An assumed batch size is an assumed order of magnitude, and shall be measured instead.**
  Batching is worth up to the full read-term leverage, so a model that assumes 64 candidates per
  binned batch has assumed the answer - and in this case understates the design it is defending,
  which is the wrong direction for a published figure. Region-file reads are therefore counted per
  distinct 32x32 bin, and both the candidate count and the read count are written per attempt and
  per phase so reads-per-teleport is regressed against measured occupancy rather than asserted.
  The mean occupancy never ships without its peak.
- **The read count is chunk-load-implied, and its blind spot favours the competition.** It is not
  an intercepted syscall count: it cannot see region bytes read without a chunk materialising,
  which is precisely what this project's own prefilter does. It therefore under-states this
  plugin's avoided reads and can only err against our own case.
- **The apparatus stays off the measured path.** All probe I/O is asynchronous and never waited
  on; bin and region accounting runs once per attempt at completion over coordinates already
  collected, adding no per-event work and no snapshot copy. This tier has twice had its own
  instrumentation become the headline, and the rule earned there applies here unchanged.


## Addendum: reconciled instrumentation rules, and completeness as a gate

Five instrumentation efforts landed against this tier independently - per-attempt teleport-mode
classification, storage-class and region-read accounting, Folia region-context and freeze
detection, GC and residency accounting, and the per-attempt foreground/async split. Each wrote its
own rules; the rules agree, and the agreement is worth stating once rather than five times.

- **No-data sentinel, uniformly.** Every instrumented quantity has an explicit "not available"
  value: `-1` for counts and durations, empty for strings in the harness CSV, and `NaN` for the
  real-valued anchors the model reads. `NaN` is chosen on the model side deliberately, because it
  propagates through arithmetic instead of producing a plausible product. Sentinels are documented
  in the run's schema sidecar rather than in a `#` comment line, which a dictionary reader would
  not tolerate. A blank or a zero shall never be readable as a measurement, and a `-1` shall never
  be readable as "this arm did none of that".
- **Provenance is computed, not asserted.** A quantity's `MEASURED` / `MODELED` label is derived
  from whether its slot holds a sentinel, at report time. A hand-written label is how a placeholder
  eventually gets published as evidence; deriving it makes that failure unrepresentable.
- **Completeness precedes accuracy, and its failure is not the lesser failure.** An accuracy gate
  evaluated while inputs are absent is a model agreeing with its own fallback constants, so the
  tier carries a third gate: every quantity the harness was instrumented to measure must actually
  be present. When it is not, the tier verdict is `FAIL (INPUTS ABSENT)`, and it withholds
  publication exactly as a numeric failure does. The expected-input list lives in one place, so
  instrumenting a new quantity and forgetting to declare it cannot make the gate pass by omission.
- **Thresholds are written down before results are read, and are not moved afterwards.** The
  accuracy threshold (within-2x fraction `>= 0.80`) and the bias threshold (mean **signed**
  residual within `+-0.50`) are carried forward unchanged. The signed test is retained precisely
  because the observed bias is in the flattering direction: a model that is consistently
  conservative about a competitor's cost is biased, not safe, and an absolute-error gate cannot
  distinguish the two.
- **Off-path instrumentation is a correctness requirement, not a preference.** Counters are
  primitive fields, `AtomicLong` at worst, allocate nothing per attempt, take no snapshot copy and
  never block a tick thread. This tier has twice had its own apparatus become the headline - a 24x
  allocation inflation from a snapshot copy, and a shared FIFO reporting its own queue discipline
  as the design's latency - so an allocating or blocking counter is a measurement error.
- **Folia is detected at runtime, never configured.** Folia-only columns are gated on the existing
  runtime detection. No build variant and no config toggle is introduced for something the process
  can observe about itself. Off Folia the columns are still emitted carrying their sentinel, so the
  schema is platform-independent and a reader cannot mistake a platform for a result.
- **Fitting surface is unchanged.** A harness-supplied competitor quantity may be used as a
  disclosed input or compared as a signed residual. It may never become a fitting target:
  parameters are fitted against this plugin on Paper and against nothing else.

**Consequence at the time of writing.** All five efforts are integrated and none has been run, so
every new slot holds its sentinel, input completeness stands at 0 of 7, and the tier verdict is
`FAIL (INPUTS ABSENT)` on top of the pre-existing accuracy (0.643 against 0.80) and bias (+1.554
against +-0.50) failures. Nothing was relabelled `MEASURED` and no threshold was relaxed, so no
latency or throughput figure from this tier publishes. The instrumentation becomes live on the
first archived run with no further model change.
