# ADR-105 — Intent-Based Documentation Architecture, Operator-Centric Routing, and Documentation Lifecycle Refinement

**Status:** Accepted
**Date:** 2026-10-03
**Authors:** RTP Core Team
**Extends:** [ADR-018](ADR-018-agents-md-public-release-structure.md) (`AGENTS.md` Public Release Structure), [ADR-045](ADR-045-rtp-docs-menu-consumer.md) (`/rtp docs` Menu Consumer), [ADR-071](ADR-071-config-organization-and-discoverability.md) (Config Organization and Discoverability), [ADR-104](ADR-104-ephemeral-web-editor-and-packed-docs-integration.md) (Ephemeral Web Editor and In-Game Packed Docs Integration)
**Related REQs:** S-004, S-006, S-007, REQ-RTP-F-013

---

## 1. Context and Problem Statement

Over several major feature iterations (spatial memory indexing, Anvil/Linear NBT prefilters, Count-Bound task pipes, multi-server Velocity networking, interactive cartography, and declarative scripted actions), the LeafRTP documentation repository expanded into a deep, highly rigorous corpus comprising over 100 Architecture Decision Records (ADRs), dozens of architecture slices, platform-specific plans, configuration reference sheets, in-game packed book menus, and legacy redirect stubs.

While this extensive documentation provides invaluable architectural stability, traceability, and invariant protection for core contributors and AI agents, it exhibits critical friction points for server operators, new contributors, and web visitors:

1. **Volume and Cognitive Overload ("Too Much vs. What Is Good"):**
   - High volume of documentation is beneficial for architectural safety only when it is partitioned by audience and intent.
   - When documentation is presented as flat catalogs or exhaustive lists, server operators searching for a quick configuration key or basic `/rtp` setup are confronted with low-level concurrency invariants, mathematical proofs, or internal lifecycle stages.
2. **Navigational Flatness vs. Intent-Driven Discovery:**
   - Modern industry-standard documentation ecosystems (e.g., Microsoft Learn, SonarQube, Stripe, Tailwind) avoid flat file taxonomies. Instead, they classify content by **user intent**: *What is the user trying to accomplish right now?*
   - The public MkDocs navigation requires curated categorization that channels users directly into high-signal landing pages (Onboarding/Quickstart, Configuration & Regional Shaping, Production Operations & Runbooks, and API/Addon Development).
3. **Legacy Redirection Stubs (`wiki/`):**
   - The repository retains a `wiki/` directory with 48 legacy redirect stubs created during the migration to docs-as-code. These stubs currently link out to raw GitHub repository markdown files (`https://github.com/DailyStruggle/RTP/blob/V3/docs/...`) rather than the formatted, interactive MkDocs website (`https://dailystruggle.github.io/RTP/...`), breaking user immersion and navigation.
4. **Scratch and Lifecycle Hygiene:**
   - Active multi-step agent tasks generate transient checklists (`docs/dev/scratch/CHECKLIST-*.md`). While essential for state preservation during active local development, untracked/stale scratch artifacts must not bleed into canonical public documentation or clutter search indices.

---

## 2. Decision Drivers

- **Intent-Driven Audience Segmentation:** Separate documentation strictly by audience (Operator vs. Developer vs. Maintainer) and user task mode (Learning, Configuring, Troubleshooting, Extending).
- **Single Source of Truth (SSOT):** Canonical documentation lives in `docs/` and publishes to GitHub Pages via MkDocs on merge to trunk; inline configuration comments, in-game book menus (ADR-045, ADR-104), and website pages derive from this single truth.
- **Operator-Centric Clarity:** Operator documentation must prioritize low-friction onboarding, concrete configuration recipes, copy-paste snippets, and symptom-diagnosis-resolution runbooks over theoretical exposition.
- **Preservation of Invariant Rigor:** Maintain internal architecture records, formal requirements (S-001..S-007), traceability matrices, and ADRs intact for contributors and agents without exposing them in operator-facing site navigation.
- **Link Integrity:** All public-facing redirect points (including wiki stubs and external README links) must target the canonical published documentation website rather than raw repository blobs.

---

## 3. Considered Options

### Option 1: Aggressive Consolidation and Pruning
Merge all existing documentation into a minimal set of monolithic markdown guides, deleting old ADRs and combining configuration files into one manual.
- *Rejected:* Destroys historical decision reasoning, breaks requirement traceability (REQ-* -> test/ADR), degrades agent contextual alignment, and creates unwieldy, unmaintainable single files.

### Option 2: Status Quo (Flat File List & Raw GitHub Links)
Maintain current flat navigation structure and let operators browse directory hierarchies.
- *Rejected:* Continues high cognitive friction for operators, fails to direct users based on their active task, and causes high support burden on Discord/forums.

