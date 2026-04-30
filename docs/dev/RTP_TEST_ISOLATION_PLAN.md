# RTP Test Isolation Plan

Tracks the multi-step rollout of innate isolation between every `rtp test *`
runtime test, approved under D-005 on 2026-04-28.

Companion docs:
- Existing chain mechanics: [`RTP_TEST_FULL_RELEASE_PLAN.md`](./RTP_TEST_FULL_RELEASE_PLAN.md)
- Test catalogue: [`RUNTIME_TEST_SUITE_PLAN.md`](./RUNTIME_TEST_SUITE_PLAN.md)
- Architectural decision: **ADR-019** (to be authored as part of this plan)

---

## Goal

No two `rtp test *` invocations may share runtime state — neither across
distinct callers nor across the steps of a single `rtp test full` sweep.
Future test additions inherit isolation automatically, without per-author
discipline.

---

## Approved design (locked 2026-04-28)

Three layers, composed:

1. **Per-caller semaphore.** A single permit per caller UUID. A second
   `rtp test *` invocation by the same caller while one is running is
   rescheduled via `RTP.scheduler.runTaskLater` (no thread parking,
   REQ-RTP-S-005). Released on completion, exception, or
   `ActiveTestJobs.cancelOwned`. Bounded retry budget; give-up logs S-004
   WARNING.
2. **Per-test `cleanup(UUID)` contract.** Each `Test*Cmd` owns its own
   teardown, invoked by the dispatcher in a `finally` block. Default
   implementation is a no-op (covers read-only tests).
3. **Post-cleanup audit (`TestIsolation`).** A canonical state-surface
   list snapshots/asserts/force-cleans the caller's state. Anything left
   dirty after `cleanup` is a bug in the test; logged at WARNING per
   S-004, then force-cleared so the next test still starts pristine.

### Approved decisions

- [x] **Semaphore granularity**: per-caller.
- [x] **Isolation scope**: applied to both `rtp test full` and standalone
      `rtp test <sub>`.
- [x] **`cleanup` throw policy**: log WARNING + force-clean, then continue.
      Aborting the sweep on a single buggy cleanup is rejected.

---

## Implementation Checklist

Mark items `[x]` only after the change is committed and tests pass.

### Phase 1 — Semaphore foundation

- [x] **1.1** New `rtp-plugin/.../test/TestSemaphore.java`
  - [x] `tryAcquire(UUID caller, String subName) -> boolean`
  - [x] `release(UUID expectedOwner, String expectedSubName)` (CAS-guarded)
  - [x] `holderOf(UUID caller)` for `rtp test cancel` reporting
  - [x] ~~REQ-RTP-S-006: `IllegalStateException` if `RTP.getInstance() == null`~~
        — N/A: class is package-private; S-006 applies to public `rtp-api`
        entry points only. Documented in the class Javadoc.
  - [x] Per-caller storage: `ConcurrentMap<UUID, Holder>`
- [x] **1.2** New `TestSemaphoreTest.java` (8/8 passing)
  - [x] acquire/release round-trip
  - [x] second acquire by same caller while held → false
  - [x] second acquire by *different* caller → true (per-caller granularity)
  - [x] release(wrong owner) is a no-op
  - [x] release(wrong subName) is a no-op; null subName matches (cancel path)
  - [x] `releaseOwned` force-releases regardless of subName
  - [x] null arguments rejected at acquire time
- [x] **1.3** Wire into `TestCmd.onCommand` (parent dispatcher)
  - [x] Acquire before child dispatch; on failure, `runTaskLater(retry, 20 ticks)`
  - [x] Bounded retries (default 60 → ~60s); S-004 WARNING on give-up
  - [x] Release after both the args-form future completes AND the
        caller's `ActiveTestJobs` entries drain (via
        `ActiveTestJobs.addOnEmptyListener`)
  - [x] Override is on the args-form `onCommand(callerId, perm, msg, args, i, params)`
        in `TestCmd`; bridges to the default dispatch through
        `BaseRTPCmdImpl.defaultOnCommand` (Java forbids
        `BaseRTPCmd.super.x()` from a transitive subclass — the bridge
        is the only legal way to invoke the inherited `TreeCommand`
        default from outside the direct implementer)
  - [x] Synchronous-failure path releases the permit and logs S-004
        before propagating the throwable
  - [x] Headless fallback: scheduler-unavailable retry runs inline
        (mirrors `TestFullCmd.onCommand`'s `runTaskAsynchronously`
        try/catch) so commands never silently no-op
