# Network Mode Configuration Reference (`network.yml`)

`network.yml` configures **multi-server / multi-proxy network mode** (cross-server `/rtp`). It is **disabled by default**; flipping `network.enabled: true` opts a backend into the cross-server network.

> When `enabled: false`, this file is read, validated, and otherwise ignored: no threads start, no database tables are created, no transport opens. The backend behaves byte-identically to a build without network mode.

For the conceptual model and verification walkthroughs see [`INDEX.md`](INDEX.md), [`SINGLE_BACKEND_VERIFICATION.md`](SINGLE_BACKEND_VERIFICATION.md), and [`CROSS_SERVER_VERIFICATION.md`](CROSS_SERVER_VERIFICATION.md).

---

## `network` (Backend identity)

| Key | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `false` | Opt this backend into the cross-server network. |
| `serverId` | String | `""` | Stable backend identity. **Required** when `enabled: true`; no default. Two backends sharing a `serverId` overwrite each other's heartbeat row. |
| `secretEnv` | String | `"RTP_NET_SECRET"` | Environment variable holding the shared HMAC secret. Required when `enabled: true` **and** `transport.type: redis`. The SQL binding treats it as optional (DB auth + TLS is the security boundary). |

## `transport` (Backing store for cross-process state)

| Key | Type | Default | Description |
|---|---|---|---|
| `type` | Enum | `"auto"` | `auto`, `plugin-message`, `in-memory`, `sql`, or `redis`. See below. |

**`type` options:**

| Value | Behaviour |
|---|---|
| `auto` | No database required. Detects a BungeeCord/Velocity proxy at runtime and gossips region availability over the proxy's plugin-messaging channel; falls back to local-only (disabled) when no proxy is detected. Recommended starting point. |
| `plugin-message` | Forces the proxy plugin-messaging transport without auto-detection. No database. Availability is best-effort and only as fresh as the last heartbeat seen over a player connection. Not intended for very large multi-proxy networks. |
| `in-memory` | Development only; nothing leaves the JVM. |
| `sql` | DB-as-bus. Reuses the existing RTP database accessor pool. Durable across restarts. |
| `redis` | Jedis-backed heartbeats + pub/sub fan-out. Heartbeats and snapshots work; atomic claim (used for cross-backend reservation tokens) is not yet available. |

### `transport.pluginMessage`

Used when `type` is `auto` (with a detected proxy) or `plugin-message`; ignored otherwise. Gossip cadence and peer-staleness reuse the shared `heartbeat.*` knobs.

| Key | Type | Default | Description |
|---|---|---|---|
| `channel` | String | `"bungeecord:main"` | Proxy plugin-messaging channel. Leave default unless your proxy uses a non-standard channel name. |

### `transport.redis`

Used when `type: redis`.

| Key | Type | Default | Description |
|---|---|---|---|
| `host` | String | `"localhost"` | Hostname/IP of the Redis instance shared by every backend and proxy. |
| `port` | Integer | `6379` | Redis port. |
| `password` | String | `""` | Optional. Empty for unauthenticated Redis; set when Redis ACL / `requirepass` is configured. |

## `heartbeat` (State publication cadence)

| Key | Type | Default | Description |
|---|---|---|---|
| `intervalMs` | Integer | `1000` | How often this backend publishes its state row. On the plugin-message tier this also sets the region-availability gossip cadence. |
| `staleAfterMs` | Integer | `5000` | How long a peer's last-seen state stays trusted before being treated as unknown. Keep comfortably above `intervalMs` so one dropped heartbeat does not flap a peer. |

## `routing` (Backend-side router)

| Key | Type | Default | Description |
|---|---|---|---|
| `mode` | Enum | `"local"` | `local` = never enrol into the cross-server wait queue (zero behaviour change). `auto` = prefer local when this backend can serve the region, enrol only when it cannot. `always` = always enrol (developer / acceptance harness; not for production). |
| `lobbyMode` | Boolean | `false` | When `true`, this backend becomes a pure dispatch node: heartbeats publish an empty `regions` set and `acceptingRequests:false`; a bare `/rtp` auto-routes to the peer+region with the largest `regionKeptCounts` ("most-kept" policy); when no peer advertises a region, bare `/rtp` rejects with the localized `networkRegionUnavailable` message. |

## `queue` (Cross-server wait queue)

Ignored when `network.enabled: false` or `routing.mode: local`.

