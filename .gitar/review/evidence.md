# Review Guide: Proportional Evidentiary Standards

"Extraordinary claims require extraordinary evidence." Pull requests must substantiate architectural safety, memory stability, and performance claims with empirical proof proportional to the blast radius of the touched subsystems.

Theoretical assertions of correctness are not accepted for critical subsystems.

---

## 1. Critical Concurrency & Regional Threading Subsystems
*Scope: `rtp-core/.../tasks/teleport/`, `rtp-folia/`, scheduler SPI implementations.*

### Mandatory Evidentiary Requirement
Pull requests modifying task dispatching, regional thread boundaries, or chunk retrieval must include:
1. **Regional Ownership Verification:** Traceable unit tests explicitly proving that tasks execute within the correct regional thread context or verify `Bukkit.isOwnedByCurrentRegion`.
2. **Acceptance Test Execution:** For multi-platform or cross-server scheduling modifications, headless devstack acceptance output (`devstack/run-acceptance.ps1 -Scenario rtptest` or relevant scenarios) demonstrating zero regional lockups, zero timing violations, and passing accessor tests across all active backends (Paper, Folia, Fabric, NeoForge).

---

## 2. Resource Lifecycle & Memory Management Subsystems
*Scope: `MemoryTracker.java`, chunk ticket lifecycles, player session wrappers.*

### Mandatory Evidentiary Requirement (PR #57 Fuzzing & Leak Suite)
Pull requests touching `MemoryTracker`, chunk ticket retention, or player session lifecycle management must present empirical validation using the leak and fuzzing harness (`helpers/StressTestRTP/LEAK_FUZZING.md`, ADR-111):

1. **Retained Session Verification:**
   - Execute a minimum 24-episode fuzzing sweep using `scripts/bench_fuzz.py`:
     ```powershell
     python scripts/bench_fuzz.py --mode leak --episodes 24 --reps 3
     ```
   - Must prove that no class is flagged by the per-departure or cumulative class screen against the `direct` control (`departure_screen` and `screen` in the target's `fit.json`) across all disconnected client runs.
2. **Heap Residency Trajectory:**
   - Demonstrate that the post-GC heap residency slope remains flat relative to the `direct` control baseline.
3. **Oracle Exception Audit:**
   - Confirm zero `ServerLogOracle` exceptions (no orphaned task stack traces, no unhandled pipeline errors).
4. **Heap Dump Root Path Analysis (if heap delta > 0):**
   - If post-fuzzing heap residency increases, provide root path analysis (via Eclipse MAT or `scripts/heap_owner.py`) proving that retained objects are not orphaned `TeleportPipelineTask` instances or unreleased chunk tickets.

---

## 3. Region File Prefiltering & I/O Decoders
*Scope: `api/anvil-api/`, multi-format region decoders (ADR-016, ADR-077), `L3BacklogCache` (ADR-028).*

### Mandatory Evidentiary Requirement
1. **Off-Tick Execution Proof:** Unit tests proving that raw file reads, chunk layout parsing, and decompression execute entirely off the main server tick thread (zero `S-005` risk).
2. **Bounded Memory Occupancy:** Benchmark or test evidence proving that chunk caches and backlog prefilters enforce strict bounded capacities under continuous allocation without memory growth.

---

## 4. Configuration & Command Dispatch
*Scope: `commands-api/`, `rtp-core/.../configuration/`, schema parsers.*

### Mandatory Evidentiary Requirement
1. **Diagnostic Audit (`REQ-RTP-S-004`):** Verification that invalid command parameters, malformed tokens, and schema parsing errors emit warning logs rather than failing silently or crashing.
2. **Round-Trip Serialization Integrity:** Unit tests demonstrating that loading and saving configuration preserves default keys, structure, and comment blocks (ADR-041, ADR-042).
