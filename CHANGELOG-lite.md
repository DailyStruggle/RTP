# CHANGELOG (rtp-lite)

Release log for the **rtp-lite** assembly variant published to
[Modrinth: rtp-lite](https://modrinth.com/project/rtp-lite).

Scope and feature delta vs the full (Pro) edition are defined by
[ADR-024](docs/adr/ADR-024-rtp-lite-assembly-variant.md). This file is the
single source of truth for lite release notes; the Modrinth release workflow
(`.github/workflows/release.yml`) extracts the section matching each
`lite-v*` tag and publishes it as the Modrinth version description.

Versioning is **independent** from the Pro edition (`CHANGELOG.md`). Lite
follows its own `MAJOR.MINOR.PATCH` line; do not assume Pro `X.Y.Z` and lite
`X.Y.Z` share scope.

Per `.junie/AGENTS.md` *CHANGELOG Hygiene*, each unreleased section
describes the **net delta from the last released lite tag**, not from any
intermediate working-tree state. Diff against `lite-v<previous>` before
editing entries here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [1.0.0] - Unreleased

Initial public release of rtp-lite on Modrinth.

### Added

- First Modrinth publication of the lite assembly variant (ADR-024).
- Supported platforms: Bukkit, Spigot, Paper. Folia is Pro-only by design
  (ADR-024). Fabric support is planned and held until `rtp-fabric` Phase 4
  closes (`docs/dev/MULTI_PLATFORM_PLAN.md`).
- Bundled features (per ADR-024 amendments):
  - Multilingual support and `/rtp lang` (ADR-020 amendment, 2026-05-11(b)).
  - Claim-plugin softdepend integrations: GriefDefender, GriefPrevention,
    Towny, HuskTowns, Factions, Lands, RedProtect, WorldGuard (ADR-019
    amendment, 2026-05-11).
  - Anvil pre-filter (kept in lite for performance parity).
- Automated release pipeline: `.github/workflows/release.yml` publishes the
  lite jar to Modrinth on every `lite-v*` tag.

### Not included (Pro-only)

- Folia adapter and region-thread scheduling.
- SQL/Redis persistence (H2, SQLite, MySQL, PostgreSQL, Jedis).
- Login reserve cache (ADR-023).
- Visitor mode and economy/Vault wiring.
- Advanced `performance.yml` toggles documented in the full edition.

### Compatibility

- Java 21+ required.
- Minecraft 1.20.x through 1.21.x (see `game-versions` in the release
  workflow for the exact published list).

---

## Tag naming

Lite releases use the `lite-v<version>` tag prefix (e.g. `lite-v1.0.0`,
`lite-v1.0.1-beta.1`). The Pro edition uses unprefixed `v<version>` tags
and is published via the Jenkins pipeline (`Jenkinsfile`) to BuiltByBit.
The two tag namespaces are disjoint and must not be reused.
