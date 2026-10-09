---
sessionId: session-261006-220817-1vzc
---

# Requirements

### Overview & Goals
Work Package A resolves three critical network security, routing freshness, and transport integrity issues across multi-server environments:
1. **`RTP-23` (Backend Redis ignores `transport.redis.tls` and `username`):** Ensure backend servers connecting to Redis clusters use TLS encryption and ACL usernames when configured, preventing credential leaks and connection failures.
2. **`RTP-18` (Signed plugin-message heartbeats can be replayed indefinitely):** Protect the plugin-message heartbeat channel from replay attacks by adding HMAC-covered `sentAtMs` timestamps and monotonic sequence numbers, rejecting stale or replayed envelopes.
3. **`RTP-24` (Unsolicited `GetServer`/`GetServers` replies set backend topology):** Gate BungeeCord channel topology replies so only responses to active, pending requests within a validity window update the peer registry, and enforce bounds on peer lists.

### Scope
- **In Scope:**
  - `NetworkModeBootstrap.java` in `rtp-core`: parse `transport.redis.tls` and `transport.redis.username` and configure `RespEndpoint`.
  - `PluginMessageEnvelope.java`, `AbstractPluginMessageNetworkBinding.java`, and `VelocityProxyAvailabilityCache.java`: bump `pmv` to 3, sign `sentAtMs` and `seq`, validate timestamp freshness and monotonic ordering per server.
  - `BukkitNetworkBridge.java`: gate `GetServer` / `GetServers` topology replies with request generation / pending window tracking and cap topology peer sets.
  - Removal of resolved entries from `docs/dev/POTENTIAL_BUGS.md` and synchronization with Linear tracker.
- **Out of Scope:**
  - Replacing the HMAC secret distribution mechanism with asymmetric keypairs (deferred to v2 per rtp-proxy-ADR-010).
  - Protocol changes to other transports (`sql`, `proxy-direct`).

### User Stories
- As a server network operator using TLS-secured Redis, I want backend servers to connect using TLS and specified ACL usernames so that network traffic is encrypted and authentication succeeds.
- As a network administrator, I want old or replayed plugin-message heartbeats to be rejected so that defunct or stopped backend servers are promptly removed from proxy routing tables.
- As a server administrator, I want unsolicited or oversized proxy topology messages to be ignored so that unverified peers cannot pollute tab-completion or routing state.

### Functional Requirements
- **FR-001 (Redis TLS & User):** When `network.yml` specifies `transport.redis.tls: true` and/or `transport.redis.username: <name>`, `NetworkModeBootstrap` must construct `RespEndpoint` with TLS enabled and the specified username. Default port must be 6380 if TLS is enabled without an explicit port.
- **FR-002 (Envelope Timestamp & Sequence):** `PluginMessageEnvelope` version 3 must include signed `sentAtMs` and monotonic `seq` fields covered by the HMAC signature.
- **FR-003 (Replay & Freshness Validation):** Inbound heartbeats must be rejected if `|now - sentAtMs| > staleAfterMs + SKEW_TOLERANCE_MS` (5000ms skew tolerance) or if `seq <= lastSeenSeq` for that server.
- **FR-004 (Topology Handshake Gating):** `BukkitNetworkBridge` must ignore `GetServer` and `GetServers` replies unless an outstanding topology request was issued within the last 5000ms.
- **FR-005 (Topology List Bounds):** Topology peer lists must be capped at 256 entries and individual server names validated against standard identifier formats.

# Technical Design

### Current Implementation
- **RTP-23:** In `NetworkModeBootstrap.java` (lines 270-281), `bootstrap()` inspects `transport.redis.host`, `port`, and `password`, but completely omits `transport.redis.tls` and `transport.redis.username`. Unless an explicit `rediss://` URI is provided in `host`, standard host/port setups fail on TLS Redis or send plaintext authentication.
- **RTP-18:** In `PluginMessageEnvelope.java`, `VERSION` is 2 (`pmv=2`), covering only the version and canonical heartbeat payload. In `AbstractPluginMessageNetworkBinding.admit()` and `VelocityProxyAvailabilityCache.onPush()`, liveness is stamped with the receiver's local clock (`new Entry(hb, now)`). A captured signed frame can be replayed indefinitely to keep stopped backends alive.
- **RTP-24:** In `BukkitNetworkBridge.Listener.onPluginMessageReceived()` (lines 308-318), `GetServer` and `GetServers` replies on `bungeecord:main` or `BungeeCord` are accepted unconditionally whenever received, without verifying if a request was ever made, and without bounding peer list sizes.

