# ADR-117 - Release Cadence: Contractual Pro Patches, Discretionary Lite Releases, One Version Line

**Status:** Accepted
**Date:** 2026-10-18
**Amends:** [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md) section 2.3 ("Release Staging"), [ADR-108](ADR-108-all-mit-licensing-pro-as-support.md) decision 2 ("Pro sells service, not code")

## Context

ADR-100 section 2.3 staged releases by edition: Pro marketplaces get "continuous production and early-access builds", and the free channels (Modrinth, Hangar, GitHub Releases) get "stable milestone builds ... 1-2 weeks post-commercial release". ADR-108 decision 2 kept "early-access release staging" in the list of things Pro buys. Both were carried over from the open-core split (ADR-024, ADR-061) and were not re-checked when ADR-108 made Pro a service, not code.

The staging contradicts that model:

1. **It sells code by time.** A holding period makes the newest code Pro-only for that period, which ADR-108 decision 4 forbids for features ("no feature shall be made Pro-exclusive").
2. **It withholds nothing.** The source is public on `V3` under MIT; anyone can build either jar. A delay only keeps known-bad builds on the official free channels (e.g. the 3.3.0 cooldown and portal fixes would have waited a week on Modrinth).
3. **It costs maintenance.** A separate `liteVersion` property, version-scheme workarounds (four-part or two-part Lite numbers), roll-up release notes and tag collisions all exist only to keep the editions apart.

The actual difference between the editions is obligation, as with Red Hat Enterprise Linux and Fedora: a paying Pro customer with a real problem is owed a fix under the support terms; a Lite user is not owed anything, and Lite releases happen because the maintainer wants to publish them.

## Decision

1. **One version line.** Both jars are built from the same commit with the same `version` (`gradle.properties`); `liteVersion` is removed. Every version number names exactly one commit.
2. **Pro releases are contractual.** Pro (`release-bbb.yml`, BuiltByBit) receives every release, including patch releases cut to resolve a paying customer's reported problem. Shipping such a patch is part of the support obligation (ADR-100 section 2).
3. **Lite releases are discretionary.** Lite (`release.yml`: Modrinth, Hangar, GitHub Releases) ships a version when the maintainer chooses to publish it. There shall be no fixed holding period and no "settle" requirement. Lite may skip patch versions; it shall not ship a version number or commit that no Pro release has shipped.
4. **No early access as a product.** Purchasing Pro buys priority/SLA support (including patches owed under it) and sponsorship, not earlier access to code. Pre-releases (beta / RC), if published, are offered on the same terms to both editions.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep ADR-100 section 2.3 (Lite 1-2 weeks behind) | Time-limited paywall on public MIT code; leaves known bugs on the free channels; contradicts ADR-108. |
| Four-part Pro sub-patches (`3.3.0.1`) rolled up into Lite | Works with the tooling, but needs a second version line, roll-up release notes and tag handling to model a delay this ADR removes. |
| Lite on minor lines only (`3.3` when Pro moves to `3.4.0`) | Same cost as above; Lite users wait for a whole minor line for fixes. |
| Opt-in early-access pre-releases for Pro only | Still sells code by time; pre-releases are open to both editions instead. |

## Consequences

- **Positive:**
  - The free listings never wait on a fixed timer for fixes that are already public on `V3`.
  - One version, one tag, one changelog section per release; `release.yml` and `release-bbb.yml` read the same `version`.
  - Pro's value is stated as what is actually guaranteed: response and fixes for the buyer's problem.
- **Negative / Trade-offs:**
  - Lite can lag Pro when the maintainer does not publish a patch to the free channels; that lag is a choice, not a policy, and the code is public either way.
  - Both workflows still publish under `v<version>`; `release.yml` renames the shared GitHub Release to "rtp-lite <version>" when Lite ships that version.

## References

- `.github/workflows/release.yml`, `.github/workflows/release-bbb.yml`, `gradle.properties`.
- [`docs/dev/PUBLISHING.md`](../dev/PUBLISHING.md) - release procedure.
- [`docs/dev/LICENSING.md`](../dev/LICENSING.md) - edition comparison.
- [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md), [ADR-108](ADR-108-all-mit-licensing-pro-as-support.md).
