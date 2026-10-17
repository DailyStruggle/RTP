# Testing and quality gates

| Module | Instruction / branch measured | Enforced floor | Target (90 / 80) |
|---|---|---|---|
| `metrics-api` | 100% / 94.6% | 0.95 / 0.85 | met |
| `tags-api` | 98.2% / 90.9% | 0.95 / 0.85 | met |
| `yaml-api` | 97.0% / 88.1% | 0.92 / 0.80 | met |
| `rtp-api` | 95.7% / 81.3% | 0.90 / 0.80 | met |
| `maps-api` | 94.7% / 82.1% | 0.90 / 0.80 | met |
| `commands-api` | 93.6% / 80.3% | 0.90 / 0.80 | met |
| `anvil-api` | 90.5% / 80.5% | 0.90 / 0.80 | met |
| `rtp-core` | 81.6% / 65.8% | 0.80 / 0.64 | not met (branch coverage still climbing) |
| `rtp-proxy-common` | 87.0% / 68.8% | 0.85 / 0.67 | not met (branch coverage still climbing) |

Measured by the `-Pcoverage` run of 2026-09-15. The SonarCloud badge on the project pages is the whole-tree number, which includes the platform adapters and addons these per-module gates leave out, so it reads lower than this table.

Safety-critical packages inside `rtp-core` have higher floors on top of the module number: the teleport pipeline at 0.87 / 0.75, region selection at 0.82 / 0.68, and world-border math at 0.99 / 0.89.

| Gate | What it enforces | Where it runs |
|---|---|---|
| Test suite | over 650 committed JUnit test classes; `rtp-core` alone has roughly 4,700 test methods | `gradle.yml` on every push and pull request |
| Mutation testing (PIT) | weekly PIT run in four shards over the `rtp-core` teleport pipeline, region selection, world-border and tools packages; each shard fails below a 60% mutation score | `mutation-testing.yml` |
| Safety prohibitions | one automated test per prohibition (unsafe destinations, force-loaded chunks, claim bypass, swallowed teleport failures, main-thread chunk I/O, null-guard no-ops on the public API, hardcoded failure messages), plus ArchUnit layering and chunk-ticket rules | `gradle.yml` |
| Changed-line coverage | new and modified lines must be >= 80% covered | `gradle.yml` (`scripts/diff-coverage.py`) |
| Static analysis | PMD with a curated ruleset (`config/pmd/ruleset.xml`), including a project-specific rule that flags blocking constructs; any violation fails the build | `gradle.yml` (`-PstaticAnalysis`) |
| Fuzzing | Five Jazzer targets: the Anvil chunk and column readers, the LZ4 decoder, the NBT readers and the Redis (RESP) reply parser. Only the two Anvil read targets have real-region seeds, and the reworked weekly workflow hasn't completed a scheduled run yet. A separate behaviour fuzzer drives seeded bot players against a server and checks what stays in memory after a full GC, against a control run with no rtp plugin | `fuzzing.yml` (weekly, Jazzer); the behaviour fuzzer (`scripts/bench_fuzz.py`) is run by hand |
| Profiling and load | Stress harness with spark and JFR attached, bot clients, open-loop and paced modes. It is the source of every benchmark number above | run by hand from `helpers/StressTestRTP/` |
| Binary API compatibility | japicmp against the 3.2.1 release on Maven Central for `rtp-api`, `commands-api`, `effects-api`, `maps-api` and `metrics-api`; a binary break not listed in `config/binary-compatibility-accepted-breaks.json` fails the build. `anvil-api` and `tags-api` have no published 3.2.1 artifact, so their check skips. Removals follow a 2-minor deprecation policy | `gradle.yml` (japicmp) |
| Release provenance | SLSA build provenance on every release; GitHub releases also carry a CycloneDX SBOM and SHA-256 / SHA-512 checksums | `release.yml`, `release-bbb.yml` |
| Runtime acceptance | nightly multi-server devstack: 2 Velocity proxies, 2 lobbies and 4 backends over Redis, on Velocity + Paper + Folia + Fabric (MC 1.21.11, Java 25) and NeoForge (MC 1.21.1, Java 21). Separately, a single Paper and a single Fabric server on the latest release (26.3) must pass the accessor self-test, fill the location queue and not crash | `devstack-acceptance.yml` |
| Encoding and docs | tracked text files scanned for UTF-8 mojibake; the docs site builds from the same tree | `gradle.yml`, `docs.yml` |

**Coverage scope.** The `rtp-core` and `rtp-proxy-common` floors sit a point or two under the measured numbers and only go up. The uncovered remainder in `rtp-core` is mostly platform bootstrap and dispatch code, which the nightly devstack exercises. Spigot, NeoForge, BungeeCord and non-LTS Java have no scheduled live CI suite and are best-effort, as are Folia on 26.x and the other platforms below 1.21.11 (Paper 1.18.2 and Fabric 1.20.1 on Java 17 have only been booted by hand). Folia below 1.21.11 is not supported. The weekly Jazzer workflow runs each of the five fuzz targets as its own job for the configured duration (5 minutes by default). Only the two Anvil read targets are seeded from real region files; the LZ4 and NBT targets have no seeds yet. The reworked workflow has not completed a scheduled run yet.
