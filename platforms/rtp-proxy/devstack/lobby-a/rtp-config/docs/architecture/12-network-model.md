# Network Model (Multi-Server / Multi-Proxy)

Mermaid-rendered overview of RTP's network mode: the topology, the cross-server `/rtp` request lifecycle, and the reservation-token state machine. This doc is a **visual companion** to the normative sources, not a substitute. For the full rationale and acceptance criteria, read:

- [`../dev/MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md): roadmap, design rules, config surface.
- [ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md): umbrella decision.
- [rtp-proxy-ADR-014](../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md): backend-owned `/rtp` + network wait queue (L6, the current shape).
- [`../dev/GLOSSARY.md`](../dev/GLOSSARY.md): canonical terms (`backend`, `proxy`, `transport`, `network snapshot`, `backend selector`, `reservation token`).

Diagrams below describe the **L6 architecture** as accepted on 2026-05-21. Earlier L1-L5 shapes (proxy-hosted `/rtp`) are superseded.

---

## 1. Topology

Hub-and-spokes star around a single durable shared store. Proxies never call each other; backends never call each other. All cross-host coordination flows through the transport (Redis / SQL).

```mermaid
flowchart LR
    subgraph Clients
        P1[Player on Proxy A]
        P2[Player on Proxy B]
    end

    subgraph Proxies["Proxy fleet (any N, identical network.yml + per-host proxyId)"]
        PX1["Proxy A<br/>(Velocity / Bungee)<br/>DefaultRtpDispatcher<br/>TransportRequestTriggerSource<br/>ReservationTokenReaper"]
        PX2["Proxy B<br/>(Velocity / Bungee)"]
    end

    subgraph Transport["Shared store (D3)"]
        T["NetworkTransport<br/>NetworkRequestQueue<br/>NetworkStateBinding<br/>backend_state / proxy_state / reservations"]
    end

    subgraph Backends["Backend fleet (Spigot / Paper / Folia / Fabric)"]
        B1["Backend A<br/>RegionQueueManager<br/>NetworkRouter<br/>NetworkEnrolmentBuffer<br/>networkKeptLocations<br/>networkReservedLocations<br/>JoinTriggerSource"]
        B2["Backend B<br/>(same shape)"]
    end

    P1 -->|connects| PX1
    P2 -->|connects| PX2

    PX1 <-->|enqueue / poll / claim / release| T
    PX2 <-->|enqueue / poll / claim / release| T

    B1 <-->|heartbeat / enrol / status| T
    B2 <-->|heartbeat / enrol / status| T

    PX1 -.->|transferPlayer<br/>ServerPreConnectEvent| B2
    PX2 -.->|transferPlayer| B1
```

Key invariants (see *Multi-Proxy Deployment* in `MULTI_SERVER_PLAN.md`):

- No proxy-to-proxy chatter. Hub-and-spokes only.
- "The proxy" always means "some proxy"; any code path that assumes the same proxy observes a side effect it issued is a bug.
- Per-proxy local state (snapshot cache, `recentPicks`) is **advisory only**; safety-load-bearing state lives in the transport.

---

## 2. Cross-Server `/rtp` Request Lifecycle (L6)

The backend owns `/rtp`. The proxy is a dispatch + reservation broker. Walks through the happy path of a player on backend A running `/rtp` and landing on backend B.

```mermaid
sequenceDiagram
    autonumber
    actor Player
    participant BA as Backend A<br/>(origin)
    participant TQ as NetworkRequestQueue<br/>(transport)
    participant PX as Proxy<br/>(any proxy)
    participant TS as NetworkStateBinding<br/>(transport)
    participant BB as Backend B<br/>(destination)

    Player->>BA: /rtp [region]
    BA->>BA: NetworkRouter.decide()<br/>(kill-switch, RPS, region, mode)
    alt local-serve
        BA-->>Player: existing local pipeline (unchanged)
    else cross-server
        BA->>BA: NetworkEnrolmentBuffer.enrol(EnrolmentEnvelope)
        BA->>TQ: flushPending() - atomic SADD+RPUSH+HSET
        PX->>TQ: BLPOP / dequeueReady
        TQ-->>PX: EnrolmentEnvelope
        PX->>TS: read snapshot (backend_state heartbeats)
        PX->>PX: BackendSelector.choose(req, snap)
        PX->>BB: ReservationClient.claim(tokenId, region)
        BB->>BB: reserveFromNetworkKept(tokenId)<br/>park coord in networkReservedLocations
        BB-->>PX: ReservationToken{worldKey,x,y,z,yaw,pitch}
        PX->>TQ: transition RESERVED to ROUTING (StatusSink)
        PX->>Player: transferPlayer(B) via ServerPreConnectEvent
        Player->>BB: connects
        BB->>BB: JoinTriggerSource.onJoin<br/>redeemReserved(tokenId)
        BB->>BB: acceptRedeemedReservation(playerId, coord)<br/>(personal queue)
        BB-->>Player: local /rtp consumes that exact coord
        PX->>TQ: transition COMPLETED (StatusSink)
    end

    opt player disconnects mid-route
        BB->>BB: PlayerQuitEvent: releaseToNetworkKept
        BB->>TS: transport.release(PLAYER_DISCONNECTED)
    end

    opt token expires before redeem
        PX->>TS: ReservationTokenReaper sweeps<br/>ReleaseSink fires per token
    end
```

Notes:

- Coordinates are resolved on backend B **before** the player transfers (`MULTI_SERVER_PLAN.md` *Coordinate Resolution Timing*). The proxy carries `worldKey + x/y/z/yaw/pitch` in the token; the destination's join handler *consumes*, it does not re-run the pipeline.
- `JoinTriggerSource` is **redeem-first**: if a token is present, it bypasses the normal join-rtp trigger and feeds the personal queue directly.
- The `releaseToNetworkKept` path is bounded; on overflow it falls through to `unkeptLocations` (S-004 attribution preserved).

---

## 3. Reservation Token State Machine

A single coordinate's life from "earmarked on backend B" to "consumed by the player" or "reclaimed by the reaper". Multi-proxy races are resolved by the `WHERE state='PENDING'` row-count guard on the `PENDING` to `CLAIMED` transition.

```mermaid
stateDiagram-v2
    [*] --> PENDING : reserveFromNetworkKept(tokenId)<br/>(backend B carves from networkKeptLocations)
    PENDING --> CLAIMED : UPDATE ... WHERE state='PENDING'<br/>(row-count = 1, winning proxy)
    PENDING --> CLAIMED_LOSS : row-count = 0<br/>(racing proxy loses)
    CLAIMED_LOSS --> [*] : surface messages.yml failure;<br/>capped-retry chain picks next candidate

    CLAIMED --> ROUTING : StatusSink ROUTING<br/>transferPlayer fired
    ROUTING --> COMPLETED : JoinTriggerSource.redeemReserved<br/>player consumes coord
    COMPLETED --> [*] : terminal (env HASH deleted, SREM seen)

    ROUTING --> CANCELLED : Player disconnects mid-route<br/>releaseToNetworkKept + transport.release(PLAYER_DISCONNECTED)
    CANCELLED --> [*]

    CLAIMED --> EXPIRED : TTL elapses without redeem<br/>ReservationTokenReaper sweep
    ROUTING --> EXPIRED : same
    EXPIRED --> PENDING : claimReanimateMs window<br/>(proxy died; surviving proxy re-opens)
    EXPIRED --> [*] : beyond reanimation (coord falls back to unkeptLocations)
```

The `EXPIRED` to `PENDING` reanimation edge is what makes multi-proxy failover work: a token claimed by a dead proxy returns to the pool after `claimReanimateMs`, and any surviving proxy can pick it up without operator intervention. The same primitive covers proxy restart and proxy death (`MULTI_SERVER_PLAN.md` *Reservation tokens under multiple proxies*).

---

## 4. Data Plane Summary

What lives where, at a glance.

```mermaid
flowchart TB
    subgraph Backend["Backend (rtp-core)"]
        direction TB
        RQM[RegionQueueManager]
        KL[keptLocations<br/>L1 / hot]
        UL[unkeptLocations<br/>L2 / cold]
        BL[backlogLocations<br/>L3 / binned]
        NKL[networkKeptLocations<br/>cross-server reserve]
        NRL[networkReservedLocations<br/>in-flight tokens]
        NR[NetworkRouter]
        NEB[NetworkEnrolmentBuffer]
        JTS[JoinTriggerSource]
        RQM --- KL
        RQM --- UL
        RQM --- BL
        RQM --- NKL
        RQM --- NRL
    end

    subgraph Proxy["Proxy (rtp-proxy-common + adapter)"]
        direction TB
        DRD[DefaultRtpDispatcher]
        TRT[TransportRequestTriggerSource]
        BS[BackendSelector<br/>WeightedAverage default]
        RTR[ReservationTokenReaper]
        SS[StatusSink / ReleaseSink]
    end

    subgraph Store["Transport (Redis / SQL)"]
        direction TB
        NRQ[NetworkRequestQueue<br/>FIFO + envelopes]
        NSB[NetworkStateBinding<br/>backend_state / proxy_state / reservations]
    end

    NR --> NEB --> NRQ
    NRQ --> TRT --> DRD
    DRD --> BS
    DRD -->|claim| RQM
    JTS -->|redeem| RQM
    RTR --> NSB
    DRD --> SS --> NRQ
    Backend -- heartbeat --> NSB
    Proxy -- heartbeat --> NSB
```

The `cacheCap` budget on each region is the upper bound for `keptLocations + networkKeptLocations`; `networkReserveSize` (per-region, default `0`) carves a slice for cross-server use without changing the existing single-server behaviour when set to `0`.

---

## 5. Where to go next

- For per-key config semantics: `network.yml` section of [`MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md).
- For the SPI shapes (`NetworkTransport`, `NetworkRequestQueue`, `BackendSelector`, `ReservationClient`): [rtp-proxy-ADR-001](../../rtp-proxy/docs/adr/rtp-proxy-ADR-001-spi-shape.md) and [rtp-proxy-ADR-014](../../rtp-proxy/docs/adr/rtp-proxy-ADR-014-backend-owned-rtp-with-network-queue.md).
- For Redis Lua envelope atomicity: [rtp-proxy-ADR-005](../../rtp-proxy/docs/adr/rtp-proxy-ADR-005-redis-binding.md).
- For SQL binding: [rtp-proxy-ADR-011](../../rtp-proxy/docs/adr/rtp-proxy-ADR-011-sql-network-state-binding.md).
- For glossary disambiguation (`fast cache` vs `kept cache` vs `network wait queue` vs `personal queue`): [`AGENTS.md` *Domain Analogies & Aliases*](../../.junie/AGENTS.md) and [`GLOSSARY.md`](../dev/GLOSSARY.md).
