# ADR-007 — Per-User Isolated Queues Alongside the Global Queue

**Status:** Accepted (operational details superseded by [ADR-043](ADR-043-personal-queue-permission-semantics.md))
**Date:** 2026-04-15

## Context

RTP maintains a pre-generation queue of validated teleport locations per region (see [ADR-006](ADR-006-async-queue-pre-generation.md)). The simplest model is a single global queue per region: all teleport requests consume from the same pool, and a background task replenishes it.

However, the global queue alone is vulnerable to **queue-flooding abuse**. Because the queue is a shared resource, a high-frequency burst of teleport requests — whether from a legitimate spike in player activity or a deliberate denial-of-service attack — can drain the global queue faster than the bounded replenishment task can refill it. Legitimate players, including operators and trusted users, then wait alongside everyone else for the queue to recover.

The plugin's bounded replenishment design already protects the server from CPU overload (see ADR-006), but it does not by itself prevent a less privileged group of users from degrading the teleport experience for higher-priority users.

## Decision

Maintain **both** a global queue and **per-user isolated queues** tied to individual player UUIDs.

Per-user queues are created when a permissioned player joins the server. The permission node controls which players receive a dedicated queue. Teleport requests from those players are fulfilled from their personal queue rather than the global one, insulating them from global queue pressure.

This provides a tiered priority model:
- **Standard players** consume from the global queue and are subject to its current depth.
- **Permissioned players** (e.g., operators, VIPs, trusted staff) consume from their own pre-warmed queue, guaranteeing availability regardless of global queue state.

## Consequences

- **Positive:**
  - Permissioned players (operators, VIPs) have a guaranteed teleport queue unaffected by public player flood or DDoS-style abuse.
  - The global queue still serves the majority of players efficiently without per-player overhead.
  - The boundary between global and per-user queue is entirely permission-driven — no code changes are needed to adjust which players receive dedicated queues.

- **Negative / Trade-offs:**
  - Each active per-user queue consumes memory and a share of the replenishment task budget. Granting the permission to a large fraction of the player base increases overhead proportionally.
  - Per-user queues are tied to the player's session; they are initialised on join and torn down on quit, so there is a brief warm-up period after each login before the personal queue is fully stocked.

## References

- Implementing classes: `RegionQueueManager` (global + per-UUID queue management), `RTPBukkitPlugin` (queue initialisation on player join) (`rtp-core`, `rtp-plugin`)
- Design reference: [`DESIGN.md` §1 — Asynchronous Queue-Based Pre-Generation](../DESIGN.md)
- Related: [ADR-006](ADR-006-async-queue-pre-generation.md) (pre-generation queue rationale)
- Requirements: `REQ-RTP-NF-002` (resource starvation prevention), `REQ-RTP-S-001` (permission-based access control)
