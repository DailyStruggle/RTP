# Licensing and Edition Clarity

> **Scope:** Repository licensing and edition differentiation (LeafRTP Lite vs. LeafRTP Pro).
> **Related:** [ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md) (All-MIT Licensing; Pro as Support), [ADR-100](../adr/ADR-100-superseding-adr-024-sla-and-support-tier.md) (Technical Parity and Pro as SLA & Support Tier), [ADR-061](../adr/ADR-061-open-core-dual-licensing.md) (superseded), [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md) (superseded), [ADR-069](../adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md) (Claim Addon Extraction), `LICENSE`, `LICENSE-MIT`.

---

## 1. Executive Summary

RTP is **MIT-licensed** ([ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md)):

1. **All source is MIT.** Every module, platform adapter, the network/proxy modules, and both binaries (**LeafRTP (Lite)** and **LeafRTP Pro**) are licensed under the MIT License (root `LICENSE`, identical to `LICENSE-MIT`). Anyone can use, modify, distribute, or bundle them, commercially or non-commercially.
2. **Pro is a service, not a licence.** Buying Pro grants no additional copyright rights; it buys priority/SLA support routing, early-access builds, and sponsorship (ADR-100).
3. **More specific grants win.** NeoForge platform components and carrier modules are licensed under **GPL-3.0-or-later** (`neoforge.mods.toml`). Addon modules that ship their own licence keep it.
4. **Earlier releases.** Releases published before 2026-10-05 keep the licence they shipped with (ADR-061 open-core terms); MIT is strictly more permissive, so no licensee loses rights.

---

## 2. License Matrix by Module and Artifact

| Module / Component | License | Permitted Commercial Usage | Governed By |
|---|---|:---:|---|
| **`rtp-api`**, **`rtp-core`** | **MIT** | Yes | `LICENSE` / `rtp-api/LICENSE` / `rtp-core/LICENSE` |
| **SPI modules** (`commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`, `yaml-api`) | **MIT** | Yes | `LICENSE` |
| **Platform Adapters** (Spigot/Paper, Folia, Fabric) | **MIT** | Yes | `LICENSE` |
| **Network & Proxy Modules** (plugin-message transport, `RespRedisClient`, SQL/Redis bindings, `rtp-proxy-*`) | **MIT** | Yes | `LICENSE` |
| **SQL Persistence & Pool** (SQLite / H2 / MySQL / PostgreSQL accessors, in-house pool per ADR-101) | **MIT** | Yes | `LICENSE` |
| **Safety Tag / Predicate Grammar** (`SafetyTokenParser`, `SafetyTokenExpander`; offline `tags-api` resolver) | **MIT** | Yes | `LICENSE` |
| **Login Reserve Cache** (`LoginCacheTask`, ADR-023) | **MIT** | Yes | `LICENSE` |
| **NeoForge Carrier & Adapters** (`rtp-neoforge-*`) | **GPL-3.0-or-later** | Yes (per GPL terms) | `neoforge.mods.toml` |
| **Bundled Addons** (`LeafRTPClaimAddon`, `LeafRTPGuiAddon`, `LeafRTPCountdownAddon`, `LeafRTPActionAddon`) | **MIT** unless the addon ships its own licence | Yes | Bundled in both Lite and Pro jars |
| **LeafRTP Lite Jar** (`LeafRTP-<version>.jar`) | **MIT** | Yes | `LICENSE-MIT` (bundled inside jar as `LICENSE`) |
| **LeafRTP Pro Jar** (`LeafRTP-Pro-<version>.jar`) | **MIT** | Yes | root `LICENSE` (bundled inside jar) |

---

## 3. Edition Feature Comparison (Lite vs. Pro)

As detailed in [ADR-100](../adr/ADR-100-superseding-adr-024-sla-and-support-tier.md), [ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md), and [ADR-069](../adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md). Both jars ship the same code; the editions differ only in support and release cadence.

