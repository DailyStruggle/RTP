# Server & Network Design Guide (`LeafRTP`)

This guide maps **obvious (everyday)** and **non-obvious (specialized / multi-server / edge-case)** Minecraft server designs to their exact `LeafRTP` configuration files, commands, and permissions.

For visual configuration of any blueprint below, run `/rtp editor` ([Web Editor Guide](WEB_EDITOR_GUIDE.md)) or `/rtp admin setup` ([Quick Start](QUICK_START.md)).

---

## Server Topology & Dimension Matrix

| Server Design | Region & World Mapping (`definitions/`) | Key Tuning (`advanced/performance.yml` / `safety.yml` / `advanced/network.yml`) | Pre-Scan (`/rtp scan`) |
|---|---|---|---|
| **1. Single-World Survival SMP** (`Paper` / `Spigot` / `Fabric` / `NeoForge`) | `definitions/regions/default.yml` (`world: "world"`, `shape.radius: 10000b`, `shape.centerRadius: 500b`) | Keep `syncLoading: false`, `viewDistanceSelect: 0`, `cacheCap: 50`; grant `rtp.use` only (never `rtp.unqueued` or `rtp.*`) | Run `/rtp scan start region=default` once |
| **2. Folia Regionised Multithreaded Server** (`Folia 1.21.11+`) | 1 region per target dimension (`default.yml`, `nether.yml`, `end.yml`) | Keep `syncLoading: false` and `autoTune: true`; regional work division and `CountBoundTaskPipe` isolate tick budgets per region | Run `/rtp scan start region=<name>` on static worlds |
| **3. Multi-World Single JVM with Resetting Resource World** | Dedicated `definitions/regions/resource.yml` (`world: "resource_world"`) mapped from `definitions/worlds/resource_world.yml` | Set `cacheCap: 25` on `resource.yml`; leave `lobby` / `creative` worlds unmapped | **Skip** `/rtp scan` on frequently wiped `resource_world`s |
| **4. Nether Below-Bedrock Ceiling (`Y < 128`)** | Dedicated `definitions/regions/nether.yml` (`world: "world_nether"`, `vert.minY: 32`, `vert.maxY: 120`, `vert.requireSkyLight: false`) | Never change `default.yml` to `world_nether` (that redirects all unmapped worlds into the Nether) | Run `/rtp scan start region=nether` |
| **5. End Outer Islands (Skipping the Dragon Island & Void Moat)** | Dedicated `definitions/regions/end.yml` (`world: "world_the_end"`, `shape.centerRadius: 1000b`, `shape.radius: 10000b`, `vert.minY: 45`, `vert.maxY: 120`, `vert.requireSkyLight: false`) | `shape.centerRadius: 1000b` creates a 1,000-block donut hole over the central island and void gap; pre-scan maps remaining void chunks | Run `/rtp scan start region=end` |
| **6. Underground / Cave / Stoneblock / Deep-Dark World** | Dedicated region with `vert.name: LINEAR` (or `JUMP`), `vert.minY: -56`, `vert.maxY: 40`, `vert.requireSkyLight: false` | Disable sky-light requirement (`requireSkyLight: false`) so sealed caverns qualify as valid landing spots | Run `/rtp scan start region=<name>` |
| **7. Ocean / Archipelago / Island World (80%+ Water)** | Standard region + `/rtp scan start region=<name>` to map ocean sectors into spatial memory ahead of time | Keep `biomeWhitelist: false` (or whitelist land biomes only after `/rtp scan`), or allow water/boat/platform landings via `advanced/schematics/` | **Required:** `/rtp scan start region=<name>` prevents runtime reroll storms over open ocean |
| **8. Auth / Limbo Lobby -> Survival Handoff** | Map `definitions/worlds/world.yml` -> `region: "default"`; leave `limbo` / `auth` unmapped | Grant `rtp.onevent.changeworld` (never `rtp.onevent.join`) so scatter fires only when entering survival after authentication | Run `/rtp scan start region=default` |
| **9. Pure Dispatch Hub + N Survival Backends (`Velocity` / `BungeeCord`)** | Hub has `advanced/network.yml` `network.enabled: true`, `routing.lobbyMode: true`; survival shards have `network.enabled: true`, `routing.mode: "auto"` | Set unique `network.serverId` per node and shared `RTP_NET_SECRET`; `loadBalancer` scores peers by `keptRegion`, `mspt`, `queueDepth`, and `heapUsed` | Run `/rtp scan start` on each survival backend |
| **10. Heavily Claimed / Long-Running Map (Dense Land Claims)** | Enable `rerollWorldGuard: true`, `rerollGriefPrevention: true`, `rerollLands: true`, `rerollTownyAdvanced: true` in `integrations.yml` | Enable `shape.expand: true` or increase `shape.radius`, and keep `advanced/ttl.yml` claim TTLs active so claimed chunks are remembered in spatial memory | Run `/rtp scan start` to pre-mark static claims |
| **11. Unpregenerated World on Shared / Low-RAM Hosting** | Modest `shape.radius` (e.g. `5000b`) + `cacheCap: 20`, `activeChunkCap: 5` in `definitions/regions/default.yml` | Set `pregeneratedPreference: 0.9`, `syncLoading: false`, `viewDistanceSelect: 0`, `maxHeapPercent: 80` in `advanced/performance.yml` | Prefer `/rtp fill` or `pregeneratedPreference: 0.9` |

