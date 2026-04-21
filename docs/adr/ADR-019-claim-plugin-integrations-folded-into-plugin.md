# ADR-019 — Claim-Plugin Integrations Folded Into `rtp-plugin`; Example Addon Retained

**Status:** Accepted
**Date:** 2026-04-20
**Supersedes (partially):** ADR-013 ("Addons as External Gradle Projects") — only for the specific case of the claim-plugin integrations.

## Context

`addons/RTP_ClaimPluginIntegrations` shipped eight static checkers (Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard) that each registered a `GlobalRegionVerifier` to honour REQ-RTP-S-003 ("no teleport into protected territory"). ADR-013 codified the decision to keep addons as separate Gradle sub-projects so RTP would not grow runtime dependencies on third-party plugins.

Three issues forced a revisit:

1. **Discoverability.** Operators repeatedly asked why `/rtp` teleported into claims; the answer "install a second jar" is easy to miss, and the integration is a quality-of-service expectation on Bukkit-family servers.
2. **Stale addon.** The addon's `RTPClaimPluginIntegrations#onEnable` never actually invoked `setupIntegrations()`, so the verifiers were silently inert — a latent bug that the addon-as-separate-artifact boundary made harder to detect in CI.
3. **Example gap.** `addons/` was meant to teach third-party authors the API surface, but two of the three examples (`RTP_ClaimPluginIntegrations`, `RTP_Iris_integration`) couple tightly to specific third-party APIs and obscure the common interfaces (`ConfigParser`, `GlobalRegionVerifiers`, Bukkit events, `Configs.onReload`).

## Decision

1. **Fold the claim-plugin checkers into `rtp-plugin`.** The eight `*Checker` classes, the `IntegrationsKeys` enum, and `integrations.yml` move to package `io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims` in the `rtp-plugin` module. A new orchestrator, `ClaimIntegrations`, is invoked from `RTPBukkitPlugin#setupIntegrations` (the same seam that wires `VaultChecker`). Registration remains **at server startup only**; no claim-plugin call is ever inlined into a command or a teleport pipeline stage, preserving the "common wrong move" guardrail for REQ-RTP-S-003.
2. **Introduce `addons/RTP_ExampleAddon`.** A minimal, well-documented addon that exercises the four most common API touch-points (typed `ConfigParser`, `GlobalRegionVerifiers` predicate, Bukkit event listener, `Configs.onReload` hook) and ships with a walkthrough `README.md`. It replaces `RTP_ClaimPluginIntegrations` as the canonical "how to write an addon" example.
3. **Keep `RTP_Glide` and `RTP_Iris_integration`** unchanged. `RTP_Iris_integration` is marked for removal in a future release once internal dependency-lite handling lands, per the current development focus.

The claim-plugin third-party libs remain `compileOnly` in `rtp-plugin/build.gradle`; plugin.yml `softdepend` is extended to include them so Bukkit's classloader isolates them at runtime. REQ-RTP-S-003's behaviour is therefore unchanged — the verifiers still live behind `GlobalRegionVerifiers` and are gated per-plugin by `Bukkit.getPluginManager().isPluginEnabled(...)`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Leave the addon as-is and fix the latent bug in place | Operators continue to need a second jar for a feature almost every Bukkit server expects; the addon surface was also drifting and had no regression test in CI. |
| Fold-in without retaining any example addon | Leaves no practical template for third-party authors; the `addons/` directory was historically that template. |
| Inline claim calls directly into the teleport pipeline / commands | Explicitly forbidden by the S-003 "common wrong move" note in `AGENTS.md`. The chosen design preserves `GlobalRegionVerifiers` as the single seam. |
| Promote `RTP_Glide` to the example role | `RTP_Glide` is a user-facing gameplay feature, not a teaching example; conflating the two muddies documentation. Keeping it separate preserves both roles. |

## Consequences

- **Positive:**
  - Claim-plugin integration works out of the box on every Bukkit-family server with no extra install step.
  - The latent "onEnable registers nothing" bug is eliminated because registration is now covered by the same startup path as Vault.
  - `addons/RTP_ExampleAddon` gives third-party authors a compact, purpose-built teaching example with a `README.md` walkthrough and a safety checklist.
  - `compileOnly` classpath remains the same for downstream builds; no new runtime dependencies.
- **Negative / Trade-offs:**
  - `rtp-plugin/build.gradle` gains ~10 `compileOnly` soft-dep lines and five additional Maven repositories. Build time is essentially unchanged because these are compile-only.
  - ADR-013's "all addons are external" principle now has one documented exception (this ADR). The principle still applies to gameplay addons and third-party integrations; it no longer applies to officially-supported safety integrations.
  - Removing the addon is a breaking change for anyone who had the standalone `RTP_ClaimPluginIntegrations` jar installed. Mitigation: plugin.yml now advertises the soft-depends directly, so removing the old jar is the only action required.

## References

- `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/tools/softdepends/claims/ClaimIntegrations.java`
- `rtp-plugin/src/main/resources/integrations.yml`
- `addons/RTP_ExampleAddon/README.md`
- ADR-013 (addons as external Gradle projects) — partially superseded by this ADR for safety-critical integrations.
- REQ-RTP-S-003, REQ-RTP-F-011 (see `docs/dev/TRACEABILITY.md`).
