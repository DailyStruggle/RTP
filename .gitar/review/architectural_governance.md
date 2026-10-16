# Review Guide: Architectural Governance & Boundary Discipline

Architectural drift occurs when changes bypass structural boundaries or alter code without deliberate planning. Gitar enforces architectural discipline to ensure that the repository's modular boundaries, package hierarchies, and platform isolation guarantees remain intact.

---

## 1. Architectural Proposals Prior to Implementation (Rule D-005 / ADR-000)

- **The Rule:** Any pull request that touches more than one class, crosses a module boundary, or introduces a new command architecture must present an approved architectural proposal or cite a governing ADR from `docs/adr/` in its description before implementation.
- **Review Evaluation:**
  - Check the pull request description for:
    1. Governing ADR reference (e.g., `Governs: ADR-115`, `Closes: RTP-123`).
    2. Summary of affected classes/modules.
    3. Trade-offs and worst-case risks considered.
  - **Review Action:** Hard-block any multi-class or cross-module pull request that lacks design provenance or cites no governing ADR. Hasty implementations written without an approved design must be paused for architectural evaluation.

---

## 2. Platform Isolation & Boundary Discipline

- **The Rule:** `rtp-core` and `rtp-api` are strictly platform-agnostic. Platform-specific imports are forbidden.
- **Review Evaluation:**
  - Verify that no class in `rtp-core/` or `rtp-api/` imports platform packages:
    - `org.bukkit.*` / `org.spigotmc.*` / `com.destroystokyo.paper.*` / `io.papermc.paper.*`
    - `net.minecraft.*`
    - `net.fabricmc.*`
    - `net.neoforged.*`
  - Platform integrations must live strictly within platform adapter modules (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`) or unified SPI frameworks (`commands-api`, `effects-api`, `anvil-api`, `metrics-api`).
  - **Review Action:** Hard-block any pull request introducing platform-specific types into core or API modules.

---

## 3. Package DAG & Dependency Hierarchy (ADR-115)

- **The Rule:** Packages in `rtp-core` must conform to the 4-tier directed acyclic graph (DAG). Package cycles are strictly prohibited.
- **Review Evaluation:**
  - **Tier 1 (Contracts & Schemas):** Data transfer records, configuration schemas, mathematical definitions. Must not import Tiers 2, 3, or 4.
  - **Tier 2 (Domain Engines & Storage):** Shape selectors, database managers, cache layers. Must not import Tiers 3 or 4.
  - **Tier 3 (Execution Pipelines):** Task lifecycles, teleport execution pipelines (`tasks/teleport/`). Must not import Tier 4.
  - **Tier 4 (Orchestration & Ingress):** Command dispatchers, console runners, event ingress.
  - **Review Action:** Hard-block pull requests introducing circular package dependencies or inverted tier references.

---

## 4. Centralized Logging & Error Reporting

- **The Rule:** Zero direct console printing and zero unformatted stack traces.
- **Review Evaluation:**
  - Forbid `System.out.println` and `System.err.println` across all runtime modules.
  - Forbid direct `Bukkit.getLogger()` in core/API logic.
  - Forbid `e.printStackTrace()` - all exceptions must be logged via `RTP.log(Level.WARNING, "diagnostic message", e)` or `RTPServerAccessor.log(...)`.
