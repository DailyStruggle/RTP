# Enterprise Readiness Plan

> Created: 2026-09-10
> Scope: whole repository. Goal: make "enterprise quality and compatibility" a claim
> that an outside reviewer can verify without the maintainer's help.
> Companion docs: [COVERAGE_PLAN.md](COVERAGE_PLAN.md) (rtp-core tactics),
> [TRACEABILITY.md](TRACEABILITY.md), [REQUIREMENTS.md](REQUIREMENTS.md).

---

## 1. Premise

The repository already carries the substrate most plugins never build: normative
requirements with REQ-* IDs, an ADR corpus, the S-001..S-007 prohibition set, Spotless,
SpotBugs at MAX effort, ArchUnit, JaCoCo, locale parity tests, and a five-node
proxy devstack with an acceptance harness.

What is missing is not capability. It is **enforcement and published evidence**.
Every item below converts something that is currently true-but-unprovable into
something a stranger can check from a release page or a CI run.

Two claims are in scope and they are graded separately:

- **Quality** - the code does what the requirements say, and regressions are caught
  mechanically rather than by review attention.
- **Compatibility** - the supported platform/version matrix is measured, and the
  public API does not break without notice.

---

## 2. Measured coverage baseline (2026-09-10)

Live JaCoCo run: `./gradlew :<module>:jacocoTestReport` across the platform-neutral
graph. These are instruction / branch percentages, not estimates.

### 2.1 Platform-neutral modules (the 90%+ target set)

| Module | Instr % | Branch % | Missed instr | Verdict |
|---|---|---|---|---|
| `yaml-api` | **no tests at all** | - | - | Zero test sources (10 main classes). Pure parser - trivially testable. |
| `metrics-api` | 18.8 | 18.9 | 506 | Pure SPI, 8 classes. Should be ~100%. |
| `commands-api` | 30.9 | 23.0 | 2,362 | `common/localCommands` at 21%. Bukkit subpackage skews the number. |
| `rtp-api` | 39.9 | 40.5 | 7,176 | `configuration/enums` at **0%**, `world` at 5.9%, `group` at 0%. |
| `rtp-core` | 59.6 | 45.8 | 56,583 | The dominant mass. Detail in section 2.3. |
| `rtp-proxy-common` | 59.0 | 44.1 | 7,508 | `transport/redis` at 4.4% is nearly the whole gap. |
| `maps-api` | 67.6 | 48.6 | 1,505 | `render` 73.8%, `bukkit` binding drags it down. |
| `anvil-api` | 71.1 | 60.3 | 1,609 | Closest to target of the large modules. |
| `tags-api` | 84.5 | 74.4 | 203 | Nearly done. |

`effects-api` (21.9 / 19.7) is listed with the platform set below: two of its three
largest packages (`effectsapi/fabric`, `effectsapi/bukkit`) are platform bindings.

### 2.2 Platform-coupled modules (lower, explicit targets)

| Module | Instr % | Missed instr | Realistic ceiling |
|---|---|---|---|
| `rtp-neoforge-common` | 1.6 | 17,634 | 40-50% (logic split from NeoForge calls) |
| `rtp-plugin` | 13.7 | 17,292 | 40% (it is an assembler; most lines are wiring) |
| `rtp-fabric-common` | 11.5 | 13,751 | 40-50% |
| `rtp-bukkit-common` | 21.2 | 8,221 | 50-60% |
| `rtp-folia-common` | 8.7 | 5,855 | 50% (COVERAGE_PLAN notes the suite is broken - verify) |
| `effects-api` | 21.9 | 7,295 | 60% overall, 90% on `common/` |
| `rtp-paper-common` | 15.9 | 1,475 | 60% |
| `rtp-proxy-velocity` | 50.1 | 1,906 | 70% |
| `rtp-*-vXX_YY_R1` carriers | 0-100 (tiny) | <200 each | Exempt - thin version shims |

### 2.3 `rtp-core` worst packages by absolute missed instructions

| Package (after `...rtp.common.`) | Instr % | Missed |
|---|---|---|
| `commands/menu` | 39.3 | 10,475 |
| `selection/region` | 55.5 | 6,681 |
| `tools` | 39.9 | 5,001 |
| `network` | 45.9 | 3,820 |
| `selection/region/selectors/memory/shapes` | 84.6 | 2,975 |
| `database/options` | 33.6 | 2,936 |
| `configuration` | 71.7 | 2,306 |
| `tasks` | 60.3 | 2,210 |
| `(root package)` | 28.0 | 2,103 |
| `selection/region/selectors/memory/table` | 57.5 | 1,853 |
| `commands/test` | **0.0** | 1,814 |
| `commands` | 46.8 | 1,713 |
| `tasks/teleport` | 64.6 | 1,073 |
| `selection/.../shapes/util` | 14.7 | 791 |
| `effects` (root-level) | **0.0** | 625 |
| `menu/search` | **0.0** | 470 |
| `tasks/tick` | 21.4 | 442 |