| Key | Type | Default | Description |
|---|---|---|---|
| `maxDepth` | Integer | `50` | Max locally-buffered enrolments before backpressure rejects new `/rtp` calls (`QUEUE_FULL`). |
| `flushIntervalMs` | Integer | `250` | Backend dirty-write flush cadence (ms). Lower = lower latency, higher = fewer round trips. |
| `pollIntervalMs` | Integer | `1000` | Backend status-cache poll cadence (ms); drives per-player progress refresh. |
| `entryTtlMs` | Integer | `0` | Optional passive aging of envelope hashes. `0` disables (terminal transitions still delete explicitly). |
| `crossServerRequestsPerSecond` | Integer | `5` | Cross-server `/rtp` enrolment rate limit (token bucket refill). |
| `crossServerRequestsBurst` | Integer | `10` | Token-bucket burst capacity. |
| `workerThreads` | Integer | `1` | Proxy-side BLPOP worker count. Increase only when a single worker is observed to saturate. |

## `reservation` (Reservation-token lifecycle)

| Key | Type | Default | Description |
|---|---|---|---|
| `reapIntervalMs` | Integer | `30000` | Proxy-side TTL reaper cadence (ms). Sweeps expired tokens and fires release so co-located backends can return the coordinate. |
| `ttlMs` | Integer | `60000` | Per-token TTL (ms). A claimed-but-not-consumed reservation older than this is treated as abandoned and released. |

## `regionCollision` (Region-collision policy)

| Key | Type | Default | Description |
|---|---|---|---|
| `policy` | String | `"warn"` | Only `warn` is shipped; `rename-local` and `reject-startup` are documented follow-ups. Unknown values silently degrade to `warn`. |

## `servers` (Optional per-destination policy overlay)

Never required: backends announce themselves over the proxy, so the destination list assembles itself. Add an entry (matched by `serverId`) only to override policy for one destination. Commented out by default.

| Key | Type | Default | Description |
|---|---|---|---|
| `permission` | String | `""` | Permission required to target this destination (empty = none). |
| `display-name` | String | `serverId` | Label shown in menus / suggestions. |
| `hide-when-unavailable` | Boolean | `false` | Hide from tab-completion when it has no servable region. |
| `allow-when-unavailable` | Boolean | `true` | Allow targeting it even when it reports no servable region. |

## `loadBalancer` (Blank-`/rtp` region selection scoring)

Lower score wins. Each `terms` entry scores a backend by one metric through a curve; scores sum, divide by `backendWeight`, and ties break by `serverId` then region. The shipped defaults mirror "most-kept wins" with smooth fall-off, gating overloaded backends via `step` curves on heap and region.

| Key | Type | Default | Description |
|---|---|---|---|
| `regionScarcityWeight` | Number | `0` | Weight of the built-in region-scarcity term. `0` because the explicit `region` term owns the kept-region signal. |
| `terms` | Map | *(see below)* | Scoring terms, one per metric. Each has an `input`, a `weight`, and a `curve`. |

**Inputs:** `mspt`, `queueDepth`, `heapUsed`, `heapFree`, `playerCount`, `keptCount`, `tps`, `keptRegion`.

**Curves:** `linear`, `exponential` (`k`), `logarithmic` (`k`), `sigmoid` (`k`, `x0`), `step` (`threshold`), `power` (`p`). Ranges: `k` 0.1 to 20, `p` 0.1 to 8, `x0`/`threshold` 0 to 1.

**Default `terms`:**

| Term | input | weight | curve | Notes |
|---|---|---|---|---|
| `mspt` | `mspt` | `1.0` | `sigmoid` k=8.0, x0=0.6 | Tick pressure; knee at 0.6. |
| `queue` | `queueDepth` | `1.0` | `exponential` k=3.0 | Small queues silent, large ones explode. |
| `heap` | `heapUsed` | `5.0` | `step` threshold=0.85 | Heap gate; fires at >= 85% used. |
| `region` | `keptRegion` | `5.0` | `step` threshold=0.95 | Region gate; fires when kept pool >= 95% full. |
| `kept` | `keptCount` | `0.25` | `logarithmic` k=9.0 | Gentle tie-breaker on kept count. |

---

> The `config.yml` `network.redis` block is a separate, simpler Redis-backed state binding for single-network cached-location sync; see [`CORE_CONFIG.md`](../configuration/CORE_CONFIG.md#network-redis).