---

## Part 1: Obvious Server Designs

### 1. Single-World Survival SMP (`Paper` / `Spigot` / `Purpur` / `Fabric` / `NeoForge`)

For a standard one-overworld survival server where players type `/rtp` (or `/wild`) to land between 500 and 10,000 blocks from spawn:

1. **Configure `definitions/regions/default.yml`** (or drag the border in `/rtp editor`):
   ```yaml
   world: "world"
   cacheCap: 50
   activeChunkCap: 10
   shape:
     name: CIRCLE
     mode: ACCUMULATE
     radius: 10000b
     centerRadius: 500b
     centerX: 0
     centerZ: 0
   ```
2. **Permissions:** Grant regular players `rtp.use` only. Do **not** grant `rtp.unqueued`, `rtp.params`, or `rtp.*` to default players (those bypass the pre-warmed `cacheCap` queue and force live on-demand chunk selection).
3. **Pre-scan once:** Run `/rtp scan start region=default` to map oceans and hazards into persistent spatial memory off-tick.

---

### 2. Folia Regionised Multithreaded Server (`Folia 1.21.11+`)

On `Folia`, tick execution is split across independent spatial regions (`RegionizedServer`).

- **How `LeafRTP` operates on Folia:**
  - All candidate chunk reads use asynchronous chunk I/O and off-tick `.mca` / `.linear` region-file pre-filtering (`S-005`).
  - Background queue warming (`QueueTask`) and entity teleports flow through `CountBoundTaskPipe` and `Bukkit.isOwnedByCurrentRegion` checks so no world or region stalls another.
- **Configuration:** Keep `syncLoading: false` and `viewDistanceSelect: 0` in `advanced/performance.yml`. Verify Folia region ownership and pipeline health anytime with `/rtp test` and `/rtp info`.

---

### 3. Multi-World Single JVM (Main Survival + Resetting Resource World + Creative + Hub)

When one server hosts `world` (permanent survival), `resource_world` (wiped weekly/monthly), `creative`, and `lobby`:

1. **Create a dedicated region per RTP-enabled world:**
   - `definitions/regions/default.yml` -> `world: "world"` (`shape.radius: 15000b`, `shape.uniquePlacements: 1` so players never land on the same chunk twice).
   - `definitions/regions/resource.yml` -> `world: "resource_world"` (`shape.radius: 5000b`, `shape.uniquePlacements: 0`).
