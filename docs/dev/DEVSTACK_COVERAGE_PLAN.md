# Devstack Runtime Coverage & Real-Client Verification Plan

Status: approved 2026-09-15. Owner: enterprise-readiness criterion #1 (see
[`ENTERPRISE_READINESS.md`](./ENTERPRISE_READINESS.md)).

## 0. Why this document exists

Chasing pure-unit 90/80 on `rtp-core` has hit diminishing returns. The remaining
~26.6k missed instructions are concentrated in code that a plain-JVM harness
cannot reach honestly: the `RTP` constructor's server-bound bootstrap lambdas,
platform-adapter seams, and the full teleport/dispatch pipelines that only exist
at runtime. The devstack already exercises exactly that code with a JaCoCo
`-javaagent` attached to every backend/lobby, so runtime-attested coverage is the
more honest way to close the last mile.

This plan splits the work into two independent tracks:

- **Track A - Intermediate release** (product/versioning). Ship today's strong
  state; lock in floors; re-scope criterion #1's definition of done.
- **Track B - Devstack + real-client runtime verification** (evidence/coverage).
  Close the remaining criterion #1 gap with runtime proof instead of increasingly
  artificial unit tests.

Do Track A first (cheap, unblocks shipping). Then run Track B as the ongoing
last mile.

### What already exists (this is a wiring/plan job, not a build job)

- `devstack/docker-compose.coverage.yml` attaches
  `-javaagent:jacocoagent.jar=...,includes=io.github.dailystruggle.rtp.*,dumponexit=true`
  to all 3 backends + 2 lobbies, writing `./jacoco/<service>.exec`.
- `devstack/run-acceptance.ps1 -Coverage` layers that overlay and runs
  `:jacocoServerReport` at the end (registered in root `build.gradle`, merges
  `devstack/jacoco/*.exec` into `build/reports/jacoco/server/`).
- The acceptance harness drives `boot`, `heartbeat`, `killmidflight`,
  `killswitch`, and `rtptest` **headlessly** (Redis introspection + `rcon`/console).
  Only `roundtrip` needs a live client - the pipeline is gated on
  `PlayerJoinEvent`, which cannot be synthesized headlessly.
- `/rtp test accessor` / `/rtp test full` force a mid-session `.exec` dump, so a
  client session's coverage is captured without stopping the stack.

---

## 1. Track A - intermediate release prompt sequence

Run these prompts one per session (or in order within a session). Each is written
to be self-contained and to end with a concrete, verifiable artifact.

### Prompt A1 - re-scope criterion #1 in the readiness doc
> In `docs/dev/ENTERPRISE_READINESS.md`, re-scope criterion #1's definition of
> done: strict 90/80 **unit** JaCoCo floors remain the bar for the pure-logic
> modules (already met by 7/9), while the platform/pipeline seams in `rtp-core`
> (the `RTP` bootstrap lambdas, teleport/dispatch pipeline, adapter glue) are
> credited by **runtime-attested** devstack coverage instead. Keep criterion #1
> marked PARTIAL, add a short subsection pointing at
> `docs/dev/DEVSTACK_COVERAGE_PLAN.md`, and state that the intermediate release
> is cut at the current measured state. Docs-only change; verify with a mojibake
> scan of the diff.

### Prompt A2 - ratchet the rtp-core floors to measured values
> Run `:rtp-core:test :rtp-core:jacocoTestReport -Pcoverage`, read the fresh
> module + per-package numbers from `build/reports/jacoco/test/jacocoTestReport.xml`,
> then use `scripts/ratchet-coverage.py` (monotonic-upward only) to raise
> `coverageFloors[':rtp-core']` in root `build.gradle` from the current
> `0.69/0.54` toward the measured `~0.816/0.658` (leave the documented ~1-2 pt
> stale-exec margin; never lower a floor). Keep the safety-package rules at or
> above the new module floor. Verify with
> `:rtp-core:jacocoTestCoverageVerification -Pcoverage` green, then update the
> floor comment in `build.gradle` with the new measured baseline.

