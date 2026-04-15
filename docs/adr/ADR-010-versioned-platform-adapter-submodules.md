# ADR-010 — Versioned Platform Adapter Submodules

**Status:** Accepted  
**Date:** 2026-04-15

---

## Context

RTP must support multiple Minecraft server platforms (Spigot, Paper, Folia) across multiple NMS versions (e.g. `v1_20_R1`, `v1_21_R1`, `v26_1_R1`). Each NMS version exposes a different internal API surface; methods, classes, and behaviours change between versions and between platforms.

Two broad implementation strategies exist:

1. **Runtime version detection** — a single adapter jar that detects the running server version at startup and branches via reflection, `try/catch` fallbacks, or `if (version >= X)` guards.
2. **Compile-time version submodules** — a separate submodule per platform-version combination, each compiled against the exact NMS stubs for that version, with no runtime branching.

---

## Decision

RTP uses **compile-time versioned submodules** for all platform adapters:

- `rtp-spigot-common`, `rtp-spigot-v1_20_R1`, `rtp-spigot-v1_21_R1`, `rtp-spigot-v26_1_R1`
- `rtp-paper-common`, `rtp-paper-v1_20_R1`, `rtp-paper-v1_21_R1`, `rtp-paper-v26_1_R1`
- `rtp-folia-common`, `rtp-folia-v1_20_R1`, `rtp-folia-v1_21_R1`, `rtp-folia-v26_1_R1`

Each versioned submodule compiles against the NMS stubs for exactly that version. A `-common` submodule per platform holds shared logic that does not vary between NMS versions.

---

## Rationale

### Runtime efficiency
Compile-time separation eliminates all per-call reflection overhead and version-guard branching from hot paths (teleport execution, chunk loading). The correct implementation is loaded once at plugin startup via the platform adapter selection; after that, every call is a direct method invocation with no runtime dispatch cost.

### Clean stub organisation
Each versioned submodule has a well-defined, minimal dependency on exactly one set of NMS stubs. This makes it immediately clear which NMS classes each version relies on, and prevents accidental use of APIs that do not exist in a given version.

### Elimination of try/catch version fallbacks
A single-adapter approach requires `try/catch` blocks or `if (serverVersion >= X)` guards around every API call that differs between versions. These fallbacks are fragile: a missing catch, a wrong version comparison, or a renamed class causes a silent runtime failure rather than a compile-time error. Versioned submodules make version mismatches a build error, not a runtime surprise.

### Platform methodology isolation
Paper-specific and Folia-specific methodologies (e.g. async chunk loading on Paper, regional thread scheduling on Folia) are structurally incompatible — they cannot safely share a single code path. Separate submodules enforce this isolation at the module boundary rather than relying on developer discipline.

---

## Alternatives Considered

| Alternative | Reason Rejected |
|-------------|----------------|
| Single adapter with reflection | Reflection overhead on hot paths; brittle at runtime; no compile-time safety |
| Single adapter with version guards (`if/else`) | Fragile fallback chains; silent failures when a guard is wrong; poor readability |
| Runtime class-loading with a plugin classloader | Complex build and deployment; harder to debug; no improvement over submodules |

---

## Consequences

- **Positive:** Zero runtime version-detection overhead; compile-time safety for all NMS API usage; clear per-version dependency surface.
- **Positive:** Adding a new NMS version is a contained, mechanical task: add a new submodule, implement the versioned adapter interface, register it in `rtp-plugin`.
- **Negative:** More submodules to maintain; each new NMS version requires a new submodule rather than a one-line version guard.
- **Negative:** Build time increases linearly with the number of supported versions.
