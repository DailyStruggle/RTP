# ADR-094 — Quality-Engineering Gates: Tiered Logic Tests, Local Static Analysis, and Changed-Line Coverage

**Status:** Accepted
**Date:** 2026-09-11

## Context

The build was slow and its feedback loop noisy. Investigation showed Gradle's core
caching (build cache, daemon, parallel execution, VFS watching) was already enabled
correctly in `gradle.properties`; the drag came from the test configuration in the
root `build.gradle` `subprojects` block: JaCoCo instrumentation plus an HTML **and**
XML report generated after every `test` in every module, maximally verbose test
logging (`showStandardStreams = true`, `events 'started','passed',...`), and no
within-module test parallelism. On top of speed, the project wants to grow its
correctness story toward an "enterprise-grade" claim (see
[`docs/dev/ENTERPRISE_READINESS.md`](../dev/ENTERPRISE_READINESS.md)) without slowing
the everyday development loop.

Three concrete goals emerged from that work:

1. **Room to grow tests per-library, then in core**, without ballooning the default
   build. `rtp-core` alone carries ~3,687 tests, so a single flat suite cannot stay
   fast as coverage grows.
2. **Time budgets:** the full multi-module build stays well under 10 minutes, and a
   logic/coverage-focused suite (excluding demonstration / simulation / drawing
   suites) stays under 5 minutes.
