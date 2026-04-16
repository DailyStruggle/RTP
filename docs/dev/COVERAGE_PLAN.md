# rtp-core Coverage Plan: 23% → 80%

> Generated: 2026-04-16
> Scope: `rtp-core` only (rtp-folia-common has broken tests; rtp-spigot-common is a server impl layer)

## Current Baseline

- **23% instructions / 14% branches** across ~38,800 instructions
- ~22,000 more instructions need coverage to reach 80%
- JaCoCo is already configured in `build.gradle` for all subprojects
- CI uploads HTML reports as `jacoco-coverage` artifact but **no minimum threshold is enforced**

---

## Prioritized Attack Plan

Key principle: **pure-logic first (no mocks), then lightweight Mockito mocks, then skip server-coupled code**.

---

### Tier 1 — Pure Logic, Zero Mocking Required

No server dependencies. Plain JUnit5 tests only.

| Class | ~Instructions | Current |
|---|---|---|
| `ParseString` | 16 | 0% |
| `TrackedObject` | 15 | 0% |
| `SupportInfo` | 10 | 0% |
| `SupportLogger` | 5 | 0% |
| `RTPLocation` (record) | small | 0% |
| `RegionSettings` (record) | small | 0% |
| `LockFreeLocationBuffer` | medium | low |
| `Factory` / `FactoryValue` | ~202 | 39% |
| `TPS` | ~92 | 8% |
| `RTPTaskPipe` / `TimeBoundTaskPipe` | ~461 | 8% |
| `PlaceholderProvider` | ~178 | 0% |

**Prompts for Junie:**
- "Write comprehensive JUnit5 unit tests for `ParseString` covering: normal placeholder extraction, empty input, no matching placeholders, nested/adjacent delimiters, and null-safety."
- "Write JUnit5 tests for `TrackedObject` covering all public methods, including edge cases for tracking state transitions."
- "Write JUnit5 tests for `TPS` covering tick recording, average calculation, and boundary conditions (empty, single tick, overflow)."
- "Write JUnit5 tests for `LockFreeLocationBuffer` covering concurrent put/take, capacity limits, and empty-buffer behavior."
- "Extend existing `Factory`/`FactoryValue` tests to cover all uncovered branches — check the JaCoCo HTML report at `rtp-core/build/reports/jacoco/test/html/io.github.dailystruggle.rtp.common.factory/index.html` for which lines are red."
- "Write JUnit5+Mockito tests for `PlaceholderProvider` covering: `fillPlaceholders` with a known UUID and mocked `RTPServer`/`RTPCommandSender`, `fillNumericPlaceholders` with numeric and non-numeric strings, and the static `placeholders` map registration. Note: `PlaceholderProvider` has NO PlaceholderAPI dependency — it lives entirely in `rtp-core` and only uses `rtp-api` interfaces."

---

### Tier 2 — Configuration & MultiConfigParser ✅ COMPLETE

~506 + ~272 instructions, previously 45%/21%. Now 48 tests, 0 failures.

Covered:
- `ConfigParser`: missing key lookups, type coercion, null YAML values, round-trip save/reload, `getMap()` all branches, `clone()`, version mismatch → `renameFiles()`, `set()` invalid key
- `MultiConfigParser`: state isolation, override precedence, reload-after-mutation, `addAll()`, `getParser()` DEFAULT fallback, `addParser`/`removeParser`, `getMainDirectory`, `getClassLoader`
- `Configs`: singleton init, double-init guard, `putParser` null/wrong-type guards, `getParser` all paths, `getWorldParser`, `getWorldParserValue`, `reload()`, `reloadConfigs()` standard parsers, `onReload` callback

---

### Tier 3 — Shape Selectors

~1,335 instructions, currently 32%. Pure math — no server deps.

Gaps:
- Edge cases: min/max radius, zero radius, negative inputs
- All `select()` distribution paths
- `MemoryShape` cache hit/miss paths

**Prompts for Junie:**
- "Extend `MemoryShapeTest` and `DeterministicShapeTest` with parameterized `@MethodSource` tests covering: zero radius, max-int radius, negative min radius, and uniform distribution verification across 10,000 samples for `Circle`, `Square`, `Rectangle`, `Circle_Normal`, and `Square_Normal`."
- "Write tests for `MemoryShape` cache behavior: verify cache hit returns same location, cache miss triggers new selection, and cache invalidation on config change."

---

### Tier 4 — Tasks with Mocks

~461 (tasks) + ~381 (tasks/teleport) instructions, currently 8%/1%.

