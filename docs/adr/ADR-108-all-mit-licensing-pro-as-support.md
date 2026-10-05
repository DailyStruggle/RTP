# ADR-108 - All-MIT Licensing; Pro as Support, Not Source

**Status:** Accepted
**Date:** 2026-10-05
**Supersedes:** [ADR-061](ADR-061-open-core-dual-licensing.md) (Open-Core Dual Licensing)
**Amends:** [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md) section 2 ("Commercial Indemnity & Grant")

## Context

[ADR-061](ADR-061-open-core-dual-licensing.md) split the repository into MIT (`rtp-api`, `rtp-core`, the lite binary) and PolyForm Noncommercial 1.0.0 (all other source, described as "the Pro-only features"). That boundary assumed the [ADR-024](ADR-024-rtp-lite-assembly-variant.md) feature split: the lite jar physically omitted SQL/Redis persistence, Folia, the login reserve cache, the multilingual bootstrap, claim integrations and network mode.

[ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md) removed that split. Lite and Pro share the engine, storage, Redis transport, Folia scheduling and the bundled addons, and Pro is re-framed as an SLA / support / sponsor tier. Two contradictions followed:

1. The PolyForm grant still covered source that ships in the MIT lite binary (SQL accessors, `LoginCacheTask`, Folia adapter, network modules), so the same class was offered under MIT inside the lite jar and PolyForm in the repository. ADR-061's own alternatives table names this incoherent ("a consumer picks MIT and ignores PolyForm").
2. ADR-100 section 2 still promised "explicit commercial rights under the PolyForm Commercial License" for Pro buyers - a licence the repository never shipped.

ADR-061's precondition for relicensing ("any third-party-copyright contribution would need contributor sign-off") is met: every commit in the repository is authored by the copyright holder, and `CONTRIBUTING.md` carries an inbound licence grant for future contributions.

## Decision

1. **All source is MIT.** The root `LICENSE` carries the MIT text (identical to `LICENSE-MIT`). Every module, adapter, addon without its own grant, and both binaries (`LeafRTP-<version>.jar`, `LeafRTP-Pro-<version>.jar`) are MIT. Module-local grants that already exist (`rtp-api/LICENSE`, `rtp-core/LICENSE`, NeoForge GPL-3.0-or-later carriers per `neoforge.mods.toml`, addon-specific licences) keep governing their files.
2. **Pro sells service, not code.** Purchasing Pro buys priority/SLA support routing (ADR-100 buyer metadata), early-access release staging (ADR-100 section 2.3) and sponsorship. It grants no extra copyright licence, because MIT already grants commercial use. ADR-100's "Commercial Indemnity & Grant" bullet is withdrawn; no indemnity is offered unless a separate written support agreement says so.
3. **Releases already published keep the licence they shipped with.** This decision applies from the first release after 2026-10-05; MIT is strictly more permissive, so no earlier licensee loses rights.
4. **No build delta remains.** The safety tag / predicate grammar lives in `rtp-api` (`SafetyTokenParser`) and `rtp-core` (`SafetyTokenExpander`, resolving tags through `RTPServerAccessor.blockTagSnapshot()`) and ships in both jars. The lite `rtp/tags/**` exclude and its `liteJarStructureCheck` entry guarded `:tags-api`, an offline resolver neither jar depends on, so they are removed. The `**(Pro)**` CHANGELOG marker is retired for new entries; no feature shall be made Pro-exclusive.

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
- [ADR-061](ADR-061-open-core-dual-licensing.md), [ADR-024](ADR-024-rtp-lite-assembly-variant.md), [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md).
