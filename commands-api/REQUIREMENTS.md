# commands-api Requirements

This document captures the contract that the `commands-api` module shall satisfy across all platform bridges (Bukkit/Spigot/Paper/Folia dispatcher and the Brigadier bridge used by Fabric and any future Velocity/Brigadier client). The contract is derived from the long-standing, working Bukkit dispatcher behavior (`TreeCommand.onCommand`) and is written here so that any alternate bridge — present or future — has a single, testable specification of "what already works on Bukkit".

For design, code-level details, and the Brigadier mapping see [`docs/dev/DESIGN.md`](../docs/dev/DESIGN.md) and [`commands-api-ADR-001`](docs/adr/commands-api-ADR-001-brigadier-bridge.md). For the project-wide safety prohibitions referenced below see [`docs/dev/REQUIREMENTS.md §3`](../docs/dev/REQUIREMENTS.md).

Requirement style follows project rules: requirements state *what*, not *how*; obligations use `shall`; prohibitions use `shall not`. Where a requirement codifies behavior already exhibited by the Bukkit dispatcher, that fact is noted as the **source of truth**, not as implementation guidance.

---

## 1. Functional Requirements

### 1.1 Command Tree as Single Source of Truth

- **REQ-API-F-001 — Single Definition.** The command tree (root, sub-commands, parameters, permissions, help text) shall be defined exactly once in `commands-api`. Platform bridges shall not redefine, fork, or shadow nodes of the tree.
- **REQ-API-F-002 — Platform Parity.** For an identical caller, identical input string, and identical permission set, every platform bridge shall produce identical observable outcomes: the same target command's `onCommand` shall run, with the same `args[]` content, the same cursor index `i`, the same `tempParameters` accumulation, and the same set of user-facing messages.

### 1.2 Dispatch Semantics (Bukkit dispatcher is the source of truth)

- **REQ-API-F-003 — Recursive Parent-Then-Child Dispatch.** When input resolves to a sub-command, the parent command's `onCommand` shall execute first and the sub-command's `onCommand` shall execute afterward, in that order. A bridge shall not skip the parent, shall not skip the child, and shall not invoke either more than once per dispatch.
- **REQ-API-F-004 — Parent Pre-Processing Pipeline.** Any parent-level pre-processing that the Bukkit dispatcher schedules onto `CommandsAPI.commandPipeline` (the `CommandExecutor` queued before recursion into the sub-command) shall also be scheduled by every other bridge. The pipeline shall be drained by the platform's tick loop.
- **REQ-API-F-005 — Wire Format for `args[]`.** The `args[]` array delivered to `TreeCommand.onCommand` shall use the commands-api wire format: sub-command tokens appear as bare literals (e.g. `"info"`); parameter values appear as `<name><parameterDelimiter><value>` tokens (e.g. `"count=7"`). Bridges that consume tokens via a typed argument system shall reconstruct this exact wire format before invoking the root.
- **REQ-API-F-006 — Cursor Advancement.** When the parent recurses into a sub-command, the sub-command's `onCommand` shall be entered with cursor index `i` advanced past the literal that selected it. After parameter tokens are consumed, the cursor shall be advanced past those tokens before any subsequent recursion.
- **REQ-API-F-007 — Tab Completion Parity.** For an identical caller, partial input, and permission set, every bridge shall offer the same set of completions that the Bukkit dispatcher offers via `TreeCommand.onTabComplete`, filtered by the same permission and relevance predicates.

### 1.3 Parameters

- **REQ-API-F-008 — Permission-Gated Parameters.** A parameter shall be offered, accepted, and applied only when the caller passes the parameter's permission predicate. Rejected parameters shall produce a configurable "bad parameter" message and shall not mutate `parameterValues`.
- **REQ-API-F-009 — Multi-Value Expansion.** A single parameter token's value side may contain multiple values separated by `CommandsAPI.multiParameterDelimiter`, and `reg:<pattern>` regex tokens shall be expanded against the parameter's caller-relevant value set. Bridges shall not strip, reorder, or deduplicate values before delivering them to the root.
- **REQ-API-F-010 — Sub-Parameter Accumulation.** When a parameter value contributes sub-parameters (`CommandParameter.subParams`), those sub-parameters shall be merged into `tempParameters` for the remainder of the current dispatch and shall be visible to subsequent argument processing within the same `onCommand` invocation.

### 1.4 Help, Errors, and Messages

- **REQ-API-F-011 — Help Resolution.** The literal token `help` shall print the current node's `help(...)` output unless a sub-command named `HELP` is explicitly registered. This rule shall apply on every bridge.
- **REQ-API-F-012 — Configurable Failure Messages.** The "invalid command" and "bad parameter" messages shall be configurable per platform (matches project-wide **S-007**). Bridges shall route those messages through `messageMethod` rather than hardcoding strings or writing directly to a platform logger.
- **REQ-API-F-013 — Failure Auditing.** Platform-specific overrides of `msgInvalidCommand` / `msgBadParameter` shall additionally log the failure via `RTP.log(Level.WARNING, msg)` so that `rtp test full` and the project's S-004 audit can observe it (project-wide **REQ-RTP-S-004** alignment).

