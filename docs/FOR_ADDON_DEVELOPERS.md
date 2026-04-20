# Start Here — Addon Developers

**Current Plugin Version:** `3.0.0-beta.1`

This page guides third-party plugin developers who extend RTP using the `rtp-api` module.
If you are implementing a custom shape, vertical adjustor, biome filter, or claim-check hook, start here.

---

## Recommended Reading Order

### 1. [CONCEPTS.md](dev/CONCEPTS.md)
Plain-language explanation of how RTP's queue, shapes, vertical adjustors, and teleport pipeline fit together.
Read this first to understand the execution model your addon will plug into.

### 2. [ARCHITECTURE.md](dev/ARCHITECTURE.md)
Module breakdown: what lives in `rtp-api` vs `rtp-core` vs platform adapters, and why.
Explains the boundary your addon must respect — compile against `rtp-api` only, never `rtp-core`.

### 3. [DESIGN.md](dev/DESIGN.md)
Deep-dive into the bounded execution model, concurrency guarantees, and fault-tolerance contracts.
Read this to understand what guarantees RTP makes to your addon and what it expects in return.

### 4. [GLOSSARY.md](dev/GLOSSARY.md)
Definitions for every domain term used across the codebase and documentation:
`ChunkReservation`, `MemoryShape`, `RTPPipeline`, `pulse`, `sector`, and more.

### 5. [docs/adr/](adr/README.md)
Architecture Decision Records — the *why* behind key design choices.
Particularly relevant: ADR-001 (spiral mapping), ADR-006 (async queue), ADR-011 (`rtp-api` as a separate module), ADR-013 (addons as external Gradle projects).

---

## Reference Material

- [REQUIREMENTS.md](dev/REQUIREMENTS.md) — the `REQ-API-*` requirements define the stability contract your addon can rely on.
- [STAKEHOLDERS.md](dev/STAKEHOLDERS.md) — actor definitions; the "Addon Developer" section describes the goals and guarantees the API is designed to satisfy.

---

## Also Useful

- [`addons/RTP_ClaimPluginIntegrations/`](../addons/RTP_ClaimPluginIntegrations/) — a working reference implementation of a claim-check hook addon.
- [CONTRIBUTING.md](../CONTRIBUTING.md) — if you want to upstream a change to `rtp-api` itself, follow the contribution workflow there.
