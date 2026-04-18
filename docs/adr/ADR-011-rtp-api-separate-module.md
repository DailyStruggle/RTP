# ADR-011 — `rtp-api` as a Separately Published Addon Interface

**Status:** Accepted  
**Date:** 2026-04-15

---

## Context

RTP is designed to be extensible: claim plugins, economy systems, custom shapes, and other integrations are expected to hook into the plugin at runtime. Two approaches exist for providing that hook surface:

1. **Expose `rtp-core` directly** — addon developers compile against the full core module, giving them access to all implementation classes.
2. **Publish a dedicated `rtp-api` module** — a curated, stable interface module that addon developers compile against, with `rtp-core` internals kept separate.

Early in the project's history, there was miscommunication with external developers about which classes and reflection-based utilities were safe to call, how to access internal state, and what the supported integration path was. This created support burden and fragile addons that broke on internal refactors.

---

## Decision

All addon developers compile against **`rtp-api` only**. The `rtp-core` module is not published as a public dependency surface. `rtp-api` contains only the interfaces, enums, shared models, and event hooks that are explicitly supported and maintained across versions.

---

## Rationale

### Clear contract between core and addons
`rtp-api` provides an unambiguous signal: anything in this module is actively supported and will follow SemVer. Anything in `rtp-core` is an implementation detail — subject to change, refactor, or removal without notice. This removes the ambiguity that caused early miscommunication about which classes were safe to depend on.

### Prevents reflection-based coupling
Without a dedicated API module, addon developers were accessing internal utilities via reflection, creating invisible, undocumented dependencies. A clearly bounded `rtp-api` module gives developers an explicit, documented path and removes the incentive to reach into internals.

### Allows `rtp-core` to evolve freely
Because addon developers never compile against `rtp-core`, internal refactors — renaming classes, changing method signatures, restructuring packages — do not break addons. Only changes to `rtp-api` constitute a breaking change (and trigger a major version bump per the SemVer policy in `CONTRIBUTING.md`).

---

## Alternatives Considered

| Alternative | Reason Rejected |
|-------------|----------------|
| Expose `rtp-core` as the addon API | No distinction between supported and unsupported surfaces; every refactor is a potential breaking change for addons |
| Document "supported" classes within `rtp-core` | Documentation-only contracts are not enforced; developers still depend on undocumented internals |
| Annotations to mark public API within `rtp-core` | Adds complexity without the module-level isolation that prevents accidental dependency on internals |

---

## Consequences

- **Positive:** Addon developers have a clear, stable, version-controlled interface with no ambiguity about what is supported.
- **Positive:** `rtp-core` can be refactored freely without affecting addon compatibility, as long as `rtp-api` is unchanged.
- **Positive:** SemVer major bumps are only required when `rtp-api` changes, not on every internal refactor.
- **Negative:** Any new capability that addons need shall be explicitly added to `rtp-api`; it cannot be accessed ad-hoc from `rtp-core`.
- **Negative:** Maintaining two modules requires discipline to keep `rtp-api` minimal and not leak implementation details into it.
