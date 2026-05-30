# ADR-051 - Two-Tier API Model: Thin `rtp-api` Contract + `rtp-core` Extension API

**Status:** Accepted
**Date:** 2026-05-29

## Context

Developer-UX work (improving the experience of plugin authors who build against RTP) surfaced a recurring request: a developer should be able to derive a new shape from an existing one, the same way the project itself derives `Polygon` and the other built-ins from `MemoryShape` / `Shape`. The natural assumption was that this belongs in `rtp-api`, the module addon authors are told to compile against.

Investigation showed that the extensible selection types cannot be reduced to a thin contract:

- `Shape<E>` and `VerticalAdjustor<E>` both `extend FactoryValue<E>`.
- `FactoryValue` couples to the configuration substrate (`RtpYamlConfig`) and to `RTP` for logging and plugin-directory access.
- `MemoryShape` (~1200 lines) is bound to file/DB save-load, biome bookkeeping, and the `Factory` registry, which in turn depends on `ConfigParser` (the whole config subsystem).

Forcing these into `rtp-api` would drag `Factory`, `ConfigParser`, and the configuration subsystem upward until `rtp-api` became `rtp-core` under a different name, violating REQ-API-NF-002 ("API interfaces shall not expose internal implementation specifics of `rtp-core`").

The key realization: there are two distinct kinds of "API" that had been conflated.

1. A **contract API** - thin, stable, semver-pinned, dependency-light, publishable - for the common case (do an RTP, read hooks, by-world queries). This is `rtp-api`.
2. An **implementation/extension API** - the platform-independent engine itself, with no Bukkit/Fabric imports - for authors who need to subclass the real, heavyweight base classes. This is `rtp-core`.

Deriving a new shape is fundamentally an implementation-tier (`rtp-core`) extension, not a contract-tier one. `rtp-core` already exposes the typed, type-safe registration entry points `RTP.addShape(Shape<?>)` and `RTP.addVerticalAdjustor(VerticalAdjustor<?>)`; the missing piece was recognizing and documenting `rtp-core` as a first-class extension surface, and removing the untyped `Object`-based shim that pretended `rtp-api` owned shape registration.

## Decision

1. **Adopt an explicit two-tier API model.**
   - `rtp-api` is the thin contract surface: teleport (`RTPAPI.teleport`), hooks (`RTPAPI.hooks()`), by-world queries, shared platform-agnostic models. It stays dependency-light and is the publish target for the common consumer.
   - `rtp-core` is the implementation-extension API: authors who derive a custom `Shape` / `VerticalAdjustor` compile against `rtp-core` and subclass the concrete base classes (`Shape`, `MemoryShape`, `VerticalAdjustor`), registering them through the typed `RTP.addShape(Shape)` / `RTP.addVerticalAdjustor(VerticalAdjustor)`.

2. **Remove the untyped registration shim from `rtp-api`.** `RTPAPI.addShape(Object)`, `RTPAPI.addVerticalAdjustor(Object)`, and their backing `shapeAdder` / `vertAdder` delegate fields are deleted. They were untyped (no compile-time guidance) and only existed because `rtp-api` cannot see the `Shape` type without a dependency cycle. Internal/built-in registrations (`RTP` static init, `ChunkyChecker`, `ChunkyRTPShape`, `ChunkyBorderChecker`) now call the typed `RTP.addShape(...)` directly. The artifact is unpublished (no `maven-publish` on `rtp-api` yet), so this is a clean, non-deprecated removal.

3. **Extract `yaml-api`.** The in-house, zero-dependency YAML substrate (ADR-025), previously in `rtp-core`'s `io.github.dailystruggle.rtp.common.configuration.yaml` package, is extracted into a new pure-Java `yaml-api` module. The package name is preserved verbatim so no import site across the monorepo changes. This decouples a genuinely reusable parser and is a prerequisite for any future lift of `FactoryValue` into a higher tier.

