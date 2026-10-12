# Paper 26.2 Benchmark Analysis: Effective Safe Max & Resource Breakdown

Analysis of the 86 benchmark runs (stepped ramp tests from 5 to 160 TP/s and 100 TP/s saturation bursts) executed on the Paper 26.2 server atop an AMD Ryzen Threadripper 7970X (32 cores / 64 threads @ up to 5.35 GHz, 128 GiB RAM, Samsung 990 PRO NVMe).

---

## 1. Effective Safe Maximum Throughput Across Tested Plugins

Each plugin was evaluated across 5 sequential stepped ramp test rounds (offering 5, 10, 20, 40, 80, 160, and 320 TP/s for 60 seconds per stage) and sustained 100 TP/s saturation bursts (120 seconds).

The **Effective Safe Max** represents the highest offered throughput stage where the plugin met all SLA stability criteria:
- **Achieved Throughput:** >= 95% of offered load
- **Tick Health:** MSPT P95 <= 50.0 ms (stable 20.0 TPS line)
- **Failure / Timeout Rate:** <= 1.0% failures
- **Low-TPS Abort Line:** No drop below 5 TPS

| Plugin | Tested Command | Effective Safe Max | Bottleneck / Limiting Factor | Performance at Safe Max | Peak Throughput Observed |
|---|---|---|---|---|---|
| **LeafRTP Pro** | `rtp:rtp` | **40 TP/s** | Main-thread tick budget (MSPT > 50 ms at 80 TP/s) | 39.97 TP/s @ 40 TP/s (MSPT P95 = 48.1 ms, 0.00% fail) | 107.4 TP/s (ramp 160) / 99.3 TP/s (100 burst) |
| **HuskHomes** | `huskhomes:rtp` | **40 TP/s** | Internal queue throttle cap (~43-45 TP/s ceiling) | 38.37 TP/s @ 40 TP/s (MSPT P95 = 42.0 ms, 0.00% fail) | 44.9 TP/s (ramp 160) / 43.1 TP/s (100 burst) |
| **JustRTP** | `justrtp:justrtp` | **20 TP/s** | Internal pipeline saturation cap (~25.5 TP/s ceiling) | 19.98 TP/s @ 20 TP/s (MSPT P95 = 34.7 ms, 0.00% fail) | 25.6 TP/s (ramp 40) / 25.3 TP/s (100 burst) |
| **JakesRTP** | `jakesrtp:forcertp` | **20 TP/s** | Main-thread tick collapse (MSPT 96ms @ 40, 295ms @ 80) | 19.98 TP/s @ 20 TP/s (MSPT P95 = 41.2 ms, 0.00% fail) | 74.5 TP/s (aborted: MSPT 295ms, < 5 TPS) |
| **EzRTP** | `ezrtp:forcertp` | **5 TP/s** | High candidate failure/timeout rate (26-36% fails) | 4.98 TP/s @ 5 TP/s (MSPT P95 = 21.5 ms, 0.00% fail) | 8.4 TP/s (ramp 20) / 8.3 TP/s (100 burst) |
| **BetterRTP** | `betterrtp:betterrtp` | **None (0 TP/s)** | Command/permission dispatch issue (100% timeouts) | 0.00 TP/s @ 5 TP/s (100% timeouts) | 0.0 TP/s (untestable via player dispatch) |

---

## 2. Rigorous Chunk Metrics: Selection vs Landing Footprint

The previously cited figure of ~2 to ~16 chunks touched per attempt reflected only **coordinate selection probing** (`chunks_selection_per_attempt`), omitting the landing footprint.

As documented in the StressTestRTP schema notes:
`phases CSV chunks_inclusive_per_attempt (all loads / attempts) is the chunk figure to publish across plugins.`

When a player teleports, Paper loads a square of chunks around the landing location (`(2 * viewDistance + 1)^2`) so the player's client can render the world. At server view distance 4, this adds ~80 chunks per successful teleport.

### Chunk Load Breakdown at 20 TP/s (1,199 Attempts over 60s)

| Plugin | Selection Chunks / Attempt | Landing Chunks / Attempt | Total Chunks / Attempt (`chunks_inclusive`) | Total Phase Chunks Loaded |
|---|---|---|---|---|
| **LeafRTP Pro** | 16.7 | 55.8 | **80.8** | 96,910 |
| **HuskHomes** | 3.5 | 79.2 | **82.8** | 99,269 |
| **JakesRTP** | 2.0 | 81.8 | **84.0** | 100,695 |
| **JustRTP** | 30.8 | 80.1 | **111.1** | 133,156 |
| **EzRTP** | 45.1 | 9.0 | **54.3** | 42,858 |
| **BetterRTP** | 0.0 | 0.0 | **0.0** | 0 |

### Key Chunk Inferences
1. **Total Chunk Footprint is Constant Across Functional Plugins (~80 to 84 chunks):**
   Every player teleporting to an unloaded destination incurs an unavoidable ~80 chunk load footprint for client view distance.
2. **Selection Strategies Differ:**
   - **JakesRTP & HuskHomes:** Perform minimal pre-checks (2 to 3.5 chunks) and rely on late placement checks, shifting chunk loading entirely to the landing event.
   - **LeafRTP Pro:** Performs ~16 selection checks asynchronously (decompression, Anvil bin verification, vertical column safety) to guarantee safe arrival, which also pre-populates the landing neighborhood (reducing fresh landing loads to 55.8 chunks).
   - **JustRTP:** Over-probes selection candidates (30.8 chunks per attempt), ballooning total chunk loads to 111.1 chunks per teleport.
   - **EzRTP:** Rejects candidates excessively (45.1 selection checks), and because 36% of teleports time out or fail, landing loads drop to 9.0 chunks.

