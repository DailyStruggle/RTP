# ADR-118 - Claim Checkers Compile Against Published Plugin APIs; Reflection Only Where None Exists

**Status:** Accepted (implemented 2026-10-24)
**Date:** 2026-10-19
**Extends:** [ADR-069](ADR-069-claim-integrations-extracted-to-bundled-addon.md) (claim integrations in `LeafRTPClaimAddon`).

## Context

ADR-069 places each claim-plugin API as `compileOnly` in `addons/LeafRTPClaimAddon/build.gradle`, but about half of the checkers (`MinePlots`, `UltimateClaims`, `Residence`, `CrashClaim`, `HuskClaims`, `HuskTowns`, `PlotSquared`, `KingdomsX`, `SaberFactions`, `FactionsBridge`) resolved classes and methods by name with reflection. A name that matched nothing was not an error: the checker answered "not claimed".

`MinePlotsChecker` in 3.3.0 showed the cost. It probed four guessed main classes, looked the plugin up as `MinePlots` (the real name is `minePlots`), and, once it found the real service, took the zero-argument `getPlots()` (a `List`) as a plot manager and returned false for every location. With `rerollMinePlots: true`, players could be sent into plots (REQ-RTP-S-003) and nothing was logged (REQ-RTP-S-004). Its tests called methods on the test's own mock classes and never ran the checker, so the build stayed green. A 3.3.0 user review found it by reading the class-name list.

minePlots publishes `pl.minecodes.plots:plugin-api` on a public Maven repository. Compiling against it would have failed the build on the first wrong name.

## Decision

1. **Published API: compile against it.** When a claim plugin publishes an API artifact, its checker shall reference the API types directly, with the artifact as `compileOnly` and `testImplementation` in `LeafRTPClaimAddon/build.gradle`. Third-party repositories are scoped with `content { includeGroup ... }`. The artifact shall not be shaded into the addon jar.
2. **No published API: reflection with a shape test.** A checker may stay reflective only when no API artifact is published. It shall then have a test that runs the checker itself (not the test's own mocks) against stubs reproducing the real class names, package, plugin name and method signatures, including overloads or zero-argument methods a name-based probe could pick by mistake. The stub's source (jar version or docs URL) is cited in the test.
3. **Plugin names are exact.** `PLUGIN_NAME` constants match the plugin's `plugin.yml` `name`, case-sensitive.
4. **Fail closed while the plugin is enabled.** Plugin absent or disabled: not claimed. Plugin enabled but its service or API cannot be resolved, or the call throws: the location is rejected (REQ-RTP-S-003) through `ClaimCheckFailure`, logged once per integration for API mismatches and throttled for per-call errors (REQ-RTP-S-004). An operator who wants teleports to resume turns the integration's `integrations.yml` flag off.
5. **Compatibility docs say what was verified.** `docs/admin/CLAIM_PLUGIN_COMPATIBILITY.md` states the binding (`Direct API` or `Reflective`) and whether the checker has run on a live server with the plugin; checkers tested only against the API or stubs are marked best effort.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep reflection for every checker (no compile-time dependency) | Wrong names fail open and silently; this is the defect class that shipped in 3.3.0. `compileOnly` adds no runtime dependency, so reflection buys nothing when an API is published. |
| Reflection plus better tests only | Tests catch drift only as far as the stub matches reality; hand-copied stubs reproduced a wrong package name during the minePlots fix. Compiling against the artifact removes that step. |
| Disable the integration on an API mismatch (fail open with a SEVERE log) | Lets teleports into claims continue after one log line, contrary to REQ-RTP-S-003. Rejecting until the operator acts keeps the safety rule and makes the problem visible in play. |
| Shade the claim APIs into the addon | Classes would clash with the installed plugin's own copies and grow the jar; the plugin supplies them at runtime. |

## Consequences

- **Positive:**
  - A wrong class, package or method name fails the build instead of passing silently on a live server.
  - Each reflective checker that remains has a test exercising its real call path.
  - Operators see a rejected-destination log instead of players landing in claims.
- **Negative / Trade-offs:**
  - Each published API adds a `compileOnly` coordinate and possibly a repository to `build.gradle`; a repository outage breaks clean builds until the dependency cache is warm.
  - Some API artifacts declare a newer JVM than the addon's Java 17 target in their Gradle module metadata (`plugin-api` 4.6.2 pins `org.gradle.jvm.version=21`). Such repositories resolve from the POM (`metadataSources { mavenPom(); artifact() }`). This is safe only because the API classes are linked solely when that plugin, which itself requires that JVM, is enabled; checkers shall not touch the API types before the enabled check.
  - Fail-closed on mismatch means a claim-plugin update that breaks its API stops RTP from finding destinations in that world until the addon is updated or the flag is turned off.
  - Compiling against an API does not prove runtime behaviour (service registration, thread affinity); live-server verification stays a separate, documented step.

## References

- `addons/LeafRTPClaimAddon/build.gradle` (mineCodes and William278 repositories, `pl.minecodes.plots:plugin-api:4.6.2`, `com.intellectualsites.plotsquared:plotsquared-core:7.6.0`, and `shadedClaimPlugins` extraction for `huskclaims-bukkit:1.5.10`, `husktowns-bukkit:3.1.4`, `Residence:6.0.2.3`, and `Saber-Factions:4.1.4-STABLE`).
- `addons/LeafRTPClaimAddon/.../MinePlotsChecker.java`, `HuskClaimsChecker.java`, `HuskTownsChecker.java`, `PlotSquaredChecker.java`, `ResidenceChecker.java`, `SaberFactionsChecker.java`, `ClaimCheckFailure.java`, `ClaimIntegrations.java`.
- `MinePlotsCheckerS003Test`, `DirectApiClaimCheckersS003Test`, `ReflectiveClaimCheckerApiShapeS003Test` (REQ-RTP-S-003 regression; see `docs/dev/TRACEABILITY.md`).
- `docs/admin/CLAIM_PLUGIN_COMPATIBILITY.md`; `docs/dev/EXTERNAL_HOOKS.md` (ADR-026 verifier surface).
- ADR-026 (external hook API surface), ADR-069 (claim addon), ADR-113 (Java 17 baseline).
