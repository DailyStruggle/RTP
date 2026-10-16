# Mandatory Pull Request Blocking Criteria

Gitar must evaluate pull requests with an uncompromising stance on correctness and stability. The operational and human costs of defects in multi-threaded Minecraft server environments (Folia, Paper, Fabric, NeoForge) are severely asymmetrical: a single escaped bug can freeze tick loops, exhaust server heaps, corrupt world regions, or mislead server operators across thousands of production servers.

Under no circumstances should review friction be minimized or velocity prioritized when evaluating changes against the following non-negotiable criteria. Any violation must trigger a hard block on the pull request.

---

## 1. Concurrency & Regional Threading (Rules S-005, F-001, F-003)

- **Synchronous Main-Thread Chunk I/O (Rule S-005 / REQ-RTP-S-005):**
  - Any blocking chunk load (`world.getChunkAt(...)` on main thread), synchronous generation, or calling blocking `.get()` on an asynchronous chunk future without offloading.
  - All chunk loading and region prefiltering must route asynchronously or through off-tick region decoders (`anvil-api`, ADR-016, ADR-077).
- **Folia Regional Thread Invariants (Rule F-001 / REQ-FOLIA-ARCH-001):**
  - Regional task scheduling dispatched without prior evaluation of `Bukkit.isOwnedByCurrentRegion`.
  - Missing region ownership validation before accessing chunk or entity state.
- **Cross-Thread Economy Invocations (Rule F-003 / REQ-FOLIA-ARCH-007):**
  - Direct, synchronous calls to third-party APIs (e.g., Vault, economy hooks) from Folia regional threads. Economy transactions must route to global or asynchronous schedulers to eliminate `ThreadAccessException`.
- **Raw Thread Prohibitions:**
  - Instantiating raw Java threads (`new Thread()`, `Executors.new*ThreadPool`) outside `RTP.scheduler` (`RTPScheduler` SPI).

---

## 2. Resource Lifecycle & Memory Leaks (Rules S-002, S-004, ADR-111)

- **Chunk Ticket & Task Leaks (Rule S-002 / REQ-RTP-S-002):**
  - Any chunk ticket acquisition or `TeleportPipelineTask` allocation that does not register with `MemoryTracker`.
  - Any missing release path across all termination branches: normal completion, timeout, player disconnect, cancellation, and unexpected exceptions.
  - Redundant double-release calls (`close()` called more than once on a ticket).
- **Swallowed Failures (Rule S-004 / REQ-RTP-S-004):**
  - Silent `return;` statements in pipeline stages, empty `catch` blocks, or unlogged teleport aborts. Every terminal failure state must log diagnostic output and provide player feedback.

---

## 3. Platform Boundary Isolation & Architecture (Rule D-005, ADR-115)

- **Platform Leakage:**
  - Any import of platform-specific packages (`org.bukkit.*`, `net.minecraft.*`, `net.fabricmc.*`, `net.neoforged.*`) into platform-agnostic modules (`rtp-core`, `rtp-api`).
- **Pre-Implementation Architectural Proposal (Rule D-005 / ADR-000):**
  - Any pull request touching more than one class, crossing module boundaries, or altering command architectures that fails to cite an approved architectural proposal or governing ADR in its description.
- **Package DAG Cycles & Tier Invariants (ADR-115):**
  - Introduction of circular dependencies between packages in `rtp-core` or violations of the 4-tier dependency order (Tier 1 schemas/contracts -> Tier 2 domain engines/storage -> Tier 3 execution pipelines -> Tier 4 orchestration/ingress).
- **Pre-Init API Fail-Closed Contract (Rule S-006 / REQ-RTP-S-006):**
  - Public `rtp-api` accessors invoked prior to core initialization failing to throw `IllegalStateException`.

---

## 4. Documentation Accuracy & Traceability Parity (Rules D-001, D-007)

- **Admin Documentation Parity & Accuracy:**
  - Any addition, deletion, or modification of configuration keys (`configuration.schema.*`, `config.yml`, `messages.yml`) without matching, verified updates to `docs/admin/configuration/` and baseline localization files (`lang/messages.lang.yml`, `lang/<locale>/messages.yml`).
  - Syntactic or semantic inaccuracies in documented admin configuration examples (wrong data types, invalid YAML syntax, or default values that contradict code constants).
- **Command & Permission Parity:**
  - New subcommands, parameters, or permission nodes introduced without matching documentation in `docs/admin/COMMANDS.md`.
- **Traceability Maintenance (Rule D-001):**
  - Production classes implementing `REQ-*` requirements modified or renamed without corresponding updates to `docs/dev/TRACEABILITY.md`.
- **Subsystem Scope Alignment (Rule D-007):**
  - New modules, command families, or platform adapters introduced without updating `docs/dev/REQUIREMENTS.md` Section 0 (In Scope / Out of Scope).

---

## 5. Evidentiary Burden on Critical Paths

- **Unattested Concurrency or Memory Claims:**
  - Pull requests altering critical execution pipelines (`rtp-core/.../tasks/teleport/`, `MemoryTracker`, `rtp-folia/`, or `anvil-api`) that assert thread safety or leak-freedom without presenting empirical test/fuzzing attestation (`scripts/bench_fuzz.py`, ADR-111).
