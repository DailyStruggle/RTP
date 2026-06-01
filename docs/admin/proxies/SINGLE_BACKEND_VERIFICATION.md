# Single-Backend Network Verification (`rtp test network` + Redis)

This page documents how a single Paper/Folia backend operator can verify the RTP network-mode SPI end-to-end against a real Redis instance, **without** standing up a proxy or a second backend. The verification uses `rtp test network`, an in-process Shape A simulator that publishes synthetic peers through the live `NetworkTransport`, asserts subscriber fan-out, and exercises the reservation-token claim/release/reap path.

> Scope: this is a **single-JVM** smoke test. It proves the Redis transport binding is reachable, the Lua scripts load, heartbeats round-trip, and reservation tokens claim atomically. It does **not** prove multi-server agreement, HMAC trust boundaries (Phase 4), or proxy-side routing. Multi-backend acceptance lives in `MULTI_SERVER_PLAN.md` Phase 2 acceptance row.

Related design docs:

- [`docs/dev/MULTI_SERVER_PLAN.md`](../../dev/MULTI_SERVER_PLAN.md) - phase status, Phase 2e-Redis A1/A2 slice notes.
- [`rtp-proxy-ADR-005`](../../../platforms/rtp-proxy/docs/adr/rtp-proxy-ADR-005-redis-binding.md) - Redis binding key layout and Lua scripts.
- [`rtp-proxy-ADR-002`](../../../platforms/rtp-proxy/docs/adr/rtp-proxy-ADR-002-network-yml-schema.md) - `network.yml` schema and validation rules.
- [`rtp-proxy-ADR-010`](../../../platforms/rtp-proxy/docs/adr/rtp-proxy-ADR-010-security-hardening.md) - HMAC + kill switch (deferred for single-backend).
- REQ-RTP-NET-002 (parity when disabled), REQ-RTP-PROXY-007 (`secretEnv` fail-fast).

---

## Prerequisites

- A running Paper or Folia backend on Java 21+, RTP plugin installed (full jar, not lite - lite strips Jedis and the SQL/Redis drivers per ADR-024).
- Docker available on the same host (or a reachable Redis 7+ instance on the network).
- Console / op access to run `/rtp test network` from the server console or an opped player.

---

## Step 1: Start Redis

The simplest local Redis is a single container:

```powershell
docker run -d --name rtp-redis -p 6379:6379 redis:7-alpine
```

Verify:

```powershell
docker exec rtp-redis redis-cli PING
# expect: PONG
```

For a Redis that already lives in your infrastructure, skip this step and use its host/port/password below.

---

## Step 2: Set the shared-secret env var

The plugin's network bootstrap fail-fasts at startup if `network.enabled: true` and the configured `secretEnv` is unset or empty. This guard is REQ-RTP-PROXY-007; it fires regardless of whether HMAC envelope enforcement is wired (the wire-level HMAC verifier is Phase 4, but the operator-discipline guard fires today).

Generate a 32+ byte base64 secret and export it before launching the server JVM:

```powershell
# Generate (Windows PowerShell):
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
# Copy the printed string.

# Export for the current shell (the server JVM inherits this):
$env:RTP_NET_SECRET = "<paste the base64 string>"
```

On Linux:

```bash
RTP_NET_SECRET=$(head -c 32 /dev/urandom | base64)
export RTP_NET_SECRET
```

If you forget this step, the server log will print `network.secretEnv='RTP_NET_SECRET' is unset or empty ... (REQ-RTP-PROXY-007)` and network mode will refuse to enable. The rest of the plugin works normally.

---

## Step 3: Edit `plugins/RTP/network.yml`

Boot the server once with default settings so the plugin writes the bundled `network.yml` into the data folder. Then stop the server and edit:

```yaml
network:
  enabled: true
  schemaVersion: 1
  serverId: "test-1"          # any stable string; must be unique across the network
  role: backend
  secretEnv: RTP_NET_SECRET

transport:
  type: redis                 # was 'sql' by default
  redis:
    host: localhost           # or your Redis hostname
    port: 6379
    password: ""              # set if Redis has requirepass / ACL

heartbeat:
  intervalMs: 1000
  staleAfterMs: 5000
```

Keep `serverId` non-empty - two backends sharing one `serverId` would overwrite each other's heartbeat row. For a single-backend smoke test any string works (e.g. `"test-1"`).

---

## Step 4: Start the server and confirm bootstrap

Restart the server. Look for these log lines in order:

```
[NETWORK] Backend network mode enabled: serverId='test-1' transport=redis intervalMs=1000 reapIntervalMs=30000
```

If you instead see:

- `network.secretEnv='RTP_NET_SECRET' is unset or empty` -> go back to Step 2.
- `transport.type=redis requires transport.redis.host to be set` -> `host` was blank; fix `network.yml`.
- A Jedis `JedisConnectionException` -> the `host:port` is wrong or Redis isn't accepting connections.

A correctly-booted backend will start publishing its own heartbeat to `rtp:net:backend:test-1` every second. You can verify externally:

```powershell
docker exec rtp-redis redis-cli HGETALL rtp:net:backend:test-1
# expect: a hash with serverId, pluginState, lastSeenEpochMs, ...
```

---

## Step 5: Run the simulator

From the server console (or as an opped player):

```
/rtp test network all count=4
```

Probe-mode subcommands and their parameters (commands-api §2.2: bare tokens dispatch as subcommands; sizing is passed as `key=value` parameters):

