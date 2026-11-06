# Licensing and Edition Clarity

> **Scope:** Repository licensing, edition differentiation (`LeafRTP`, `LeafSleep`, `LeafTreeGen`), and the open-source `ServiceLoader` SPI boundary.
> **Related:** [ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md) (All-MIT Licensing; Pro as Support), [ADR-126](../adr/ADR-126-multi-product-monorepo-release-architecture.md) (Multi-Product Monorepo Release Architecture), [leafrtp-ADR-078](../../resource/LeafRTP/docs/adr/leafrtp-ADR-078-superseding-adr-024-sla-and-support-tier.md) (Technical Parity and Pro as SLA & Support Tier), [leafrtp-ADR-044](../../resource/LeafRTP/docs/adr/leafrtp-ADR-044-open-core-dual-licensing.md) (superseded), [leafrtp-ADR-016](../../resource/LeafRTP/docs/adr/leafrtp-ADR-016-rtp-lite-assembly-variant.md) (superseded), [leafrtp-ADR-051](../../resource/LeafRTP/docs/adr/leafrtp-ADR-051-claim-integrations-extracted-to-bundled-addon.md) (Claim Addon Extraction), `LICENSE`, `LICENSE-MIT`.

---

## 1. Executive Summary

`DailyStruggle/RTP` (`V3`), `DailyStruggle/OnePlayerSleep` (`main`), and `LeafCraftDev/LeafTreeGen` (`main`) are **MIT-licensed** ([ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md)):

1. **All source in public open-source repositories is MIT with zero artificial lock checks.** Every SDK module (`api/*`), platform adapter, network/proxy module, and public multi-loader resource (`resource/LeafRTP`, `resource/LeafSleep`, `resource/LeafTreeGen`) is licensed under the MIT License (root `LICENSE`, identical to `LICENSE-MIT`). Anyone can use, modify, distribute, or bundle them, commercially or non-commercially.
2. **Pro is a service, not a licence (`LeafRTP`, `LeafSleep`, & `LeafTreeGen`).** Buying a BuiltByBit `Pro` listing (`LeafRTP-Pro`, `LeafSleep-Pro`, `LeafTreeGen-Pro`) grants no additional copyright rights for the repository source; it buys priority/SLA support routing, patch releases owed under that support, and sponsorship (ADR-100, [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md)). It buys no early access to code.
3. **Private monorepo trunk (`LeafCraftDev/MC` on `main`) + Option A per-resource public OSS projections.** Full monorepo development happens in `LeafCraftDev/MC` (`mc` remote, branch `main`), and `scripts/release/sync_public_oss_pr.py` (guarded by `scripts/pre_push_guard.py`) projects Option A public repositories (`--target rtp` -> `DailyStruggle/RTP:V3`; `--target sleep` -> `DailyStruggle/OnePlayerSleep:main`; `--target treegen` -> `LeafCraftDev/LeafTreeGen:main`) that share root `docs/` (`docs/dev/*`, `docs/adr/ADR-*.md`), `api/*` (minus `atlas/*`), and `platforms/*` alongside only the target's `resource/<Product>/**` directory, and imports contributor PRs back via `--import-pr`. Reserved resources (`resource/LeafAnvil`, `resource/LeafSleuth`, `resource/LeafConfig`, `resource/LeafArtifacts`, `resource/LeafShapeMining`) remain private to `LeafCraftDev/MC`; `resource/LeafAnvil` is distributed under the **LeafCraft Commercial Software License** (`LicenseRef-LeafCraft-Commercial`, `resource/LeafAnvil/LICENSE`) while keeping its `.lca` specification (`LCA-ICD-1.0`) and `api/anvil-api` under **MIT** for third-party interoperability.
4. **More specific grants win.** Module-local grants (`resource/LeafAnvil/LICENSE`, `rtp-api/LICENSE`, `rtp-core/LICENSE`) and NeoForge platform components licensed under **GPL-3.0-or-later** (`neoforge.mods.toml`) govern their respective modules. Addon modules that ship their own licence keep it.
5. **Earlier releases.** Releases published before 2026-10-05 keep the licence they shipped with (ADR-061 open-core terms); MIT is strictly more permissive, so no licensee loses rights.

---

