# ADR-013 — Addons as External Gradle Projects Rather Than Built-In Optional Modules

**Status:** Accepted  
**Date:** 2026-04-15

---

## Context

RTP integrates with a wide ecosystem of third-party Minecraft plugins: claim systems (GriefPrevention, Towny), world generators (Iris), movement plugins (Glide), economy plugins, and others. Each integration requires compiling against a different third-party API and shipping code that is only relevant to servers running that specific plugin.

Two approaches exist:

1. **Built-in optional modules** — integrations live inside the main RTP repository, guarded by soft-dependency checks (`Bukkit.getPluginManager().isPluginEnabled(...)`) at runtime, compiled as optional Gradle submodules.
2. **External addon jars** — each integration is a separate Gradle project compiled and distributed independently, using `rtp-api` as its only RTP dependency.

---

## Decision

Integrations are implemented as **separate external Gradle projects**. The `addons/` directory in the RTP repository contains reference examples (`RTP_ClaimPluginIntegrations`, `RTP_Glide`, `RTP_Iris_integration`), but these are illustrative — not exhaustive, and not shipped as part of the core plugin jar.

Third-party developers are expected to own and maintain their own addon jars independently, using the examples as a starting point.

---

## Rationale

### The core plugin cannot absorb every desired feature
The scope of possible integrations is unbounded — every server has a different plugin stack. Bundling integrations into the core jar would require the core maintainer to evaluate, implement, test, and maintain every requested integration indefinitely. This is not sustainable for a solo-developed project.

### Demonstrates the extension model
The `addons/` examples serve a specific purpose: showing developers exactly how to tell RTP to perform additional validation or behaviour without recompiling the core plugin. They are documentation-by-example for `rtp-api` usage, not production features.

### Independent release cadence
An addon that targets GriefPrevention or Iris has its own dependency on that plugin's API, which evolves on its own schedule. Keeping addons external means their release cadence is decoupled from RTP's — an Iris API update does not block a RTP core release.

### Reduces core jar footprint and dependency surface
Every soft-dependency bundled into the core jar increases its size and transitive dependency surface. Servers that do not use GriefPrevention should not ship GriefPrevention stubs. External addon jars are only installed by operators who need them.

---

## Alternatives Considered

| Alternative | Reason Rejected |
|-------------|----------------|
| Built-in optional modules with soft-dependency guards | Unbounded maintenance scope for a solo project; bloats core jar; ties release cadence to third-party APIs |
| Monorepo with all addons as Gradle submodules | Same maintenance burden; all addons must be updated on every RTP release |
| No example addons at all | Leaves addon developers without guidance on how to use `rtp-api`; increases support burden |

---

## Consequences

- **Positive:** Core plugin remains lean; no third-party plugin stubs shipped to servers that don't need them.
- **Positive:** Addon release cadence is fully independent of RTP core releases.
- **Positive:** The extension model is clearly demonstrated via reference examples without imposing maintenance obligations.
- **Negative:** The core maintainer does not control the quality or compatibility of community addons.
- **Negative:** Operators must source and install addon jars separately; there is no single-jar "batteries included" distribution.