2. **Map each world in `definitions/worlds/`:**
   - `definitions/worlds/world.yml` -> `region: "default"`
   - `definitions/worlds/resource_world.yml` -> `region: "resource"`
   - Leave `lobby` and `creative` unmapped (or set `requirePermission: true` on their region) so players in the lobby or creative world do not trigger random survival scatters unless targeting `/rtp world=world`.
3. **Non-obvious rule for resetting resource worlds:**
   - Pre-scan your permanent `default` region (`/rtp scan start region=default`), but **skip** `/rtp scan` on `resource` if you wipe `resource_world`'s region files every week—or run `/rtp reload` after wiping `resource_world` so stale spatial memory from the old seed is discarded.

---

## Part 2: Non-Obvious Dimension & Terrain Designs

### 4. Nether Below-Bedrock Ceiling (`Y = 32..120`, Never on the Roof)

**The classic mistake:** Editing `definitions/regions/default.yml` to `world: "world_nether"`, or leaving `vert.requireSkyLight: true` and `vert.maxY: 320` in the Nether.
- Changing `default.yml` to `world_nether` redirects **all** unmapped worlds into the Nether.
- Leaving `vert.requireSkyLight: true` in the Nether rejects every block because the Nether has a bedrock ceiling with zero skylight.
- Leaving `vert.maxY` above `126` places players on top of the `Y = 128` bedrock roof.

**The right way:** Create `definitions/regions/nether.yml` and map `definitions/worlds/world_nether.yml` to `region: "nether"` (or run `/rtp config worlds world_nether region=nether`):

```yaml
# definitions/regions/nether.yml
world: "world_nether"
cacheCap: 30
shape:
  name: CIRCLE
  mode: ACCUMULATE
  radius: 5000b
  centerRadius: 250b
  centerX: 0
  centerZ: 0
vert:
  name: LINEAR
  direction: DOWN
  minY: 32
  maxY: 120
  requireSkyLight: false
```

---

### 5. The End Outer Islands (Skipping the Dragon Island & 1,000-Block Void Moat)

In vanilla Minecraft, The End has a central dragon island around `(0, 0)`, surrounded by roughly `1,000` blocks of empty void before the outer chorus-fruit islands begin, and has no skylight.

**How to design an End region (`definitions/regions/end.yml`):**
1. Set `shape.centerRadius: 1000b` and `shape.radius: 10000b` so candidate selection starts **outside** the central island and void moat.
2. Set `vert.minY: 45`, `vert.maxY: 120`, and `vert.requireSkyLight: false`.
3. Run `/rtp scan start region=end` so the `.mca` / `.linear` Anvil pre-filter marks void chunks between End islands in spatial memory ahead of time:

```yaml
# definitions/regions/end.yml
world: "world_the_end"
cacheCap: 30
shape:
  name: CIRCLE
  mode: ACCUMULATE
  radius: 10000b
  centerRadius: 1000b
  centerX: 0
  centerZ: 0
vert:
  name: LINEAR
  direction: DOWN
  minY: 45
  maxY: 120
  requireSkyLight: false
```

---

### 6. Underground / Cave-Only / Stoneblock / Deep-Dark Worlds

On cave-survival or Stoneblock worlds where the surface is deadly or solid rock and players must spawn inside underground caverns between `Y = -56` and `Y = 40`:

```yaml
# definitions/regions/caves.yml
world: "cave_world"
vert:
  name: LINEAR
  direction: UP
  minY: -56
  maxY: 40
  requireSkyLight: false
```

- Setting `vert.requireSkyLight: false` is mandatory underground.
- Using `vert.direction: UP` starts from `minY` (`-56`) and scans upward for the first safe 2-block air pocket with a solid floor; `direction: DOWN` starts from `maxY` (`40`) and scans downward.

---

### 7. Ocean / Archipelago / Island Worlds (High Water-to-Land Ratio)

