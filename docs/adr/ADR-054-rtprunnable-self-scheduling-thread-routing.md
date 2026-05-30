# ADR-054 — RTPRunnable Self-Scheduling Thread Routing

**Status:** Accepted
**Date:** 2026-05-29

## Context

`RTPRunnable` (`rtp-api`, package `io.github.dailystruggle.rtp.common.tasks`) is the platform-agnostic base task for the RTP execution pipeline (countdown delays, post-setup hooks, post-teleport actions, pre-generation work). Historically a caller had to know *both* the task *and* the correct scheduler tier, then call the matching `RTPScheduler` method explicitly:

- async pool — `runTaskAsynchronously` / `runTaskTimerAsynchronously`
- main / region thread — `runTask(RTPLocation, Runnable)`, `runTask(RTPWorld, cx, cz, ...)`
- entity (player) thread — `runTaskForPlayer(RTPPlayer, RTPRunnable, delayTicks)`

On Folia this coupling is a recurring footgun: a task that touches a player must land on that player's entity scheduler, and a task that touches world state must land on the owning region thread, or it throws `ThreadAccessException`. The routing decision lived at every call site rather than with the task that carries the spatial/entity context.

The long-standing backlog item (`todo.md` Phase 1, "`RTPRunnable` spatial-context routing") proposed letting the task self-schedule onto the correct thread. An earlier sketch suggested reusing the `RtpTarget` API value type, but `RtpTarget` is a destination *selector* (`Kind` DEFAULT/REGION/WORLD + a name); it carries no coordinates or player and therefore cannot identify a tick thread. The thread-routing context is an `RTPLocation` (region thread) or an `RTPPlayer` (entity thread) — both already first-class `rtp-api` types, and `RTPRunnable` already exposed a (null-returning) `getLocation()`.

## Decision

`RTPRunnable` gains the thread-routing context and a self-scheduling entry point:

1. **Routing fields (additive).**
   - `RTPPlayer target` — optional entity-thread routing target, with `getTarget()` / `setTarget(...)`.
   - `RTPLocation location` — optional region-thread routing context, with `setLocation(...)`; `getLocation()` now returns this field instead of `null` (subclasses may still override to derive it dynamically). Setters are fluent (return `this`).

2. **Static scheduler hook.** A `public static RTPScheduler scheduler` field on `RTPRunnable`, installed by core in the `RTP` constructor (`RTPRunnable.scheduler = scheduler;`) alongside the existing `null`-scheduler guard. This mirrors the established static-hook injection pattern already used for `trackHook` / `updateHook` / `untrackHook`, keeping `rtp-api` free of any `rtp-core` reference. `RTPScheduler` lives in `rtp-api`, so no new module dependency is introduced.

3. **`schedule()` / `schedule(long delayTicks)`.** Self-dispatch by routing context, in precedence order:
   - a `target` player is set → `runTaskForPlayer(target, this, delay)` (entity thread; natively supports a tick delay and accepts `RTPRunnable` directly);
   - else a `location` is set → `runTask(location, this::runWithTracking)` when no delay, or `runTaskLater(location.world(), cx, cz, this::runWithTracking, delay)` when delayed (chunk coords via `blockX >> 4`, `blockZ >> 4`);
   - else (no spatial context) → `runTaskAsynchronously(this::runWithTracking)` when no delay, or `runTaskLater(this::runWithTracking, delay)` when delayed.
   - `schedule()` uses the task's own `getDelay()`.

4. **Require-by-contract.** `schedule(...)` throws `IllegalStateException` when `scheduler` is `null` (core not yet loaded), consistent with the `rtp-api` policy that entry points must not silently no-op.

The `Runnable`-typed scheduler paths submit `this::runWithTracking` so MSPT accounting and lifecycle cleanup continue to run; the entity path submits `this` because `runTaskForPlayer` takes `RTPRunnable` and the adapter performs tracking.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Store an `RtpTarget` on the runnable for routing | `RtpTarget` is a destination selector (region/world *name*), not a coordinate or player; a name cannot resolve to a tick thread. It cannot drive entity/region routing. |
| Keep routing at every call site | The coupling between "what the task touches" and "which thread it must run on" belongs with the task, not duplicated at each caller; the status quo is the Folia `ThreadAccessException` footgun this ADR removes. |
| Inject the scheduler via constructor parameter | Would touch every `RTPRunnable` construction site across core and adapters; the static-hook pattern already exists for the memory-tracker hooks and is the project idiom for one-time core→api wiring. |
| Have `rtp-api` reach into `RTP.scheduler` (core) | Forbidden: `rtp-api` must not depend on `rtp-core`. `RTPScheduler` is an `rtp-api` interface, so a static field of that type stays within module boundaries. |
| Add an async one-shot-with-delay scheduler method | Out of scope; the no-context delayed case maps cleanly onto the existing `runTaskLater(Runnable, delay)` (main thread), and adding a scheduler method would touch every `RTPScheduler` implementation. |

## Consequences

- **Positive:**
  - Callers that hold an `RTPRunnable` with a player or location can `schedule()` without knowing the scheduler tier; routing is Folia-correct by construction (entity → region → async).
  - No new module dependency, no new `RTPScheduler` method, no change to existing call sites; the change is purely additive.
  - The `null`-scheduler guard makes premature scheduling a loud failure rather than a silent no-op.
  - `getLocation()` now has a usable backing field, closing the long-open `todo.md` spatial-context item without a parallel `SpatialContext` type.
- **Negative / Trade-offs:**
  - A mutable static (`RTPRunnable.scheduler`) is process-global; acceptable because the scheduler is a process-wide singleton already mirrored by `RTP.scheduler`, and it is reset only in tests.
  - `schedule(...)` is opt-in; existing tasks submitted via `RTP.scheduler` directly are unchanged and unaffected.

## References

- `rtp-api/src/main/java/io/github/dailystruggle/rtp/common/tasks/RTPRunnable.java` — routing fields, static `scheduler` hook, `schedule()` / `schedule(long)`.
- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/scheduling/RTPScheduler.java` — the tier methods routed to.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/RTP.java` — installs `RTPRunnable.scheduler` in the constructor.
- `rtp-core/src/test/java/io/github/dailystruggle/rtp/common/tasks/RTPRunnableScheduleRoutingTest.java` — routing + require-by-contract coverage.
- [ADR-023](ADR-023-login-reserve-cache.md), Folia threading rules in `.junie/AGENTS.md` (entity/region scheduler hard rules).