- [x] **1.4** Wire `ActiveTestJobs.cancelOwned` to release the caller's permit
      (calls `TestSemaphore.releaseOwned(owner)` after firing drain listeners;
      `ActiveTestJobsTest` 10/10 still passing)
- [x] **1.5** New `TestSemaphoreCancelTest.java` (3/3 passing)
  - [x] cancel mid-hold frees the permit for re-acquire (with and without jobs)
  - [x] cancel on one caller does not affect another caller's permit

### Phase 2 — Per-test `cleanup` contract

- [ ] **2.1** Add `protected void cleanup(UUID callerId)` default no-op to
      the appropriate base class (TBD: `BaseRTPCmdImpl` vs. a new
      `TestBaseCmdImpl` subclass; lean toward subclass to keep the contract
      scoped to test commands).
- [ ] **2.2** Implement `cleanup` in each state-mutating subcommand:
  - [ ] `TestChunkTicketCmd` — release leaked sentinels; assert baseline
  - [ ] `TestDisconnectMidflightCmd` — assert+force-clean probe UUID's four maps
  - [ ] `TestSchedulerCmd` — unregister hook; verify no orphan timers
  - [ ] `TestStressCmd` — clear caller from processing/invulnerable; restore teleport-data snapshot
  - [ ] `AsyncReplyTestJob` (via its parent cmd) — same as stress (single iter)
  - [ ] `DisconnectTestJob` — assert+force-clean its synthetic UUID
  - [ ] `QueueStarvationTestJob` — release any leaked reservation tickets
  - [ ] `TestReloadSafetyCmd` — formalise existing snapshot/restore as `cleanup`
  - [ ] `TestConfigSetCmd` — formalise existing snapshot/restore as `cleanup`
  - [ ] All read-only tests — explicit comment confirming default no-op suffices
- [ ] **2.3** New `TestCleanupContractTest.java`
  - [ ] Reflectively asserts every shipped subcommand either overrides
        `cleanup` or is on a documented `READ_ONLY_ALLOWLIST`
  - [ ] Fails CI if a new subcommand is added without taking a side

### Phase 3 — `TestIsolation` audit + safety net

- [ ] **3.1** New `rtp-plugin/.../test/TestIsolation.java`
  - [ ] `Snapshot capture(UUID caller)` — pure read of canonical surfaces
  - [ ] `void forceClean(UUID caller)` — clears every surface for the caller
  - [ ] `void restore(UUID caller, Snapshot s)` — re-applies pre-sweep state
  - [ ] `List<String> assertClean(UUID caller)` — returns dirty surface names
  - [ ] Canonical `SURFACES` list (ordered for deterministic logging):
        `processingPlayers`, `invulnerablePlayers`, `latestTeleportData`,
        `priorTeleportData`, plus any caller-scoped cooldown map
  - [ ] REQ-RTP-S-006: throws `IllegalStateException` if `RTP.getInstance()` null
- [ ] **3.2** New `TestIsolationTest.java`
  - [ ] capture → mutate → restore round-trip on every surface
  - [ ] `assertClean` returns expected dirty surfaces when each is touched
  - [ ] `forceClean` zeros every surface
- [ ] **3.3** Wire into `TestCmd.onCommand` (standalone isolation)
  - [ ] Pre-dispatch: `preSnapshot = capture()`; `forceClean()` (start pristine)
  - [ ] Post-drain: invoke `cleanup()`; `dirty = assertClean()`; if dirty
        → S-004 WARNING + `forceClean()`; finally `restore(preSnapshot)`
