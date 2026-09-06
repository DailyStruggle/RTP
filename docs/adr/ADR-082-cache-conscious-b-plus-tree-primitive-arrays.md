# ADR-082 — Cache-Conscious B+ Tree of Primitive Array Pages for Spatial Run Memory

**Status:** Proposed
**Date:** 2026-09-05

## Context

`MemoryShape` tracks valid, invalid, and biome-categorized spatial coordinates by discretizing continuous 2D space into 1D scalar intervals over an Archimedean spiral ([ADR-001](ADR-001-archimedean-spiral-1d-mapping.md)).

Over iterative architectural cycles, run storage has transitioned through three major stages:
1. Flat continuous primitive arrays (`long[]` keys, prefix sums, and cause tags): excellent binary-search cache locality and zero pointer overhead, but rebuilding requires allocating full-length arrays, pushing large tables ($>131,072$ entries) into G1 GC humongous allocation regions.
2. Linked and skip-list pointer structures: evaluated and rejected because object headers (16 bytes per node) and reference pointers (`next` links) inflate resident heap cost to 32–48 bytes per run and induce pointer-chasing L1/L2/L3 cache thrashing during candidate selection passes.
3. Blocked chunked tables with copy-on-write (`BiomeUnionTable`, [ADR-081](ADR-081-unified-blocked-biome-run-table.md)): leaf blocks capped at $B = 1024$ entries ($\le 4\text{ KiB}$) using 32-bit offsets over 64-bit block bases, achieving 10 bytes/run and Eden-only allocation.

While ADR-081 solved allocation pressure during rebuilds, two structural bottlenecks remain as worlds scale to extreme radii (native world border of 30,000,000 blocks / $\pm 30\text{ km}$, $500\text{ km}$, or multi-million block custom horizons):
1. **Linear Rebuild Complexity:** Although untouched leaf blocks are preserved by reference, each pulse merge in `flushAndRebuild()` still performs an $O(N)$ linear walk across all run entries to merge pending discoveries and recompute prefix sums.
2. **Top-Level Fanout Bounds:** The single-level block directory (`blockBaseKey[]`, `blockBaseSum[]`, `blockStart[]`) remains a flat array. When run counts push past millions, the directory itself grows large and ceases to fit in L1/L2 cache during selection binary searches.

## Decision

Adopt a **cache-conscious, immutable B+ tree composed of fixed-size primitive array pages** (inspired by the page layouts of DuckDB, SQLite, and analytical column stores) for long-term spatial run storage and incremental rebuilds.

### 1. Primitive Array Page Layout (No Object Pointers per Run)
- Nodes and leaves are organized strictly as unboxed, flat primitive arrays sized to hardware cache / Eden thresholds (e.g., 2 KiB or 4 KiB per leaf).
- **Leaf Page Structure:**
  - `long baseKey`, `long baseSum`
  - `int[] keyOffsets`
  - `int[] sumOffsets`
  - `short[] biomeOrCauseIds`
  - `long[] expiryEpochs` (optional / tiered)
- **Internal Branch Page Structure:**
  - `long[] separatorKeys`
  - `long[] subtreeCumulativeSums`
  - Child page array references (`LeafPage[]` or `BranchPage[]`)

### 2. $O(\log N)$ Weighted Selection Path
- Selection continues to be driven by a weighted random draw across total available space.
- The binary search navigates the internal branch pages via `subtreeCumulativeSums` down to the target leaf page, and then performs a binary search over `sumOffsets` within the leaf page.
- Traversal touches only $O(\log_B N)$ cache lines (where $B \approx 512\text{--}1024$), retaining sub-microsecond selection times without heap allocation.

### 3. Logarithmic Incremental Merging (LSM / B-Tree Branch Replacement)
- Newly discovered bad sectors or biome observations accumulate in an unmerged $L_0$ pending buffer.
- When $L_0$ is merged into the tree during background maintenance pulses:
  - Only the leaf pages whose key intervals overlap new observations are rewritten.
  - Sibling leaves that do not intersect pending intervals are reused directly by reference.
  - Ancestor branch pages along the insertion path are updated with refreshed cumulative prefix sums.
