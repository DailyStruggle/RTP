# React v2.2.2 (`[CraftBukkit] 26.1.2-26.3`) — Live Server Bug Report & Contraption Integrity Audit

**Target Artifact:** `React v2.2.2 [CraftBukkit] 26.1.2-26.3.jar` (`embedded XZ runtime: d7229508a7e5f4290c14d570b6a41aedd4095e0f826fc5da1f895fb7a8b66814.jar`)
**Test Environment (`bamboo`):**
- **Server:** Paper `26.2-56-main@8cd4f47` (`MC: 26.2`, `Native monitoring backend active: v26_2_R1`)
- **JVM & OS:** OpenJDK 64-Bit Server VM `25.0.4.1+1` (`12 GB` G1GC) on Linux `7.2.11-200.fc44.x86_64` (AMD Ryzen Threadripper 7970X 32-Core / 64-Thread)

Every issue below was **reproduced live on the server** (`Paper 26.2-56`) and traced to the exact class and method in `React v2.2.2`.

---

## Automated Reproduction & Verification Tool (`scripts/bench_mechanics_repro.py`)

To make these issues easy to reproduce and verify fixes against without manual in-game setup, we packaged a standalone, zero-dependency Python 3 stdlib RCON reproducer (`scripts/bench_mechanics_repro.py`). It connects over RCON, builds the minimal contraption fixture in temporary chunks, measures the quantitative outcome against Vanilla parity invariants (`BIT 5401`–`5404`), cleans up all blocks/forceloads, and exits `0` (`PASS`) or `1` (`FAIL`):

```bash
# Run all 6 reproduction checks against any Paper server with RCON enabled:
python3 scripts/bench_mechanics_repro.py \
    --host 127.0.0.1 --rcon-port 25575 --password <rcon_password> \
    --log-file /path/to/server/logs/latest.log --case all

# Or run a single issue reproducer while testing a fix:
python3 scripts/bench_mechanics_repro.py \
    --host 127.0.0.1 --rcon-port 25575 --password <rcon_password> \
    --case sorter-coalesce
```

| `--case` Name | Issue Verified | Quantitative Vanilla Invariant |
|---|---|---|
| `web-ipv4` | Issue 1 (`WebController` IPv6 `::` bind failure) | No `UnsupportedAddressTypeException` / `JavalinBindException` in server log under `-Djava.net.preferIPv4Stack=true` |
| `sorter-coalesce` | Issue 2 (`FeatureHopperChainCoalescing` bypasses middle filter hoppers) | `sorted_barrel == 20`, `overflow_barrel == 0` (`L1 routing drift <= 0.15`, `BIT 5401`) |
| `circuit-ranking` | Issue 3 & 4b (`FeatureCircuitManager` raw event ranking & piston freeze) | `piston_transitions >= 2`, `slime_attached == True`, `specialist_throttled_first == False` (`BIT 5404`) |
| `clock-death` | Issue 4a (`FeatureRedstoneClockGovernor` permanent clock death after cooloff) | `post_cooldown_transitions >= 2` after `7.5 s` cooloff window (`BIT 5403`) |
| `hopper-starvation` | Issue 5 (`FeatureHopperTokenBucket` tick-order hopper starvation) | All `10` sampled composter-capped chains deliver `~10` items (`fairness_ratio >= 0.50`, `starved == 0`, `BIT 5402`) |
| `test-run-stall` | Issue 6 (`/react test run` main-thread `MapsRenderingCheck` stall) | Main-thread MSPT `< 40.0 ms` during `/react test run` |

---

## Issue 1: `WebController` Fails to Start on Any Server Using `-Djava.net.preferIPv4Stack=true` and Misreports `Port already in use`

### Context
- **Class / Method:** `art.arcane.react.core.controller.WebController` (`bindAndPublishRuntime` / `WebController$IncrementingPortConnector.openAcceptChannel`, `WebController.java:1138, 1431`)
- **Config File:** `plugins/React/web.toml` (`listenAddress = "::"`, `port = 9696`)