- [ ] **3.4** Wire into `TestFullCmd.runStep` (per-step isolation)
  - [ ] Same wrap as 3.3, but `restore(preSweepSnapshot)` runs only in
        `finishSweep` so the sweep ends with the caller's real state intact
- [ ] **3.5** New cases in `TestFullCmdTest`
  - [ ] Two consecutive pipeline-running steps observe pristine state
  - [ ] Drain-timeout path still hits `forceClean`
  - [ ] Caller's pre-sweep state is restored after `finishSweep`

### Phase 4 — Future-drift safeguard

- [ ] **4.1** Tag every caller-scoped field on `RTP` with the comment
      `// caller-scoped: extend TestIsolation`
- [ ] **4.2** New `scripts/check_test_isolation.sh` (mirrors
      `check_traceability.sh`)
  - [ ] Scans `RTP.java` for the marker comment
  - [ ] Fails if any tagged field is absent from `TestIsolation.SURFACES`
- [ ] **4.3** Wire into Jenkins build alongside existing checks

### Phase 5 — Documentation

- [ ] **5.1** Author **ADR-019 — Innate isolation between runtime tests**
  - [ ] Captures the three-layer model and the locked decisions
  - [ ] Documents the SURFACES extension contract
  - [ ] Linked from `AGENTS.md` (Required Reading table) and from
        `RUNTIME_TEST_SUITE_PLAN.md`
- [ ] **5.2** Update `RTP_TEST_FULL_RELEASE_PLAN.md` to point at ADR-019
- [ ] **5.3** Update `RUNTIME_TEST_SUITE_PLAN.md` §4 to note that every
      shipped test must declare `cleanup` or be on the read-only allowlist
- [ ] **5.4** Update `TRACEABILITY.md` if any new REQ-* IDs are introduced
      (currently anticipated: none — REQ-RTP-S-004/005/006 already cover this)

---

## Risks tracked during rollout

- **R-1** Per-caller semaphore lets two operators run concurrent tests.
  By design — re-evaluate if shared global resources (region queues,
  Vault) cause flakes.
- **R-2** `TestIsolation.forceClean` on drain timeout may mask the
  underlying bug. Mitigation: timeout already logs WARNING per S-004; the
  force-clean log is a *separate* WARNING so both are visible.
- **R-3** `cleanup` running for a test that never mutated state still
  costs a snapshot+assertClean round-trip. Mitigation: snapshot is pure
  reads of small maps; cost is negligible.
- **R-4** Player running `rtp test full` no longer ends up wherever
  `stress` last teleported them — they end at their starting location.
  This is a behavioural change for operators; documented in ADR-019 and
  surfaced via a final chat message.

---

## Status

- **2026-04-28** — Plan approved, design locked. Implementation pending
  (Phase 1.1 next).
- **2026-04-28** — Phase 1.1, 1.2, 1.4, 1.5 complete. `TestSemaphore`
  landed with 8 unit tests; `ActiveTestJobs.cancelOwned` now releases
  the permit and is covered by 3 new tests. Next: **1.3** (wire into
  `TestCmd.onCommand` with reschedule-via-`runTaskLater` retry).
- **2026-04-28** — Phase 1.3 complete. `TestCmd` now overrides the
  args-form `onCommand` and acquires the per-caller permit before any
  child dispatch; on contention it reschedules via `runTaskLater(20
  ticks)` up to 60 retries (system-property `rtp.test.dispatch.retryLimit`
  override) before logging S-004 WARNING and falling through. Release
  is gated on both args-form completion AND `ActiveTestJobs` drain via
  `addOnEmptyListener`. Required a small new bridge
  `BaseRTPCmdImpl.defaultOnCommand` in `rtp-core` because
  `BaseRTPCmd.super.onCommand(...)` is illegal from a transitive
  subclass. Lint clean; `TestSemaphoreTest` 8/8, `TestSemaphoreCancelTest`
  3/3, `ActiveTestJobsTest` 10/10, `TestFullCmdTest` 8/8 all green.
  Phase 1 fully complete; next: **2.1** (cleanup contract on test
  base class).