3. **Diff-based coverage and SonarQube-style static analysis, for Java** — starting
   local and CI-gateable with no server wired (the maintainer has GitHub and a LAN
   server but no running SonarQube instance yet), with a clang-tidy-style,
   configurable **and** programmable analyzer that ships with known-issue detectors
   and accepts appended project-specific checks (e.g. "prefer non-locking
   execution").

Prior art in this repo: two-tier test execution via JUnit tags and opt-in JaCoCo
already existed from earlier build-speed work, and ADR-080 established the opt-in
`simulation` benchmark tier. This ADR records the decision that generalises those
into a coherent quality-gates mechanism; it does **not** restate the staged rollout
backlog, which lives in `ENTERPRISE_READINESS.md`.

## Decision

Adopt a set of **opt-in, local-first quality gates** wired into the root
`build.gradle` `subprojects` block and companion tooling, so the fast default build
is untouched while each gate is available locally and in CI.

1. **Purpose-based test tiers via JUnit 5 tags.** Untagged tests are the default
   **logic / coverage tier** — the fast, deterministic unit/logic tests that new
   tests join by default (per library, then core). The tags `slow`, `edge`,
   `simulation`, `drawing`, and `demo` mark non-logic suites and are **excluded from
   the default tier**; `-PfullTests` runs everything (release / nightly CI). Untagged
   tests always run in both tiers, so the default stays a safe
   superset-minus-non-logic. The tier toggle is an input to each `Test` task so
   cached results from the other tier are not silently reused.

2. **Opt-in local static analysis (the clang-tidy analogue), engine PMD.** Applied
   only under `-PstaticAnalysis`, so a routine `.\gradlew.bat build` neither resolves
   the PMD toolchain nor pays its scan cost. When enabled, `pmdMain`/`pmdTest` run as
   part of `check` and **fail** the build on any violation — this is the local + CI
   gate. The curated, appendable ruleset is the single source of truth at
   [`config/pmd/ruleset.xml`](../../config/pmd/ruleset.xml): a starter set of
   high-signal built-in Java detectors plus project-specific programmable rules,
   including `PreferNonLockingExecution`, which encodes the RTP scheduler /
   non-blocking contract by flagging `synchronized` blocks in favour of atomics,
   concurrent collections, or the `RTPScheduler` SPI.

3. **Server-less changed-line (diff) coverage gate.** `scripts/diff-coverage.py`
   (stdlib-only, Python 3.12+) cross-references the lines changed since a git
   baseline ref against JaCoCo's per-line coverage and fails (exit 1) when the
   covered fraction of changed, *instrumented* lines falls below a threshold
   (default baseline `origin/main`, default threshold 0.80). It runs offline, works
   on GitHub Actions and on a LAN runner, and its exit code / summary line can back a
   PR status check later. Coverage remains opt-in JaCoCo via `-Pcoverage`, so the
   gate consumes reports produced by an explicit coverage run.

4. **Local-first, server later.** SonarQube / SonarCloud and a LAN or GitHub-hosted
   remote quality gate are explicitly **deferred**. This ADR covers the local + CI
   half only; the remote status-check half is a future ADR that extends this one.

`ENTERPRISE_READINESS.md` is the living execution plan for the staged rollout
(per-module coverage targets, ratchet script, ArchUnit prohibition enforcement,
compatibility matrix, supply chain). This ADR is the durable decision it realises;
heavier sub-decisions (API-compatibility gating, supply-chain attestation) will get
their own ADRs as those phases land rather than being folded in here.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Fold into ADR-000 (Development Workflow) | ADR-000 is the meta "how a developer works" narrative; absorbing concrete tooling decisions, thresholds, and ruleset rationale would blur it. A one-line cross-reference is sufficient. |
| Fold into ADR-080 (opt-in simulation benchmark tier) | ADR-080 owns the `simulation` tag and the measurement-vs-model instrument tiers — a performance-measurement concern. Correctness gating is distinct; this ADR only references ADR-080 for the `simulation` tier it excludes from the logic tier. |
| One monolithic "enterprise readiness" ADR | The plan spans separable decisions (API compat, supply chain, prohibition enforcement) the repo would normally record apart. A single mega-ADR would be neither falsifiable nor independently supersedable. |
| SonarQube / SonarCloud server now | No server is wired today, and a hosted quality gate needs a token/instance. Deferred to a future ADR; the local PMD + diff-coverage gates deliver the same value offline first. |
| SpotBugs / Checkstyle instead of PMD | SpotBugs already runs elsewhere in the enterprise plan; PMD's XPath rule engine is what makes the analyzer *programmable* (appendable project-specific checks such as `PreferNonLockingExecution`) — the clang-tidy-style requirement. The two are complementary, not exclusive. |
| Always-on coverage / static analysis | Instrumentation and scan cost is pure overhead on the everyday build and would break the sub-budget goals. Opt-in via `-Pcoverage` / `-PstaticAnalysis` keeps the default loop fast while remaining CI-gateable. |
| A single flat test suite | Cannot stay under the 5-minute logic budget as coverage grows to thousands of tests; purpose-based tiers give room to grow without slowing the default loop. |
| Absolute per-module coverage floor as the only gate | An absolute floor punishes legacy gaps and does not target new risk. Changed-line coverage gates exactly the code a change touches; absolute floors remain a separate, staged mechanism in the readiness plan. |

## Consequences

- **Positive:**
  - The default build stays fast and quiet: no JaCoCo instrumentation, no PMD
    toolchain resolution, and no stdout streaming unless explicitly requested.
  - New tests join the logic tier by default, per library then core, so coverage can
    grow without inflating the everyday loop; heavy/non-logic suites are tagged out.
  - Static analysis is both configurable and programmable in one place
    (`config/pmd/ruleset.xml`): rules can be toggled/tuned and new XPath checks
    appended, with a worked project-specific example encoding the non-blocking
    contract.
  - Changed-line coverage is enforceable locally, on GitHub, and on a LAN runner with
    no server, and its exit code is ready to drive a PR status check.
- **Negative / Trade-offs:**
  - Opt-in gates do not protect the default build unless explicitly invoked; they
    must be wired into CI to have teeth.
  - `PreferNonLockingExecution` currently flags many existing `synchronized` blocks
    in `rtp-core`, so `-PstaticAnalysis` fails until those are triaged or suppressed
    with `@SuppressWarnings("PMD.PreferNonLockingExecution")` and a justification —
    expected for a first-pass gate.
  - The tag taxonomy only pays off as suites are tagged; until heavy non-logic suites
    are tagged `simulation`/`drawing`/`demo`/`slow`/`edge`, the default tier is larger
    than the 5-minute target. `scripts/bench-tests.py` ranks the offenders to guide
    incremental tagging.
  - The diff-coverage gate depends on a prior `-Pcoverage` run and on JaCoCo not
    discarding stale execution data (run `test` and the report task in one
    invocation, per `ENTERPRISE_READINESS.md` section 2.4).

## References

- [`build.gradle`](../../build.gradle) — root `subprojects` block: tier exclusion,
  opt-in JaCoCo (`-Pcoverage`), opt-in PMD (`-PstaticAnalysis`).
- [`config/pmd/ruleset.xml`](../../config/pmd/ruleset.xml) — curated + appendable
  starter ruleset, including the `PreferNonLockingExecution` custom XPath rule.
- [`scripts/diff-coverage.py`](../../scripts/diff-coverage.py) — server-less
  changed-line coverage gate.
- [`scripts/bench-tests.py`](../../scripts/bench-tests.py) — per-suite wall-time
  ranking used to guide tier tagging.
- [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) — pinned `pmd` and
  `jacoco` versions.
- [`docs/dev/ENTERPRISE_READINESS.md`](../dev/ENTERPRISE_READINESS.md) — the living
  execution plan (staged coverage targets, ratchet, prohibition enforcement,
  compatibility matrix, supply chain) this decision realises.
- `docs/adr/ADR-080-opt-in-simulation-benchmark-tier.md` — the `simulation` tier this
  ADR excludes from the default logic tier.
- `docs/adr/ADR-000-development-workflow.md` — meta development workflow (cross-links
  to this ADR for the quality gates).