---

## 2. Strict Architectural Requirements

### 2.1 Module Boundaries

- **REQ-API-ARCH-001 — Platform Neutrality.** The `commands-api` module shall not import `org.bukkit.*`, Brigadier client-only types, or any other platform-specific symbol from its core interfaces. Bridge classes that necessarily reference a platform type (e.g. `BrigadierCommandAdapter` referencing `com.mojang.brigadier.*`) shall isolate that dependency to bridge-only files and shall declare it `compileOnly`.
- **REQ-API-ARCH-002 — Thin Platform Shims.** Platform adapters (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, future `rtp-velocity`) shall delegate registration and dispatch to `commands-api` and shall not re-implement tree walking, permission gating, parameter parsing, help text rendering, or completion filtering.

### 2.2 Bridge Contract

- **REQ-API-ARCH-003 — Single Execution Target.** A platform bridge shall route every node's executor to a single execution entry point on the commands-api side (the root's `TreeCommand.onCommand`). A bridge shall not invoke a sub-command's `onCommand` directly when the parent's recursive dispatch would otherwise queue parent-level pre-processing on `CommandsAPI.commandPipeline`.
- **REQ-API-ARCH-004 — No Side-Channel State.** A bridge shall not retain dispatch state between invocations beyond what `commands-api` itself retains (`commandPipeline`, async continuations). Each `/command ...` shall be self-contained from the bridge's perspective.
- **REQ-API-ARCH-005 — Permission Predicate Wiring.** A bridge shall apply each node's `permission()` predicate to its node-visibility filter (e.g. Brigadier's `requires(...)`) so that nodes the caller cannot use shall not appear in tab completion or accept dispatch.

### 2.3 Threading & Pipeline

- **REQ-API-ARCH-006 — Tick-Driven Drain.** `CommandsAPI.commandPipeline` shall be drained by the platform's main-thread tick loop (or its platform-appropriate equivalent on Folia/Fabric). A bridge shall not drain the pipeline synchronously inside a Brigadier executor or any other dispatch callback.
- **REQ-API-ARCH-007 — Async Continuation Safety.** The recursive descent into a sub-command shall be permitted to complete asynchronously (the Bukkit dispatcher uses `CompletableFuture.whenCompleteAsync`). A bridge shall not assume that the sub-command's `onCommand` has run by the time the bridge's executor returns.

---

## 3. Traceability

The following table maps each requirement to its source-of-truth implementation in the Bukkit dispatcher and (where applicable) the regression test that pins the behavior. New bridge implementations shall add a row to `docs/dev/TRACEABILITY.md` cross-referencing the same REQ-API-* IDs.

| REQ ID | Source-of-truth symbol | Regression test |
|--------|------------------------|-----------------|
| REQ-API-F-002, F-003, F-004, F-005, F-006 | `TreeCommand.onCommand` (sub-command branch, lines around the `CommandExecutor` queue + `whenCompleteAsync` continuation) | `ReqApiArch005BrigadierBridgeTest.subCommandDispatchRoutesThroughRootForParity` |
| REQ-API-F-005 (parameter half) | `TreeCommand.splitOnParamDelimiter` + `CommandsAPI.parameterDelimiter` | `ReqApiArch005BrigadierBridgeTest.parameterDispatchReconstructsWireFormat` |
| REQ-API-F-008, F-009, F-010 | `TreeCommand.onCommand` parameter branch (regex expansion, `subParams` merge into `tempParameters`) | (coverage gap — see Notes) |
| REQ-API-F-011 | `TreeCommand.onCommand` `help` literal branch | (coverage gap — see Notes) |
| REQ-API-F-012, F-013 | `TreeCommand.msgInvalidCommand`, platform overrides of `msgInvalidCommand` / `msgBadParameter` | project-wide `REQ-RTP-S-004` audit |
| REQ-API-ARCH-003 | `BrigadierCommandAdapter.attachChildren` (executor target = root) | `ReqApiArch005BrigadierBridgeTest.subCommandDispatchRoutesThroughRootForParity` |
| REQ-API-ARCH-005 | `BrigadierCommandAdapter.applyRequires` | `ReqApiArch005BrigadierBridgeTest.permissionPredicateIsWired` |

---

## Notes

- This document codifies behavior that already works on Bukkit; it does not change the Bukkit dispatcher. Any change to the Bukkit dispatcher that would break one of these requirements shall first update this document (and the corresponding ADR) under Rule **D-005**.
- Coverage gaps marked above (parameter regex expansion through the Brigadier bridge; `help` literal through the Brigadier bridge) are eligible for future regression tests; see `docs/dev/COVERAGE_PLAN.md` for prioritization.
- Cross-references: [`commands-api-ADR-001`](docs/adr/commands-api-ADR-001-brigadier-bridge.md), project-wide [`REQUIREMENTS.md`](../docs/dev/REQUIREMENTS.md), [`DESIGN.md`](../docs/dev/DESIGN.md), [`TRACEABILITY.md`](../docs/dev/TRACEABILITY.md).