### Option 3: Intent-Based Documentation Architecture (The Diátaxis Alignment)
Structure public documentation into four distinct, descriptive intent pillars modeled after the Diátaxis documentation framework (Tutorials, How-To/Runbooks, Reference, Concepts), optimize `mkdocs.yml` navigation around these pillars, update legacy entry points to target the published site, and maintain internal architectural trees (`dev/`, `adr/`, `architecture/`) as an unbloated foundation.
- *Accepted.*

---

## 4. Architectural Design

```
+====================================================================================================+
|                                      LeafRTP DOCUMENTATION HUB                                     |
+====================================================================================================+
                                                  │
                 ┌──────────────────┬─────────────┴─────────────┬──────────────────┐
                 ▼                  ▼                           ▼                  ▼
          "Get Started"       "Configure"                  "Operate"           "Extend"
        (First 10 Minutes)  (Shape & Tune)             (Maintain/Diagnose)  (API & Addons)
        [Tutorials/Recipes]   [Reference]                   [How-To/Runbook]     [Architecture]
                 │                  │                           │                  │
                 ▼                  ▼                           ▼                  ▼
         - Quick Start       - Regions & Shapes          - Incident Runbook  - Addon Quickstart
         - Intended Usage    - Worlds & Overrides        - Hazard Register   - Custom Menus
         - Common Recipes    - Safety & Biomes           - Metric Dashboards - Cartography SPI
         - Prefabs/Templates - Economy & Effects         - Migration Guides  - Multi-Platform
         - What NOT to Do    - Proxy/Network Settings    - Region Prefilter  - Formal Invariants
```

### 4.1 Four Intent Pillars

1. **Pillar 1: Get Started (Onboarding & Evaluation)**
   - *Audience:* Operators installing LeafRTP for the first time or evaluating its feature set.
   - *Focus:* Fast time-to-value (< 5 minutes to first successful teleport).
   - *Core Assets:* `admin/QUICK_START.md`, `site/intended-usage.md`, `admin/RECIPES.md`, `admin/PREFABS.md`, `site/what-not-to-do.md`, `admin/FAQ.md`.
2. **Pillar 2: Configure (Customization & Design)**
   - *Audience:* Server administrators fine-tuning regions, world setups, safety rules, economy pricing, and messaging.
   - *Focus:* Declarative reference, valid value ranges, schema options, and copy-pasteable YAML examples.
   - *Core Assets:* `admin/configuration/CONFIGURATION.md` (Overview), `CORE_CONFIG.md`, `REGIONS.md`, `WORLDS.md`, `SCHEMATICS.md`, `SAFETY.md`, `PERFORMANCE.md`, `ECONOMY.md`, `EVENTS_AND_EFFECTS.md`, `INTEGRATIONS.md`, `MESSAGES.md`, `admin/proxies/CONFIGURATION.md`.
3. **Pillar 3: Operate & Maintain (Production Health)**
   - *Audience:* Active server owners monitoring performance, debugging failures, or migrating versions.
   - *Focus:* Operational runbooks following **Symptom → Diagnosis → Resolution**, hazard mitigations, command execution, and metrics monitoring.
   - *Core Assets:* `admin/COMMANDS.md`, `admin/RUNBOOK.md`, `admin/HAZARDS.md`, `admin/REGION_FILE_READING.md`, `admin/MIGRATION.md`, `admin/configuration/METRICS.md`, `admin/proxies/SINGLE_BACKEND_VERIFICATION.md`, `admin/proxies/CROSS_SERVER_VERIFICATION.md`.
4. **Pillar 4: Extend & Develop (Addons & Contributions)**
   - *Audience:* Addon developers, modders, and core contributors.
   - *Focus:* Stable SPI surfaces (`rtp-api`), custom shape registration, event listeners, architecture overviews, and build pipelines.
   - *Core Assets:* `ADDON_QUICKSTART.md`, `FOR_ADDON_DEVELOPERS.md`, `FOR_CONTRIBUTORS.md`, `site/why.md`, `dev/ARCHITECTURE.md`, `dev/DESIGN.md`, `dev/REQUIREMENTS.md`.

---

### 4.2 Website Navigation Architecture (`mkdocs.yml`)

The published documentation site reorganizes top-level tabs and sidebar navigation into these functional groupings:

