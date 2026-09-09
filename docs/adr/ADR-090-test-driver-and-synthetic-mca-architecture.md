# ADR-090 — Test-Driver, Synthetic MCA Generator, and Dimension Benchmark Dataset

**Status:** Proposed  
**Date:** 2026-09-08  

## Context

Testing the end-to-end teleport pipeline (`shape.rand()` -> chunk loading -> vertical raycasting -> biome/block safety verification -> chunk ticket registration -> destination dispatch) currently faces a structural verification gap:

1. **The Mock Fragility Trap:** Traditional unit tests in `rtp-core` rely on mock interfaces (`MockRTPServerAccessor`, `MockRTPWorld`, `Mockito.mock(World.class)`). These mocks do not execute realistic chunk I/O, binary Anvil parsing, or vertical block collision, allowing regressions in palette bit-unpacking or raycast bounds to escape into production undetected.
2. **Repository Size vs. Authentic MCA Reality:** Real Minecraft `.mca` files contain authentic format quirks: variable-bit palette layouts (4-bit to 12-bit packed longs), Linear/ZSTD compression headers (`ADR-077`), sparse chunk sections, and non-uniform biome palettes. However, committing entire server worlds (such as the 4,356 `.mca` files available on local Folia test servers) into git creates unsustainable repository bloat and slows down CI runners.
3. **Reproducibility of Extreme Terrain Geometries:** Real world files provide authentic data but cannot easily produce exact, mathematically controlled boundary cases (e.g. perfect 1-chunk checkerboard hazard lattices, infinite void basins, vertical bedrock step-gradients) required for automated property-based testing of candidate selection strides (`ADR-088`).
4. **Folia Concurrency Simulation:** `ADR-087` specifies an adaptive tick-budget and multi-region concurrency governor. Testing this backpressure mechanism without spinning up a full Minecraft server requires an in-memory virtual scheduler that accurately simulates Folia's threaded regionizer and regional worker pool contention.

## Decision

Establish a two-tier test-driver architecture combining a **curated multi-dimension real MCA benchmark fixture** with a **symmetric in-memory synthetic MCA generator** and a **Folia concurrency test-driver harness**.

### 1. Curated Multi-Dimension Real MCA Benchmark Fixture

Sample and commit a minimal, high-density matrix of 6 authentic region files harvested from the pre-generated Folia server (`C:\GameServers\Minecraft\testServer\RTP-Folia\26.1`) into `rtp-core/src/test/resources/fixtures/regions/`:

| Dimension | Target Profile | Source Path / Region | Verification Target |
|-----------|----------------|----------------------|---------------------|
| Overworld | Coastal / Deep Ocean | `overworld/region/r.-1.-1.mca` | Water surface rejection, ocean biome exclusion, stride downsampling |
| Overworld | Mountain / Cave System | `overworld/region/r.0.0.mca` | Vertical raycast safety, subterranean roof avoidance, cliff edges |
| Overworld | Plains / Forest | `overworld/region/r.0.1.mca` | Baseline positive control (near-100% safe candidate density) |
| Nether | Lava Sea / Basalt Deltas | `the_nether/region/r.0.0.mca` | Y=32 lava lake rejection, bedrock ceiling raycast limits |
| Nether | High Forest Plateau | `the_nether/region/r.-1.0.mca` | Mid-air platform detection ($32 < Y < 120$) in multi-floor caves |
| The End | Island to Void Transition | `the_end/region/r.0.0.mca` | Void basin handling, island boundary raycast termination |

These files shall be committed in standard compressed format with an aggregate disk footprint strictly capped at $\le 15\text{ MB}$.

### 2. Symmetrical Synthetic Region Generator (`SyntheticRegionBuilder`)

Provide a zero-dependency, in-memory procedural generator in test scope (`io.github.dailystruggle.rtp.common.test.synthetic.SyntheticRegionBuilder`):
- **Binary Compliance:** Directly emits binary-accurate MCA (NBT + ZLIB) or Linear (ZSTD) region files.
- **Parametric Terrain Synthesis:**
  - `checkerboard(int safeChunkEdge, int hazardChunkEdge)`: Generates alternating safe land and hazard (lava/void) chunks to assert stride coverage and prevent aliasing.
  - `steepStep(int xSplit, int yLow, int yHigh)`: Generates a vertical cliff face to test `LinearAdjustor` raycast bounds and fall-damage prevention.
  - `allHazard(Material hazard)`: Generates a 1024-chunk region with zero safe spots to verify bounded retry limits and fail-safe timeout handling.
- **Speed:** Constructs an entire 1024-chunk region in memory in $< 50\text{ ms}$, enabling fast property-based tests in standard Gradle test cycles.

### 3. Folia Concurrency & Backpressure Harness (`SimulatedFoliaRuntime`)

Implement a lightweight, headless runtime harness that exercises the `ADR-087` adaptive governor:
- Models a regional worker thread pool of configurable capacity ($N$ threads).
- Simulates tick-time degradation (injecting configurable artificial MSPT delays).
- Asserts that when disjoint region tasks saturate $C_{\text{max\_regional\_inflight}}$, the scheduler automatically defers Type C regional dispatches and expands the pulse period (`period`) without thread starvation or unhandled exceptions.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Pure Mock Framework (Mockito)** | Does not exercise actual binary chunk decompression, palette bit unpacking, or Anvil section parsing; obscures real-world runtime crashes. |
| **Full World Commit (>500MB)** | Inflates git history, slows down repository cloning for all contributors, and exceeds CI bandwidth budgets. |
| **Synthetic-Only Generation** | Synthetic data lacks the complex palette bit-shift variations, legacy chunk versions, and noisy entity/block data produced by real Minecraft world generation. |

## Consequences

- **Positive:**
  - Real MCA data validates the Anvil/Linear parsing pipeline against genuine Minecraft data structures.
  - The synthetic generator enables deterministic property testing of spatial candidate strides without disk bloat.
  - Headless Folia scheduler simulation validates the `ADR-087` backpressure governor in standard Gradle test runs without booting full server containers.
- **Negative / Trade-offs:**
  - Adds ~15 MB of binary test fixtures to `rtp-core/src/test/resources/`.
  - Maintaining `SyntheticRegionBuilder` requires keeping its NBT chunk schema aligned with targeted Minecraft data versions.

## References

- [ADR-016: Anvil Read-Only Subsystem](ADR-016-anvil-subsystem.md)
- [ADR-077: Multi-Format Region Support (Linear ZSTD)](ADR-077-multi-format-region-support.md)
- [ADR-087: Adaptive Tick-Budget and Memory Backpressure for Folia](ADR-087-adaptive-tick-budget-and-memory-backpressure-for-folia.md)
- [ADR-088: Configurable Downsampling Stride Filter](ADR-088-configurable-downsampling-stride-filter.md)
