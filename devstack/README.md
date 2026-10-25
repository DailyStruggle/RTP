# rtp-proxy devstack

First-class runtime verification fixture for the cross-server `/rtp` slice
(CHECKLIST-cross-server-rtp.md L3). Boots 1 Redis + 2 Velocity proxies + 2
Paper lobbies + 4 backends (Paper / Folia / Fabric / NeoForge) on a
single docker-compose network and exercises the round-trip, kill-mid-flight,
and kill-switch scenarios.

The backends are: `backend-a` runs Paper, `backend-b` runs Folia, `backend-c` runs Fabric, and `backend-d` runs NeoForge. Mixing Paper, Folia, Fabric, and NeoForge exercises all four platform scheduler and modding runtime families simultaneously, so platform regressions surface here immediately rather than in production. Every `/rtp` round-trip routes through the `BackendSelector`
against these platform adapters at once.

## Topology

```
            +-----------+        +-----------+        +-----------+
client ---> | proxy-a   |---+--->| lobby-a   |---+--->| backend-a | (Paper)
            +-----------+   |    +-----------+   |    +-----------+
                            |                    +--->+-----------+
                            |                    |    | backend-b | (Folia)
            +-----------+   +--->+-----------+   |    +-----------+
client ---> | proxy-b   |------->| lobby-b   |---+--->+-----------+
            +-----------+        +-----------+   |    | backend-c | (Fabric)
                                                 |    +-----------+
                                                 +--->+-----------+
                                                      | backend-d | (NeoForge)
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
  to across `backend-a` (Paper) / `backend-b` (Folia) / `backend-c` (Folia).
- After redeem, the player is transferred to the chosen backend by the
  proxy and teleported to the resolved coordinate.

This is the topology to use when verifying that a player on a lobby cannot
accidentally teleport locally and that the network-mode dispatch is the only
path to a destination.

## One-time setup

1. Build the jar (from repo root):

   ```powershell
   .\gradlew :rtp-plugin:remapJar
   Copy-Item rtp-plugin\build\libs\LeafRTP-Pro-*.jar devstack\jars\plugin\
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

`run-acceptance.ps1` drives the harness scenarios from the host:

```powershell
.\run-acceptance.ps1                       # full sweep (tears down automatically on exit)
.\run-acceptance.ps1 -Keep                 # leave containers running after scenarios finish
.\run-acceptance.ps1 -Scenario boot         # individual scenario
.\run-acceptance.ps1 -Scenario heartbeat
.\run-acceptance.ps1 -Scenario roundtrip
.\run-acceptance.ps1 -Scenario killmidflight
.\run-acceptance.ps1 -Scenario killswitch
.\run-acceptance.ps1 -Scenario rtptest      # in-game `/rtp test accessor` per server
```

Each scenario is described in `docs/admin/proxies/CROSS_SERVER_VERIFICATION.md`.

### What the harness can verify headlessly

| Scenario           | Headless evidence                                                |
|--------------------|------------------------------------------------------------------|
| boot               | `docker compose ps` reports all nine services `Up`              |
| heartbeat          | `redis-cli` lists 6 backend (4 backends + 2 lobbies) + 2 proxy keys |
| roundtrip          | requires a manual MC client login (see admin doc)                |
| killmidflight      | reservation row clears within `reservation.ttlMs + reapInterval` |
| killswitch         | Lua claim returns `KILL_SWITCH`; harness asserts proxy log line  |
| rtptest            | `/rtp test accessor` dispatched to every backend + lobby via `rcon-cli`; harness asserts the `[RTP test/accessor] pass=true` verdict per service |

The roundtrip scenario requires a live Minecraft client by design: the cross-
server `/rtp` pipeline is gated on `PlayerJoinEvent`, which a headless tool
cannot synthesize without re-implementing the protocol. The harness drives
every other scenario via Redis introspection and `docker exec` Bukkit-console
commands.

## Lite edition (plugin-message transport, no Redis)

By default the harness builds and stages the **Pro** jar and seeds each
backend/lobby with a Redis `network.yml`. Pass `-Lite` to instead exercise the
free/lite edition's DB-free, tier-1 plugin-message transport (ADR-024
2026-06-12 amendment, `rtp-proxy-ADR-016`):

```powershell
.\run-acceptance.ps1 -Lite                 # boot + roundtrip against the lite jar
.\run-acceptance.ps1 -Lite -Scenario boot   # single scenario
```