- `/rtp test network` - heartbeat round-trip with defaults (3 peers, 500ms observe window).
- `/rtp test network heartbeat [count=N] [observeMs=M]` - publish N synthetic peers (default 3, max 16), assert snapshot + subscriber fan-out, clean up with `SHUTTING_DOWN`. `observeMs` clamps to 250..30000.
- `/rtp test network tokens [count=N] [ttlMs=M]` - claim N reservation tokens (default 2, max 8), find them, release them, then verify a doomed 1ms-TTL token gets reaped within the observe window. `ttlMs` clamps to 250..30000.
- `/rtp test network all [count=N] [observeMs=M]` - run heartbeat then tokens. Recommended for a first verification.

The audit row appears in console output and the server log. A successful run on the Redis transport looks like:

```
[RTP test/network] PASS: peers=4 observed=4/4 latency=<us>us (RedisNetworkStateBinding)
[RTP test/network] PASS: tokens peers=4 claim_us=<us> find_us=<us> release_us=<us> reap_us=<us> total_us=<us> (RedisNetworkStateBinding)
```

If the audit row reports `(InMemoryNetworkStateBinding)` instead of `(RedisNetworkStateBinding)`, the D3 slot resolved to the wrong transport - usually because `network.enabled: false` or because `transport.type` was not changed from `sql` / `in-memory`. Re-check `network.yml`.

A `NOT-CONFIGURED` skip means the binding never opened. Look upstream in the log for the bootstrap warning.

---

## Step 6: Verify on the Redis side (optional, satisfying)

While the test is running you can watch the keyspace from a second terminal:

```powershell
docker exec rtp-redis redis-cli --scan --pattern 'rtp:net:*'
# expect rtp:net:backend:test-1 plus rtp:net:backend:rtp-sim-0-test ... -3-test
#   and (during tokens mode) rtp:net:tok:* and rtp:net:tokactive:* entries

docker exec rtp-redis redis-cli MONITOR
# expect HSET / EXPIRE / PUBLISH / EVALSHA traffic at ~1Hz
```

The simulator cleans up its synthetic rows by republishing them with `pluginState=SHUTTING_DOWN` and a far-past `lastSeenEpochMs`, so the reaper drops them on the next pass. `redis-cli --scan` should converge back to a single `rtp:net:backend:test-1` row within `heartbeat.staleAfterMs` after the test finishes.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `network.secretEnv='RTP_NET_SECRET' is unset` at startup | Env var not exported to the server JVM | Step 2; on systemd, set in the unit's `Environment=` block |
| `JedisConnectionException: Failed to connect` | Wrong host/port or Redis bound to `127.0.0.1` inside docker | Use `host.docker.internal` or expose with `-p 0.0.0.0:6379:6379` |
| Audit row says `(InMemoryNetworkStateBinding)` | `network.enabled: false` or `transport.type` not `redis` | Re-check `network.yml`; the bootstrap log line in Step 4 confirms transport type |
| Audit row says `NOT-CONFIGURED` | Bootstrap failed silently; binding not installed on the D3 slot | Look earlier in the log for `[NETWORK]` warnings; usually a config validation throw |
| `Lua script SHA mismatch` on first claim | Redis was restarted mid-session and lost its script cache, OR the shipped `.sha1` sidecar drifted from `.lua` | Restart the server; the binding re-loads on construction. Persistent mismatch is a bug - file with the audit row attached |
| `tokens` step times out on TTL reap | Reservation reaper interval is longer than the observe window | Lower `reservation.reapIntervalMs` in `network.yml` (default 30000ms) or run the test with a larger observe argument |
| Heartbeat row never appears in `HGETALL` | Server clock skew vs Redis past the TTL window | Sync NTP on the host |

---

## What this does NOT verify

- **Multi-server agreement**: a second backend reading the first backend's heartbeat. That requires two JVMs and is the Phase 2 acceptance row in `MULTI_SERVER_PLAN.md`.
- **Proxy-side routing**: `/rtp` on a Velocity proxy dispatching to this backend. Requires a Velocity instance with `rtp-proxy-velocity` and the same `RTP_NET_SECRET`.
- **HMAC verification**: ADR-010 Phase 4. The single-backend self-loop never crosses a trust boundary, so wire-level HMAC is not currently enforced even when Redis is the transport.
- **Kill switch propagation**: ADR-010, deferred.
- **DragonflyDB compatibility**: open Phase 2 acceptance row; the Lua scripts are written to RESP-compatible primitives but have not been exercised against Dragonfly.
- **Network partition recovery**: A4 hardening turn, not yet implemented; the current binding logs and retries on reconnect via Jedis pool's default behaviour.

---

## Tearing down

```powershell
# Stop the server normally so SHUTTING_DOWN heartbeats are published.
# Then:
docker stop rtp-redis
docker rm rtp-redis
```

In `network.yml`, set `network.enabled: false` to return the backend to byte-identical pre-Phase-2 behaviour (REQ-RTP-NET-002). The `secretEnv` no longer needs to be exported once disabled.

---

## Promoting to multi-backend

Once a single-backend run is green:

1. Start a second backend on a different `serverId`, same `RTP_NET_SECRET`, same Redis host. Confirm each sees the other's row in `HGETALL rtp:net:backend:<other-serverId>` and that `/rtp test network heartbeat count=1` observes the real peer in its snapshot alongside the synthetic one.
2. Add a Velocity proxy with `rtp-proxy-velocity`, same secret, same Redis. The proxy publishes its own heartbeat on `rtp:net:proxy:<proxyId>` and reads backend snapshots for selection.
3. The 2-proxy + 2-backend devstack acceptance harness is the next plan box (Phase 2, `MULTI_SERVER_PLAN.md`) and remains an open follow-up.
