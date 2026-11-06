# ADR-108 - All-MIT Licensing; Pro as Support, Not Source

**Status:** Accepted (decision 2 amended 2026-10-18 by [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md): early-access staging removed)
**Date:** 2026-10-05
**Supersedes:** [leafrtp-ADR-044](../../resource/LeafRTP/docs/adr/leafrtp-ADR-044-open-core-dual-licensing.md) (Open-Core Dual Licensing)
**Amends:** [leafrtp-ADR-078](../../resource/LeafRTP/docs/adr/leafrtp-ADR-078-superseding-adr-024-sla-and-support-tier.md) section 2 ("Commercial Indemnity & Grant")

## Context

[leafrtp-ADR-044](../../resource/LeafRTP/docs/adr/leafrtp-ADR-044-open-core-dual-licensing.md) split the repository into MIT (`rtp-api`, `rtp-core`, the lite binary) and PolyForm Noncommercial 1.0.0 (all other source, described as "the Pro-only features"). That boundary assumed the [leafrtp-ADR-016](../../resource/LeafRTP/docs/adr/leafrtp-ADR-016-rtp-lite-assembly-variant.md) feature split: the lite jar physically omitted SQL/Redis persistence, Folia, the login reserve cache, the multilingual bootstrap, claim integrations and network mode.

[leafrtp-ADR-078](../../resource/LeafRTP/docs/adr/leafrtp-ADR-078-superseding-adr-024-sla-and-support-tier.md) removed that split. Lite and Pro share the engine, storage, Redis transport, Folia scheduling and the bundled addons, and Pro is re-framed as an SLA / support / sponsor tier. Two contradictions followed:

1. The PolyForm grant still covered source that ships in the MIT lite binary (SQL accessors, `LoginCacheTask`, Folia adapter, network modules), so the same class was offered under MIT inside the lite jar and PolyForm in the repository. ADR-061's own alternatives table names this incoherent ("a consumer picks MIT and ignores PolyForm").
2. ADR-100 section 2 still promised "explicit commercial rights under the PolyForm Commercial License" for Pro buyers - a licence the repository never shipped.

ADR-061's precondition for relicensing ("any third-party-copyright contribution would need contributor sign-off") is met: every commit in the repository is authored by the copyright holder, and `CONTRIBUTING.md` carries an inbound licence grant for future contributions.

## Decision