## 2. License Matrix by Module and Artifact

| Module / Component | License | Permitted Commercial Usage | Governed By |
|---|---|:---:|---|
| **`rtp-api`**, **`rtp-core`** | **MIT** | Yes | `LICENSE` / `rtp-api/LICENSE` / `rtp-core/LICENSE` |
| **SPI modules** (`anvil-api`, `bit-api`, `bstats-api`, `commands-api`, `config-bit-api`, `database-api`, `effects-api`, `maps-api`, `menu-api`, `metrics-api`, `predict-api`, `schematics-api`, `server-api`, `tags-api`, `units-api`, `yaml-api`) | **MIT** | Yes | `LICENSE` |
| **Platform Adapters** (Spigot/Paper, Folia, Fabric) | **MIT** | Yes | `LICENSE` |
| **Network & Proxy Modules** (plugin-message transport, `RespRedisClient`, SQL/Redis bindings, `rtp-proxy-*`) | **MIT** | Yes | `LICENSE` |
| **SQL Persistence & Pool** (SQLite / H2 / MySQL / PostgreSQL accessors, in-house pool per ADR-101) | **MIT** | Yes | `LICENSE` |
| **Safety Tag / Predicate Grammar** (`SafetyTokenParser`, `SafetyTokenExpander`; offline `tags-api` resolver) | **MIT** | Yes | `LICENSE` |
| **Login Reserve Cache** (`LoginCacheTask`, ADR-023) | **MIT** | Yes | `LICENSE` |
| **NeoForge Carrier & Adapters** (`rtp-neoforge-*`, `treegen-neoforge`, `leafsleep-neoforge`) | **GPL-3.0-or-later** | Yes (per GPL terms) | `neoforge.mods.toml` |
| **Bundled Addons** (`LeafRTPClaimAddon`, `LeafRTPGuiAddon`, `LeafRTPCountdownAddon`, `LeafRTPActionAddon`, `LeafRTPRiftAddon`) | **MIT** unless the addon ships its own licence | Yes | Bundled in both Free and Pro jars |
| **Sibling Showcase & Diagnostic Plugins** (`LeafTreeGen`, `LeafSleuth`, `LeafConfig`, `LeafSleep`, `LeafShapeMining`, `LeafArtifacts`) | **MIT** | Yes | root `LICENSE` |
| **`LeafAnvil` Runtime Carrier** (`resource/LeafAnvil`, `LeafAnvil-<version>.jar`) | **LeafCraft Commercial (`LicenseRef-LeafCraft-Commercial`)** | Yes (licensed server/network) | `resource/LeafAnvil/LICENSE` (`.lca` spec `ICD.md` & `anvil-api` remain **MIT**) |
| **Extension SPIs** (`SleuthExtensionProvider`, `SleuthExtensionRegistry`, `ConfigExtensionProvider`, `ConfigExtensionRegistry`) | **MIT** | Yes (including external commercial providers) | root `LICENSE` ([ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md)) |
| **LeafRTP Jar (Free Default)** (`LeafRTP-<version>.jar`) | **MIT** | Yes | `LICENSE-MIT` (bundled inside jar as `LICENSE`) |
| **LeafRTP Pro Jar (Priority Support)** (`LeafRTP-Pro-<version>.jar`) | **MIT** | Yes | root `LICENSE` (bundled inside jar) |

---

## 3. Edition Comparison (Free Default vs. Pro Support)

As detailed in [leafrtp-ADR-078](../../resource/LeafRTP/docs/adr/leafrtp-ADR-078-superseding-adr-024-sla-and-support-tier.md), [ADR-108](../adr/ADR-108-all-mit-licensing-pro-as-support.md), and [leafrtp-ADR-051](../../resource/LeafRTP/docs/adr/leafrtp-ADR-051-claim-integrations-extracted-to-bundled-addon.md). Both jars ship the same code; the free download (`LeafRTP`) is the default, and `LeafRTP-Pro` adds priority/SLA support and contractual patch cadence.