### Prompt A3 - do the same for rtp-proxy-common
> Repeat the ratchet for `:rtp-proxy:rtp-proxy-common`: measure via
> `jacocoTestReport -Pcoverage`, raise its `0.78/0.65` floor toward the measured
> value with `scripts/ratchet-coverage.py`, and confirm
> `jacocoTestCoverageVerification -Pcoverage` stays green.

### Prompt A4 - cut the intermediate release
> Prepare the intermediate release: update `CHANGELOG.md` (diff against the last
> released tag, tag Pro-exclusive lines with `**(Pro)**`, absolute phrasing), bump
> the version per the project convention, and confirm the CI gates
> (`build shadowJar -Pcoverage -PstaticAnalysis`, `diff-coverage.py`,
> `dependency-check`) are green. Do NOT commit or tag unless I explicitly ask.
> Report the release-ready state and any remaining blockers.

> Note: `:rtp-core:spotbugsMain` currently carries a pre-existing 841-finding
> backlog on `src/main` (unrelated to test-only work). If a full `build` is
> required for the release gate, treat that backlog as a separate, tracked item -
> do not attempt to clear it inside the release-cut prompt.

---

## 2. Track B - devstack real-client runtime test plan

Goal: produce a merged runtime JaCoCo report that credits the platform/pipeline
seams, and a repeatable evidence trail, then diff it against the unit report to
show exactly which `rtp-core` classes the devstack uniquely covers.

### 2.1 Preconditions (one-time per machine)

1. Docker Desktop running (Docker Engine 29+, min API 1.40 - same daemon the
   Testcontainers SQL tier now uses).
2. Build + stage jars from repo root:
   ```powershell
   .\gradlew.bat :rtp-plugin:shadowJar :rtp-proxy:rtp-proxy-velocity:shadowJar
   Copy-Item rtp-plugin\build\libs\LeafRTP-Pro-*.jar devstack\jars\plugin\
   Copy-Item rtp-proxy\rtp-proxy-velocity\build\libs\rtp-proxy-velocity-*.jar devstack\jars\velocity\
   ```
3. Provision secrets (once):
   ```powershell
   cd devstack
   Copy-Item .env.example .env            # set RTP_NET_SECRET to a 32-byte base64 string
   Copy-Item shared\forwarding.secret.example shared\forwarding.secret
   ```
4. Ensure the JaCoCo agent jar is present in `devstack/jacoco/` (the overlay
   mounts `./jacoco` and references `jacocoagent.jar`). Stage it from the JaCoCo
   toolchain if missing.

### 2.2 Headless coverage sweep (no client needed)

Captures dispatch, heartbeat, reservation reap, kill-switch, and per-service
`/rtp test accessor` with the agent attached:

```powershell
cd devstack
.\run-acceptance.ps1 -Coverage -Scenario boot
.\run-acceptance.ps1 -Coverage -Scenario heartbeat
.\run-acceptance.ps1 -Coverage -Scenario rtptest
.\run-acceptance.ps1 -Coverage -Scenario killmidflight
.\run-acceptance.ps1 -Coverage -Scenario killswitch
```

Expected verdicts (from the README headless-evidence table):

| Scenario      | Pass condition |
|---------------|----------------|
| boot          | `docker compose ps` reports all eight services `Up` |
| heartbeat     | `redis-cli` lists 5 backend + 2 proxy keys |
| rtptest       | `[RTP test/accessor] pass=true` per backend + lobby |
| killmidflight | reservation row clears within `reservation.ttlMs + reapInterval` |
| killswitch    | Lua claim returns `KILL_SWITCH`; proxy log line asserted |

### 2.3 Real-client roundtrip (the one manual step)

