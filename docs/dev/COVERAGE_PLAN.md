# rtp-core Coverage Plan: 49% → 80%

> Updated: 2026-04-16 (rescanned from live JaCoCo run)
> Scope: `rtp-core` only (rtp-folia-common has broken tests; rtp-spigot-common is a server impl layer)

## Current Baseline

- **49% instructions / 35% branches** across 38,863 instructions (19,540 covered)
- ~12,050 more instructions need coverage to reach 80% (target: 31,090 covered)
- JaCoCo is already configured in `build.gradle` for all subprojects
- CI uploads HTML reports as `jacoco-coverage` artifact but **no minimum threshold is enforced**
- 57 test files already exist in `rtp-core/src/test`

---

## Per-Package Coverage Snapshot (2026-04-16)

| Package (suffix after `…rtp.common.`) | Instr % | Branch % | Missed Lines | Status |
|---|---|---|---|---|
| `tools` | 16% | 6% | 630 | 🔴 Critical gap |
| `commands` | 1% | 0% | 312 | 🔴 Critical gap |
| `selection` | 7% | 0% | 82 | 🔴 Critical gap |
| `commands.config.list` | 25% | 0% | 39 | 🔴 Critical gap |
| `database.options` | 26% | 21% | 671 | 🔴 Critical gap |
| `tasks.teleport` | 22% | 16% | 285 | 🔴 Critical gap |
| `(root)` | 34% | 16% | 149 | 🟠 Large gap |
| `tasks.tick` | 34% | 23% | 59 | 🟠 Large gap |
| `selection.region.selectors.verticalAdjustors` | 40% | 16% | 20 | 🟠 Large gap |
| `selection.region` | 44% | 31% | 656 | 🟠 Large gap |
| `selection.region.selectors.shapes` | 47% | 12% | 31 | 🟠 Large gap |
| `database` | 50% | 35% | 162 | 🟡 Moderate gap |
| `commands.config` | 32% | 23% | 181 | 🟡 Moderate gap |
| `commands.parameters` | 37% | 0% | 59 | 🟡 Moderate gap |
| `configuration` | 63% | 53% | 177 | 🟡 Moderate gap |
| `tasks` | 64% | 46% | 163 | 🟡 Moderate gap |
| `network` | 65% | 33% | 9 | 🟡 Moderate gap |
| `commands.reload` | 60% | 42% | 34 | 🟡 Moderate gap |
| `commands.info` | 71% | 71% | 24 | 🟢 Near target |
| `selection.region.selectors.memory.shapes` | 74% | 59% | 352 | 🟢 Near target |
| `factory` | 80% | 72% | 37 | ✅ At target |
| `commands.fill` | 83% | 60% | 27 | ✅ At target |
| `selection.region.selectors.verticalAdjustors.linear` | 84% | 60% | 15 | ✅ At target |
| `selection.region.selectors.verticalAdjustors.jump` | 95% | 75% | 1 | ✅ Done |
| `playerData` | 96% | 100% | 2 | ✅ Done |
| `selection.worldborder` | 97% | 70% | 0 | ✅ Done |
| `commands.help` | 98% | 81% | 0 | ✅ Done |
| `configuration.enums` | 100% | n/a | 0 | ✅ Done |
| `selection.region.selectors.memory.shapes.enums` | 100% | n/a | 0 | ✅ Done |
| `selection.region.selectors.memory` | 100% | n/a | 0 | ✅ Done |
| `selection.region.selectors.shapes.enums` | 0% | n/a | 18 | 🔴 Critical gap (small) |

---

## Remaining Attack Plan

Key principle: **pure-logic first (no mocks), then lightweight Mockito mocks, then skip server-coupled code**.

---

### Tier A — `tools` Package (16% → 80+%) 🔴 HIGHEST PRIORITY

**~630 missed lines** — single biggest gap in the codebase.

Classes inside `rtp.common.tools`:
- `TPS` (~92 instructions, 8% covered) — tick recording and average calculation
- `PerformanceTracker` — large class, nearly untouched
- Other utility helpers

