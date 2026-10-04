# Testing Guide

Practical router and operational guide for running tests across RTP. Explains which test tier to run depending on what components or subsystems were changed.

---

## 1. Quick Decision Matrix (What to test when)

| Change Type | Primary Test Command | Secondary / Acceptance Verification |
|---|---|---|
| **Core logic / algorithm math** (`rtp-core`, shapes, spiral, caches) | `.\gradlew.bat :rtp-core:test --tests "<ClassOrPattern>"` | `.\gradlew.bat :rtp-core:test` + JaCoCo coverage verification (`-Pcoverage`) |
| **Command tree / arguments** (`commands-api`, `rtp-core/commands`) | `.\gradlew.bat :commands-api:test` | `.\gradlew.bat :rtp-core:test --tests "*Command*"` |
| **Configuration / Locales** (`config.yml`, `messages.yml`, `lang/`) | `.\gradlew.bat :rtp-plugin:test --tests "*LocaleParityTest*"` | `.\gradlew.bat :rtp-core:test --tests "*Config*"` |
| **Platform Adapters** (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`) | `.\gradlew.bat :platforms:<platform>:test` (or module test) | Platform compile tasks + ArchUnit architecture tests (`:rtp-core:test --tests "*ArchitectureTest*"`) |
| **Database / SQL Accessors** (`rtp-core/.../database/options`) | `.\gradlew.bat :rtp-core:test --tests "*DatabaseAccessorTest*"` | Docker-gated container tests (`RealMySQLDatabaseAccessorTest`, `RealPostgreSQLDatabaseAccessorTest`) |
| **Multi-Server / Network Mode** (`rtp-proxy-*`, `rtp-core/.../network`) | `.\gradlew.bat :rtp-proxy:test :rtp-core:test --tests "*Network*"` | Headless Devstack acceptance (`devstack/run-acceptance.ps1 -Scenario all`) |
| **Safety packages / Critical predicates** | `.\gradlew.bat :rtp-core:test --tests "*Safety*"` | PIT mutation testing (`.\gradlew.bat :rtp-core:pitest -Pmutation`) |
| **Cross-Module API / Core SPI Changes** (`rtp-api`, `commands-api`, `effects-api`) | Scoped affected module builds (e.g. `.\gradlew.bat :rtp-api:build :rtp-core:build`) | Full multi-module build (`.\gradlew.bat build`) before merge |
| **Documentation / Markdown** (`docs/`, `*.md`) | Link & formatting review | Exempt from build and test runs |
| **Pre-Release / Enterprise Audit Baseline** | `.\gradlew.bat build` | Full multi-module build + devstack acceptance (`run-acceptance.ps1 -Scenario all`) |

---

## 2. Testing Tiers & How to Run Them

### Tier 1: Unit & Targeted JVM Tests (Fast, Per-Task)
Always run targeted unit tests during implementation to verify bugfixes or new behaviors locally before expanding scope:

```powershell
# Single test class
.\gradlew.bat :rtp-core:test --tests "io.github.dailystruggle.rtp.common.selection.region.RegionQueueManagerTest"

# Pattern match across modules
.\gradlew.bat :rtp-core:test --tests "*Spiral*"
.\gradlew.bat :rtp-plugin:test --tests "*LocaleParityTest*"
```

#### Build Tiers: Scoped vs. Full Multi-Module Build
- **Targeted module build/test (`.\gradlew.bat :<module>:build` or `:test`):**
  - **Sufficient for:** Localized changes confined to a single module or leaf adapter (e.g. `commands-api`, `rtp-core` math/cache adjustments, `rtp-plugin` locale updates, single platform adapter bugfix).
  - Also sufficient for rapid inner-loop iterative feedback while working.
- **Full multi-module build (`.\gradlew.bat build` / `./gradlew build`):**
  - **Required for:**
    1. Cross-module structural changes (e.g. public interfaces in `rtp-api`, SPI refactoring in `commands-api`/`effects-api`, core model signatures).
    2. Shared build scripts, dependencies, or root Gradle configuration changes (`build.gradle`, `settings.gradle`, `gradle/`).
    3. Final pre-release verification or when explicitly requested by the user.
- **Exemptions:** Pure documentation or markdown changes (no compiled source or resource files touched) require no Gradle build or test execution.

### Tier 2: Coverage & Mutation Verification
When modifying core safety or high-assurance logic:
* **Coverage Verification:** Enforces module and package JaCoCo minimums.
  ```powershell
  .\gradlew.bat :rtp-core:jacocoTestCoverageVerification -Pcoverage
  ```
* **PIT Mutation Testing:** Verifies mutation score (>= 60% on safety packages).
  ```powershell
  .\gradlew.bat :rtp-core:pitest -Pmutation
  ```

### Tier 3: Multi-Server & Platform Acceptance (Devstack)
The `devstack/` environment provides end-to-end integration testing for proxies, lobbies, and heterogeneous backends.

* **Autonomous Execution:** Because client interaction is driven headlessly via Node.js / Mineflayer (`devstack/clients/mineflayer-bot.js`), the entire acceptance suite is **100% scriptable and executable non-interactively by AI agents or CI**.
* **Conditional Trigger (When to run devstack):**
  - **Required:**
    - Changes to proxy dispatch, network protocol, packet framing, or cross-server handoffs.
    - Multi-server token reservation, heartbeat gossip, or killswitch handling.
    - Multi-platform backend parity (Paper + Folia + Fabric behind Velocity).
    - Runtime-attested JaCoCo coverage collection (`-Coverage`).
  - **Skip (NOT required):**
    - Single-module algorithm, cache, or math updates.
    - Command tree or permission updates verified via unit tests.
    - Configuration, locale, and documentation changes.
    - Local adapter tweaks verifiable via compile and unit tests.

#### Acceptance Commands:
```powershell
# Standard Pro acceptance (Redis + 2 Velocity + 2 Paper lobbies + Paper/Folia backends)
.\devstack\run-acceptance.ps1 -Scenario all

# Specific scenario smoke check
.\devstack\run-acceptance.ps1 -Scenario roundtrip
.\devstack\run-acceptance.ps1 -Scenario killswitch

# Lite tier (DB-free plugin-message transport)
.\devstack\run-acceptance.ps1 -Scenario roundtrip -Lite

# Heterogeneous multi-platform cluster (Fabric on backend-c)
.\devstack\run-acceptance.ps1 -Scenario all -Fabric

# Runtime-attested JaCoCo coverage dump
.\devstack\run-acceptance.ps1 -Coverage
```

On Linux/POSIX runners or CI:
```bash
./devstack/run-acceptance.sh --scenario all
```

---

## 3. Interpreting Results & Failure Triage

1. **Unit Test Failures:**
   - Review stack traces. Check if behavior change violated a documented REQ-* in `docs/dev/TRACEABILITY.md`.
2. **Devstack Acceptance Failures:**
   - Check `devstack/acceptance-evidence.log` or console output for structured JSON report:
     `{"status":"FAIL","reason":"..."}`
   - Inspect target container logs:
     `docker compose logs proxy-a` or `docker compose logs backend-b`
3. **Locale Parity Failures:**
   - Ensure any added baseline keys in `rtp-plugin/src/main/resources/` are mirrored in `lang/<file>.lang.yml` and all `lang/<locale>/<file>.yml`.