| Feature / Capability | LeafRTP (Lite) | LeafRTP Pro | Notes |
|---|:---:|:---:|---|
| **License** | **MIT** | **MIT** | Lite jar bundles `LICENSE-MIT`; Pro jar ships the identical root `LICENSE`. |
| **Support** | Community | Priority / SLA | Routed via marketplace buyer metadata (ADR-100). |
| **Release cadence** | Stable milestones | Early access | ADR-100 section 2.3. |
| **Single-Server Random Teleport** | Yes | Yes | Identical Archimedean spiral and Hilbert curve candidate selection. |
| **Paper & Spigot Support** | Yes | Yes | Full native async chunk loading via Paper adapter. |
| **Fabric Support** | Yes | Yes | Supported across 1.20.x, 1.21.x, and MC 26.x unobf carriers. |
| **NeoForge Support** | Yes | Yes | Supported across 1.21.x and MC 26.x; carrier bytecode merged into both jars. |
| **Folia Support** | Yes | Yes | Both editions ship the native `rtp-folia` adapter (ADR-100). |
| **Storage / Persistence** | Full (SQLite / H2 / MySQL / PostgreSQL / Redis / YAML) | Full (SQLite / H2 / MySQL / PostgreSQL / Redis / YAML) | Both editions support the complete database suite and the in-house connection pool (ADR-100, ADR-101). |
| **Cross-Server / Network Mode** | Full (Plugin-Message + Redis / SQL) | Full (Plugin-Message + Redis / SQL) | Both editions support zero-dependency `RespRedisClient` and multi-server proxy synchronization (ADR-100). |
| **Login Reserve Cache (ADR-023)** | Yes | Yes | Pre-warms a dedicated queue for join-time teleports (`rtp.onevent.join`); `LoginCacheTask` retained in Lite (ADR-100). |
| **Safety Lists & Predicates** | Full grammar | Full grammar | `#tag`, `[...]` predicates and `-` subtraction resolve against the live server tag registry in both editions (`rtp-api` / `rtp-core`; [`SAFETY.md`](../admin/configuration/SAFETY.md)). |
| **Claim Plugin Integrations** | Yes (bundled addon) | Yes (bundled addon) | Both editions bundle `LeafRTPClaimAddon.jar` in `bundled-addons/` (self-extracting). |
| **Economy / Vault Charging** | Yes | Yes | Optional per-region teleport charging supported in both editions (`economy.yml` shipped). |
| **PlaceholderAPI (PAPI) Integration** | Yes | Yes | Supported in both editions when PlaceholderAPI is present. |
| **Multilingual Support (`/rtp lang`)** | Yes | Yes | Complete `lang/**` locale trees and ADR-020 runtime switching included in both. |
| **Interactive Book & Map Menus** | Yes | Yes | Maps API and book rendering available across both editions. |
| **Anvil / Linear Prefilter (ADR-016, ADR-077)** | Yes | Yes | Off-tick chunk prefiltering enabled in both editions (`rtp-anvil` retained in Lite). |
| **Operator Documentation Extraction** | Yes | Yes | Both extract identical version-stamped `docs/**` via `JarUtils.extractDocs`. |
| **Configuration Files Parity** | Yes | Yes | Lite inherits Pro `config.yml`, `advanced/*`, and definitions verbatim (ADR-024 amendment). |
| **bStats Anonymous Telemetry** | Plugin ID 12277 | Plugin ID 30865 | Distinct metrics tracking IDs preserve separate install base analytics. |

---

## 4. Verification and Compliance

- **Build-Time License Auditing:** The Gradle task `:rtp-plugin:liteJarStructureCheck` inspects the built Lite jar to verify that `LICENSE` contains the MIT terms (and no PolyForm text), while Folia, `LoginCacheTask`, SQL and Redis are retained per ADR-100.
- **Network Transport Integrity:** The build audit verifies that the tier-1 `PluginMessageNetworkBinding` and proxy auto-detection classes, as well as the native `RespRedisClient` transport bindings, are preserved in Lite per ADR-100.
- **Third-Party Dependency Compliance:** Shaded third-party libraries (e.g. SnakeYAML, aircompressor) are compatible with Apache 2.0, MIT, or BSD licenses and audited via CycloneDX SBOM generation (`bom.json`) on every release. LZ4 region decode is in-house code (ADR-016 section 15); lz4-java is a test-scope dependency only and is not shipped.