1. **All source in public open-source repositories (`DailyStruggle/RTP`, `DailyStruggle/OnePlayerSleep`, `LeafCraftDev/LeafTreeGen`) is MIT with zero artificial lock checks.** The root `LICENSE` carries the MIT text (identical to `LICENSE-MIT`). Every SDK module (`api/*`), platform adapter (`platforms/*`), public multi-loader resource (`resource/LeafRTP`, `resource/LeafSleep`, `resource/LeafTreeGen`), addon without its own grant, and shipped public binary (`LeafRTP-<version>.jar`, `LeafRTP-Pro-<version>.jar`, `LeafSleep-<version>.jar`, `LeafSleep-Pro-<version>.jar`, `LeafTreeGen-<version>.jar`, `LeafTreeGen-Pro-<version>.jar`) in these repositories is MIT-licensed and contains zero artificial edition-blocking branches. Module-local grants that already exist (`rtp-api/LICENSE`, `rtp-core/LICENSE`, NeoForge GPL-3.0-or-later carriers per `neoforge.mods.toml`, addon-specific licences) keep governing their files.
2. **Pro sells service, not code (`LeafRTP`, `LeafSleep`, & `LeafTreeGen` showcase listings).** Purchasing a BuiltByBit `Pro` listing (`LeafRTP-Pro`, `LeafSleep-Pro`, `LeafTreeGen-Pro`) buys priority/SLA support routing (ADR-100 buyer metadata), including patch releases owed to the buyer under the support terms, and sponsorship. It buys no early access to code ([leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md) withdrew the original early-access release staging). It grants no extra copyright licence for the repository source, because MIT already grants commercial use. ADR-100's "Commercial Indemnity & Grant" bullet is withdrawn; no indemnity is offered unless a separate written support agreement says so.
3. **Private monorepo trunk (`LeafCraftDev/MC` on `main`) + Option A per-resource public OSS projections & bidirectional PR sync.** Full monorepo development takes place in `LeafCraftDev/MC` (`mc` remote, branch `main`) with all multi-loader plugins and mods organized under `resource/<Product>/`. `scripts/release/sync_public_oss_pr.py` (enforced by `scripts/pre_push_guard.py`) projects Option A public repositories that share overarching governance (`docs/dev/*`, `docs/adr/ADR-*.md`), `api/*` (minus `atlas/*`), and `platforms/*` while isolating each resource's own `resource/<Product>/**` tree (`--target rtp` -> `DailyStruggle/RTP:V3`; `--target sleep` -> `DailyStruggle/OnePlayerSleep:main`; `--target treegen` -> `LeafCraftDev/LeafTreeGen:main`) and supports reverse contributor PR imports via `--import-pr`. Reserved resources (`resource/LeafAnvil`, `resource/LeafSleuth`, `resource/LeafConfig`, `resource/LeafArtifacts`, `resource/LeafShapeMining`) remain private to `LeafCraftDev/MC`: `resource/LeafAnvil` and `resource/LeafSleuth` are governed by their module-local `LeafCraft Commercial Software License` (`resource/LeafAnvil/LICENSE` and `resource/LeafSleuth/LICENSE`, while `api/anvil-api` and `LCA-ICD-1.0` remain MIT), and `LeafSleuth` / `LeafConfig` expose `ServiceLoader` SPIs (`SleuthExtensionProvider` / `SleuthExtensionRegistry` and `ConfigExtensionProvider` / `ConfigExtensionRegistry`).
4. **Releases already published keep the licence they shipped with.** This decision applies from the first release after 2026-10-05; MIT is strictly more permissive, so no earlier licensee loses rights.
5. **No build delta remains inside `DailyStruggle/RTP`.** The safety tag / predicate grammar lives in `rtp-api` (`SafetyTokenParser`) and `rtp-core` (`SafetyTokenExpander`, resolving tags through `RTPServerAccessor.blockTagSnapshot()`) and ships in both jars. The lite `rtp/tags/**` exclude and its `liteJarStructureCheck` entry guarded `:tags-api`, an offline resolver neither jar depends on, so they are removed. The `**(Pro)**` CHANGELOG marker is retired for new entries; no feature in `DailyStruggle/RTP` shall be artificially gated behind an edition flag.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep ADR-061 open-core PolyForm | Contradicts ADR-100 parity; the same source would be MIT in the lite jar and PolyForm in the repository. |
| PolyForm Commercial (or another source-available grant) for Pro | Requires real feature gating to be meaningful, which ADR-100 removed; adds licence-compliance burden for operators with no technical difference. |
| GPL / AGPL for everything | Blocks closed-source commercial addons against `rtp-api`, which ADR-061 explicitly wanted to allow. |
| Defer the decision | Leaves README, `LICENSE`, `LICENSING.md` and ADR-100 contradicting one another during a public release push. |

## Consequences

- **Positive:**
  - One licence story across README, `LICENSE`, `docs/dev/LICENSING.md`, the jars and ADR-100.
  - No per-module licence boundary to police when code moves between modules (ADR-061's main maintenance cost).
  - Storefront copy can state "free and MIT; Pro is how you pay for support" without caveats.
- **Negative / Trade-offs:**
  - Anyone may legally rebuild and resell the Pro jar. Revenue depends on support quality, release cadence and goodwill, not on scarcity.
  - Marketplace listings must not describe Pro as unlocking any feature.

## Follow-ups

- Done 2026-10-05: `rtp/tags/**` exclude and forbidden-list entry removed from `rtp-plugin/build.gradle`, licence-check wording moved to ADR-108, `SAFETY.md` edition note and `(Pro)` grammar markers removed, CHANGELOG marker rule retired. The `release.yml` strip is kept only for re-publishing historical sections.
- Open: wire `:tags-api` (offline vanilla tag JSON) into core as a fallback for the platform registry, or retire the module.

## References

- `LICENSE`, `LICENSE-MIT`, `rtp-api/LICENSE`, `rtp-core/LICENSE`.
- [`docs/dev/LICENSING.md`](../dev/LICENSING.md) - licence matrix and edition comparison.
- [leafrtp-ADR-044](../../resource/LeafRTP/docs/adr/leafrtp-ADR-044-open-core-dual-licensing.md), [leafrtp-ADR-016](../../resource/LeafRTP/docs/adr/leafrtp-ADR-016-rtp-lite-assembly-variant.md), [leafrtp-ADR-078](../../resource/LeafRTP/docs/adr/leafrtp-ADR-078-superseding-adr-024-sla-and-support-tier.md), [leafrtp-ADR-087](../../resource/LeafRTP/docs/adr/leafrtp-ADR-087-release-cadence-contractual-pro-discretionary-lite.md).
