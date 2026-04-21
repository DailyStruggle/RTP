# ADR-019 — Claim-Plugin Integrations Folded Into `rtp-plugin`; Example Addon Retained

**Status:** Accepted
**Date:** 2026-04-20
**Supersedes (partially):** ADR-013 ("Addons as External Gradle Projects") — only for the specific case of the claim-plugin integrations.

## Context

Eight claim-plugin checkers (Factions, GriefDefender, GriefPrevention, HuskTowns, Lands, RedProtect, TownyAdvanced, WorldGuard) enforce REQ-RTP-S-003 ("no teleport into protected territory") via `GlobalRegionVerifier`. ADR-013 keeps most addons as separate Gradle sub-projects so RTP does not grow runtime dependencies on third-party plugins.

Two forces push the claim-plugin checkers in the opposite direction:

1. **Discoverability.** Claim-aware teleport is a quality-of-service expectation on Bukkit-family servers; requiring a second jar install obscures it.
2. **Example clarity.** `addons/` teaches third-party authors the API surface. An integration addon that couples tightly to specific third-party APIs obscures the common interfaces (`ConfigParser`, `GlobalRegionVerifiers`, Bukkit events, `Configs.onReload`).

## Decision

1. **Claim-plugin checkers live in `rtp-plugin`.** The eight `*Checker` classes, the `IntegrationsKeys` enum, and `integrations.yml` reside in package `io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims` in the `rtp-plugin` module. The `ClaimIntegrations` orchestrator is invoked from `RTPBukkitPlugin#setupIntegrations` (the same seam that wires `VaultChecker`). Registration shall occur **at server startup only**; no claim-plugin call shall be inlined into a command or a teleport pipeline stage, preserving the "common wrong move" guardrail for REQ-RTP-S-003.
2. **`addons/RTP_ExampleAddon` is the canonical teaching example.** A minimal, documented addon that exercises the four most common API touch-points (typed `ConfigParser`, `GlobalRegionVerifiers` predicate, Bukkit event listener, `Configs.onReload` hook), with a walkthrough `README.md`.
3. **`RTP_Glide` and `RTP_Iris_integration` remain as external addons.** `RTP_Iris_integration` is scheduled for removal once internal dependency-lite handling lands, per the current development focus.

The claim-plugin third-party libs remain `compileOnly` in `rtp-plugin/build.gradle`; plugin.yml `softdepend` is extended to include them so Bukkit's classloader isolates them at runtime. REQ-RTP-S-003's behaviour is therefore unchanged — the verifiers still live behind `GlobalRegionVerifiers` and are gated per-plugin by `Bukkit.getPluginManager().isPluginEnabled(...)`.

## Consequences

- **Positive:**
  - Claim-plugin integration works out of the box on every Bukkit-family server with no extra install step.
  - Registration is covered by the same startup path as Vault, so there is no separate enable hook that can silently no-op.
  - `addons/RTP_ExampleAddon` gives third-party authors a compact, purpose-built teaching example with a `README.md` walkthrough and a safety checklist.
  - `compileOnly` classpath remains the same for downstream builds; no new runtime dependencies.
- **Negative / Trade-offs:**
  - `rtp-plugin/build.gradle` carries ~10 `compileOnly` soft-dep lines and five additional Maven repositories. Build time is essentially unchanged because these are compile-only.
  - ADR-013's "all addons are external" principle has one documented exception (this ADR). The principle still applies to gameplay addons and third-party integrations; it does not apply to officially-supported safety integrations.
  - Operators migrating from a standalone `RTP_ClaimPluginIntegrations` jar shall remove that jar; plugin.yml advertises the soft-depends directly, so no further action is required.

## References

- `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/tools/softdepends/claims/ClaimIntegrations.java`
- `rtp-plugin/src/main/resources/integrations.yml`
- `addons/RTP_ExampleAddon/README.md`
- ADR-013 (addons as external Gradle projects) — partially superseded by this ADR for safety-critical integrations.
- REQ-RTP-S-003, REQ-RTP-F-011 (see `docs/dev/TRACEABILITY.md`).