| Feature / Capability | LeafRTP (Free Default) | LeafRTP Pro (Extra Support) | Notes |
|---|:---:|:---:|---|
| **License** | **MIT** | **MIT** | Default jar bundles `LICENSE-MIT`; Pro jar ships the identical root `LICENSE`. |
| **Support** | Community | Priority / SLA | Routed via marketplace buyer metadata (ADR-100). |
| **Release cadence** | Versions the maintainer chooses to publish, no holding period | Every release, including patches owed to buyers | One version line for both jars (ADR-117). |
| **Single-Server Random Teleport** | Yes | Yes | Identical Archimedean spiral and Hilbert curve candidate selection. |
| **Paper & Spigot Support** | Yes | Yes | Full native async chunk loading via Paper adapter. |
| **Fabric Support** | Yes | Yes | Supported across 1.20.x, 1.21.x, and MC 26.x unobf carriers. |
| **NeoForge Support** | Yes | Yes | Supported across 1.21.x and MC 26.x; carrier bytecode merged into both jars. |
| **Folia Support** | Yes | Yes | Both editions ship the native `rtp-folia` adapter (ADR-100). |
| **Storage / Persistence** | Full (SQLite / H2 / MySQL / PostgreSQL / Redis / YAML) | Full (SQLite / H2 / MySQL / PostgreSQL / Redis / YAML) | Both editions support the complete database suite and the in-house connection pool (ADR-100, ADR-101). |
| **Cross-Server / Network Mode** | Full (Plugin-Message + Redis / SQL) | Full (Plugin-Message + Redis / SQL) | Both editions support zero-dependency `RespRedisClient` and multi-server proxy synchronization (ADR-100). |
| **Login Reserve Cache (ADR-023)** | Yes | Yes | Pre-warms a dedicated queue for join-time teleports (`rtp.onevent.join`); `LoginCacheTask` included in both (ADR-100). |
| **Safety Lists & Predicates** | Full grammar | Full grammar | `#tag`, `[...]` predicates and `-` subtraction resolve against the live server tag registry in both editions (`rtp-api` / `rtp-core`; [`SAFETY.md`](../../resource/LeafRTP/docs/admin/configuration/SAFETY.md)). |
| **Claim Plugin Integrations** | Yes (bundled addon) | Yes (bundled addon) | Both editions bundle `LeafRTPClaimAddon.jar` in `bundled-addons/` (self-extracting). |
| **Economy / Vault Charging** | Yes | Yes | Optional per-region teleport charging supported in both editions (`economy.yml` shipped). |
| **PlaceholderAPI (PAPI) Integration** | Yes | Yes | Supported in both editions when PlaceholderAPI is present. |
| **Multilingual Support (`/rtp lang`)** | Yes | Yes | Complete `lang/**` locale trees and ADR-020 runtime switching included in both. |
| **Interactive Book & Map Menus** | Yes | Yes | Maps API and book rendering available across both editions. |
| **Anvil Prefilter (ADR-016, ADR-077)** | Yes | Yes | Off-tick chunk prefiltering enabled in both editions (`rtp-anvil` included in both). Other region formats require an addon reader. |
| **Operator Documentation Extraction** | Yes | Yes | Both extract identical version-stamped `docs/**` via `JarUtils.extractDocs`. |
| **Configuration Files Parity** | Yes | Yes | Both ship identical `config.yml`, `advanced/*`, and definitions verbatim (ADR-024 amendment). |
| **bStats Anonymous Telemetry** | Plugin ID 12277 | Plugin ID 30865 | Distinct metrics tracking IDs preserve separate install base analytics. |

---

## 4. Verification and Compliance

- **Build-Time License Auditing:** The Gradle task `:rtp-plugin:liteJarStructureCheck` inspects the built default `LeafRTP-<version>.jar` to verify that `LICENSE` contains the MIT terms (and no PolyForm text), while Folia, `LoginCacheTask`, SQL and Redis are retained per ADR-100.
- **Network Transport Integrity:** The build audit verifies that the tier-1 `PluginMessageNetworkBinding` and proxy auto-detection classes, as well as the native `RespRedisClient` transport bindings, are preserved in the default jar per ADR-100.
- **Third-Party Dependency Compliance:** The plugin jars shade no third-party library (ADR-077); dependencies are audited via CycloneDX SBOM generation (`bom.json`) on every release. LZ4 region decode is in-house code (ADR-016 section 15); lz4-java is a test-scope dependency only and is not shipped.
