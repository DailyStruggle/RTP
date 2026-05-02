# ADR-026 — Unified External-Hook API Surface (`rtp-api/hooks`)

**Status:** Accepted
**Date:** 2026-05-01
**Relates to:** ADR-019 (claim integrations folded into plugin), ADR-013 (addons as external Gradle projects), REQ-API-F-001…F-003, REQ-RTP-S-003, REQ-RTP-S-006.

## Context

RTP exposes several seams where third-party plugins or bundled soft-depend integrations modify its behavior:

1. **Region/location verifiers** — `GlobalRegionVerifiers.addGlobalRegionVerifier(...)` (rtp-core), used by every claim-plugin checker (Factions, GriefDefender, GriefPrevention, Lands, HuskTowns, RedProtect, TownyAdvanced, WorldGuard) and by example/addon code.
2. **Economy** — `RTP.economy` field bound by `VaultChecker` from `rtp-plugin`.
3. **Placeholders** — `PAPI_expansion` (PlaceholderAPI) attaches resolvers to RTP state.
4. **World border** — `ChunkyBorderChecker` consulted by shape/border code.
5. **Anvil pre-filter cache** — reflectively discovered via `Class.forName("io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache")` in `ScanTask`.
6. **Effects** — `effects-api` extension points for particle/potion side effects (covered separately, see *EXTERNAL_HOOKS.md → Effects* and the `effects-api` module docs).

Today, integrations couple directly to symbols in `rtp-core` (`GlobalRegionVerifiers`, `RTP.economy`) or rely on reflection (`AnvilRegionByteCache`). This violates the architecture rule that addons depend on `rtp-api` only (see `ARCHITECTURE.md` and ADR-013), and it leaves the surface undocumented for third-party authors.

## Decision

1. **Add a single API package `io.github.dailystruggle.rtp.api.hooks` in `rtp-api`** containing a thin facade `RTPHooks` exposed via static accessors on `RTPAPI` (`RTPAPI.hooks()`). The facade exposes register/unregister/list operations for each behavior-modification point listed above (except effects, which retain their own module). New seams in this ADR:
   - `RegionVerifierRegistry` (sync + async predicates over `RTPCoords`).
   - `EconomyProviderRegistry` (single-binding, `RTPEconomy`).
   - `PlaceholderProviderRegistry` (named string resolvers `(player, key) → String`).
   - `WorldBorderProviderRegistry` (predicate `(world, x, z) → boolean inside`).
   - `AnvilPrefilterRegistry` (single-binding SPI replacing the reflective lookup in `ScanTask`).
2. **Backed by volatile delegate fields populated by `rtp-core`** during `onEnable`, matching the established `RTPAPI` pattern (`shapeAdder`, `vertAdder`, `biomeProvider`). Calling any registry method before core is loaded throws `IllegalStateException` (REQ-RTP-S-006); never silently no-ops.
3. **Backward compatibility shall be preserved.** `GlobalRegionVerifiers` retains its public static methods; they delegate into the new registry. Addons compiled against the old API continue to link and run unchanged. `RTP.economy` continues to be the read path inside `rtp-core`; the new `EconomyProviderRegistry` is the *write* path that integrations shall use going forward.
4. **`rtp-plugin` soft-depend checkers shall be refactored** to register through `RTPHooks` instead of calling `rtp-core` symbols. Addons under `addons/` (notably `RTP_ExampleAddon`) shall be updated to demonstrate the new API.
5. **Reflective lookup of `AnvilRegionByteCache` in `ScanTask` shall be replaced** with a registry call. The `rtp-anvil` module registers itself via `RTPHooks.anvilPrefilter().bind(...)` during its initialisation. The reflective code remains as a fallback for one release cycle, gated on registry not being bound, then removed.
6. **A single canonical document `docs/dev/EXTERNAL_HOOKS.md`** shall list every hook (file, API symbol, target plugin, behavior modified, threading rule, fallback), and shall be linked from `docs/dev/INDEX.md` and `.junie/AGENTS.md` *Required Reading*.

## Consequences

- **Positive:**
  - Third-party integrations have a stable, discoverable, `rtp-api`-only surface — no `rtp-core` imports required.
  - Reflection in `ScanTask` (anvil discovery) is replaced by a typed SPI; failures become loud rather than silent.
  - Documentation invariant: every reflection/hook site in the codebase has a row in `EXTERNAL_HOOKS.md`. New hooks shall be added there as part of the same change.
  - Test coverage gains: each registry has a `ReqApi*HookTest` verifying registration, removal, ordering, and "absent target plugin" fallback.
- **Negative / Trade-offs:**
  - New public API surface in `rtp-api` is semver-locked. Mitigation: all registries accept functional interfaces only; no implementation classes leak through the API.
  - Two paths exist for region verifiers (legacy static + new registry) until the next major release. The legacy path is documented as `@deprecated` only after at least one release in which both work; this ADR does not deprecate it.
  - `effects-api` is intentionally out of scope here; its hook surface is documented separately to avoid coupling two evolving subsystems in one ADR.

## References

- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/hooks/` (new)
- `rtp-api/src/main/java/io/github/dailystruggle/rtp/api/RTPAPI.java` (extended with `hooks()`)
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/selection/region/GlobalRegionVerifiers.java`
- `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/tools/softdepends/`
- ADR-019 (claim-plugin integrations folded into plugin)
- ADR-013 (addons as external Gradle projects)
- `docs/dev/EXTERNAL_HOOKS.md` (new)
- REQ-API-F-001/F-002/F-003, REQ-RTP-S-003, REQ-RTP-S-006 — see `docs/dev/TRACEABILITY.md`.
