# CHECKLIST — Build the `rtp test full` umbrella on Fabric

**Effective Issue:** Build the `rtp test full` umbrella on Fabric (TODO.md §1, bullet 1).

**Mode:** `[CODE]` (multi-phase, multi-session).

**Strategic decision already accepted:** Fabric is an in-scope platform per
[`rtp-fabric-ADR-002`](../../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md).
No new ADR is required — this checklist tracks the implementation lift
that the already-accepted plan calls for. (User confirmed 2026-05-14; see
`AGENTS.md` *Self-Updating Protocol* — single-implementation follow-through
on an accepted plan, not a project-wide architectural decision.)

**Acceptance gate (when this checklist closes):** A Fabric operator running
`/rtp test full` produces the same pass/fail matrix as Paper/Folia, with the
following carve-outs explicitly skipped on Fabric and labeled in the audit
log: `folia-ownership` (Folia-only by definition), `economy-isolation`
(Vault is Bukkit-only). All other 13 shipped subcommands must run on
Fabric or be marked skipped with a configurable, S-007-compliant message.

---

## Current Bukkit umbrella shape (baseline survey, 2026-05-14)

Source: `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/commands/test/`.

Umbrella entry: `TestFullCmd extends BaseRTPCmdImpl` (596 lines). Dispatches
in order via the `commands-api` `TreeCommand` parent lookup
(`findChild(subName)`). The 15 dispatched subcommands per
`TestFullCmd.SHIPPED_SUBCOMMAND_NAMES`:

```
commands, api-compat, chunk-ticket, disconnect-midflight,
anvil-prefilter, biome-source, async-chunk-load, scheduler,
folia-ownership, economy-isolation, queue-starvation, async-reply,
disconnect-job, safety-verifier, stress
```

Deliberately excluded from the sweep (still registered as standalone):
`cancel`, `reload-safety`, `config-set`, `chunk-probe-perf`, `full`, `all`.

Supporting infrastructure:

- `ActiveTestJobs` — process-wide registry of in-flight async test jobs.
- `TestSemaphore` — global single-flight gate so umbrella sweeps and
  standalone runs don't collide.
- `*TestJob` classes (`AsyncReplyTestJob`, `DisconnectTestJob`,
  `EconomyIsolationTestJob`, `FoliaOwnershipTestJob`,
  `QueueStarvationTestJob`, `SafetyVerifierTestJob`) — async job bodies
  invoked by their matching `Test*Cmd`.

Bukkit-specific dependencies the lift must abstract:

1. `io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage` (caller output).
2. `org.bukkit.Bukkit.getScheduler()` / `Bukkit.getServer()` direct calls
   inside individual `*TestJob` bodies (use sites surveyed during Phase 2).
3. `org.bukkit.entity.Player` for resolving the caller name in
   `TestFullCmd.resolveName(UUID)` (already null-tolerant via
   `RTPAPI.serverId` fallback).

The dispatch model is already command-driven through `commands-api`'s
`TreeCommand`, so the lift is about making the **leaves** platform-agnostic;
the tree-traversal scaffolding in `TestFullCmd` is already
platform-agnostic logically and only needs its `SendMessage.log` calls
re-routed.

---

## Phase 1 — Core SPI extraction (no behavior change on Bukkit)

- [x] **1.1** Add `io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender`
      interface in `rtp-core` — minimum surface: `void log(Level, String)`,
      `String resolveCallerName(UUID callerId)`. S-006 enforcement deferred
      to the `TestUmbrellaContext` accessor (Phase 1.3) where the null-guard
      lives. Evidence: `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/test/TestUmbrellaSender.java`.
      Signature widened from the original plan (`log(String)` → `log(Level, String)`) to mirror
      `SendMessage.log(Level, String)` and preserve audit-level fidelity required by REQ-RTP-S-004.
- [x] **1.2** Add `io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaScheduler`
      interface in `rtp-core` — minimum surface:
      `void runLater(long delayMillis, Runnable task)` (async or sync left
      to the implementation per platform threading rules; Folia callers
      must respect S-005 / region ownership in their adapters).
      Evidence: `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/test/TestUmbrellaScheduler.java`.
- [x] **1.3** Add `TestUmbrellaContext` carrier in `rtp-core` holding the
      sender + scheduler + audit consumer. Inject through `RTP` (or a new
      `RTP.testUmbrellaContext` static, populated by each platform plugin
      at startup). Evidence: `rtp-core/.../common/commands/test/TestUmbrellaContext.java` and
      `RTP.testUmbrellaContext` volatile static (S-006 enforced via
      `TestUmbrellaContext.require()` throwing `IllegalStateException`).
- [x] **1.4** Bukkit adapter implementations in `rtp-plugin`:
      `BukkitTestUmbrellaSender` (delegates to `SendMessage.log` and
      `RTP.serverAccessor.getPlayer` mirroring the pre-extraction
      `TestFullCmd#resolveName` path), `BukkitTestUmbrellaScheduler`
      (delegates to `RTP.scheduler.runTaskTimerAsynchronously` with a
      self-cancelling single-shot wrapper; falls back to inline-after-sleep
      when the scheduler is unavailable for headless tests). Evidence:
      `rtp-plugin/.../bukkit/commands/test/BukkitTestUmbrellaSender.java`,
      `rtp-plugin/.../bukkit/commands/test/BukkitTestUmbrellaScheduler.java`.
