# Licensing and Edition Clarity

> **Scope:** Repository dual-licensing structure, open-core boundaries, and edition differentiation (LeafRTP Lite vs. LeafRTP Pro).
> **Related:** [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md) (RTP-lite assembly), [ADR-061](../adr/ADR-061-open-core-dual-licensing.md) (Open-Core Dual Licensing), [ADR-069](../adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md) (Claim Addon Extraction), `LICENSE`, `LICENSE-MIT`.

---

## 1. Executive Summary

RTP uses an **open-core dual-licensing** model:

1. **Permissive Open Core (MIT):** The foundation modules (`rtp-api`, `rtp-core`, `anvil-api`, `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `tags-api`, `yaml-api`), the Paper/Spigot and Fabric adapters, the plugin-message network transport, and the free **LeafRTP (Lite)** binary distribution are licensed under the **MIT License** (`LICENSE-MIT`). Anyone can use, modify, distribute, or bundle them, commercially or non-commercially.
2. **Enterprise & Pro Extensions (PolyForm Noncommercial 1.0.0):** Advanced enterprise extensions (durable SQL and Redis network state bindings, external SQL/Redis persistence drivers and accessors, tuned native Folia multithreading adapter, login reserve cache, dynamic tag refresh engine) and the **LeafRTP Pro** binary assembly are licensed under **PolyForm Noncommercial 1.0.0** (`LICENSE`).
3. **Purchased Pro Deliverables:** Pre-compiled release binaries of LeafRTP Pro purchased via BuiltByBit grant production commercial usage on the purchaser's server networks, while source builds remain subject to PolyForm Noncommercial terms.
4. **GPL-Licensed Modules:** NeoForge platform components and carrier modules are licensed under **GPL-3.0-or-later** (`neoforge.mods.toml`). Standalone addon modules (e.g. `LeafRTPGuiAddon`) carry their respective open-source licenses and are bundled into release artifacts.

---

## 2. License Matrix by Module and Artifact

| Module / Component | License | Permitted Commercial Usage | Governed By |
|---|---|:---:|---|
| **`rtp-api`** | **MIT** | Yes | `LICENSE-MIT` / `rtp-api/LICENSE` |
| **`rtp-core`** | **MIT** | Yes | `LICENSE-MIT` / `rtp-core/LICENSE` |
| **SPI modules** (`commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`, `yaml-api`) | **MIT** | Yes | `LICENSE-MIT` |
| **Platform Adapters (Spigot/Paper, Fabric)** | **MIT** | Yes | `LICENSE-MIT` |
| **Basic Folia Support (`FoliaAwareScheduler`)** | **MIT** | Yes | `rtp-paper-common` (`LICENSE-MIT`) |
| **Plugin-Message Network Transport** | **MIT** | Yes | `rtp-core` (`LICENSE-MIT`) |
| **LeafRTP Lite Jar** (`LeafRTP-<version>.jar`) | **MIT** | Yes | `LICENSE-MIT` (bundled inside jar) |
| **NeoForge Carrier & Adapters** (`rtp-neoforge-*`) | **GPL-3.0-or-later** | Yes (per GPL terms) | `neoforge.mods.toml` |
| **Bundled Addons** (`LeafRTPClaimAddon`, `LeafRTPGuiAddon`) | **GPL-3.0 / MIT** | Yes | Bundled in both Lite and Pro jars |
| **Tuned Folia Adapter** (`rtp-folia`) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |
| **Durable Network & Proxy Modules** (`rtp-proxy-common` SQL/Redis bindings, `rtp-proxy-velocity`) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |
| **SQL & Redis Persistence** (HikariCP, Jedis, SQLite JDBC, H2, PostgreSQL, MySQL accessors) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |
| **Dynamic Tags Engine** (`rtp-tags`, `tagsRefresh.yml`) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |
| **Login Reserve Cache** (`LoginCacheTask`, ADR-023) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |
| **LeafRTP Pro Jar** (`LeafRTP-Pro-<version>.jar`) | **PolyForm Noncommercial 1.0.0** | Non-commercial only (source) / Commercial with purchased license | `LICENSE` |

---

## 3. Edition Feature Comparison (Lite vs. Pro)

As detailed in [ADR-024](../adr/ADR-024-rtp-lite-assembly-variant.md), [ADR-061](../adr/ADR-061-open-core-dual-licensing.md), and [ADR-069](../adr/ADR-069-claim-integrations-extracted-to-bundled-addon.md):

| Feature / Capability | LeafRTP (Lite) | LeafRTP Pro | Notes |
|---|:---:|:---:|---|
| **License** | **MIT** | **PolyForm Noncommercial 1.0.0** | Lite jar bundles `LICENSE-MIT`; Pro jar ships root `LICENSE`. |
| **Single-Server Random Teleport** | Yes | Yes | Identical Archimedean spiral and Hilbert curve candidate selection. |
| **Paper & Spigot Support** | Yes | Yes | Full native async chunk loading via Paper adapter. |
| **Fabric Support** | Yes | Yes | Supported across 1.20.x, 1.21.x, and MC 26.x unobf carriers. |
| **NeoForge Support** | Yes | Yes | Supported across 1.21.x and MC 26.x; carrier bytecode merged into both jars. |
| **Folia Support** | Basic (regionized fallback) | Tuned native adapter | Lite hops via `FoliaAwareScheduler`; Pro includes throughput-optimized `rtp-folia`. |
| **Storage / Persistence** | Flat-file YAML | SQLite / H2 / MySQL / PostgreSQL / Redis / YAML | Lite strips all SQL/Redis accessors and JDBC/Jedis drivers (uses `YamlFileDatabase`). |
| **Cross-Server / Network Mode** | Tier-1 Plugin-Message & Proxy-Direct | Tier-1 Plugin-Message + Durable SQL/Redis | Lite supports zero-driver messaging (`auto` proxy detect); Pro adds durable tokens and cluster sync. |
| **Login Reserve Cache (ADR-023)** | No (stripped) | Yes | Pro pre-warms a dedicated queue for join-time teleports (`rtp.onevent.join`). |
| **Safety Lists & Predicates** | Standard + Predicates | Standard + Predicates + Dynamic Tag Refresh | Both support base `safety.yml` block tags and predicates; Pro adds `rtp-tags` engine. |
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

- **Build-Time License Auditing:** The Gradle task `:rtp-plugin:liteJarStructureCheck` inspects the built Lite jar to verify that `LICENSE` contains the MIT terms, does not contain PolyForm Noncommercial text, and confirms that no forbidden Pro classes (SQL accessors, JDBC/Jedis drivers, tuned Folia adapter, `LoginCacheTask`, `rtp-tags`) leak into the Lite assembly.
- **Network Transport Integrity:** The build audit verifies that the tier-1 `PluginMessageNetworkBinding` and proxy auto-detection classes are preserved in Lite, while durable SQL and Redis transport subtrees are excluded.
- **Third-Party Dependency Compliance:** Shaded third-party libraries (e.g. HikariCP, Jedis, SnakeYAML, SQLite JDBC) are compatible with Apache 2.0, MIT, or BSD licenses and audited via CycloneDX SBOM generation (`bom.json`) on every release.
