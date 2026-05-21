# rtp-proxy devstack

First-class runtime verification fixture for the cross-server `/rtp` slice
(CHECKLIST-cross-server-rtp.md L3). Boots 1 Redis + 2 Velocity proxies + 2
Paper backends on a single docker-compose network and exercises the
round-trip, kill-mid-flight, and kill-switch scenarios.

## Topology

```
            +-----------+        +-----------+
client ---> | proxy-a   |---+--->| backend-a |
            +-----------+   |    +-----------+
                            |
            +-----------+   +--->+-----------+
client ---> | proxy-b   |------->| backend-b |
            +-----------+        +-----------+
                          \             /
                           \           /
                            +-> redis <+
                             (heartbeat, claim/release/redeem)
```

- Proxies expose 25577 / 25578 on the host. Connect any Minecraft client to
  `localhost:25577` or `localhost:25578`.
- Backends are reachable only on the compose network, exactly as in production.
- Redis is published on 6379 for `redis-cli MONITOR` from the host.

## One-time setup

1. Build the jars (from repo root):

   ```powershell
   .\gradlew :rtp-plugin:shadowJar :rtp-proxy:rtp-proxy-velocity:shadowJar
   Copy-Item rtp-plugin\build\libs\rtp-plugin-*.jar rtp-proxy\devstack\jars\plugin\
   Copy-Item rtp-proxy\rtp-proxy-velocity\build\libs\rtp-proxy-velocity-*.jar rtp-proxy\devstack\jars\velocity\
   ```

2. Provision shared secrets:

   ```powershell
   cd rtp-proxy\devstack
   Copy-Item .env.example .env
   # edit .env and set RTP_NET_SECRET to a 32-byte base64 string
   Copy-Item shared\forwarding.secret.example shared\forwarding.secret
   # edit shared\forwarding.secret to any random string
   ```

3. Boot the stack:

   ```powershell
   docker compose up -d
   docker compose logs -f proxy-a backend-a
   ```

## Acceptance harness

`run-acceptance.ps1` drives the five harness scenarios from the host:

```powershell
.\run-acceptance.ps1                       # full sweep
.\run-acceptance.ps1 -Scenario boot         # individual scenario
.\run-acceptance.ps1 -Scenario heartbeat
.\run-acceptance.ps1 -Scenario roundtrip
.\run-acceptance.ps1 -Scenario killmidflight
.\run-acceptance.ps1 -Scenario killswitch
```

Each scenario is described in `docs/admin/proxies/CROSS_SERVER_VERIFICATION.md`.

### What the harness can verify headlessly

| Scenario           | Headless evidence                                                |
|--------------------|------------------------------------------------------------------|
| boot               | `docker compose ps` reports all five services `Up`               |
| heartbeat          | `redis-cli` lists 2 backend + 2 proxy heartbeat keys             |
| roundtrip          | requires a manual MC client login (see admin doc)                |
| killmidflight      | reservation row clears within `reservation.ttlMs + reapInterval` |
| killswitch         | Lua claim returns `KILL_SWITCH`; harness asserts proxy log line  |

The roundtrip scenario requires a live Minecraft client by design: the cross-
server `/rtp` pipeline is gated on `PlayerJoinEvent`, which a headless tool
cannot synthesize without re-implementing the protocol. The harness drives
every other scenario via Redis introspection and `docker exec` Bukkit-console
commands.

## Teardown

```powershell
docker compose down -v   # also removes the per-backend world volume
```

## See also

- `docs/admin/proxies/CROSS_SERVER_VERIFICATION.md` - operator-facing manual
  verification procedure for the same scenarios.
- `docs/admin/proxies/SINGLE_BACKEND_VERIFICATION.md` - single-backend
  precursor (no proxies, in-memory transport).
- `docs/dev/MULTI_SERVER_PLAN.md` - phase status and roadmap.
- `docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md` - umbrella ADR.