On custom generator maps where 80–95% of chunks are ocean and safe land islands are small:

- **Why naive setups struggle:** If `WATER` is blocked in `safety.yml` (`unsafeBlocks`) and 90% of the map is ocean, uncached random picks will reject 9 out of 10 chunks.
- **Three solutions (pick one or combine):**
  1. **Off-Tick Spatial Pre-Scan (`/rtp scan start region=default`):** Walks the region's Archimedean spiral using the fast `.mca` / `.linear` region-file reader (`anvil-api`) and records ocean chunks in spatial memory (`mode: ACCUMULATE`), so future selections jump directly to real islands in $O(1)$.
  2. **Arrival Platform Schematic (`advanced/schematics/`):** Configure a small boat, raft, or lilypad/dock schematic in the region so players landing on water receive a safe floating platform (`PlatformRestoreSqlStore` automatically cleans temporary platforms up after expiration).
  3. **Slope & Elevation Filtering (`vert.maxSlope`):** If islands have steep cliffs, configure `vert.maxSlope` and `vert.slopeRadius` to avoid placing players on sheer cliff faces.

---

## Part 3: Non-Obvious Network, Proxy & Gameplay Designs

### 8. Auth / Limbo Lobby -> Survival Scatter Handoff

**The trap:** Granting `rtp.onevent.join` or `rtp.onevent.firstjoin` when an authentication plugin spawns unauthenticated players in a `limbo` or `auth` world first, then teleports them to `world` after `/login`. `join` fires while the player is still in limbo and fights with the auth plugin!

**The solution:**
- Leave `limbo` / `auth` unmapped (no file in `definitions/worlds/`).
- Grant `rtp.onevent.changeworld: true` (instead of `rtp.onevent.join`) so `LeafRTP` scatters the player the instant the auth plugin transfers them into `world`, served immediately from `world`'s pre-warmed queue.

---

### 9. Pure Dispatch Hub + N Survival Backends (`Velocity` / `BungeeCord` Network Mode)

When players stand in a central `hub` server (or run `/rtp` on an overcrowded shard) and you want `/rtp` to route them across `survival-1`, `survival-2`, `survival-3`, and `survival-4` based on real-time queue depth, MSPT, and heap headroom:

1. **On the Hub / Lobby backend (`advanced/network.yml`):**
   ```yaml
   network:
     enabled: true
     serverId: "hub-1"
     secretEnv: "RTP_NET_SECRET"
   transport:
     type: "redis"
     redis:
       host: "redis.internal"
       port: 6379
       passwordEnv: "RTP_REDIS_PASSWORD"
   routing:
     mode: "auto"
     lobbyMode: true
   ```
   With `routing.lobbyMode: true`, `hub-1` advertises zero local regions (`acceptingRequests: false`) and acts as a pure dispatch router: a bare `/rtp` on `hub-1` scores all healthy survival backends through `loadBalancer.terms` (`mspt`, `queueDepth`, `heapUsed`, `keptRegion`, `keptCount`), reserves a pre-verified coordinate token on the winning backend, and transfers the player across the proxy directly onto that coordinate.
2. **On each Survival backend (`survival-1..4`, `advanced/network.yml`):**
   ```yaml
   network:
     enabled: true
     serverId: "survival-1"   # unique per backend: survival-1, survival-2, survival-3, survival-4
     secretEnv: "RTP_NET_SECRET"
   transport:
     type: "redis"
     redis:
       host: "redis.internal"
       port: 6379
       passwordEnv: "RTP_REDIS_PASSWORD"
   routing:
     mode: "auto"
     lobbyMode: false
   ```
3. **Critical invariant:** Every backend **must** have a unique `network.serverId` and identical Base64 `RTP_NET_SECRET` (`>= 32` decoded bytes). Full walkthrough: [Network Mode Configuration (`network.yml`)](proxies/CONFIGURATION.md).

---