---

## 3. Rigorous CPU Metrics: Plugin Logic vs Chunk Engine vs Fixed Idle

The previously reported figure of ~215 to 230 ms of "Process CPU per Attempt" conflated three distinct operational layers:
1. **The Plugin's Own Code:** Bukkit async scheduler tasks and Anvil reader threads.
2. **The Minecraft Chunk Engine:** Asynchronous worker threads decompressing, generating, and preparing the 80+ chunks per teleport.
3. **The Idle Server Baseline:** A Paper server with 48 connected player bots consumes ~21 to 24 seconds of server thread CPU every 60 seconds (~35% to 40% of one core) completely at idle.

### CPU Consumption Breakdown at 20 TP/s (1,199 Attempts over 60s)

| Plugin | Plugin Code CPU / Attempt | Chunk System CPU / Attempt | Server Thread CPU / Attempt (Raw) | Marginal Server CPU / Attempt (Net) | Total Process CPU / Attempt |
|---|---|---|---|---|---|
| **LeafRTP Pro** | **2.03 ms** (0.07ms sched + 1.96ms Anvil) | 161.29 ms | 30.23 ms | **~10.6 ms** | 254.03 ms |
| **HuskHomes** | **0.36 ms** (0.36ms sched) | 170.91 ms | 28.80 ms | **~9.2 ms** | 256.29 ms |
| **JakesRTP** | **0.03 ms** (0.03ms cache) | 155.58 ms | 29.93 ms | **~10.3 ms** | 238.65 ms |
| **JustRTP** | **0.00 ms** (minimal async) | 172.28 ms | 32.10 ms | **~12.5 ms** | 265.91 ms |
| **EzRTP** | **0.00 ms** (minimal async) | 108.01 ms | 36.25 ms | **~16.6 ms** | 178.18 ms |
| **BetterRTP** | **0.00 ms** (idle) | 0.08 ms | 70.90 ms (at 5tps idle) | N/A | 96.39 ms (idle bots) |

### Key CPU Inferences
1. **Plugin Code Execution is Extremely Light (0.03 ms to 2.0 ms per attempt):**
   The plugins themselves do NOT consume 200+ ms of CPU. LeafRTP spends ~1.96 ms in background `RTP-Anvil-IO` worker threads performing region-file pre-filtering, while HuskHomes spends ~0.36 ms in scheduler tasks.
2. **Chunk Loading Dominates Process CPU (~155 to 175 ms per attempt):**
   Over 65% to 70% of total JVM CPU time is consumed by Paper's `cpu_chunk_system_ms` threads decompressing, parsing, and lighting the ~81 landing chunks.
3. **Marginal Server Thread Cost is ~10 to 11 ms per Teleport:**
   When a player is detached, repositioned, synchronized across the network, and re-registered with tracking chunks, Paper spends ~10 to 11 ms of main tick thread time.
4. **Why Raw Server CPU / Attempt Varies with Rate:**
   Because idle bots burn ~23.5 seconds of server CPU per 60s window:
   - At 5 TP/s (299 attempts): `(23.5s idle + 2.3s RTP) / 299 = 86.2 ms / attempt`
   - At 20 TP/s (1,199 attempts): `(23.5s idle + 12.7s RTP) / 1,199 = 30.2 ms / attempt`
   - At 40 TP/s (2,398 attempts): `(23.5s idle + 29.1s RTP) / 2,398 = 21.9 ms / attempt`
   - At 80 TP/s (4,797 attempts): `(23.5s idle + 31.7s RTP) / 4,797 = 11.5 ms / attempt`
   At high throughput, fixed idle cost amortizes away, revealing the true marginal cost of ~10 to 11.5 ms per teleport.

---

## 4. Architectural Bottleneck Profiles

1. **LeafRTP Pro (Pipelined Engine):**
   - **Architecture:** Zero-tick cached handoff from asynchronous pre-generation queues.
   - **Bottleneck:** Not worker threads or chunk I/O, but strictly the **50 ms server tick budget**. At 80 TP/s, serving 80 players on the main thread pushes MSPT to ~96 ms (~10.5 TPS). Safe limit is 40 to 45 TP/s.
2. **HuskHomes & JustRTP (Throttled Engines):**
   - **Architecture:** Synchronous command intake throttled by internal rate limiters.
   - **Bottleneck:** Protects server TPS by flatlining intake at ~43 TP/s (HuskHomes) and ~25.5 TP/s (JustRTP). When offered load exceeds this, queues back up and player wait times swell to 1.8 - 3.0 seconds.
3. **JakesRTP (Unbounded Engine):**
   - **Architecture:** Dispatches fast but lacks Paper 26.2 asynchronous thread isolation.
   - **Bottleneck:** At >= 40 TP/s, synchronous chunk and placement work spills onto the main server thread, causing severe tick dilation (MSPT 96 ms at 40 TP/s, 295 ms at 80 TP/s) and crashing TPS below 5.
4. **EzRTP (Inefficient Sampling Engine):**
   - **Architecture:** Simple random trial-and-error sampling.
   - **Bottleneck:** Candidate rejection rate spikes at higher loads (26% to 36% fails), making it unable to sustain even 10 TP/s.