### Issue
On first boot with default generated configuration (`web.toml`), `WebController` defaults `listenAddress` to the IPv6 wildcard literal `"::"` (`/[0:0:0:0:0:0:0:0]`). When the server JVM runs with `-Djava.net.preferIPv4Stack=true` (standard in Aikar's flags, Pterodactyl templates, and many Paper startup scripts), `ServerSocketChannelImpl.bind` throws `java.nio.channels.UnsupportedAddressTypeException`. `WebController$IncrementingPortConnector` catches the `IOException` across its port-retry loop, and Javalin rethrows it as a misleading `JavalinBindException: Port already in use. Make sure no other process is using port 9696 and try again.` even when no process is bound to port `9696`.

### Root Cause
In `sun.nio.ch.Net.checkAddress(SocketAddress)` (`Net.java:156`), passing an `Inet6Address` (`InetAddress.getByName("::")`) when `Net.isIPv6Available()` is `false` (`-Djava.net.preferIPv4Stack=true`) unconditionally throws `UnsupportedAddressTypeException`. `JettyServer.kt:101` wraps any `IOException` whose message starts with `"Failed to bind to"` into `JavalinBindException("Port already in use...")`.

### Live Server Reproduction
1. Start Paper `26.2-56` with `-Djava.net.preferIPv4Stack=true` and `React v2.2.2` (`plugins/React/web.toml` generated from defaults).
2. Observe the server log during startup:
   ```text
   [15:13:42 ERROR]: [React] WebController failed to start: Port already in use. Make sure no other process is using port 9696 and try again.
   io.javalin.util.JavalinBindException: Port already in use. Make sure no other process is using port 9696 and try again.
       at io.javalin.jetty.JettyServer.start(JettyServer.kt:101) ~[?:?]
       at io.javalin.Javalin.start(Javalin.java:136) ~[?:?]
       at React-2.2.2.jar//art.arcane.react.core.controller.WebController.bindAndPublishRuntime(WebController.java:1138) ~[?:?]
   Caused by: java.io.IOException: Failed to bind to /[0:0:0:0:0:0:0:0]:9696
       at org.eclipse.jetty.server.ServerConnector.openAcceptChannel(ServerConnector.java:344) ~[?:?]
       at React-2.2.2.jar//art.arcane.react.core.controller.WebController$IncrementingPortConnector.openAcceptChannel(WebController.java:1431) ~[?:?]
   Caused by: java.nio.channels.UnsupportedAddressTypeException
       at java.base/sun.nio.ch.Net.checkAddress(Net.java:156) ~[?:?]
       at java.base/sun.nio.ch.ServerSocketChannelImpl.netBind(ServerSocketChannelImpl.java:332) ~[?:?]
   ```

### Suggested Fix
- Default `listenAddress` in `web.toml` to `"0.0.0.0"` (or `null` / wildcard `InetSocketAddress(port)`), or fall back from `"::"` to `"0.0.0.0"` when `Boolean.getBoolean("java.net.preferIPv4Stack")` is `true` or when `openAcceptChannel()` catches `UnsupportedAddressTypeException`.

---

## Issue 2: `FeatureHopperChainCoalescing` Bypasses Middle Filter Hoppers in ImpulseSV Item Sorters

### Context
- **Class / Method:** `art.arcane.react.content.feature.FeatureHopperChainCoalescing` (`computeFastPathEligible`, `walkForwardFromHead`, `hasBranch`, `synthesizeChainTransfer`, `applyChainTransfer`)
- **Config File:** `plugins/React/feature/hopper-chain-coalescing.toml` (`minChainLength = 4`)

### Issue
When `FeatureHopperChainCoalescing` coalesces a horizontal hopper line (`H0 -> H1 -> H2 -> H3 -> Overflow Container`) that runs across the top of a standard ImpulseSV item sorter (where vertical filter/suction hoppers sit underneath middle hoppers `H1`, `H2`, etc. at `y - 1`), `applyChainTransfer` clones items from the head hopper (`positions[0]`) and inserts them directly into the tail container (`positions[length - 1] + direction`), **skipping all middle hoppers (`H1`, `H2`)**. As a result, **95%+ of sortable items bypass the item sorter completely and land in the overflow chest**.

### Root Cause
Two separate checks in `FeatureHopperChainCoalescing` fail to detect an item sorter underneath a horizontal hopper chain:
1. **`computeFastPathEligible(World, HopperChain)` (bytecode offsets `24..110`) checks the wrong coordinate for comparators:**
   - It unpacks `positions[0]` (`headX, headY, headZ`), constructs `new Location(world, headX, headY + 1, headZ)` (**one block ABOVE the head hopper**), and checks if that block is `Material.COMPARATOR` or `Material.REPEATER`.
   - Comparators read hoppers **horizontally** (`dx = +-1` or `dz = +-1` at the same `y`), never from `y + 1`, and in an ImpulseSV sorter the comparator is attached to the **filter hopper at `y - 1`** underneath the middle hoppers (`positions[1..length-2]`), not on top of `positions[0]`.
2. **`hasBranch(long pos, BlockFace chainDir, Long2ObjectOpenHashMap<HopperNode> nodes)` (bytecode offsets `21..196`) only checks for upstream hoppers pointing *into* the chain:**
   - It iterates `UPSTREAM_FACES` and checks whether a neighbor hopper's `facing` points *into* `(x, y, z)` (`nx + facing.modX == x && ny + facing.modY == y && nz + facing.modZ == z`) with `branchCount > 1`.
   - A filter hopper at `(x, y - 1, z)` underneath a middle hopper `H1` does **not** point into `H1` (it points horizontally into a comparator wall or downward into a chest while **pulling** items from `H1` above it via vanilla hopper suction). Thus `hasBranch` returns `false` and `computeFastPathEligible` marks the sorter pipe eligible for head-to-tail teleportation.

### Live Server Reproduction
1. In chunk `world (41, -8)`, build a 4-hopper horizontal input pipe at `y = 67` (`H0 -> H1 -> H2 -> H3 -> Overflow Barrel` facing `EAST`, capped with composters at `y = 68`).
2. Underneath middle hopper `H1` at `y = 66`, place a vertical suction/filter hopper feeding a `Sorted Barrel` at `y = 65`.
3. Insert `20` emeralds into head hopper `H0` while `FeatureHopperChainCoalescing` is active (`hopper-chain-coalescing=2 HC/s`):
   ```text
   === [REPRO A] FeatureHopperChainCoalescing Bypasses Filter Hopper Under Middle Hopper H1 ===
     20 Emeralds inserted into H0 (4-hopper chain with suction hopper under H1):
       Sorted Barrel under H1 (vanilla = 20 emeralds):  1 emeralds
       Overflow Barrel after H3 (vanilla = 0 emeralds): 19 emeralds
       React sampler: hopper-chain-coalescing=2 HC/s
   ```

### Suggested Fix
1. In `walkForwardFromHead` / `hasBranch` (or `computeFastPathEligible`), check **every** hopper `positions[i]` in the chain (`0 <= i < length`) for:
   - Any hopper at `(x, y - 1, z)` below `positions[i]` (which pulls items out of `positions[i]` via vertical hopper suction), and
   - Any horizontal neighbor (`NORTH, SOUTH, EAST, WEST`) at `(x + dx, y, z + dz)` that is a `Material.COMPARATOR` reading `positions[i]`.
2. If any node in the chain has a hopper below `(x, y - 1, z)` or an adjacent horizontal comparator, exclude that segment from head-to-tail fast-path coalescing.

---

## Issue 3: `FeatureCircuitManager` Ranks Circuits by Raw Event Count (`CircuitWorld.worst`) and Throttles Low-Cost Optimization-Specialist Contraptions Before True Lag Sources

### Context
- **Class / Method:** `art.arcane.react.content.feature.FeatureCircuitManager.evaluate()`, `art.arcane.react.model.CircuitServer.throttleWorst(long, long)`, `art.arcane.react.model.CircuitWorld.worst(long)`
- **Config File:** `plugins/React/feature/circuit-manager.toml` (`enabled = true`, `throttleDurationMS = 10000`)

### Issue
When `SamplerRedstoneEventSpan` crosses `maxCircuitMS`, `FeatureCircuitManager.evaluate()` calls `CircuitServer.throttleWorst()`, which selects a single connected component (`Circuit`) to freeze for `10,000 ms`. Because `CircuitWorld.worst(long)` ranks circuits strictly by **raw `BlockRedstoneEvent` count (`Circuit.getEvents()`)** rather than actual CPU / MSPT cost, a well-optimized contraption that fires many cheap localized block-state events (e.g. `iron_trapdoor` / `note_block` / `observer` updates at `~0.002 ms/ev`) is **throttled first**, while a multi-layer high-gradient dust + piston lag machine with fewer events per disconnected layer continues lagging the server. Furthermore, `FeatureCircuitManager` has no closed-loop rollback when freezing a circuit fails to reduce MSPT.

### Root Cause
In `CircuitWorld.worst(long)` (bytecode offsets `38..77`):
```java
if (c.getEvents() <= 0 || c.isBlocked(nowMs)) continue;
if (best == null || c.getEvents() > best.getEvents()) {
    best = c;
}
```
All `BlockRedstoneEvent` calls are weighted identically (`1` event = `1` point), regardless of whether the event is a zero-cascade `iron_trapdoor`/`note_block` toggle or a 15-power `redstone_wire` neighbor cascade + sticky-piston slime relocation.

### Live Server Reproduction
1. In chunk `world (44, -8)` (`x = 706..718`), build a low-cost specialist contraption (`observer` + `iron_trapdoor` array in a connected 50-node component).
2. In chunk `world (43, -8)` (`x = 690..700`), build a 10-layer high-cost `redstone_wire` + `sticky_piston` + `slime_block` lag machine.
3. Observe `/tmp/bamboo-26.2-bench.log`:
   ```text
   [15:39:04 WARN]: [React] Throttling redstone activity component 1 in world near 715,65,-126 (339 events, 50 active nodes, 7.4ms global event span)
   [15:39:04 WARN]: [React] Throttling redstone activity component 8 in world near 692,81,-125 (905 events, 51 active nodes, 7.0ms global event span)
   ```
   - `FeatureCircuitManager` throttled `component 1` at `715, 65, -126` (the specialist contraption in chunk `44, -8`) **first**.
   - Freezing `component 1` only changed `global event span` from `7.4 ms` to `7.0 ms` (`-0.4 ms`), yet `component 1` remained frozen for the full `10,000 ms` `throttleDurationMS`.

### Suggested Fix
- Weight circuit observations by measured per-event execution time (`System.nanoTime()` delta around listener/block updates or empirical `ms/event` regression slope) rather than raw unweighted event counts, and release/roll back a circuit throttle early if `redstone-event-span` / `tick-time` does not drop after the circuit is blocked.

---

## Issue 4: `FeatureRedstoneClockGovernor` and `FeatureCircuitManager` Permanently Kill Edge-Triggered Clocks and Freeze Piston/Slime Contraptions (Zero Restoration)

### Context
- **Class / Method:**
  - `art.arcane.react.content.feature.FeatureRedstoneClockGovernor.on(BlockRedstoneEvent)` (bytecode offsets `248..256`)
  - `art.arcane.react.content.feature.FeatureCircuitManager.on(BlockRedstoneEvent)` (`34..40`), `on(BlockPistonExtendEvent)` (`20..25`), `on(BlockPistonRetractEvent)` (`20..25`)
- **Config Files:** `redstone-clock-governor.toml` (`enabled = true`, `windowMS = 1000`, `maxTransitionsPerWindow = 12`, `cooloffMS = 6000`), `circuit-manager.toml` (`enabled = true`)

### Issue
1. **Open-Loop Clock Destruction at Idle MSPT (`FeatureRedstoneClockGovernor`):** Even when server MSPT is `~1.5 ms` (`20.0 TPS`), `FeatureRedstoneClockGovernor` blocks any coordinate that transitions `> 12` times within `1000 ms` for `cooloffMS = 6000 ms` by calling `event.setNewCurrent(event.getOldCurrent())`. For feedback-loop clocks (comparator subtraction loops, repeater rings, torch-repeater loops), suppressing a single transition destroys the circulating pulse token or locks the feedback wire high (`power = 15`), so **when `cooloffMS` expires 6 seconds later, the clock never restarts (`0` transitions forever)**.
2. **Piston / Slime Contraption Freezing (`FeatureCircuitManager`):** When `FeatureCircuitManager` blocks a circuit, `on(BlockPistonExtendEvent)` and `on(BlockPistonRetractEvent)` call `event.setCancelled(true)` while `on(BlockRedstoneEvent)` calls `event.setNewCurrent(event.getOldCurrent())`. Cancelling piston extend/retract events mid-cycle stalls or splits stateful sticky-piston + slime-block contraptions and leaves edge-triggered loops permanently dead after `throttleDurationMS` expires.

### Live Server Reproduction
1. With `React v2.2.2` at 100% default config on an idle server (`~1.5 ms` MSPT), build a comparator subtraction clock in `world (40, -8)` and a sticky-piston + slime-block oscillator in `world (43, -8)`:
   ```text
   === [REPRO 1] FeatureRedstoneClockGovernor permanently kills a clock at ~1.5ms MSPT ===
     Comparator subtraction loop wire changes (t=0..0.6s): 1
     Comparator subtraction loop wire changes during React cooloff (t=1.8..2.4s): 0
     Waiting 6.5s for React cooloffMS (6000ms) to expire...
     Comparator subtraction loop wire changes AFTER cooloffMS expired (t=+8.5s): 0

   === [REPRO C] FeatureCircuitManager Cancelling Pistons & Freezing Circuits ===
     Sticky piston state changes during FeatureCircuitManager throttle (12 samples): 0
   ```

### Suggested Fix
1. Gate `FeatureRedstoneClockGovernor` on actual server tick pressure (`tick-time` / MSPT budget) so it does not kill clocks on an idle `1.5 ms` MSPT server.
2. Never cancel `BlockPistonExtendEvent` or `BlockPistonRetractEvent` directly (which breaks push/pull symmetry on sticky pistons and slime/honey flying machines).
3. When suppressing a rising `BlockRedstoneEvent` edge (`oldCurrent < newCurrent`), record the suppressed `(world, x, y, z)` coordinate in a bounded per-chunk restoration queue and trigger a block update (`block.getState().update(true, true)`) when `blockedUntilMs` expires so stationary clocks self-resume.

---

## Issue 5: `FeatureHopperTokenBucket` Starves Composter-Capped Hoppers Later in Chunk Tick Order Even at Idle Server MSPT

### Context
- **Class / Method:** `art.arcane.react.content.feature.FeatureHopperTokenBucket.on(InventoryMoveItemEvent)` & `tryConsume(Location)`
- **Config File:** `plugins/React/feature/hopper-token-bucket.toml` (`enabled = true`, `bucketCapacity = 120.0`, `refillPerSecond = 55.0`, `costPerMove = 1.0`, `bypassWhenNearbyPlayers = true`)

### Issue
`FeatureHopperTokenBucket` enforces a flat per-chunk token bucket (`refillPerSecond = 55.0` moves/sec/chunk, equivalent to only `~11` vertical `hopper -> hopper` pairs ticking every 8 gt) regardless of whether server MSPT is `1.5 ms` or `50 ms`, and regardless of whether the hoppers are capped with composters (`HOPPER_CAPPED_BY_CONTAINER`) or part of an ImpulseSV comparator sorter. Worse, because Minecraft ticks block entities in a deterministic list order within each chunk, **the first ~22 hoppers in the chunk's tick order consume 100% of the refilled tokens every tick, while hoppers placed later in the same chunk are starved to `0` transfers/sec**.

### Live Server Reproduction
1. In unattended chunk `world (42, -8)` (`server MSPT ~ 1.5 ms`), place `35` composter-capped vertical `hopper -> hopper -> barrel` chains (`5` items/sec/chain = `175` moves/sec total).
2. Wait `3.0 s` for the initial `120.0` bucket capacity to drain, clear the output barrels, and measure items delivered over `4.0 s` (`10` hopper cooldown cycles = `10` items expected per barrel):
   ```text
   === [REPRO B] FeatureHopperTokenBucket Throttling Composter-Capped Hoppers (Chunk 42, -8) ===
     Composter-capped hopper chains delivered to barrels in 4.0s (vanilla = 10 each = 100 total):
     [10, 10, 10, 10, 10, 5, 10, 0, 0, 0] (total=65/100)
   ```
   - Chains `0..4` and `6` transferred `10/10` items, chain `5` transferred `5/10`, and chains `7..9` transferred **`0/10` items** (`100%` starved).

### Suggested Fix
- Gate `FeatureHopperTokenBucket` on server MSPT exceeding a target budget (or chunk marginal MSPT), exempt container/composter-capped and comparator-locked hoppers, and use round-robin / randomized phase skipping rather than deterministic first-come token depletion so late-indexed hoppers in a chunk are never starved to zero.

---

## Issue 6: `CommandTest.run` (`/react test run`) Executes `253 x 8` Map Wall Renders Synchronously on the Main Server Thread, Causing a `45+ ms` Tick Stall

### Context
- **Class / Method:** `art.arcane.react.content.directorcommand.CommandTest.run(boolean, boolean)` (bytecode offsets `103..190`), `art.arcane.react.api.test.ReactTestRunner.run(...)`, `art.arcane.react.api.test.checks.MapsRenderingCheck`

### Issue
In `CommandTest.run`, `MonitoringCheck` and `MapsRenderingCheck` are registered via `runner.add(...)` (`syncChecks`) instead of `runner.addAsync(...)`. When an operator runs `/react test run`, `MapsRenderingCheck` synchronously renders all `253` registered map renderers across `8` megamap wall sizes (`1x1, 2x1, 1x2, 1x3, 2x2, 3x1, 3x3, 5x3`) and flushes `3,584` frames on the main server thread inside a single tick, spiking server MSPT from `0.5 ms` to `45.1 ms` (`TPS 19.28`). Additionally, when `/react test run` is invoked over RCON, all subsequent `asyncChecks` (`FeatureLifecycleCheck`, `ActionSuiteCheck`, `MapsFrameWallCheck`, `BridgeCheck`, `ErrorScanCheck`) send their output to the already-closed `RemoteConsoleCommandSender` buffer and are silently lost.

### Live Server Reproduction
1. Run `/react test run` via RCON on an idle Paper `26.2-56` server (`MSPT = 0.5 ms`, `0` players online).
2. Observe `/tmp/bamboo-26.2-bench.log`:
   ```text
   [15:17:39 INFO]: Thread RCON Client /100.126.156.48 started
   [15:17:42 INFO]: [RTP] Server tick unhealthy (TPS 19.28, MSPT 45.1; minTPS 19.00, maxMSPT 45.0); pausing background
   ```

### Suggested Fix
- Move the pure-canvas CPU rendering passes in `MapsRenderingCheck` (`megamap-expansion`, `renderer-containment`, `flush-fidelity`) off the main server thread (or slice them across multiple ticks via `addAsync`), and mirror async test output to `Bukkit.getConsoleSender()` when `sender` is a `RemoteConsoleCommandSender`.

---

## Head-to-Head Summary on `bamboo` (`Paper 26.2-56`): `React v2.2.2` vs. `LeafSleuth v0.1.0`

| Live Server Test Scenario on `bamboo` (`Paper 26.2-56`) | Vanilla Expected | `React v2.2.2` (Observed on `bamboo`) | `LeafSleuth v0.1.0` (Observed on `bamboo`) |
|---|---|---|---|
| **1. Startup Web Listener under `-Djava.net.preferIPv4Stack=true`** | Starts cleanly | **CRASHES:** `UnsupportedAddressTypeException` on `listenAddress = "::"`, misreported as `Port already in use: 9696` | Clean startup (`5/5` PBIT self-tests `HEALTHY` in `8.6 ms`) |
| **2. 4-Hopper Input Pipe over ImpulseSV Filter Hopper (`20` emeralds)** | `20/20` sorted, `0/20` overflow | **BROKEN:** `1/20` sorted, **`19/20` teleported to overflow** (`FeatureHopperChainCoalescing` skips middle filter hoppers) | **`20/20` sorted, `0/20` overflow** (`100%` sorter accuracy preserved) |
| **3. `35` Composter-Capped Hopper Chains at Idle MSPT (`4.0 s`, `100` items)** | `100/100` (`10` per barrel) | **STARVED:** `65/100` (`[10, 10, 10, 10, 10, 5, 10, 0, 0, 0]`; last 3 chains starved to `0`) | **`100/100` (`[10, 10, 10, 10, 10, 10, 10, 10, 10, 10]`)** (`ProtectedSpecialist: 1,985`) |
| **4. Low-Cost Specialist Contraption `(44,-8)` vs. High-Cost Lag Machine `(43,-8)`** | Hit lag machine first; leave specialist untouched | **WRONG TARGET:** `FeatureCircuitManager` throttled specialist circuit `715,65,-126` **first** (`7.4 ms -> 7.0 ms` span, no rollback) | **PARETO $L_{95}$ + UNIT SLOPE:** Throttled only the high-$L_{95}$ culprit (`SkippedPulses: 2,023`), kept low-cost chunks at `OBSERVE_ONLY (u=0%)` |
| **5. Sticky-Piston + Slime-Block Oscillator During Active Mitigation** | Remains intact & oscillating | **FROZEN:** `0` piston state changes (`BlockPistonExtendEvent.setCancelled(true)`) | **INTACT & OSCILLATING:** `5` piston state changes (`PISTON_BLOCK_RELOCATION` guard, `0` piston cancellations) |
| **6. Stationary Clock Recovery After Lag Spike / Cooloff** | Clock resumes ticking | **PERMANENTLY DEAD:** `0` transitions after `cooloffMS` (`6000 ms`) expires | **SELF-RESTORED:** Replayed `64` suppressed rising edges (`RestoredEdges: 64`) on recovery |