4. **`rtp-api` may depend on sibling APIs.** `rtp-api` now depends (`api`) on `commands-api` and `yaml-api`. Both are pure-Java and depend on nothing else in the project graph, so the dependency DAG stays acyclic: `commands-api`, `yaml-api` <- `rtp-api` <- `rtp-core`.

5. **Requirements updated.** REQ-API-F-001 / REQ-API-F-002 are reworded so that custom shape / vertical-adjustor registration is an implementation-extension-tier (`rtp-core`) capability, consistent with REQ-API-NF-002. The contract surface (`rtp-api`) no longer claims to own shape registration.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Thin marker interfaces (`RTPShape`/`RTPVerticalAdjustor`) in `rtp-api`, core types `implements` them | Gives compile-time typing but does **not** let an author *derive* a new shape from an existing one (the actual request) - they would only see the marker's tiny surface, not `MemoryShape`'s reusable machinery. Rejected by the project owner. |
| Physically move `FactoryValue` + `Shape` + `VerticalAdjustor` + `MemoryShape` + concrete shapes into `rtp-api` | Requires also lifting `Factory` + `ConfigParser` + the configuration subsystem (transitive coupling), which inverts the architecture and makes `rtp-api` indistinguishable from `rtp-core`. Violates REQ-API-NF-002. |
| Keep the untyped `RTPAPI.addShape(Object)` shim as a deprecated convenience | Preserves the exact untyped delegate the developer-UX work set out to eliminate; the typed `RTP.addShape(Shape)` already exists and is strictly better. Artifact is unpublished, so no compatibility cost to removing now. |

## Consequences

- **Positive:**
  - Authors can derive a new shape (e.g. `extends MemoryShape`) with full type safety, exactly like the built-in `Polygon`, by compiling against `rtp-core` - the platform-independent engine has no Bukkit/Fabric imports, so this is safe.
  - The untyped `Object` registration shim is gone; the only registration path is the typed `RTP.addShape(Shape)` / `RTP.addVerticalAdjustor(VerticalAdjustor)`.
  - `yaml-api` is a clean, reusable, zero-dependency module; `rtp-api` no longer (transitively) pretends to own the config substrate.
  - The contract vs extension distinction is documented, so future "should this go in rtp-api?" questions have a decision rule: contract = `rtp-api`, heavyweight extension = `rtp-core`.

- **Negative / Trade-offs:**
  - Shape/vert authors take a dependency on `rtp-core`, which is heavier and changes more often than `rtp-api`. This is inherent to the task (the base classes *are* heavy) and is now an explicit, documented choice rather than an accident.
  - `rtp-api` -> `commands-api` is a new (additive) dependency edge. It is acyclic and `commands-api` is platform-neutral, but it does widen the `rtp-api` transitive surface; consumers who only want the contract now also resolve `commands-api` + `yaml-api`.
  - Publishing `rtp-core` as a consumable extension artifact (with a documented compatibility posture) is follow-up work, not landed here.

## References

- `rtp-api/REQUIREMENTS.md` - REQ-API-F-001 / F-002 (reworded), REQ-API-NF-002 (decoupling).
- [ADR-025] - the in-house zero-dependency YAML substrate now hosted by `yaml-api`.
- [ADR-026](ADR-026-external-hook-api-surface.md) - the behavior-modification hook facade (`RTPHooks`), which remains the contract-tier extension seam.
- `docs/dev/EXTERNAL_HOOKS.md`, `docs/dev/DESIGN.md` (rtp-api Implementation Notes), `docs/admin/HAZARDS.md` (H-009), `docs/admin/RUNBOOK.md` - updated to the typed `RTP.addShape` / `RTP.addVerticalAdjustor` entry points.
- `RTP.addShape(Shape)` / `RTP.addVerticalAdjustor(VerticalAdjustor)` in `rtp-core/.../common/RTP.java` - the extension-tier registration entry points.
- Phase 1 of the same developer-UX initiative: `RTPAPI.teleport(...)` first-class contract-tier entry point.
