# ADR-061 — Open-Core Dual Licensing

**Status:** Accepted
**Date:** 2026-06-02

## Context

The repository historically shipped a single root `LICENSE` (PolyForm
Noncommercial 1.0.0) governing every file in the tree. That single grant covers
both the freely reusable foundation (`rtp-api`, `rtp-core`) and the
differentiating Pro-only features, and it forbids all commercial use.

Two goals motivated a change:

1. Make the reusable foundation genuinely open for public use, including
   commercial use, so third-party addon authors and downstream projects can
   build on `rtp-api` / `rtp-core` without a noncommercial restriction.
2. Keep the Pro-only features (the subsystems ADR-024 lists as "Drops" from the
   lite assembly: SQL/Redis persistence, the Folia adapter, the login reserve
   cache, the multilingual bootstrap, claim-plugin integrations, and the proxy
   / network-mode modules) under the existing noncommercial terms.

The two editions are built from one source tree (ADR-024): the RTP (lite)
binary is a ShadowJar subset of the same `rtp-core` plus the Spigot/Paper
(and Fabric) adapters; it does not contain any Pro-only source. This makes a
clean open-core boundary possible without forking the codebase.

## Decision

Dual-license the repository (open-core):

- **MIT** (`LICENSE-MIT` at the repository root, mirrored as a module-local
  `LICENSE` in `rtp-api/` and `rtp-core/`): the `rtp-api` and `rtp-core`
  modules, and the **RTP (lite) binary distribution**. These may be used,
  modified, and redistributed, including commercially, under the MIT License.
- **PolyForm Noncommercial 1.0.0** (the root `LICENSE`): all other source in
  the repository, i.e. the Pro-only features and the Pro plugin assembly.

The root `LICENSE` carries a preamble describing the split. Where a module or
file ships a more specific `LICENSE` or SPDX header, that grant governs the
file; otherwise the PolyForm Noncommercial terms apply.

### Lite binary license

The lite assembly (ADR-024) bundles its in-jar `LICENSE` from `LICENSE-MIT`
(renamed to `LICENSE` in the jar), not from the root PolyForm `LICENSE`. The
`liteJarStructureCheck` Gradle audit asserts the lite jar's `LICENSE` entry
contains the MIT text and does not contain the PolyForm Noncommercial text, so
a regression that re-bundles the wrong license fails the build.

The Pro (`shadowJar` / `remapJar`) assembly continues to ship under the root
PolyForm `LICENSE`.

## Relationship to ADR-024

ADR-024 (RTP-lite Assembly Variant) is the structural prerequisite: because the
lite jar already contains only the MIT-eligible subset (`rtp-core` + adapters,
no Pro-only source), licensing the lite binary under MIT does not leak any
Pro-only code. This ADR is additive to ADR-024 and does not change which
classes/resources ship in either edition.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep a single PolyForm Noncommercial license for everything | Blocks the stated goal of letting addon authors and downstream projects reuse `rtp-api` / `rtp-core` commercially. |
| License the two binaries differently but leave all source under one license | Incoherent: MIT and PolyForm are one-directionally incompatible, so a shared file offered under both lets a consumer pick MIT and ignore PolyForm. A per-module/per-file boundary is required. |
| Split the Pro-only code into a separate private repository | Largest effort; ADR-024 already isolates Pro-only code into distinct classes/source sets, so a per-module license boundary achieves the goal without a repo split. |

## Consequences

- **Positive.**
  - `rtp-api`, `rtp-core`, and the lite binary are reusable under MIT, including
    commercially.
  - The lite jar ships the correct (MIT) license, enforced by a build-time
    audit.
  - The Pro-only differentiators remain noncommercial.
- **Negative / Trade-offs.**
  - The licensing boundary must be maintained as code moves between modules:
    code that migrates into `rtp-api` / `rtp-core` becomes MIT, and Pro-only
    code must not be moved into those modules without intending to relicense it.
  - Re-licensing the shared core to MIT assumes the copyright holder controls
    those files; any third-party-copyright contribution to `rtp-api` /
    `rtp-core` would need contributor sign-off.

## References

- `LICENSE` — root PolyForm Noncommercial license with the dual-license preamble.
- `LICENSE-MIT` — MIT license text bundled into the lite binary.
- `rtp-api/LICENSE`, `rtp-core/LICENSE` — module-local MIT grants.
- `rtp-plugin/build.gradle` — `shadowLiteJar` bundles `LICENSE-MIT`;
  `liteJarStructureCheck` asserts the lite jar ships the MIT license.
- ADR-024 — RTP-lite Assembly Variant (the structural prerequisite).