Mock `RTPServer` and `RTPScheduler` (both are interfaces in `rtp-api`). The `RTPTestSetup` fixture likely already wires this.

`TeleportPipelineTask` **can and should** be tested against server mocks — it does not require a live world/chunk, only mocked `RTPServer`/`RTPWorld`/`RTPPlayer` interfaces.

**Prompts for Junie:**
- "Write JUnit5+Mockito tests for `RTPTaskPipe` covering: enqueue, execute-in-order, cancel-mid-queue, and empty-queue behavior. Mock `RTPServer` via `RTPTestSetup`."
- "Write JUnit5+Mockito tests for `TimeBoundTaskPipe` covering: tasks completing within time budget, tasks exceeding budget being deferred, and empty pipe behavior."
- "Write JUnit5+Mockito tests for `RTPTeleportCancel` covering: cancel before teleport starts, cancel after teleport completes (no-op), and cancel with null player."
- "Write JUnit5+Mockito tests for `TeleportPipelineTask` covering the full state machine: PENDING → RUNNING → COMPLETE and PENDING → CANCELLED. Mock `RTPServer`, `RTPWorld`, `RTPPlayer`, and `RTPScheduler` — no live server or chunk loading required."

---

### Tier 5 — Database Layer

~886 instructions, currently 5%.

`YamlFileDatabase` is file-based (no external server). `H2DatabaseAccessor` needs H2 added as a test dependency. Database servers (H2, SQLite) should be **initialized inline** within integration tests — no external process required.

**Prompts for Junie:**
- "Add `testImplementation 'com.h2database:h2:2.2.224'` to `rtp-core`'s dependencies and write JUnit5 integration tests for `H2DatabaseAccessor` that spin up an in-memory H2 instance inline (no external server) covering: create table, insert, select, update, delete, and connection lifecycle."
- "Write JUnit5 tests for `YamlFileDatabase` using a temp directory (`@TempDir`) covering: write player data, read it back, overwrite, delete, and missing-key fallback."
- "Write a shared abstract base test class `AbstractDatabaseAccessorTest` that `H2DatabaseAccessorTest` and `YamlFileDatabaseTest` both extend, covering the common `DatabaseAccessor` contract."

---

### Tier 6 — Commands (argument parsing only)

~320 (commands) + ~272 (commands/config) instructions, currently 1%/21%.

Only test argument-parsing and validation paths — skip execution side (too server-coupled).

**Prompts for Junie:**
- "Write JUnit5+Mockito tests for `ConfigCmd` and `SubConfigCmd` covering: valid argument parsing, invalid/missing arguments returning usage, and permission-check short-circuit. Mock `CommandSender` with Mockito."
- "Write JUnit5+Mockito tests for `ReloadCmd`/`SubReloadCmd` covering argument validation paths only — do not test actual reload execution."
- "Write JUnit5 tests for all parameter classes (`BiomeParameter`, `BooleanParameter`, `FloatParameter`, `IntegerParameter`, `RegionParameter`) covering: valid parse, invalid string input, and boundary values."

---

## What to Skip (for now)

| Class | Reason |
|---|---|
| `ChunkyChecker` / `ChunkyRTPShape` | Requires Chunky plugin at runtime |
| `RedisManager` | Requires live Redis server |
| `FillCmd` family | Deeply server-coupled |
| MySQL / PostgreSQL accessors | Require running DB server — use inline H2/SQLite for integration tests instead |

---

## Rough Coverage Projection

| Tier | Est. Instructions Gained | Cumulative % |
|---|---|---|
| Baseline | — | 23% |
| Tier 1 (pure logic) | ~2,000 | ~28% |
| Tier 2 (config) | ~3,000 | ~36% |
| Tier 3 (shapes) | ~5,000 | ~49% |
| Tier 4 (tasks w/ mocks) | ~6,000 | ~64% |
| Tier 5 (database) | ~5,000 | ~77% |
| Tier 6 (commands parsing) | ~2,000 | **~82%** |

---

## Enforcing the Gate in CI

Add to `build.gradle` `subprojects` block once coverage is sufficient. Start at 30% and ratchet up:

```groovy
jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'INSTRUCTION'
                minimum = 0.80  // raise gradually: 0.30 → 0.50 → 0.65 → 0.80
            }
        }
    }
}
check.dependsOn jacocoTestCoverageVerification
```

Also enable XML reports for Codecov integration (optional):

```groovy
jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}
```

Then add to `.github/workflows/gradle.yml`:

```yaml
- name: Upload coverage to Codecov
  uses: codecov/codecov-action@v4
  with:
    files: '**/build/reports/jacoco/test/jacocoTestReport.xml'
```