### Key Decisions
1. **Envelope Format Version 3 (`pmv=3`):**
   - *Decision:* Add signed header lines `sentAtMs=<timestamp>` and `seq=<counter>` into `PluginMessageEnvelope` covered by HMAC.
   - *Rationale:* Ensures cryptographic binding of both timestamp and monotonic sequence to prevent packet replay and tampering.
2. **Freshness & Monotonicity Window:**
   - *Decision:* Enforce `|now - sentAtMs| <= staleAfterMs + 5000ms` clock skew tolerance, and maintain an in-memory `ConcurrentHashMap<String, Long> lastSeenSeq` to reject non-strictly-monotonic sequence numbers per server.
   - *Rationale:* Accommodates slight NTP drift between servers while strictly preventing playback of historical packets.
3. **Topology Request Generation & Expiry Window:**
   - *Decision:* Use an `AtomicLong topologyRequestTimestamp` and generation check in `BukkitNetworkBridge`. Responses arriving without an active request or after 5000ms are dropped.
   - *Rationale:* Lightweight, non-blocking, and guarantees unsolicited or delayed packets cannot inject unverified peer entries.

### Proposed Changes
- **`NetworkModeBootstrap.java`**:
  - Read `transport.redis.tls` (default `false`) and `transport.redis.username` (default `null`).
  - Set default port to 6380 if `tls` is true and `port` is 6379 or unspecified.
  - Pass TLS and username to `new RespEndpoint(...)`.
- **`PluginMessageEnvelope.java`**:
  - Update `VERSION` to 3.
  - Include `sentAtMs` and `seq` in `signedBody` and canonical hashing.
  - Extract and expose `sentAtMs` and `seq` in `Result`.
- **`AbstractPluginMessageNetworkBinding.java`**:
  - Pass monotonic sequence and timestamp during outbound encoding.
  - On inbound, validate `sentAtMs` freshness against receiver clock and check per-server monotonic sequence progression before calling `admit()`.
- **`VelocityProxyAvailabilityCache.java`**:
  - Enforce timestamp freshness and monotonic sequence checks in `onPushPayload()` / `onPush()`.
- **`BukkitNetworkBridge.java`**:
  - Set `topologyRequestTimestamp = System.currentTimeMillis()` in `requestTopology()`.
  - In `Listener.onPluginMessageReceived`, drop `GetServer`/`GetServers` if `now - topologyRequestTimestamp > 5000ms`.
  - Sanitize and cap parsed peers to `MAX_TOPOLOGY_PEERS = 256`.

### Architecture Diagram
```mermaid
graph TD
    subgraph Backend [Backend Server]
        PUB[BackendStatePublisher] -->|Heartbeat| NB[AbstractPluginMessageNetworkBinding]
        NB -->|pmv=3, sentAtMs, seq| ENV[PluginMessageEnvelope Seal + HMAC]
        ENV -->|Signed Frame| BRIDGE[BukkitNetworkBridge]
        BRIDGE -->|GetServer / GetServers (Gated)| PROXY_CHAN[Bungee/Velocity Channel]
    end

    subgraph Proxy [Velocity Proxy]
        PROXY_CHAN -->|PluginMessageEvent| CACHE_LISTEN[VelocityProxyCacheListener]
        CACHE_LISTEN -->|Verify HMAC & Freshness| VCACHE[VelocityProxyAvailabilityCache]
    end

    subgraph Redis [Redis Infrastructure]
        NMB[NetworkModeBootstrap] -->|TLS + Username| REDIS[Secure RespEndpoint]
    end
```

### Risks & Mitigations
- **Clock Drift Across Network Hosts:** If host clocks differ significantly, heartbeats could be prematurely rejected.
  - *Mitigation:* Allow a 5000ms clock skew tolerance (`SKEW_TOLERANCE_MS`) on top of the configured `staleAfterMs`.
- **Server Restarts Resetting Monotonic Sequence:** When a server restarts, its sequence resets to 0.
  - *Mitigation:* A sequence reset is accepted if the timestamp `sentAtMs` is strictly greater than the prior observed `sentAtMs` and falls within the valid freshness window.

# Testing

### Validation Approach
Verification combines unit testing of protocol codecs, replay rejection harnesses, configuration parsing tests, and cross-module regression runs.

### Key Scenarios
1. **Redis TLS and Username Configuration (RTP-23):**
   - Verify `NetworkModeBootstrap` correctly parses `transport.redis.tls: true` and `transport.redis.username: "rtp_user"`.
   - Verify default port resolves to 6380 when TLS is enabled without an explicit port.