```yaml
nav:
  - Home: index.md
  - Getting Started:
      - Quick Start: admin/QUICK_START.md
      - Intended Usage: site/intended-usage.md
      - Common Recipes: admin/RECIPES.md
      - Prefabs & Templates: admin/PREFABS.md
      - Anti-Patterns (What NOT to do): site/what-not-to-do.md
      - FAQ: admin/FAQ.md
  - Commands & Permissions:
      - Command Reference: admin/COMMANDS.md
      - Actions & Arenas: admin/ACTIONS.md
  - Configuration Reference:
      - Overview & Lifecycle: admin/configuration/CONFIGURATION.md
      - Core Settings (config.yml): admin/configuration/CORE_CONFIG.md
      - Regions: admin/configuration/REGIONS.md
      - Worlds: admin/configuration/WORLDS.md
      - Schematics: admin/configuration/SCHEMATICS.md
      - Safety & Biomes: admin/configuration/SAFETY.md
      - Performance & Memory: admin/configuration/PERFORMANCE.md
      - Economy: admin/configuration/ECONOMY.md
      - Events & Effects: admin/configuration/EVENTS_AND_EFFECTS.md
      - Integrations & Claims: admin/configuration/INTEGRATIONS.md
      - Messages & Locales: admin/configuration/MESSAGES.md
  - Proxy & Multi-Server:
      - Network Mode Overview: admin/proxies/INDEX.md
      - Network Configuration: admin/proxies/CONFIGURATION.md
      - Single-Backend Verification: admin/proxies/SINGLE_BACKEND_VERIFICATION.md
      - Cross-Server Verification: admin/proxies/CROSS_SERVER_VERIFICATION.md
  - Operations & Runbook:
      - Runbook (Incident Response): admin/RUNBOOK.md
      - Hazard Register: admin/HAZARDS.md
      - Region File Prefiltering: admin/REGION_FILE_READING.md
      - Migration Guide: admin/MIGRATION.md
      - Metrics & Monitoring: admin/configuration/METRICS.md
  - Developers & Addons:
      - Addon Quickstart: ADDON_QUICKSTART.md
      - Addon Developer Guide: FOR_ADDON_DEVELOPERS.md
      - Contributor Guide: FOR_CONTRIBUTORS.md
      - Architecture & Algorithms: site/why.md
```

Internal engineering trees (`dev/`, `adr/`, `architecture/`) remain strictly excluded from public site navigation (`not_in_nav`), preserving deep technical precision in source control while preventing operator clutter.

---

### 4.3 Wiki Redirect Stubs Normalization

All 48 redirect files under `wiki/*.md` shall route to the published MkDocs documentation site (`https://dailystruggle.github.io/RTP/...`) rather than raw GitHub blob URLs (`https://github.com/DailyStruggle/RTP/blob/V3/...`).

Canonical target mapping pattern:
- `wiki/Home.md` → `https://dailystruggle.github.io/RTP/`
- `wiki/Commands.md` → `https://dailystruggle.github.io/RTP/admin/COMMANDS/`
- `wiki/Config-yml.md` → `https://dailystruggle.github.io/RTP/admin/configuration/CORE_CONFIG/`
- `wiki/Regions.md` → `https://dailystruggle.github.io/RTP/admin/configuration/REGIONS/`
- `wiki/intended-usage.md` → `https://dailystruggle.github.io/RTP/site/intended-usage/`
- `wiki/API.md` → `https://dailystruggle.github.io/RTP/FOR_ADDON_DEVELOPERS/`

---

### 4.4 Documentation Lifecycle & Scratch Hygiene

1. **Transient Scratch Files:**
   - Multi-step task checklists and scratch review documents live in `docs/dev/scratch/`.
   - Scratch files are recognized as local-developer artifacts; they shall remain untracked or purged upon task verification and shall not be referenced by canonical documentation.
2. **ADR Immutability:**
   - Architecture Decision Records remain append-only historical records.
   - When decisions evolve, new ADRs reference, extend, or supersede prior ADRs (e.g. ADR-069 superseding ADR-019, ADR-100 superseding ADR-024). Superseded records are explicitly marked in `docs/adr/README.md`.
3. **Docs-as-Code Synchronization:**
   - Shipped configuration YAML files (`plugins/RTP/*.yml`) and in-game documentation menus (ADR-045, ADR-104) are validated against documentation changes in CI via unit tests and locale parity assertions.

---

## 5. Consequences

### Positive
- **Reduced Cognitive Load:** Operators find setup guides, commands, and config options without wading through engine internals.
- **Improved Discoverability:** Mirroring established intent routing (Microsoft/SonarQube) provides intuitive paths for both novice and advanced users.
- **Enhanced Professional Presentation:** Eliminates raw GitHub markdown redirects in favor of the branded, responsive Material documentation site.
- **Preserved Engineering Rigor:** Internal specifications, traceability, and architectural rationale remain fully intact for core contributors and automated agents.

### Negative / Trade-offs
- Requires keeping `mkdocs.yml` navigation updated when new operator or developer guides are introduced.
- Requires updating the static redirect links in `wiki/*.md` whenever doc page slugs are refactored.