- [x] **1.5** Wire the Bukkit adapters from `RTPBukkitPlugin` on enable;
      keep `TestFullCmd` in `rtp-plugin` for now (Phase 2 moves it).
      Evidence: `RTPBukkitPlugin#onEnable` populates
      `RTP.testUmbrellaContext` immediately after
      `BootstrapSupport.wireServerAccessorAndScheduler` so both adapter
      dependencies (`RTP.serverAccessor`, `RTP.scheduler`) are ready.
- [x] **1.6** Green: existing `TestFullCmdTest` and the broader
      `rtp-plugin :test` task still pass with no changes to test code.
      Evidence: `.\gradlew :rtp-plugin:test` -> BUILD SUCCESSFUL
      (2026-05-14).

## Phase 2 — Move umbrella + leaf commands to `rtp-core`

- [ ] **2.1** Move `TestFullCmd` and `TestCmd` from
      `rtp-plugin/.../bukkit/commands/test/` to
      `rtp-core/.../common/commands/test/`. Replace direct `SendMessage.log`
      and `Bukkit.*` calls with `TestUmbrellaContext` SPI calls.
      `TestSemaphore` and `ActiveTestJobs` are already in
      `rtp-core/.../common/commands/test/` (verified 2026-05-16) — no
      move needed for them.
      - [x] **2.1.a** Extend `TestUmbrellaSender` with default no-op
            `addAuditInterceptor(Consumer<String>)` /
            `removeAuditInterceptor(Consumer<String>)`; override on
            `BukkitTestUmbrellaSender` to delegate to
            `SendMessage.addInterceptor` / `removeInterceptor`. This is the
            precondition for `TestFullCmd`'s `FullAudit` to move into
            `rtp-core` without importing `SendMessage`. Evidence: diff to
            `rtp-core/.../TestUmbrellaSender.java` and
            `rtp-plugin/.../BukkitTestUmbrellaSender.java` (2026-05-16);
            non-Bukkit platforms get an empty audit tally (documented
            degenerate behavior) until they implement the methods.
      - [ ] **2.1.b** Move `TestFullCmd` to `rtp-core`: repackage to
            `io.github.dailystruggle.rtp.common.commands.test`, replace
            `SendMessage.addInterceptor(audit)` /
            `SendMessage.removeInterceptor(audit)` with
            `TestUmbrellaContext.require().sender().addAuditInterceptor` /
            `removeAuditInterceptor`, replace `SendMessage.log` with
            `sender().log`, and reroute the `resolveName(UUID)` Bukkit
            `Player` lookup through `sender().resolveCallerName`. Keep the
            hardcoded `SHIPPED_SUBCOMMAND_NAMES` list intact — dynamic
            per-platform filtering is Phase 2.4. Update all import sites
            in `rtp-plugin` leaf commands.
      - [ ] **2.1.c** Move `TestCmd` to `rtp-core`: same SPI rerouting as
            2.1.b. Note `TestCmd` currently has duplicated import lines
            (cosmetic, legal Java) — the move naturally deduplicates them.
            `BukkitTestCmd` in `rtp-plugin` stays as a thin registration
            shim that subclasses the moved `TestCmd`.
- [ ] **2.2** Move each shipped `Test*Cmd` leaf. Classify per leaf:
      - **Cross-platform:** `commands`, `api-compat`, `chunk-ticket`,
        `disconnect-midflight`, `anvil-prefilter`, `biome-source`,
        `async-chunk-load`, `scheduler`, `queue-starvation`, `async-reply`,
        `disconnect-job`, `safety-verifier`, `stress`. Move to `rtp-core`
        and route Bukkit-specific bits through the SPI or through a
        per-leaf platform-capability hook.
      - **Bukkit-only:** `folia-ownership` (Folia adapter symbol), `economy-isolation`
        (Vault). Keep in `rtp-plugin`; register only on Bukkit-family
        platforms. The umbrella sweep must tolerate their absence on
        Fabric and emit a configurable "skipped — not applicable on this
        platform" line per S-007.
- [ ] **2.3** Move the `*TestJob` async bodies for the cross-platform
      leaves to `rtp-core`. Bukkit-only jobs stay in `rtp-plugin`.
- [ ] **2.4** Update `TestFullCmd.SHIPPED_SUBCOMMAND_NAMES` semantics: now
      a per-platform filtered view derived from registered children rather
      than a hardcoded list. Preserve the original ordering by capability
      tier.
- [ ] **2.5** Green: `rtp-core :test`, `rtp-plugin :test`, and the
      coverage-parity assertion in `TestFullCmd.auditShippedCoverage` all
      still pass.

## Phase 3 — Fabric registration

- [ ] **3.1** Fabric-side adapters: `FabricTestUmbrellaSender`,
      `FabricTestUmbrellaScheduler` under `rtp-fabric/rtp-fabric-common/`.
      Sender uses the Fabric `ServerPlayerEntity` source + `RTP.log` for
      console fallback; scheduler uses the Fabric main-thread executor +
      `RTP.scheduler.runTaskTimerAsynchronously` per S-005.
