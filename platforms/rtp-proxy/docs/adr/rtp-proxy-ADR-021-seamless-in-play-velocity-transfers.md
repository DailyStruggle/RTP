# rtp-proxy-ADR-021 — Seamless In-Play Velocity Transfers with Zero-Trust Hardening

**Status:** Proposed
**Date:** 2026-10-02
**Refines:** [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md), [rtp-proxy-ADR-010](rtp-proxy-ADR-010-security-hardening.md), [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md)
**Related:** [rtp-proxy-ADR-020](rtp-proxy-ADR-020-direct-database-cross-server-dispatch.md)

---

## Context

In standard Velocity cross-server routing (`VelocityProxySender.sendTo`), server transitions invoke `Player.createConnectionRequest(targetServer).connect()`. Under standard protocol execution (Minecraft 1.20.2 through 1.21+):
1. **Loading Screen Artifact:** The proxy initiates backend channel disconnection and transition, causing the vanilla Minecraft client to re-enter the `CONFIGURATION` phase (via `ClientboundStartConfigurationPacket`) to synchronize registries, tags, and packs before returning to `PLAY` via `ClientboundLoginPacket`. The client wipes chunk memory, displays a dirt loading screen ("Downloading terrain..."), and resets rendering buffers.
2. **Sharded World Deployments:** For networks utilizing shared-world sharding (e.g., dynamically partitioned survival or resource worlds backed by an intermediate distributed database), this loading screen breaks immersion and introduces latency friction during `/rtp` operations.
3. **1.20.5+ / 1.21 Protocol Nuance:** The native 1.20.5+ / 1.21 `ClientboundTransferPacket` (`minecraft:transfer`) mandates a full TCP disconnect/reconnect on the client socket, guaranteeing a dirt loading screen ("Connecting to server..."). Therefore, true loading-screen elimination requires maintaining the client's TCP socket on Velocity while swapping the backend connection pipe in the `PLAY` state.
4. **Adversarial / Hacked Client Threat Model:** Modifying packet flows, intercepting Netty pipelines, or using client-carried state (such as 1.20.5+ client cookies) exposes the server to Byzantine client attacks:
   - Packet injection during transition windows (duplication glitches, movement spam, container desync).
   - Cookie/token tampering (forging coordinates, expiration, or permissions).
   - Replay attacks using intercepted reservation nonces.
   - Client desync griefing (refusing position acknowledgments).

---

## Decision

We introduce **Seamless In-Play Velocity Transfers** with a **Zero-Trust Hardened Netty Pipeline**:

### 1. In-Play Pipe Swap & Pre-Loaded Chunk Streaming (Zero Loading Screen)
- **Persistent Client Connection:** Velocity retains the active TCP connection with the client. The client never drops into the `CONFIGURATION` phase (`ClientboundStartConfigurationPacket` is suppressed if both backend servers share identical registry codecs and resource pack manifests).
- **Entity ID Remapping:** The proxy assigns and maintains an authoritative client-facing entity ID for the player across transfers, translating any mismatched backend entity IDs or despawning prior entities to avoid collision.
- **Pre-Loaded Destination Chunk Streaming:**
  - Because target locations are pre-reserved from warmed queues (`RegionQueueManager`), the destination backend already holds destination chunks loaded in memory.
  - Prior to tearing down or forgetting the origin world chunks, Velocity establishes the secondary connection pipeline and immediately begins streaming pre-loaded chunks (`ClientboundLevelChunkWithLightPacket`) from the target server into the client's view window.
  - This prevents client-side void rendering and camera clipping before the origin chunks are unloaded.
- **Chunk Stream Transition & Cleanup:**
  - Emits `ClientboundSetChunkCacheCenterPacket` with the target coordinate's `(chunkX, chunkZ)` to establish the client's new view center.
  - Clears origin entities by emitting `ClientboundRemoveEntitiesPacket` for all tracked entity IDs in the origin view window.
  - Ensures player vehicle dismount (`ClientboundSetPassengersPacket`) if mounted prior to transition.
  - Once the target server's center chunk batch is queued, the proxy sends `ClientboundForgetLevelChunkPacket` for vacated origin chunks.
  - Clears origin server scoreboards, objectives, teams, and boss bars (`ClientboundSetObjectivePacket`, `ClientboundBossEventPacket`) to prevent ghost UI artifacts.