- Rebuild CPU cost drops from $O(N)$ (entire world walk) to $O(K \log_B N)$, where $K$ is the number of pending updates.

### 4. Structural Fit for Extreme Radii & Hierarchical Encodings
- Leaf blocks can host pluggable compression strategies (e.g., bit-packed deltas or quadtree Morton bitmasks for regional tile skips) without changing the tree routing logic.
- Accommodates massive world boundaries (up to $\pm 30,000,000$ blocks) where run counts exceed millions, ensuring memory scaling remains sublinear and predictable.

### 5. Dynamic Subsystem Selection & Macro-Rejection Summaries
- **Dynamic Subsystem Selection on Initialization:**
  - The spatial memory backend dynamically selects its storage and indexing engine based on configured radius and world geometry:
    - *Small / Standard Worlds ($R \le 10\text{ km}$):* Retains the lightweight, zero-overhead blocked flat union table ([ADR-081](ADR-081-unified-blocked-biome-run-table.md)) with instant startup and minimal RAM footprint ($<50\text{ MB}$).
    - *Massive / Native Border Worlds ($R > 10\text{ km}$ or up to $\pm 30,000\text{ km}$):* Promotes to the multi-level B+ tree layout with regional summary descriptors.
  - Subsystems are decoupled behind cleanly isolated interfaces, enabling isolated deterministic unit testing of each layout without disk or chunk I/O dependencies.
- **In-Memory Region-File Descriptors (Reject-Before-File-Open):**
  - For massive worlds, opening Anvil (`.mca`) or Linear (`.linear`) region files off-tick still incurs filesystem descriptor and decompression overhead.
  - An in-memory sparse summary table tracks compact metadata per region file ($512 \times 512$ blocks, $32 \times 32 = 1024$ chunks):
    - Rejection ratio / bad-chunk proportion (e.g., a single byte storing 0–255 representing 0%–100% invalid space).
    - Compact 2D occupancy bitmasks (1024 bits = 128 bytes per active region file) or homogeneous tile markers (1 byte denoting 100% ocean, void, or out-of-bounds).
  - High-rejection regions are short-circuited entirely in memory, preserving zero-I/O rejection before touching the filesystem.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Concurrent SkipList (`ConcurrentSkipListMap`) | Pointer chasing destroys cache locality; object headers and pointers consume 32–48 bytes per entry (3–5x overhead over blocked arrays). |
| Retaining Flat Single-Level Block Tables (ADR-081 only) | Requires $O(N)$ linear scans across all existing blocks on every rebuild pulse; the directory arrays eventually exceed L1 cache at millions of runs. |
| Traditional Pointer-Linked B-Trees (Java Object Nodes) | Individual node wrapper allocations cause heap fragmentation and GC overhead; organizing pages as flat primitive arrays ensures compact memory and zero GC churn. |

## Consequences

- **Positive:**
  - Rebuild computation time scales logarithmically with table size ($O(K \log_B N)$) instead of linearly ($O(N)$), eliminating pulse lag in mature, heavily explored worlds.
  - Retains zero-allocation, lock-free selection semantics on region worker threads.
  - Eliminates G1 GC humongous allocation hazards permanently.
  - Provides a clean substrate for native Minecraft world border scale ($\pm 30,000,000$ blocks).
- **Negative / Trade-offs:**
  - Branch traversal has slightly higher branch-prediction overhead than a single-level two-array search for small run counts ($N < 4096$).
  - Structural rebalancing (splits and joins) on leaf page boundaries adds implementation complexity.

## References

- `docs/adr/ADR-001-archimedean-spiral-1d-mapping.md` (1D spiral indexing).
- `docs/adr/ADR-079-cause-based-ttl-and-staged-expiration.md` (Volatility tiering and probation arrays).
- `docs/adr/ADR-081-unified-blocked-biome-run-table.md` (Blocked array layout and base-delta offsets).
- Implementing source: `rtp-core` `MemoryShape.java` (`BiomeUnionTable`).