**Prompts for Junie:**
- "Write JUnit5 tests for `TPS` covering tick recording, average calculation, boundary conditions (empty, single tick, many ticks), and the `get()` return format."
- "Write JUnit5 tests for `PerformanceTracker` covering: start/stop tracking, multiple concurrent keys, elapsed time accuracy (within tolerance), and reset behavior."
- "Scan `io.github.dailystruggle.rtp.common.tools` for any remaining uncovered utility classes and write JUnit5 tests for each — check the JaCoCo HTML at `rtp-core/build/reports/jacoco/test/html/io.github.dailystruggle.rtp.common.tools/index.html`."

---

### Tier B — `database.options` Package (26% → 80+%) 🔴 HIGH PRIORITY

**~671 missed lines**. `YamlFileDatabaseTest` and `H2DatabaseAccessorTest` exist but coverage is only 26%, indicating many branches and methods are not exercised.

**Prompts for Junie:**
- "Open the JaCoCo HTML report at `rtp-core/build/reports/jacoco/test/html/io.github.dailystruggle.rtp.common.database.options/index.html` and identify all red/yellow lines. Extend `YamlFileDatabaseTest` and `H2DatabaseAccessorTest` to cover those branches: error paths, missing-key fallbacks, concurrent access, and transaction rollback."
- "Write a stress test for `YamlFileDatabase` using `@TempDir` that inserts, reads, overwrites, and deletes 100 entries in rapid succession to cover iteration and cleanup paths."
- "Extend `AbstractDatabaseAccessorTest` to add tests for the `getAll()`, `remove()`, and `contains()` contract methods if not already covered."

---

### Tier C — `selection.region` Package (44% → 80+%) 🟠 HIGH PRIORITY

**~656 missed lines** — second largest gap. `RegionPipelineTest` and `RegionQueueManagerTest` exist but cover only 44%.

**Prompts for Junie:**
- "Open the JaCoCo report at `rtp-core/build/reports/jacoco/test/html/io.github.dailystruggle.rtp.common.selection.region/index.html`. Identify the red classes and extend `RegionPipelineTest` and `RegionQueueManagerTest` to cover uncovered branches: empty region, full queue, cancelled pipeline, and concurrent enqueue/dequeue."
- "Write JUnit5+Mockito tests for `RegionConfigLoader` (if not already fully covered) covering: valid config load, missing keys with defaults, malformed values, and reload-after-mutation."
- "Write JUnit5+Mockito tests for `DynamicWorldConfig` covering: world registration, deregistration, config lookup for unknown world, and concurrent world registration."

---

### Tier D — `tasks.teleport` Package (22% → 80+%) 🔴 HIGH PRIORITY

**~285 missed lines**. `TeleportPipelineTaskPhaseTest` and `RTPTeleportCancelTest` exist but cover only 22%.

**Prompts for Junie:**
- "Open the JaCoCo report at `rtp-core/build/reports/jacoco/test/html/io.github.dailystruggle.rtp.common.tasks.teleport/index.html`. Identify uncovered methods and extend `TeleportPipelineTaskPhaseTest` to exercise all state transitions: PENDING → RUNNING → COMPLETE, PENDING → CANCELLED, and RUNNING → FAILED. Mock `RTPServer`, `RTPWorld`, `RTPPlayer`, `RTPScheduler` via `RTPTestSetup`."
- "Write JUnit5+Mockito tests for `DoTeleport` (or equivalent executor class) covering: successful teleport, teleport to invalid location (null world), teleport with cooldown active, and teleport with permission denied."

---

### Tier E — `commands` + `commands.config` + `commands.config.list` (1% / 32% / 25%) 🔴

**~312 + ~181 + ~39 = ~532 missed lines** combined.