2. **Plugin-Message Replay and Freshness Validation (RTP-18):**
   - Encode a valid signed envelope at timestamp $T$.
   - Assert immediate acceptance on arrival.
   - Assert that resending the exact same envelope is rejected as duplicate/non-monotonic.
   - Assert that sending an envelope with timestamp $T - 60000$ms is rejected as stale.
   - Assert that an envelope with timestamp far in the future ($T + 60000$ms) is rejected.
   - Verify round-trip encoding and decoding with `PluginMessageEnvelope` v3.
3. **Velocity Proxy Cache Replay Resistance (RTP-18):**
   - Verify `VelocityProxyAvailabilityCache` rejects replayed pushes with duplicate sequence or stale timestamps.
4. **Bukkit Network Bridge Topology Gating (RTP-24):**
   - Send unsolicited `GetServers` message when no `requestTopology()` was called; assert it is dropped.
   - Send `GetServers` after request timeout (>5000ms); assert it is dropped.
   - Call `requestTopology()` and send valid `GetServers` within 5000ms; assert topology is updated.
   - Send `GetServers` containing >256 peers; assert peer set is capped at 256.

### Targeted Test Commands
- `.\gradlew.bat :rtp-core:test --tests "*PluginMessage*"`
- `.\gradlew.bat :rtp-core:test --tests "*NetworkMode*"`
- `.\gradlew.bat :platforms:rtp-proxy:rtp-proxy-velocity:test --tests "*VelocityProxy*"`
- `.\gradlew.bat :platforms:rtp-bukkit:rtp-bukkit-common:test --tests "*NetworkBridge*"`

# Delivery Steps

### ✓ Step 1: Backend Redis TLS and username support (RTP-23)
Ensure backend Redis connections honour TLS and username settings specified in network configuration.

- Update `NetworkModeBootstrap.java` to read `transport.redis.tls` (boolean) and `transport.redis.username` (string) in addition to host, port, and password.
- Align port defaulting logic with `NetworkConfig.parseRedis` (default to 6380 when TLS is enabled without an explicit port, 6379 otherwise).
- Pass TLS flag and username to `RespEndpoint` construction, matching the proxy's Redis endpoint builder.
- Add unit test verifying that backend Redis endpoint configuration correctly propagates TLS and username credentials.

### ✓ Step 2: Plugin-message heartbeat replay protection (RTP-18)
Harden plugin-message heartbeats against replay attacks using timestamps and monotonic sequences.

- Bump `PluginMessageEnvelope.VERSION` to 3 and add signed header fields `sentAtMs` and `seq` covered by the HMAC signature.
- Update `PluginMessageEnvelope.seal` and `open` to encode and decode `sentAtMs` and monotonic `seq`, returning them in `Result`.
- Update `AbstractPluginMessageNetworkBinding` to validate timestamps within a configurable stale window plus clock skew tolerance (`|now - sentAtMs| <= staleAfterMs + SKEW_TOLERANCE_MS`), rejecting stale or futuristic packets.
- Add per-server monotonic tracking in `AbstractPluginMessageNetworkBinding` to reject out-of-order or duplicate sequences.
- Update `VelocityProxyAvailabilityCache.onPush` to enforce the same timestamp freshness and monotonic sequence validation on incoming backend pushes.
- Add unit tests in `rtp-core` and `rtp-proxy-velocity` verifying replay rejection, stale packet rejection, monotonic ordering, and backwards compatibility.

### ✓ Step 3: Bukkit topology handshake gating and peer bounding (RTP-24)
Prevent unsolicited BungeeCord topology messages from modifying backend peer registries without an active request.

- Add request state tracking in `BukkitNetworkBridge.java` (e.g. `topologyRequestPending` or generation counter with a 5-second validity window) initiated by `requestTopology()`.
- Drop unsolicited `GetServer` and `GetServers` responses when no topology request is active or when the request has expired, logging dropped messages at `Level.FINE`.
- Validate received server names (alphanumeric with hyphens/underscores, bounded length) and enforce a maximum peer limit (`MAX_TOPOLOGY_PEERS = 256`) on parsed topology lists.
- Reset or decrement pending request state once valid responses are accepted.
- Add unit tests verifying that unsolicited or expired topology packets are dropped, bounded peer caps are enforced, and valid responses within an active request window update topology.

### ✓ Step 4: Documentation update, regression verification, and Linear sync
Update documentation and sync Linear issue tracker statuses.

- Remove resolved entries for `RTP-23`, `RTP-18`, and `RTP-24` from `docs/dev/POTENTIAL_BUGS.md`.
- Run targeted test suites (`:rtp-core:test`, `:platforms:rtp-proxy:rtp-proxy-velocity:test`, `:platforms:rtp-bukkit:rtp-bukkit-common:test`) to confirm zero regressions across all modified modules.
- Update the Linear issue tracker for RTP-23, RTP-18, and RTP-24 to Done upon user confirmation.