- **Respawn Packet Sanitization:** When the destination backend completes its server login sequence in `PLAY` state and emits its initial `ClientboundLoginPacket`, Velocity intercepts and translates it into a sanitized `ClientboundRespawnPacket`:
  - Matches the origin world's `dimensionType`, `dimension` (level identifier), and `hashedSeed`.
  - Sets the `dataToKeep` / `data_kept` bitmask (flags `0x01` for attributes, `0x02` for entity metadata / data) to prevent the client engine from flushing attributes or rendering state.
- **Authoritative Position Teleport:** Once the chunk transition completes, the destination backend emits `ClientboundPlayerPositionPacket` with authoritative destination coordinates and a mandatory teleport ID.

### 2. Registry & Manifest Pre-Validation via Database State
- **Pre-Flight Parity Verification via Shared Store:**
  - Rather than relying on fragile ad-hoc query packets, backend servers publish their runtime registry checksums, custom dimension identifiers, view distances, and resource pack hashes to the central datastore (`backend_state` via Redis/SQL per [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md) / [rtp-proxy-ADR-020](rtp-proxy-ADR-020-direct-database-cross-server-dispatch.md)) during periodic heartbeats.
  - Multi-platform backends (Paper, Folia, Fabric, NeoForge) utilize their platform server accessors to register exact server metadata (dimension type hashes, world height limits, and view distance settings).
  - Velocity inspects this published database state prior to initiating the pipe swap. If registry digests match, the seamless in-play swap proceeds.
- **Clean Fallback:** If registry hashes mismatch or if a backend reports differing custom dimension definitions, Velocity transparently falls back to standard transfer flow (`createConnectionRequest().connect()`), allowing the client to execute the standard configuration phase cleanly without crashing.

### 3. Multi-Platform Backend Support (Paper, Folia, Fabric, NeoForge)
- **Proxy-Specific vs. Platform-Agnostic Boundaries:**
  - **Proxy Tier:** Low-level Netty channel swapping (`rtp-seamless-handler`) is isolated strictly within `rtp-proxy-velocity`.
  - **Backend Tier:** Backend servers run their native platform adapters (`rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`). All coordination (chunk pre-loading, reservation pinning, and telemetry publishing) occurs via shared models in `rtp-core` and datastore tables, ensuring seamless transfer compatibility across any backend platform.

### 4. Zero-Trust Hardening Against Malicious / Modded Clients
- **Netty Channel Gating (`SeamlessTransferHandler`):** During the pipe swap window (`SWAPPING_PIPES`), the proxy intercepts all clientbound and serverbound traffic:
  - Drops and releases (`ReferenceCountUtil.release(msg)`) all `ServerboundMovePlayerPacket`, `ServerboundInteractPacket`, and inventory/container packets to prevent race conditions, combat evasion, or duplication exploits.
  - Passes through `ServerboundKeepAlivePacket` and 1.20.5+ `ServerboundPongPacket` to prevent timeout disconnects.
- **Encrypted & Authenticated Cookie Tokens (AEAD AES-256-GCM):** If 1.20.5+ client cookies are used for transfer state (`ClientboundStoreCookiePacket` -> `ClientboundCookieRequestPacket` -> `ServerboundCookieResponsePacket`):
  - Payloads are authenticated and encrypted using AES-256-GCM with a 128-bit authentication tag and a random 96-bit initialization vector (IV), derived from the network cluster secret (`network.secretEnv` per [rtp-proxy-ADR-010](rtp-proxy-ADR-010-security-hardening.md)).
  - Tokens contain a unique UUID nonce, issuing timestamp, player UUID, target server ID, destination coordinates, and a strict TTL (default 10 seconds).
  - Destination backends decrypt and verify the AEAD tag; any bit alteration or replay of an existing nonce triggers an immediate disconnect.
- **Authoritative Position Acknowledgment:** The destination backend enforces a strict timeout (1500 ms) for `ServerboundAcceptTeleportationPacket`. If a hacked client refuses to acknowledge the new position, it is kicked.