### 10. Heavily Claimed / Mature Survival Maps (Preventing Claim Exhaustion)

On a 2-year-old survival map where thousands of chunks near spawn are claimed via `WorldGuard`, `GriefPrevention`, `Lands`, `TownyAdvanced`, `HuskClaims`, or `FactionsBridge`:

1. **Enable claim rerolls in `integrations.yml`:**
   ```yaml
   rerollWorldGuard: true
   rerollGriefPrevention: true
   rerollLands: true
   rerollTownyAdvanced: true
   ```
2. **Use `shape.mode: ACCUMULATE` + Cause-Based Spatial Memory (`advanced/ttl.yml`):**
   - When a candidate chunk is rejected due to a land claim, `LeafRTP` records that coordinate in persistent spatial memory with its cause-based TTL (`advanced/ttl.yml`) so the Archimedean spiral skips known claims on subsequent picks instead of re-querying the claim plugin.
3. **Enable `shape.expand: true` or `shape.uniquePlacements: 1`:**
   - Setting `shape.expand: true` in `definitions/regions/default.yml` automatically grows the outer radius as inner sectors fill up with claims or `uniquePlacements` exclusions.

---

### 11. Unpregenerated Worlds on Low-RAM / Shared Hosting

If you cannot pre-generate the entire world with Chunky because of disk quotas or shared-host RAM limits (`4–6 GB` heap):

1. **Always keep `syncLoading: false` and `viewDistanceSelect: 0` in `advanced/performance.yml`.** Never enable synchronous chunk loading on the main thread or load surrounding view-distance chunks during candidate selection.
2. **Bias selection toward already-generated chunks on disk (`pregeneratedPreference`):**
   - In `advanced/performance.yml`, set `pregeneratedPreference: 0.9` (range `0.0..1.0`). The `.mca` / `.linear` region-file pre-filter checks whether a candidate chunk already exists on disk before loading it, avoiding expensive worldgen CPU spikes 90% of the time while still gradually exploring 10% new terrain.
3. **Keep `autoTune: true` and `maxHeapPercent: 80`:**
   - When JVM heap usage crosses `maxHeapPercent`, the runtime governor automatically throttles background L3/L2 queue pre-warming while continuing to serve ready locations from the L1 hot queue.

---

### 12. Co-Deploying `LeafRTP` + `LeafSleep` (`OnePlayerSleep`) on Sharded Survival Networks

When running a multi-server survival network with both `LeafRTP` (cross-server random teleportation) and `LeafSleep` (cross-shard night skipping and `UnitCurve` / `TUG_OF_WAR` sleep quorum):

- **Shared Redis & SQL Infrastructure:**
  - Both plugins can point at the **exact same Redis server** and **exact same MySQL / MariaDB / PostgreSQL database**.
  - `LeafRTP` namespaces its Redis keys under `rtp:*` and SQL tables under `rtp_*`.
  - `LeafSleep` uses channel `leafsleep:sync` and SQL table prefix `leafsleep_` (`leafsleep_player_state`, `leafsleep_epoch_watermark`), sharing the zero-dependency `:database-api` `MiniConnectionPool` (`CloseableDataSource`) and RESP2 client (`RespPool` / `RespPubSub`).
- **Matching Shard Identities & World Groups:**
  - Match `network.serverId: "survival-1"` in `plugins/RTP/advanced/network.yml` with `serverShardId: "survival-1"` in `plugins/LeafSleep/config.yml`.
  - On survival shards that share a linked day/night cycle, set `worlds.world.shardGroupId: "overworld-main"` in `plugins/LeafSleep/sleep.yml` and `sharding.transport: "redis pubsub"`.
  - On a resetting `resource` shard where players mine underground at night, set `worlds.world.shardGroupId: "resource-overworld"` with `policy: "one player"` in `plugins/LeafSleep/sleep.yml` so underground miners on the resource shard do not inflate the surface sleep quorum on `survival-1..4`.
