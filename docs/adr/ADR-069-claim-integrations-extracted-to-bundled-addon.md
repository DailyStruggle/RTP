# ADR-069 - Claim-Plugin Integrations Extracted to the Bundled `LeafRTPClaimAddon`

**Status:** Accepted
**Date:** 2026-06-20
**Supersedes:** [ADR-019](ADR-019-claim-plugin-integrations-folded-into-plugin.md) ("Claim-Plugin Integrations Folded Into `rtp-plugin`").

## Context

ADR-019 folded the claim-plugin checkers (WorldGuard, GriefDefender, GriefPrevention, Towny Advanced, SaberFactions, FactionsBridge, Lands, RedProtect, Residence, CrashClaim, HuskClaims, KingdomsX), the `IntegrationsKeys` enum, and `integrations.yml` directly into `rtp-plugin`. That delivered out-of-the-box claim-aware teleport, but it left `rtp-plugin`:

- carrying ~10 claim-plugin `compileOnly` dependencies and five dedicated soft-dep Maven repositories in `build.gradle`;
- coupling a Bukkit-only concern into the multi-loader plugin module (the checkers are all `org.bukkit.*`-based and meaningless on Fabric / NeoForge);
- mixing the claim config (`integrations.yml` plus its full locale tree) into the core plugin's resource and locale-parity surface.

Meanwhile the project gained a bundling mechanism (`LeafRTPGuiAddon`): an addon jar is carried whole inside the RTP jar under `bundled-addons/`, listed in `bundled-addons/index`, and self-extracted into `<pluginDir>/addons/` on first run by `AddonRegistry#extractBundledAddons`. This gives an external addon the same "works out of the box" property ADR-019 wanted, without the addon's code or dependencies living in core.

## Decision

1. **The claim checkers move to a standalone `addons/LeafRTPClaimAddon` module.** It is a single Bukkit-only module: the `RTPClaimAddon` `RTPAddon` entry point (discovered via `META-INF/services`), the `ClaimIntegrations` orchestrator, the `IntegrationsKeys` enum, one `*Checker` per plugin, and the addon's own `integrations.yml`. The claim-plugin APIs are `compileOnly` in the addon's `build.gradle`, not in `rtp-plugin`.
2. **Registration behaviour is unchanged.** Verifiers register once at addon load (and re-register on reload) through the public hook facade (ADR-026); they are never inlined into a command or teleport-pipeline stage. Each checker is gated on both its `integrations.yml` flag and the host plugin being enabled. REQ-RTP-S-003 is preserved.
3. **Self-registration is platform-gated through the general API.** `RTPClaimAddon` queries `RTP.serverAccessor.getPlatformFamily()` and is a no-op unless the family is `BUKKIT`, so the addon never touches a non-Bukkit runtime. The gate uses the typed `PlatformFamily` rather than a string allowlist or an in-addon platform probe.
4. **The addon ships in the jar and self-extracts.** `LeafRTPClaimAddon.jar` is added to `bundled-addons/index` and injected (whole, post-remap) into both the Pro (`remapJar`) and lite (`remapLiteJar`) artifacts, alongside `LeafRTPGuiAddon.jar`. On first run RTP extracts it into `<pluginDir>/addons/`. This keeps ADR-019's out-of-the-box property for both editions, including rtp-lite (ADR-024), which previously called `ClaimIntegrations.setup` directly.
5. **`rtp-plugin` drops the claim surface.** The `softdepends/claims` package, the baseline `integrations.yml` and its locale mirrors, the claim `compileOnly` dependencies, the dedicated soft-dep repos, and both `ClaimIntegrations.setup(...)` call sites are removed.

## Consequences

- **Positive:**
  - Claim-aware teleport still works out of the box on every Bukkit-family server (and on lite), via the bundled-addon extraction path.
  - `rtp-plugin/build.gradle` sheds ~10 `compileOnly` claim deps and the dedicated claim Maven repos; the Bukkit-only claim concern leaves the multi-loader module.
  - The addon can ship and version on its own cadence (mirroring `LeafRTPGuiAddon` / `LeafRTPCountdownAddon`), and is an opt-out by deleting the extracted jar.
  - The claim config and its locale tree leave the core plugin's locale-parity surface.
- **Negative / Trade-offs:**
  - The claim config (`integrations.yml`) now ships only in English from the addon jar; the former per-locale `integrations.yml` translations in `rtp-plugin` are dropped. Re-localizing the addon's config is deferred follow-up work on the addon module.
  - Operators who deleted the extracted GUI addon to opt out must apply the same opt-out to the claim addon jar if undesired (deleting it after extraction is a permanent opt-out).

## References

- `addons/LeafRTPClaimAddon/` (module: `RTPClaimAddon`, `ClaimIntegrations`, `IntegrationsKeys`, `*Checker`, `integrations.yml`).
- `rtp-plugin/src/main/resources/bundled-addons/index` and the `injectBundledAddonJar` closure in `rtp-plugin/build.gradle`.
- `rtp-core/.../addon/AddonRegistry#extractBundledAddons`.
- ADR-019 (superseded), ADR-024 (rtp-lite assembly), ADR-026 (external hook API surface).
- REQ-RTP-S-003 (see `docs/dev/TRACEABILITY.md`).