Single worst class in the repository: **`MenuRedeemSubcommand`, 4,594 missed
instructions** (13% covered). `MenuWiringSupportInstaller` (833) and
`VisualizationsSubmenuBuilder` (548) are fully uncovered. These three alone are
roughly 10% of the `rtp-core` gap.

### 2.4 Measurement caveats found while taking this baseline

1. **Stale execution data reads low.** JaCoCo discards coverage for any class whose
   bytecode changed since the last test run (`Classes in bundle 'rtp-core' do not
   match with execution data`). A partially-recompiled tree therefore reports several
   points below the truth, and a coverage gate can fail for reasons unrelated to tests.
   Always run `test` and the verification/report task in the same invocation.
2. **The gate must not be set from a stale figure.** The `rtp-core` floors are
   deliberately set below the measured baseline for this reason; the ratchet script
   (item 5) must read a fresh run, not a cached report.
3. As of 2026-09-10 the `rtp-core` suite has **2 failing tests** in `MemoryShapeTest`
   (`testComputeAdmissibleGapContract`, `testBoundedDynamicGapBridgingAtResolution32`),
   originating from uncommitted in-progress gap-policy work. The floors cannot be
   ratcheted to their true values until that work lands green.

---

## 3. Is 90%+ actually achievable outside platform adapters?

**Yes, and the harness already exists.** Three facts support it:

1. `rtp-core` and `rtp-api` are forbidden platform imports by architectural rule
   (no `org.bukkit.*`, no `net.minecraft.*`). Every line is therefore reachable
   from a plain JVM test - there is no "needs a running server" excuse in these modules.
2. `rtp-core/src/testFixtures` already ships `RTPTestSetup`, `MockRTPServerAccessor`,
   `MockRTPWorld`, `MockRTPPlayer`, `MockRTPChunk`, `MockRTPScheduler`,
   `MockLocationGenerator`, `TrackedMockWorld`. The seam work is done; the tests
   simply have not been written against it.
3. 341 test files already exist in `rtp-core` against 323 main files. The suite is
   broad but shallow - it exercises happy paths and leaves branches uncovered
   (59.6% instruction vs 45.8% branch is the signature of that).

**Honest carve-outs** that should be excluded from the ratio rather than chased:

- Generated / boilerplate: `equals`/`hashCode`/`toString`, trivial record accessors.
- `commands/test` (1,814 missed) - a developer-only diagnostic command tree.
  Either cover it, move it behind a build flag, or exclude it explicitly with a
  documented reason. Do not leave it silently at 0%.
- `MenuRedeemSubcommand` at 4,594 instructions is not a coverage problem, it is a
  **design** problem. Decompose it before testing it; a 4.5k-instruction command
  class will resist any test suite.
- JDBC/Redis driver error paths that require a real broken server - use Testcontainers
  (section 5) rather than mocks, or exclude with justification.

**Target set, staged:**

| Stage | Modules | Instr target | Branch target |
|---|---|---|---|
| A | `yaml-api`, `metrics-api`, `tags-api`, `anvil-api` | 95% | 85% |
| B | `rtp-api`, `commands-api`, `maps-api` | 90% | 80% |
| C | `rtp-core`, `rtp-proxy-common` | 90% | 80% |
| D | platform commons | per-module table in 2.2 | - |

---

## 4. TODO - Coverage and test depth

### 4.1 Make the gate honest (do first, it is the cheapest credibility)

- [ ] 1. Raise `rtp-core` `jacocoTestCoverageVerification` from **0.10** to the real
      baseline (0.55 instruction, with a safety margin under the measured 0.596).
- [ ] 2. Add BRANCH counter rule to `rtp-core` (0.42, under the measured 0.458).
- [ ] 3. Move the JaCoCo verification block into the root `build.gradle` `subprojects`
      closure so every module carries a floor, with per-module overrides for the
      platform adapters.
- [ ] 4. Add a per-package rule for the safety-critical set (`tasks/teleport`,
      `selection/region`, `selection/worldborder`) at a higher floor than the module.