The cross-server `/rtp` pipeline is gated on `PlayerJoinEvent`; a live 1.21.x
Minecraft client is required. Run once on Pro, once on Lite.

Pro path:
1. `.\run-acceptance.ps1 -Coverage -Scenario roundtrip` (boots the stack with the
   agent attached and waits for the manual step).
2. Connect a 1.21.x client to `localhost:25577` (proxy-a). You land on `lobby-a`.
3. `/server backend-a`, then `/rtp`. Confirm the teleport in-client and confirm
   the destination backend log shows the local teleport-pipeline arrival line.
4. `/server backend-b` and `/server backend-c`, run `/rtp` on each (exercises both
   scheduler families - Paper on a, Folia on b/c).
5. Before disconnecting, on each backend run `/rtp test full` to force an `.exec`
   dump (belt-and-suspenders alongside `dumponexit`).
6. Disconnect.

Lite path (DB-free plugin-message transport):
1. `.\run-acceptance.ps1 -Coverage -Lite -Scenario roundtrip`.
2. Connect to `:25577`, then `/server backend-b` and `/server backend-c` once each
   to seed availability gossip (a backend only emits heartbeat/auto-detects once a
   player connection exists on it).
3. `/rtp` and confirm the cross-server move + destination-log arrival line.
   Evidence here is visual + log-based (reservation tokens are empty by design on
   lite).

### 2.4 Merge, report, and diff

```powershell
# run-acceptance.ps1 -Coverage already calls this at the end; run manually if needed:
.\gradlew.bat :jacocoServerReport
```

- HTML/XML land in `build/reports/jacoco/server/`.
- Parse `jacocoServerReport.xml` for module + `common.tasks.teleport`,
  `common.selection.region`, root `RTP`, and `network`/dispatch packages.
- Diff the runtime report against the unit report
  (`rtp-core/build/reports/jacoco/test/jacocoTestReport.xml`) to identify classes
  covered **only** at runtime. That delta is the evidence that closes the honest
  part of criterion #1.

### 2.5 Acceptance criteria & evidence artifacts

- All §2.2 headless scenarios pass with the verdicts above.
- Both §2.3 roundtrips (Pro + Lite) succeed: in-client teleport + destination-log
  arrival line.
- `jacocoServerReport` shows the `RTP` bootstrap/pipeline/adapter classes covered
  at runtime that the unit report misses (record the class list + before/after
  numbers).
- Evidence retained: `devstack/acceptance-evidence.log`, `docker compose ps`
  output, the merged `jacocoServerReport` HTML, and the runtime-vs-unit diff.
- Cite the merged numbers in `ENTERPRISE_READINESS.md` section 2 and flip the
  runtime-attested portion of criterion #1 accordingly.

---

## 3. Positioning & trade-offs (keep honest)

- Devstack coverage is **not** deterministically per-PR gateable (needs Docker +
  one manual client hop for `roundtrip`). Treat it as **scheduled/nightly +
  release-gate evidence**, mirroring how `-PfullTests` is already handled - not a
  per-PR blocker.
- Unit JaCoCo floors stay the fast per-PR gate; the devstack report is the
  release-time attestation for the runtime-only seams.
- Do not pretend the god-class bootstrap is unit-coverable: credit it via the
  runtime report, and say so in the scorecard.

## 4. See also

- [`devstack/README.md`](../../devstack/README.md) - topology, coverage overlay,
  lite/lobby-world overlays, acceptance harness.
- [`docs/admin/proxies/CROSS_SERVER_VERIFICATION.md`](../admin/proxies/CROSS_SERVER_VERIFICATION.md)
  - operator-facing manual verification for the same scenarios.
- [`docs/dev/ENTERPRISE_READINESS.md`](./ENTERPRISE_READINESS.md) - scorecard and
  criterion #1.
- [`docs/dev/MULTI_SERVER_PLAN.md`](./MULTI_SERVER_PLAN.md) - phase status.