---

## Architecture & Pipeline Flow

```
                      [ Minecraft Client (Vanilla / Modded) ]
                                         │ (Persistent TCP)
                                         ▼
                   [ Velocity Netty Pipeline: "rtp-seamless-handler" ]
                   ├── State: NORMAL -> SWAPPING_PIPES -> SYNCHRONIZING
                   ├── Gating: Drops movement & container packets during swap
                   └── Rewrite: Intercepts & normalizes Respawn packet
                                         ▲
                         ┌───────────────┴───────────────┐
                         │ (Pipe Swap)                   │
                         ▼                               ▼
                 [ Origin Backend A ]            [ Target Backend B ]
```

### State Machine Lifecycle
1. **RESERVED:** Origin backend reserves a warmed coordinate on Target Backend B (`ReservationToken`). Target Backend B ensures the destination chunk cluster is loaded in memory.
2. **SWAPPING_PIPES:** Velocity attaches `SeamlessTransferHandler` to the player channel and begins backend connection to Backend B. Client movement and container actions are gated.
3. **RESPAWN_REWRITE & ENTITY REMAP:** Initial login packets from Backend B are intercepted; player entity ID is mapped to the existing client entity ID, and login is rewritten into a sanitized `ClientboundRespawnPacket`.
4. **STREAM_CHUNKS & FORGET:** Pre-loaded destination chunks are streamed to the client; view center is updated via `ClientboundSetChunkCacheCenterPacket`; origin chunks are forgotten (`ClientboundForgetLevelChunkPacket`); origin entities, scoreboard, and bossbar elements are wiped.
5. **SYNCHRONIZING:** Authoritative `ClientboundPlayerPositionPacket` is emitted to place the player at destination coordinates.
6. **NORMAL:** Client confirms teleport ID via `ServerboundAcceptTeleportationPacket`; normal bidirectional packet flow resumes on Backend B.

---

## Configuration (`network.yml`)

The configuration integrates into canonical `network.yml` under `routing.seamless` (per [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)):

```yaml
routing:
  seamless:
    # Enable loading-screen-free in-play server switching on Velocity
    enabled: false
    # Maximum time in milliseconds to gate client packets during pipe swap
    gateTimeoutMs: 2000
    # Enforce AEAD AES-256-GCM encryption on 1.20.5+ client cookie payloads
    encryptCookies: true
    # Fallback to standard transfer if dimension registries do not match
    fallbackOnRegistryMismatch: true
```

---

## Consequences

### Positive
- **No Loading Screen:** Players experience cross-server / cross-shard teleportation as an instantaneous in-world teleport.
- **Immune to Client Tampering:** Zero-trust gating prevents item dupes, packet injection, or coordinate manipulation from modified/hacked clients.
- **Fail-Safe Fallback:** If the destination backend rejects the connection or takes longer than `gateTimeoutMs`, the swap aborts cleanly, restoring the player on the origin server without coordinate leaks (S-004).

### Trade-offs & Limitations
- **Registry Parity Requirement:** Both backend servers must share compatible dimension types, world height limits, and registry codecs. If registries mismatch, the proxy automatically falls back to standard transfer.
- **Netty Maintenance:** Low-level Netty pipeline manipulation requires maintenance across major Minecraft protocol shifts.

---

## References
- [`docs/dev/MULTI_SERVER_PLAN.md`](../../../../docs/dev/MULTI_SERVER_PLAN.md)
- [ADR-036](../../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md) (Network Mode)
- [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md) (Network Schema)
- [rtp-proxy-ADR-006](rtp-proxy-ADR-006-velocity-bootstrap.md) (Velocity Bootstrap)
- [rtp-proxy-ADR-010](rtp-proxy-ADR-010-security-hardening.md) (Security Hardening)
- [rtp-proxy-ADR-011](rtp-proxy-ADR-011-sql-network-state-binding.md) (SQL Network State Binding)
- [rtp-proxy-ADR-014](rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md) (Backend-Owned RTP)
- [rtp-proxy-ADR-020](rtp-proxy-ADR-020-direct-database-cross-server-dispatch.md) (Direct DB Dispatch)
