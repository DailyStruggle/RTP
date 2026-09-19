# rtp-core:test - Warning Triage

Source: `.\gradlew.bat :rtp-core:test` (BUILD SUCCESSFUL, 2026-09-11) plus parse of
`rtp-core/build/test-results/test/*.xml` (287 test result files, ~455 warning/exception lines).

Scope: this is a triage/TODO list only. It classifies every warning family observed in the
test log and separates genuinely actionable items from intentional negative-path test noise.

## Actionable (can / should be resolved)

| # | Warning | Where / count | Assessment | Suggested action |
|---|---------|---------------|------------|------------------|
| 1 | `WARNING: ...\rtp-core\target\test-configs-reload\default.yml` + `java.nio.file.NoSuchFileException` | `MockRTPServerAccessor.log`, seen from `PredictorAgreementTest` and others (~31 + ~31) | Test writes to the legacy `target/` tree and then reads a `default.yml` that was never created, so the mock logs a file-not-found for every reload. Pure test-harness hygiene, not a product bug. | Have the reload test create the `default.yml` fixture before reload, or point the temp config dir at a JUnit `@TempDir` that is seeded first. Remove reliance on `rtp-core/target/`. |
| 2 | `Note: Some input files use unchecked or unsafe operations` / `Recompile with -Xlint:unchecked for details` | `:rtp-core:compileJava` | Real compiler note about raw-type / unchecked generic usage in main sources. Low risk but resolvable. | Build with `-Xlint:unchecked`, identify the offending call sites, and parameterize the raw types (or add a scoped `@SuppressWarnings("unchecked")` where genuinely unavoidable). |
| 3 | `WARNING: A Java agent has been loaded dynamically (byte-buddy...)` + `Dynamic loading of agents will be disallowed by default in a future release` + `-XX:+EnableDynamicAgentLoading` hint | JVM, ~10-12x (Mockito/ByteBuddy inline mock maker) | Future-JDK compatibility warning from Mockito's dynamic agent attach. | Add `-XX:+EnableDynamicAgentLoading` to the test JVM args (or configure the Mockito agent via `-javaagent`) in `rtp-core/build.gradle` `test { jvmArgs ... }`. |
| 4 | `OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended` | Test JVM, ~12x | CDS/AppCDS warning caused by appending to the bootstrap classpath in the test launcher. Cosmetic. | Disable CDS for the test JVM (`-Xshare:off`) or stop appending to the bootstrap classpath, to silence the noise. |
| 5 | `Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.` | Gradle build | Build-script deprecation; will break on Gradle 10 upgrade. | Re-run with `--warning-mode all`, locate the deprecated API in our build scripts/plugins, and migrate. |
| 6 | `WARNING: [RTP] 'performance.yml' has moved to 'advanced/performance.yml' (ADR-071)` and `the 'regions/' folder has moved to 'definitions/regions/' (ADR-076)` | Migration path, 2x each | Correct product behavior, but noisy in tests that start from legacy layout. | Optional: update those tests to start from the migrated layout so the one-time migration warning does not fire, keeping the log clean. Not a code bug. |

## Intentional / expected (no action - deliberate negative-path coverage)

These warnings and exceptions are produced on purpose by tests exercising failure handling.
They should NOT be "fixed" - suppressing them would weaken the tests.

| Warning / exception | Notes |
|---------------------|-------|
| `java.lang.RuntimeException: boom` / `WARNING: ...threw ...: boom` | Injected fault (permission probes, global region verifier, personal-queue fill). |
| `WARNING: intentional` / `java.lang.RuntimeException: intentional` | Explicit intentional-failure tests. |
| `WARNING: Failed to connect to database` / `java.sql.SQLException: no conn` / `no connection` | DB error-path tests (`loadCachedLocations_sqlException_returnsEmptyList`, flush/close error paths). |
| `java.sql.SQLException: batch failed` / `delete failed` / `Failed to execute delete query` / `Failed to flush teleport data batch` / `Database connection error during flush` / `Failed to close database connection` | DatabaseAccessor negative-path coverage. |
| `NullPointerException: Cannot invoke "...DatabaseAccessor.cacheValue(...)"` / `AtomicInteger.incrementAndGet()` | Null/uninitialized guard tests. |
| `SEVERE: [RTP ERROR REPORT]` | Error-report formatting tests. |
| `WARNING: [RTP] slow teleport: <n>ms exceeded slowPipelineThresholdMs` | Slow-pipeline threshold logging test (threshold set artificially low). |
| `WARNING: [P?] bad parameter - region=foo` / `[RTP] openPersonalQueue: failed to schedule personal fill` | Bad-parameter and scheduling-failure coverage (REQ-RTP-S-004 auditing). |
| `WARNING: &c[RTP config/viewraw] file not found: ...performance...` | Viewraw missing-file behavior test. |

## Resolution status (2026-09-11)

- [x] 1. Silence Mockito/ByteBuddy dynamic-agent warning (item 3) - added `-XX:+EnableDynamicAgentLoading` to the shared test `jvmArgs` in root `build.gradle`.
- [x] 2. Drop CDS sharing warnings (item 4) - added `-Xshare:off` to the same shared test `jvmArgs`.
- [x] 3. Stop using `rtp-core/target/` for the reload test (item 1) - `ConfigsReloadTest` now uses a JUnit `@TempDir` instead of `new File("target/test-configs-reload")`.
- [x] 5. Migrate the Gradle-10-deprecated build feature (item 5) - `spotless { lineEndings = 'UNIX' }` now uses assignment syntax (was space-assignment, deprecated for Gradle 10).
- [x] 4. `-Xlint:unchecked` note in main sources (item 2) - RESOLVED. A `-Xlint:unchecked` compile of `rtp-core` main surfaced 74 unchecked-operation warnings across 19 files. All were classified as genuinely unavoidable: heterogeneous factory/parser maps keyed by enum class (`RTP.factoryMap`, `Configs.multiConfigParserMap` / `configParserMap`, `MultiConfigParser.configParserFactory`), untyped YAML map values narrowed after `instanceof Map`, and type-erased `RTPChunk<T>` member calls in the vertical adjustors (whose base `VerticalAdjustor` also uses raw `RTPChunk`, so parameterizing would ripple across base + all overrides + callers). Each site now carries a narrowly-scoped `@SuppressWarnings("unchecked")` with a one-line rationale. `-Xlint:unchecked` is pinned on `rtp-core`'s `compileJava` (only) as a regression guard; `.\gradlew.bat :rtp-core:compileJava --rerun-tasks` now emits 0 `[unchecked]` warnings for the main tree.
- [ ] 6. (Optional) Start migration-path tests from the new layout to suppress ADR-071 / ADR-076 move notices (item 6) - LEFT AS-IS. These are correct one-time product migration notices; the tests deliberately exercise the legacy-layout upgrade path, so silencing them would reduce coverage value.