**Prompts for Junie:**
- "Open the JaCoCo report for `rtp.common.commands`. The main command dispatcher is at ~1% — write JUnit5+Mockito tests for the top-level `RTPCommand` dispatch logic: valid subcommand routing, unknown subcommand → usage message, and permission-check short-circuit. Mock `CommandSender`."
- "Extend `ConfigCmdTest` and `SubConfigCmdTest` to cover branches currently marked red in the JaCoCo report — especially error paths, missing-argument fallbacks, and the config-value update path."
- "Write JUnit5+Mockito tests for `ListConfigCmd`/`SubListConfigCmd` covering: empty config list, non-empty list formatting, and invalid argument handling."

---

### Tier F — `selection` root + `commands.parameters` + `tasks.tick` (7% / 37% / 34%) 🟠

**~82 + ~59 + ~59 = ~200 missed lines** combined.

**Prompts for Junie:**
- "Write JUnit5 tests for the `io.github.dailystruggle.rtp.common.selection` package root — likely `SelectionAPI` or similar. Cover: null-world guard, valid selection trigger, and selection result validation."
- "Extend `ParameterParsingTest` and `ListParameterTest` to cover the 0%-branch packages: `BiomeParameter`, `BooleanParameter`, `FloatParameter`, `IntegerParameter`, `RegionParameter` — test boundary values, invalid strings, and null inputs."
- "Extend tick-related tests (`SLATest` or equivalent) to cover `rtp.common.tasks.tick` branches: tick scheduling, overdue-tick detection, and tick cancellation."

---

### Tier G — `configuration` + `tasks` + `database` + `commands.reload` (63% / 64% / 50% / 60%) 🟡

These packages have tests but still have meaningful uncovered branches worth closing.

**Prompts for Junie:**
- "Open JaCoCo reports for `configuration` (63%), `tasks` (64%), `database` (50%), and `commands.reload` (60%). For each, identify the red/yellow lines and add targeted tests for the specific uncovered branches — avoid rewriting existing tests."

---

### Tier H — `selection.region.selectors.memory.shapes` (74% → 90%+) 🟢 NEAR TARGET

**~352 missed lines** — tests exist but branch coverage is only 59%.

**Prompts for Junie:**
- "Extend `MemoryShapeTest`, `MemoryShapeCacheTest`, and `DeterministicShapeTest` with parameterized `@MethodSource` tests covering: zero radius, max-int radius, negative min radius, cache hit/miss/invalidation, and uniform distribution verification across 10,000 samples."

---

## What to Skip (for now)

| Class | Reason |
|---|---|
| `ChunkyChecker` / `ChunkyRTPShape` | Requires Chunky plugin at runtime |
| `RedisManager` | Requires live Redis server (`RedisManagerTest` should be `@Disabled`) |
| `FillCmd` family | Deeply server-coupled (currently 83% — don't regress) |
| MySQL / PostgreSQL accessors | Require running DB server — use inline H2/SQLite instead |

---

## Revised Coverage Projection

| Tier | Package(s) | Est. Lines Gained | Cumulative % |
|---|---|---|---|
| Baseline | — | — | **49%** |
| Tier A | `tools` | ~530 lines | ~54% |
| Tier B | `database.options` | ~500 lines | ~59% |
| Tier C | `selection.region` | ~450 lines | ~63% |
| Tier D | `tasks.teleport` | ~220 lines | ~66% |
| Tier E | `commands` + config variants | ~400 lines | ~70% |
| Tier F | `selection` + `parameters` + `tick` | ~160 lines | ~72% |
| Tier G | Mid-coverage packages | ~300 lines | ~75% |
| Tier H | `memory.shapes` branch gaps | ~200 lines | **~78–80%** |

> Note: line counts are a proxy. Actual instruction gains may vary ±5%. Completing Tiers A–E alone is likely sufficient to cross 80% given instruction density in `tools` and `database.options`.

---

## Enforcing the Gate in CI

Add to `build.gradle` `subprojects` block once coverage is sufficient. Ratchet up gradually:

```groovy
jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = 'INSTRUCTION'
                minimum = 0.80  // raise gradually: 0.50 → 0.65 → 0.75 → 0.80
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