- [ ] **3.2** Register the umbrella + cross-platform leaves on Fabric by
      mirroring the existing Fabric `/rtp` command-registration pattern
      (NOT through the Bukkit-style `JavaPlugin.onCommand`). Per user
      direction 2026-05-14: "mirror our other command registrations on
      fabric, as we do custom command processing".
- [ ] **3.3** Wire `RTP.testUmbrellaContext` from the Fabric entrypoint
      during `ServerLifecycleEvents.SERVER_STARTED`.
- [ ] **3.4** Manual smoke: `/rtp test full` from a Fabric dedicated server
      produces a log run with the 13 cross-platform leaves dispatched and
      the 2 Bukkit-only leaves marked skipped.

## Phase 4 — Tab-completion parity test (offline)

- [ ] **4.1** Add `FabricRtpTestFullTabCompletionParityTest` in
      `rtp-fabric/.../test/` (or `rtp-core` if the comparison is pure
      `commands-api` `TreeCommand` introspection). Per user direction:
      "tabcompletion currently works, so unit test of the functions is
      fine" — i.e. offline, no live server.
- [ ] **4.2** Test asserts: for every node in the Bukkit-built `test`
      `TreeCommand`, the Fabric-built equivalent either contains the same
      child name OR is on the documented Bukkit-only exclusion list
      (`folia-ownership`, `economy-isolation`).
- [ ] **4.3** Add a row to `docs/dev/TRACEABILITY.md` mapping this test to
      the relevant REQ-* (likely REQ-RTP-S-004 for the audit-log shape
      assertion + a new sub-row for the Fabric coverage commitment if
      `MULTI_PLATFORM_PLAN.md` defines one).

## Phase 5 — Docs and TODO

- [ ] **5.1** Update `docs/dev/TODO.md` §1 first bullet — flip to
      `- [x]` with reference to this checklist's completion commit.
- [ ] **5.2** Promote Fabric out of "unstable" in
      `docs/dev/MULTI_PLATFORM_PLAN.md` and the *Current Development
      Focus* section of `.junie/AGENTS.md` (TODO.md §1 second bullet).
- [ ] **5.3** `CHANGELOG.md` entry under the current unreleased version,
      describing the net delta from the last released tag per the
      *CHANGELOG Hygiene* rule.
- [ ] **5.4** Delete this checklist file when all phases land.

---

## Blocking decisions awaiting user approval

None at time of writing — user pre-approved the four-phase shape on
2026-05-14 (see chat transcript). Subsequent sessions should re-read this
checklist first, re-verify the last `[x]` box still holds, then resume
from the first `[ ]`.

**Accepted carve-outs (user-confirmed 2026-05-16):** the lack of
`folia-ownership` and `economy-isolation` on Fabric is **accepted as
permanent platform-capability gaps**, not deferred work:

- `folia-ownership` — Folia region-ownership is a Folia-only concept; no
  equivalent exists on Fabric. Skipping is correct by definition, not a
  shortcoming to revisit.
- `economy-isolation` — Vault is Bukkit-family-only; Fabric has no
  Vault analogue in scope for RTP. Skipping is correct by definition.

Both are excluded from the Fabric `rtp test full` matrix and emit a
configurable S-007 "skipped — not applicable on this platform" line.
They do **not** count against the acceptance gate and shall not reopen
this checklist for re-litigation. Tab-completion parity (Phase 4.2) and
the matrix (below) already encode this as the documented exclusion list.

## Per-job platform-capability matrix (decision record)

| Subcommand | Cross-platform? | Notes |
|------------|-----------------|-------|
| `commands` | ✅ | Pure `commands-api` introspection. |
| `api-compat` | ✅ | `rtp-api` only. |
| `chunk-ticket` | ✅ | Uses platform `ChunkSet` async — SPI required. |
| `disconnect-midflight` | ✅ | Pipeline-only; needs sender SPI. |
| `anvil-prefilter` | ✅ | `rtp-anvil` is platform-agnostic. |
| `biome-source` | ✅ | `BiomeSourceMetrics` is in `rtp-core`. |
| `async-chunk-load` | ✅ | Uses the platform adapter's async abstraction. |
| `scheduler` | ✅ | `TestSchedulerCmd` already abstracts via `RTP.scheduler`. |
| `folia-ownership` | ❌ | Folia-only by definition. Skip on Fabric. |
| `economy-isolation` | ❌ | Vault is Bukkit-only. Skip on Fabric. |
| `queue-starvation` | ✅ | `RegionQueueManager` is in `rtp-core`. |
| `async-reply` | ✅ | Uses the SPI sender. |
| `disconnect-job` | ✅ | Pipeline-only. |
| `safety-verifier` | ✅ | S-001 / S-005 verifier is in `rtp-core`. |
| `stress` | ✅ | Uses `RTP.scheduler`. |

13 of 15 are cross-platform; 2 are Bukkit-only and explicitly skipped
on Fabric with an S-007 message.