- [ ] 5. Commit a ratchet script (`scripts/ratchet-coverage.py`) that reads the JaCoCo
      XML and rewrites the floors upward after a green run. Never downward.
- [ ] 6. Publish the coverage badge / report to the docs site so the number is public.

### 4.2 Close the zero-coverage holes (fast wins, high optics)

- [ ] 7. `yaml-api` - create `src/test/java` and cover the parser: scalars, nested maps,
      lists, comments, anchors if supported, malformed input, round-trip fidelity.
      Currently **0 test files**. Target 95%.
- [ ] 8. `rtp-api` `configuration/enums` (0%, 1,455 missed) - enum value/parse/fallback
      tests. Almost pure table-driven work.
- [ ] 9. `rtp-api` `group` package (0%, 366 missed).
- [ ] 10. `rtp-api` `world` (5.9%, 1,313 missed) and `server` (11.7%).
- [ ] 11. `rtp-core` `effects` root package (0%, 625 missed).
- [ ] 12. `rtp-core` `menu/search` (0%, 470 missed).
- [ ] 13. `metrics-api` (18.8%, 506 missed) - 8 classes, should reach ~100% in one pass.
- [ ] 14. Decide and document the fate of `rtp-core` `commands/test` (0%, 1,814 missed).

### 4.3 The structural blockers

- [ ] 15. Decompose `MenuRedeemSubcommand` (4,594 missed instructions) before testing.
      Requires a D-005 proposal.
- [ ] 16. Cover `MenuWiringSupportInstaller` (833, 0%) and `VisualizationsSubmenuBuilder`
      (548, 0%).
- [ ] 17. `rtp-proxy-common` `transport/redis` (4.4%, 4,847 missed) - this single package
      is 65% of the module's gap. Use Testcontainers Redis, not mocks.
- [ ] 18. `rtp-core` `database/options` (33.6%, 2,936) - error paths, missing-key
      fallbacks, rollback.
- [ ] 19. `rtp-core` `tools` (39.9%, 5,001) - `TPS`, `PerformanceTracker` and helpers.
- [ ] 20. `rtp-core` `network` (45.9%, 3,820).
- [ ] 21. Verify whether the `rtp-folia-common` test suite is still broken (COVERAGE_PLAN
      says so); fix or delete the claim.

### 4.4 Depth, not just breadth

- [ ] 22. Add **PIT mutation testing** on `rtp-core` safety packages
      (`tasks/teleport`, `selection/region`, `selection/worldborder`). Target 60%
      mutation score. This is the only honest answer to "is your coverage real?".
- [ ] 23. Add property-based tests (jqwik) for the spiral math (ADR-001) and
      `MemoryShape` bin arithmetic - invariants, not examples.
- [ ] 24. Add a `MemoryTracker` **leak assertion**: after a full pipeline run including
      cancellation and failure paths, tickets and tasks must return to zero (S-002).
- [ ] 25. Add a concurrency stress harness (jcstress or a randomized soak) over
      `LockFreeLocationBuffer` and `RegionQueueManager`.
- [ ] 26. Wire `simulationBenchmark` (ADR-080) to a committed baseline JSON and fail
      or warn on >X% regression.

### 4.5 Enforce the prohibitions mechanically

- [ ] 27. Extend the ArchUnit ruleset to gate every statically expressible prohibition:
      no `org.bukkit.*` / `net.minecraft.*` in `rtp-core`/`rtp-api`;
      no `new Thread()` / `Executors.new*` outside documented carve-outs;
      no `printStackTrace()`; no `System.out`/`Bukkit.getLogger()`;
      no synchronous `getChunkAt` on main-thread paths (S-005).
- [ ] 28. Add a row in `TRACEABILITY.md` mapping each S-00x to its enforcing ArchUnit rule.
- [ ] 29. Raise SpotBugs to `MEDIUM` confidence on new code; require written
      justifications in `spotbugs-exclude.xml` (auditors read suppressions).

---

## 5. TODO - Compatibility (make it measured, not asserted)

- [ ] 30. Publish a **support matrix**: platform family (Spigot / Paper / Folia / Fabric
      / NeoForge / Velocity / BungeeCord) x MC version x Java version, each cell marked
      **tested / best-effort / unsupported**. No cell may say "should work".
- [ ] 31. Add `japicmp` or `revapi` against the previous release for `rtp-api`,
      `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`.
      Fail the build on unannounced binary-incompatible change.
- [ ] 32. Write a **deprecation policy**: minimum N minor versions of notice,
      `@Deprecated(forRemoval = true, since = ...)` everywhere, changelog removal section.
