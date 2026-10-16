# Review Guide: Safety Prohibitions & Concurrency Invariants

Every pull request must be rigorously evaluated against the core safety prohibitions (Rules S-001 through S-007) and regional threading invariants (Rules F-001 through F-003). These rules exist to prevent catastrophic server lockups, chunk corruption, and memory exhaustion.

Violations of these invariants represent critical defects and must hard-block approval.

---

## Safety Prohibitions (S-001 through S-007)

### Rule S-001: Safe Teleport Destination Blocks
- **Invariant:** Teleport targets must be validated safe from lava, fire, void, suffocation, and hazardous fluids before finalizing.
- **Evaluation:**
  - Verify that safety checks route through the central safety pipeline stage.
  - Prohibit redundant, ad-hoc block checks in platform adapters or command handlers.

### Rule S-002: Zero Chunk Ticket & Memory Leaks
- **Invariant:** No chunk ticket shall remain permanently force-loaded, and no task allocation shall leak in memory.
- **Evaluation:**
  - Every acquired chunk ticket and every `TeleportPipelineTask` must register with `MemoryTracker`.
  - Verify that explicit release logic (`MemoryTracker.removeChunkTicket(...)`, task completion hooks) is executed across **all** exit branches: normal success, player cancellation, teleport timeout, server shutdown, and unexpected runtime exceptions.
  - Flag double-release hazards (calling `close()` more than once on a ticket).

### Rule S-003: Land Claim Protection
- **Invariant:** Teleport destinations must never violate third-party claim plugin boundaries.
- **Evaluation:**
  - Prohibit inline, direct calls to claim plugin APIs inside core pipeline stages.
  - All claim boundary validations must route through the unified claim adapter SPI.

### Rule S-004: Zero Swallowed Teleport Failures
- **Invariant:** Teleport pipeline failures must never be silently ignored or discarded.
- **Evaluation:**
  - Hard-block any empty `catch` blocks or silent `return;` statements in pipeline stages.
  - Every failure state (unsafe block, claim denial, timeout, cancellation) must log diagnostic information and notify the player via configured messages.

### Rule S-005: Zero Main-Thread Chunk I/O
- **Invariant:** No synchronous chunk loading, chunk generation, or blocking I/O shall ever occur on a main server tick thread.
- **Evaluation:**
  - Flag any synchronous `world.getChunkAt(...)` call on any main-thread path.
  - Flag calling `.get()` or `.join()` on an asynchronous chunk future without offloading.
  - Verify that off-tick chunk inspection routes through `anvil-api` region decoders or asynchronous platform adapters.

### Rule S-006: Pre-Init API Fail-Closed Contract
- **Invariant:** Public `rtp-api` accessors called prior to core initialization must fail closed with an explicit `IllegalStateException`.
- **Evaluation:**
  - Reject null-guard returns that silently no-op or return `null`.
  - Reject code paths that permit uncontrolled `NullPointerException`s to propagate to callers.

### Rule S-007: Configurable Failure Messages
- **Invariant:** User-facing error and command feedback messages must be fully configurable.
- **Evaluation:**
  - Prohibit hardcoded string literals for command failure states, busy messages, or error feedback.
  - All strings must resolve via `messages.yml` (REQ-RTP-F-013).

---

## Regional Threading & Concurrency (F-001 through F-003)

### Rule F-001: Folia Regional Ownership
- **Invariant:** Tasks scheduled on Folia regional schedulers must verify regional ownership before dispatching.
- **Evaluation:**
  - On Folia platforms, verify that `Bukkit.isOwnedByCurrentRegion` is checked prior to regional scheduling to prevent 1-tick dispatch penalties and scheduling lockups.
  - Ensure entity and chunk state are accessed only when holding the appropriate regional lock.

### Rule F-003: Folia Economy & Third-Party Hook Isolation
- **Invariant:** Synchronous third-party API calls (e.g., Vault, economy plugins) must never be executed on Folia regional threads.
- **Evaluation:**
  - Verify that economy transactions and third-party integrations are offloaded to asynchronous or global schedulers.
  - Flag direct invocations that risk `ThreadAccessException`.

### Raw Java Thread Prohibition
- **Invariant:** Backend plugin JVMs must schedule all asynchronous, periodic, or delayed work through `RTP.scheduler` (`RTPScheduler` SPI).
- **Evaluation:**
  - Hard-block any direct instantiation of `new Thread(...)` or `Executors.new*ThreadPool(...)` in backend code.
