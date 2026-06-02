# rtp-proxy devstack

First-class runtime verification fixture for the cross-server `/rtp` slice
(CHECKLIST-cross-server-rtp.md L3). Boots 1 Redis + 2 Velocity proxies + 2
Paper lobbies + 3 platform-asymmetric backends (Paper / Folia / Fabric) on a
single docker-compose network and exercises the round-trip, kill-mid-flight,
and kill-switch scenarios.

The backend trio is deliberately heterogeneous: `backend-a` runs Paper,
`backend-b` runs Folia, and `backend-c` runs Fabric. Matched-pair Paper
backends would only exercise one scheduler family, so a Paper-compiles-but-
Folia-blows-up (or `rtp-fabric`-adapter) regression would not surface until a
user reported it. Asymmetric backends route every `/rtp` round-trip through
the `BackendSelector` against three platform adapters at once.

> `backend-c` (Fabric) intentionally exercises the `rtp-fabric` adapter while
> it is still stabilizing (see `docs/dev/MULTI_PLATFORM_PLAN.md`). Expect
> failures here to be common until the adapter lands its outstanding work;
> that is the point of including it - stability cannot be verified without
> testing.

## Topology

```
            +-----------+        +-----------+        +-----------+
client ---> | proxy-a   |---+--->| lobby-a   |---+--->| backend-a | (Paper)
            +-----------+   |    +-----------+   |    +-----------+
                            |                    +--->+-----------+
                            |                    |    | backend-b | (Folia)
            +-----------+   +--->+-----------+   |    +-----------+
client ---> | proxy-b   |------->| lobby-b   |---+--->+-----------+
            +-----------+        +-----------+        | backend-c | (Fabric)
                                                      +-----------+
                                       \                  /
                                        +---> redis <----+
                                  (heartbeat, claim/release/redeem)
```

- Proxies expose 25577 / 25578 on the host. Connect any Minecraft client to
  `localhost:25577` or `localhost:25578`.
- Lobbies and backends are reachable only on the compose network, exactly as
  in production.
- Redis is published on 6379 for `redis-cli MONITOR` from the host.

### Lobbies (no local RTP region; cross-server dispatch only)

`lobby-a` and `lobby-b` are full Paper servers with the same unified
`LeafRTP-Pro-<ver>.jar` and `network.yml` (`role: backend`, unique `serverId`) as
the destination backends, but their `regions/` directory is **intentionally
empty** (see `lobby-{a,b}/rtp-config/regions/README.md`). This makes them the
first hop a fresh connection lands on (both `velocity.toml` `try` lists put
lobbies before backends), and exercises the lobby use case:

- A player joins via `proxy-a` and lands on `lobby-a` (or `lobby-b` on
  `proxy-b`). The lobby has no local destination region, so running `/rtp`
  cannot resolve to a same-server coordinate.
- The cross-server pipeline dispatches the request: either to a named
  backend (`/rtp <region-on-backend-a>` style usage if the operator wires
  that up) or to whichever destination the `BackendSelector` load-balances
  to across `backend-a` (Paper) / `backend-b` (Folia) / `backend-c` (Fabric).
- After redeem, the player is transferred to the chosen backend by the
  proxy and teleported to the resolved coordinate.

This is the topology to use when verifying that a player on a lobby cannot
accidentally teleport locally and that the network-mode dispatch is the only
path to a destination.

## One-time setup

1. Build the jars (from repo root):

   ```powershell
   .\gradlew :rtp-plugin:shadowJar :rtp-proxy:rtp-proxy-velocity:shadowJar
   Copy-Item rtp-plugin\build\libs\LeafRTP-Pro-*.jar devstack\jars\plugin\
   Copy-Item rtp-proxy\rtp-proxy-velocity\build\libs\rtp-proxy-velocity-*.jar devstack\jars\velocity\
   ```

2. Provision shared secrets:

   ```powershell
   cd devstack
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
| boot               | `docker compose ps` reports all eight services `Up`             |
| heartbeat          | `redis-cli` lists 5 backend (3 backends + 2 lobbies) + 2 proxy keys |
| roundtrip          | requires a manual MC client login (see admin doc)                |
| killmidflight      | reservation row clears within `reservation.ttlMs + reapInterval` |
| killswitch         | Lua claim returns `KILL_SWITCH`; harness asserts proxy log line  |

The roundtrip scenario requires a live Minecraft client by design: the cross-
server `/rtp` pipeline is gated on `PlayerJoinEvent`, which a headless tool
cannot synthesize without re-implementing the protocol. The harness drives
every other scenario via Redis introspection and `docker exec` Bukkit-console
commands.

## Lobby world (optional)

By default both lobbies generate a vanilla flat-ish Paper world on first boot.
For a more presentable lobby (custom build, decorated spawn, etc.) the
devstack supports a one-time "bake" workflow that turns a WorldEdit schematic
into a reusable lobby world zip:

1. Drop a `.schem` (or `.schematic`) file into `shared/lobby-world/`. The
   directory is committed via `.gitkeep`; schematics themselves are gitignored
   (most marketplace schematics forbid redistribution).
2. Install FastAsyncWorldEdit into `lobby-a/plugins/` (drop the jar in
   alongside `LeafRTP-Pro-*.jar`). The compose file auto-mounts
   `shared/lobby-world/` into FAWE's schematics pickup dir at
   `/data/plugins/FastAsyncWorldEdit/schematics` (read-only), so any `.schem`
   you drop into `shared/lobby-world/` is immediately visible to
   `//schem list` without copying. Bring the stack up, join `lobby-a` from a
   Minecraft client, then run:
   ```
   //schem load <name-without-extension>
   //paste -a
   /setworldspawn
   ```
3. Disconnect, stop the stack (`docker compose stop lobby-a` is enough), and
   from the host:
   ```powershell
   .\scripts\bake-lobby-world.ps1
   ```
   This zips `lobby-a/world/` to `shared/lobby-world.zip`.
4. From now on, `run-acceptance.ps1` automatically layers
   `docker-compose.lobby-world.yml` on top of the base compose file when the
   zip is present, so every `up` boots both lobbies from the canned world
   (itzg/minecraft-server `WORLD` + `FORCE_WORLD_COPY=TRUE`).

To use the override manually (without `run-acceptance.ps1`):

```powershell
docker compose -f docker-compose.yml -f docker-compose.lobby-world.yml up -d
```

To disable: delete `shared/lobby-world.zip`. Lobbies revert to vanilla on the
next boot. See `shared/lobby-world/README.md` for the full contract.

## Teardown

```powershell
.\run-acceptance.ps1 -Scenario down            # selective: preserves the mc-image-cache named volume
.\run-acceptance.ps1 -Scenario down -Purge     # also drops mc-image-cache (re-downloads Paper + Mojang jar on next up)
```

The default selective teardown stops containers and removes the compose
network, but keeps the shared `mc-image-cache` named volume so the next
`up` doesn't redownload the Paper build jar and Mojang `server.jar`
(~110 MB per MC service). World directories are host bind mounts and are
wiped on every teardown regardless. Pass `-Purge` for a truly fresh
reset (equivalent to the old `docker compose down -v` behavior).

## See also

- `docs/admin/proxies/CROSS_SERVER_VERIFICATION.md` - operator-facing manual
  verification procedure for the same scenarios.
- `docs/admin/proxies/SINGLE_BACKEND_VERIFICATION.md` - single-backend
  precursor (no proxies, in-memory transport).
- `docs/dev/MULTI_SERVER_PLAN.md` - phase status and roadmap.
- `docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md` - umbrella ADR.