- [ ] 33. Wire a nightly CI job that boots the `devstack` containers and runs a smoke
      subset of `run-acceptance.sh`, uploading logs as release evidence.
- [ ] 34. Attach the acceptance evidence log to each GitHub release.
- [ ] 35. Add config-schema versioning with tested automatic migration; make
      `wiki/Migrating.md` per-major and backed by a test.
- [ ] 36. Add Testcontainers-based integration tests for the Redis and SQL network
      bindings (covers item 17 and gives the proxy claim real evidence).

---

## 6. TODO - Supply chain

- [ ] 37. CycloneDX SBOM generated per release, attached to the GitHub release.
- [ ] 38. Dependabot or Renovate enabled; OWASP dependency-check in CI.
      Shipped drivers: HikariCP, Jedis, H2, PostgreSQL, SQLite - all CVE surfaces.
- [ ] 39. Sign the shaded plugin jar and publish checksums alongside it
      (Maven Central signing already exists; extend to the deliverable).
- [ ] 40. Reproducible builds: `preserveFileTimestamps = false`,
      `reproducibleFileOrder = true`, plus Gradle `dependencyLocking`.
- [ ] 41. Pin all GitHub Actions by commit SHA rather than tag.
- [ ] 42. Enable GitHub build provenance / SLSA attestation.

---

## 7. TODO - Policy and presentation

- [ ] 43. Explicit SemVer contract: what is public API vs internal.
- [ ] 44. Make `SUPPORT.md` specific: which versions get fixes, for how long,
      expected response window. "Best-effort, typically within N days" beats silence.
- [ ] 45. Make `SECURITY.md` specific: private disclosure channel, triage timeline,
      advisory history.
- [ ] 46. One-page licensing clarity across `LICENSE` / `LICENSE-MIT` and the
      Pro / Lite split (ADR-024). Ambiguity blocks adoption more than bugs do.
- [ ] 47. Add severity + status columns to `POTENTIAL_BUGS.md` so it reads as a
      managed backlog rather than a pile of open defects.
- [ ] 48. Repository root hygiene: ~25 loose chart `.png` files, `gitstat.txt`,
      `checkout_list.txt`, `test_2d_map.bmp`, `default_2d_map.bmp`, a generated
      `site/` directory, and a folder named `Python Test Scripts` (with a space).
      Move charts to `docs/assets/`, gitignore `site/`, rename the spaced folder.
- [ ] 49. Add a CI grep for the usual UTF-8-read-as-CP1252 mojibake markers (the
      three-byte em-dash and non-breaking-space corruptions) across tracked text
      files. There is live mojibake in shipped source comments today, e.g.
      `rtp-core/build.gradle` lines 33 and 144, where an em dash was corrupted.
- [ ] 50. Sweep the 61 stray untracked `.bak` files out of the working tree
      (none are committed - local clutter only, but they leak into IDE search).

---

## 8. Sequencing

**Phase 1 - stop the bleeding (days).** Items 1-6, 37-41, 49, 50.
Honest gates and supply chain. After this, nothing in the repo actively contradicts
the claim.

**Phase 2 - close the zeroes (1-2 weeks).** Items 7-14, 27-29, 31.
Every platform-neutral module has tests and the prohibitions are machine-enforced.

**Phase 3 - reach the target ratios (4-6 weeks).** Items 15-21, 36.
`rtp-core` and `rtp-proxy-common` to 90/80.

**Phase 4 - prove depth (ongoing).** Items 22-26, 30, 33-35, 42-48.
Mutation score, soak tests, measured compatibility matrix, published policy.

---

## 9. Definition of done

The claim is defensible when all of the following are true and **externally visible**:

1. Every platform-neutral module is at or above 90% instruction / 80% branch, enforced
   by a build gate that cannot be lowered without a commit.
2. Mutation score on the safety packages is at or above 60%.
3. Every S-00x prohibition has an automated rule, cited in `TRACEABILITY.md`.
4. The support matrix distinguishes tested from best-effort, and the tested cells are
   re-verified by CI on a schedule.
5. Each release ships an SBOM, signed artifacts, checksums, and an acceptance log.
6. API compatibility is gated automatically, and the deprecation policy is published.
7. Zero known CVEs in shipped dependencies, checked automatically.

Until then, prefer the provable phrasing over the adjective: state the tested matrix,
the coverage number, the signing and SBOM status. That language ages into the claim
on its own, and it matches the evidence-over-adjectives voice the project already uses.