`-Lite` changes three things:

1. **Jar** - builds `:rtp-plugin:remapLiteJar` and stages the unclassified
   `LeafRTP-<ver>.jar` (no Redis/SQL `NetworkTransport` bindings) instead of
   `LeafRTP-Pro-<ver>.jar`.
2. **Seeds** - layers `docker-compose.lite.yml` (via the same `COMPOSE_FILE`
   chain the lobby-world overlay uses), which remounts each instance's
   `network-lite.yml` (`transport.type: auto`, `pluginMessage` block) over the
   Redis `network.yml`. Backends self-advertise region availability over the
   proxy's built-in `bungeecord:main` vocabulary; no Redis/SQL is required.
3. **Scenario plan** - under `-Scenario all`, only `boot` + the manual
   `roundtrip` run. The `heartbeat`, `killmidflight`, and `killswitch`
   scenarios introspect/drive Redis keys and do not apply to the lite tier.

The Redis container still boots (the base compose file defines it) but the lite
plugin never connects to it. Auto-detection completes on the first player join
(plugin messages ride a player connection), so the lite roundtrip is the
client-driven verification of the DB-free path. Mixing `-Lite` with the
lobby-world overlay is supported (both overlays compose cleanly).

### Testing a cross-server teleport on the lite stack

`docker-compose.lite.yml` is an **overlay**, not a standalone stack: it layers
on top of `docker-compose.yml`, which still defines the two Velocity proxies
(`proxy-a`, `proxy-b`). The proxy is still what performs the player move
(`Connect`) and relays backend->backend availability gossip (`Forward`); `-Lite`
only swaps the coordination *transport* underneath from Redis to the DB-free
plugin-message tier. So the cross-server `/rtp` path is exercised exactly as on
Pro - there is no proxy-less teleport (only a proxy can move a player between
backends).

Steps (a live 1.21.x Minecraft client is required - plugin messages ride a
real player connection and cannot be synthesized headlessly):

```powershell
cd devstack
.\run-acceptance.ps1 -Lite -Scenario roundtrip
```

1. Connect a client to `localhost:25577` (proxy-a). You land on `lobby-a` (or `backend-a`).
2. Under `proxy-direct` transport (rtp-proxy-ADR-017), backends dial the proxy companion's TCP port at boot to register their regions automatically—no manual player visits or server hopping required.
3. From the client, run `/rtp` (or the cross-server region form `/rtp region=<server>:<region>`) and observe the
   player being moved to another backend and teleported there.
4. Confirm in the destination backend's log window that it ran its local
   teleport pipeline on arrival.

**Evidence under lite differs from Pro.** The `roundtrip` scenario's automated
capture was written for the Redis tier: it samples reservation-token keys via
`redis-cli`, which are empty under lite (the plugin-message binding's `claim` is
best-effort/no-op by design - "no token" is expected, not a failure). On the
lite stack, verification is **visual + log-based**: confirm the teleport
in-client and grep the destination backend log for the arrival/teleport line
rather than looking for a reservation token.

## Java 17 legacy stack (unofficial pre-1.20.5 targets)

`docker-compose.legacy.yml` is a standalone stack (no proxy, no Redis) for the
unofficial ADR-113 targets: Paper 1.18.2 on `localhost:25591` and Fabric 1.20.1
on `localhost:25592`, both on the `java17` image so the jar is proven against a
Java 17 runtime.

```powershell
cd devstack
Copy-Item ..\rtp-plugin\build\libs\LeafRTP-Pro-*.jar legacy\paper-1_18\plugins\
Copy-Item ..\rtp-plugin\build\libs\LeafRTP-Pro-*.jar legacy\fabric-1_20_1\mods\
docker compose -f docker-compose.legacy.yml up -d
docker compose -f docker-compose.legacy.yml exec -T legacy-paper-1_18 rcon-cli "rtp test full"
docker compose -f docker-compose.legacy.yml exec -T legacy-fabric-1_20_1 rcon-cli "rtp test accessor"
docker compose -f docker-compose.legacy.yml down
```

Fabric 1.21.1 (Java 21, `localhost:25593`) is opt-in: stage the jar in
`legacy\fabric-1_21_1\mods\` and add `--profile fabric-1_21_1` to the
`docker compose` commands.

`rtp test full` is Bukkit-family only. Run console-only on an idle server, it
reports `commands`, `stress` and `memory-leak` as failed on Paper 1.21.11 as
well, so compare against that baseline rather than expecting 16/16.

## Bug-report window stack (1.21.11 and latest 26.x)

`docker-compose.versions.yml` is the same standalone layout for the versions
that accept bug reports (ADR-113): Paper 1.21.11 (`localhost:25594`), Paper 26.3
(`:25595`), Fabric 1.21.11 (`:25596`) and Fabric 26.3 (`:25597`). It sets
`PAUSE_WHEN_EMPTY_SECONDS=0`, because vanilla 1.21.2+ stops ticking an empty
server after 60 s, which also stops RTP's `rtp test` probes and cache fill.

```powershell
cd devstack
foreach ($d in 'paper-1_21_11\plugins','paper-26\plugins','fabric-1_21_11\mods','fabric-26\mods') { New-Item -ItemType Directory -Force "versions\$d" | Out-Null; Copy-Item ..\rtp-plugin\build\libs\LeafRTP-Pro-*.jar "versions\$d\" }
docker compose -f docker-compose.versions.yml up -d
docker compose -f docker-compose.versions.yml exec -T versions-paper-26 rcon-cli "rtp test full"
docker compose -f docker-compose.versions.yml exec -T versions-fabric-26 rcon-cli "rtp test accessor"
docker compose -f docker-compose.versions.yml down
```

`run-versions-smoke.sh` is the scripted check the nightly `versions-smoke` job
runs per service (default: `versions-paper-26` and `versions-fabric-26`). It
stages the Pro jar from `rtp-plugin/build/libs`, boots the services, and fails
unless `rtp test accessor` passes, `rtp info` shows a non-empty hot or cold
queue, and the log has no tick-loop crash, OOM or classfile-version error.
Server logs land in `logs/versions-<stamp>/`. On Windows run it from Git Bash:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' devstack/run-versions-smoke.sh --fresh
& 'C:\Program Files\Git\bin\bash.exe' devstack/run-versions-smoke.sh --service versions-fabric-1_21_11
```

For a player teleport, op the bot and run `clients/mineflayer-bot.js` against a
port (`--port 25594 --version 1.21.11 --target-region default`). Mineflayer
speaks up to 26.1, so 26.x player runs need a 26.1.2 server. Fabric lowercases
offline names in `op`, so pass a lowercase `--username` there. `--assert-effects`
can report no effects because the bot exits on landing, before the
`postteleport` effects arrive.

## Version sweep (one server per carrier boundary, with a bot)

`run-version-sweep.sh` walks the matrix at the top of the script (Paper,
Spigot, Fabric and NeoForge from 1.18.2 to 26.3, Folia from its 1.21.11
floor; older Folia builds crash on their own and are Unsupported, ADR-113
amendment 5) one row at a time on
`docker-compose.sweep.yml` (port 25598, fresh `/data` under `sweep/<slug>/`).
Each row must pass `rtp test accessor`, fill the hot or cold queue, and log no
linkage error (`NoSuchMethodError`, `AbstractMethodError`, ...), crash or OOM.
Rows with a bot version then run `clients/version-probe.js` twice as an opped
`SweepBot`:

- **effects:** `/rtp region=default` must land and deliver sound, particle and
  potion packets, a `preteleport` console `COMMAND` and a `postteleport` player
  `COMMAND`; `GLIDE.ELYTRA` and `FIREWORK` are configured and reported.
- **death:** after a restart with `DEATH` as the `postteleport` effect and the
  cooldown at 0, the bot must die, respawn, and see the preteleport and
  `when: death` console `COMMAND`s.

Mineflayer speaks up to 26.1, so the 26.2+ rows add ViaVersion/ViaBackwards
(or ViaFabric, alpha builds) and connect as 26.1; a row with bot `-` stops
after the queue check. Paper and Folia 26.3 currently kick the bot through
ViaBackwards (`invalid_player_movement`), so those rows only prove boot and
queue fill. Spigot rows build with BuildTools on first use (up to 30 minutes).
Only one sweep can run at a time: every row uses the `rtp-devstack-sweep`
container and port 25598.
Results go to `logs/sweep-<stamp>/summary.tsv`, with each row's `server.log`,
`bot-effects.log`, `bot-death.log` and any `linkage.txt`. A full run takes
about two hours; `--only` takes a regex over slugs:

```powershell
& 'C:\Program Files\Git\bin\bash.exe' devstack/run-version-sweep.sh --only '^(paper|folia)-1_21_11'
```

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

## Multi-server / multi-world GUI demo

The devstack ships pre-configured to showcase the `LeafRTPGuiAddon` (the
"DonutSMP-style" `/rtp` destination picker) across **multiple servers**, with
each backend's destination in a **different dimension** to demo per-server load
distribution. Three pieces make the menu rich:

1. **One `default` region per backend, each in a distinct dimension, with a
   configurable `displayName`.** Each lives in the backend's
   `rtp-config/regions/default.yml` and is seed-copied into the running container
   by the compose entrypoint shim (into `/data/plugins/RTP/regions/`). Only the
   backend-c and backend-d files are tracked; `devstack/.gitignore` excludes the
   whole `backend-a/` and `backend-b/` `rtp-config/` trees, so a fresh clone has
   no demo region for those two:

   | Backend              | Dimension | `displayName`               |
   |----------------------|-----------|-----------------------------|
   | backend-a (Paper)    | Overworld | `&#55ff55Verdant Wilds`     |
   | backend-b (Folia)    | Nether    | `&#ff5533Ashen Wastes`      |
   | backend-c (Fabric)   | End       | `&#c77dffVoid Reaches`      |
   | backend-d (NeoForge) | Overworld | `&#ffb703Neo Forge Frontier`|

   The optional cosmetic `displayName` key (color/gradient supported) renames the
   region in `/rtp info` and the GUI menu without changing its identity (the
   region key stays `default`), and is advertised over the network so a lobby
   shows each backend's chosen words for its cross-server destination. All
   regions are centered on `(0,0)`. Bare radii are in chunks (backend-c: 150 =
   2,400 blocks; backend-d: 2,000 = 32,000 blocks), so they reach well past the
   spawn area; the full pre-generated world copy (below) is what keeps them on
   generated terrain. backend-c (Fabric) targets the End via the world name
   `world_the_end`; backend-d uses the namespaced `minecraft:overworld`.

2. **A supplied pre-generated world.** A freshly-booted world generates chunks
   on demand, and under load the async chunk loads can time out and surface as
   null-chunk pipeline failures (most visibly on Fabric). `seed-pregen-worlds.ps1`
   copies a known pre-generated single-server world into each backend's
   **gitignored** bind-mounted world dirs. By default it copies the **full
   world** (every `.mca`), which both eliminates the null-chunk timeouts and
   gives the L3 backlog cache (`backlogCacheCap`, set in the region files) real
   `.mca` files to anvil-prefilter - the backlog cache only does useful work
   when there are on-disk `.mca` files to check. All four backends also bind
   their `world_nether` and `world_the_end` dirs (Paper/Folia keep Nether/End
   as separate top-level dirs). Pass `-RegionRadius <n>` (>= 0) to copy only a small
   spawn-area window instead (cheaper, but supplies too few `.mca` for the
   backlog cache to do meaningful prefiltering).

3. **The GUI addon**, installed into the Bukkit-family instances so a bare
   `/rtp` opens the chest menu (see `add-gui-addon.ps1`).

### One command

```powershell
cd devstack
.\setup-multiworld-demo.ps1                 # pre-gen worlds + install the GUI addon
.\setup-multiworld-demo.ps1 -NoGuiAddon     # pre-gen worlds + regions only (opt out of the addon)
docker compose up -d
```

Then connect a 1.21.x client to `localhost:25577` (proxy-a). You land on a
lobby, whose own `regions/` is intentionally empty - so `/rtp` there shows the
**cross-server peer regions** advertised by all four backends, each labelled by
its configured `displayName` (Verdant Wilds -> Overworld, Ashen Wastes ->
Nether, Void Reaches -> End, Neo Forge Frontier -> Overworld). Run `/server
backend-a` (or `backend-b` / `backend-c` / `backend-d`) and `/rtp` to teleport
into that backend's default region directly.

Pass `-Source <path>` to point at a different pre-generated world, or
`-RegionRadius <n>` (>= 0) to copy only a small spawn-area window instead of the
full world. The demo regions survive
`reset-rtp-config.ps1` (they live in the seed `rtp-config/regions/` tree, not in
the runtime `plugins/RTP/` tree it wipes).

## Teardown

By default, `run-acceptance.ps1` and `run-acceptance.sh` automatically tear down the devstack containers when a scenario run finishes or exits. Pass `-Keep` (or `--keep` in Bash) if you want to leave the containers running for interactive inspection or manual testing.

To manually tear down a kept stack:

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
