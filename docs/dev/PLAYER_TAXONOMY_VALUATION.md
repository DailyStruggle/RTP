# Player Taxonomy, Utility Functions, and Valuation Weights for Random Teleportation (`/rtp`)

> **Status**: Living Reference Document  
> **Audience**: Core Developers, Benchmark Architects, Server Administrators  
> **Related Documents**: [`BENCHMARK_ANALYSIS.md`](BENCHMARK_ANALYSIS.md), [`DESIGN.md`](DESIGN.md), [`REQUIREMENTS.md`](REQUIREMENTS.md), [`TESTING_GUIDE.md`](TESTING_GUIDE.md), [ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md), [ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-077](../adr/ADR-077-multi-format-region-support.md), [ADR-095](../adr/ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md)

---

## 1. Executive Summary & Purpose

Standard benchmark harnesses evaluate random teleportation engines through memoryless, high-rate dispatch loops (e.g., firing 4,096 teleports at stationary bots in a pristine pregenerated world). While this methodology effectively captures raw dispatch throughput, async chunk-loading latency, and initial thread allocation, it treats candidate selection as a zero-entropy, isolated event.

In an active multiplayer ecosystem, candidate selection directly governs the long-term health of the server and the aggregate welfare of its player base. Dropping players into undesirable, hazardous, or contested terrain does not result in passive acceptance; it triggers **reroll cascades**, multiplies cold chunk loads, defaces virgin landscapes, and inflates world autosave serialization costs across fragmented region files.

This document formalizes:
1. **The 8 Operational Modalities** of `/rtp` across modern multiplayer server genres.
2. **Player Archetypes & Utility Functions**, defining the mathematical valuation weights players assign to candidate locations.
3. **The Spatial Economics of Multiplayer Survival**, analyzing why classical urban agglomeration models fail in Minecraft and why spatial isolation is the primary utility delivered by `/rtp`.
4. **Empirical Telemetry Insights**, detailing observed failure modes such as the "Plains Clumping" artifact and strategic exploitation of algorithm blind spots.
5. **A Lightweight Behavioral Benchmark Architecture**, establishing how multi-agent simulations can model authentic player agency, world modification, and autosave debt without deadweight client pathfinding costs.
6. **Architectural Alignment in LeafRTP**, mapping these behavioral realities to specific engine components.

---

## 2. Functional Modalities: How Players Use RTP in Practice

Players deploy `/rtp` across eight distinct operational modes depending on server genre, game progression, and economic constraints:

```
+───────────────────────────────────────────────────────────────────────────────────+
|                           THE 8 OPERATIONAL MODES OF RTP                          |
+─────────────────────────────────────────┬─────────────────────────────────────────+
| 1. Initial Wilderness Homesteading      | 5. Strategic Border Scouting & Raiding  |
| 2. Off-Site Resource Extraction         | 6. Technical Farm & Perimeter Siting    |
| 3. Biome & Structure Hunting            | 7. Casual Wandering & Server Auditing   |
| 4. Spawn Escape & Anti-Camp Evasion     | 8. Cooperative Group Settle             |
+─────────────────────────────────────────┴─────────────────────────────────────────+
```

### Mode 1: Initial Wilderness Homesteading (Fresh Start)
- **Context:** The quintessential survival experience upon joining an SMP or leaving spawn.
- **Objective:** Locate an expansive, scenic, unmolested tract of wilderness to establish a permanent home base, storage complex, and local farm network.
- **Operational Pattern:** The player dispatches `/rtp`. If the terrain is unviable (steep jagged peaks, ocean, swamp, dense dark forest) or shows signs of prior players (stumps, floating foliage, torches, claim borders), they immediately reroll until satisfied. Once accepted, they execute `/sethome` and rarely use open-world RTP from that location again.

### Mode 2: Off-Site Resource Extraction (Quarrying & Deforestation)
- **Context:** Mid-to-late-game players with established bases requiring industrial quantities of building materials (sand, gravel, wood, terracotta, deepslate) without scarring their local landscape.
- **Objective:** Teleport to a dedicated resource world (`/rtp world_resource`) or remote wild chunks to clear-cut forests, excavate deserts, or strip-mine underground.
- **Operational Pattern:** Rapid dispatch -> high-entropy block stripping -> return via `/home`. Aesthetics and terrain continuity are irrelevant; raw harvestable density is paramount.

### Mode 3: Biome & Structure Hunting (Looting & Mapping)
- **Context:** Mid-game adventurers seeking unlooted generated structures (Trial Chambers, Ancient Cities, Woodland Mansions, Nether Fortresses, End Cities) or rare biomes (Cherry Groves, Badlands, Mangrove Swamps, Mushroom Fields).
- **Objective:** Maximize discovery rate. Players utilize targeted parameters where available (`/rtp biome <name>`) or rapidly cycle random candidates to roll coordinates near unexplored structures.
- **Operational Pattern:** Short dwell times (< 60 seconds); scan local coordinates or minimaps; loot high-value containers; depart.

### Mode 4: Spawn Escape & Anti-Camp Evasion (Anarchy & Hostile SMPs)
- **Context:** Anarchy, semi-anarchy, or hardcore PvP environments (e.g., 2b2t, DonutSMP, Lifesteal).
- **Objective:** Immediately escape spawn traps, wither swarms, lava casts, and spawn campers.
- **Operational Pattern:** Pure survival triage. The player requires instantaneous distance from $(0, 0)$ and zero fall/lava damage. Upon arrival, they do not settle; they travel an additional $20,000$ to $100,000$ blocks outward via Elytra or Nether highways before establishing a concealed base.

### Mode 5: Strategic Border Scouting & Raiding (Factions & Raiding SMPs)
- **Context:** Competitive faction servers or PvP environments where claims can be raided, breached, or monitored.
- **Objective:** Exploit spatial teleport mechanics to discover target bases.
- **Operational Pattern:** Players utilize targeted proximity commands (`/rtp action nearclaim`, `/rtp action nearplayer`) or cycle `/rtp` at the outer perimeter of the plugin's configured radius, scanning for player claims, entity radars, or underground redstone activity.

### Mode 6: Technical Farm & Perimeter Siting (Redstone & SciCraft Meta)
- **Context:** Advanced technical players designing high-output mob grinders, witch farms, raid farms, or slime perimeters.
- **Objective:** Identify coordinates that minimize external mob spawning or provide optimal algorithmic spawn conditions (low sub-chunk Y levels, deep ocean boundaries to eliminate cave lighting overhead, specific swamp boundaries, slime chunk alignment).
- **Operational Pattern:** Highly deliberate. Candidate chunks are mathematically verified via F3 debug screens, chunk border overlays (`F3 + G`), or seed mappers before any block is placed.

### Mode 7: Casual Wandering & Server Auditing (First-Impression Sampling)
- **Context:** New players evaluating world generation quality, server latency, and overall community atmosphere before committing time.
- **Objective:** Experience immediate visual interest, smooth server performance (no rubberbanding or tick lag during chunk loading), and clear progression paths.

### Mode 8: Cooperative Group Settle (Duo & Guild Settle)
- **Context:** Groups of 2 to 6 friends joining a server together who wish to establish adjacent private estates or a shared commune.
- **Objective:** Arrive together in a large, multi-biome territory with enough spatial capacity to accommodate multiple bases without crowding.
- **Operational Pattern:** Group RTP commands (`/rtp group`), party anchors, or chaining `/rtp` followed by sequential `/tpa` requests.

---

## 3. Player Archetypes & Quantitative Valuation Functions

Player decision-making can be formalized through an objective **Site Valuation Function** ($\mathcal{U}_{\text{site}}$):

$$\mathcal{U}_{\text{site}} = \sum_{i} w_i \cdot V_i - \sum_{j} c_j \cdot C_j$$

Where:
- $V_i \in [0, 1]$ represents positive environmental attributes (flatness, aesthetic score, resource density).
- $C_j \in [0, 1]$ represents negative friction and hazard penalties (sheer cliffs, nearby strangers, defaced terrain).
- $w_i, c_j \ge 0$ represent archetype-specific valuation weights.
- If $\mathcal{U}_{\text{site}} < \theta_{\text{threshold}}$, the player rejects the landing and executes an immediate `/rtp` reroll.

```
+───────────────────────────────────────────────────────────────────────────────────+
|                             PLAYER VALUE PROFILE SUMMARY                          |
+─────────────────────────┬───────────────────────┬─────────────────────────────────+
| Archetype               | Primary Driver ($w$)  | Critical Veto Factor ($c$)      |
+─────────────────────────┼───────────────────────┼─────────────────────────────────+
| 1. Aesthetic Homesteader| Biome Beauty, Flatness| Nearby Players, Defaced Land    |
| 2. Resource Extractor   | Ore & Wood Density    | Resource Depletion              |
| 3. Structure Hunter     | Structure Proximity   | Long Travel Distance            |
| 4. Technical Architect  | Mob Cap / Y-Level Opt | Unfavorable Biome / Cave Clutter|
| 5. Anarchy Survivor     | Concealment / Distance| Spawn Proximity, Visible Trails |
| 6. Anarchy Raider       | Target / Claim Access | Pure Isolation / No Targets     |
| 7. Co-op Guild Pioneer  | Multi-Base Footprint  | Cramped Terrain, Border Bounds  |
+─────────────────────────┴───────────────────────┴─────────────────────────────────+
```

### Archetype 1: The Aesthetic Homesteader (Solo Builder / Hermit)
- **Psychological Profile:** High Builder, moderate Explorer, low Killer. Represents the largest demographic on standard survival SMPs.
- **Preferred Biomes:** Plains, Meadow, Cherry Grove, Forest, Savanna, Birch Forest. Rejects Deserts, Swamps, Badlands, and Oceans.
- **Valuation Weights:**
  - $w_{\text{isolation}} = +0.95$: Premium on absolute distance from strangers ($d > 1,500$ blocks).
  - $w_{\text{flatness}} = +0.80$: Low elevation variance ($\sigma_Y < 8$) to minimize terraforming overhead.
  - $w_{\text{aesthetic biome}} = +0.85$: Vibrant foliage colors and scenic terrain backdrops.
  - $c_{\text{prior activity}} = 1.0$ (Hard Veto): Chopped trees, floating leaves, torches, or nearby claims trigger an instant reroll.
  - $c_{\text{hazard}} = 0.90$: Heavy penalty for ravines, cliffs, or surface lava pools.

$$\mathcal{U}_{\text{homestead}} = 0.95 \cdot D_{\text{isolation}} + 0.85 \cdot B_{\text{aesthetic}} + 0.80 \cdot T_{\text{flat}} - 1.0 \cdot \mathbb{I}_{\text{defaced}} - 0.90 \cdot H_{\text{hazard}}$$

### Archetype 2: The Resource Extractor (Quarry Miner / Logger)
- **Psychological Profile:** High Achiever, high Miner. Motivated by inventory accumulation and material hoarding.
- **Preferred Biomes:** Badlands (terracotta/gold), Deserts (sand), Warm/Deep Oceans (gravel/sponges), Dark Forest / Old Growth Taiga (dense wood), Deep Caves / Stony Peaks (exposed iron/coal).
- **Valuation Weights:**
  - $w_{\text{resource density}} = +1.0$: Volume of harvestable blocks per cubic meter.
  - $w_{\text{cave exposure}} = +0.85$: Open ravines or cave mouths descending into deepslate ($Y < 0$).
  - $w_{\text{isolation}} = 0.0$: Indifferent to neighboring builds, provided local resources are intact.
  - $c_{\text{depleted}} = 1.0$ (Hard Veto): Chopped tree stumps or mined quarry pits yield zero value -> instant reroll.

$$\mathcal{U}_{\text{extract}} = 1.0 \cdot R_{\text{density}} + 0.85 \cdot C_{\text{exposure}} - 1.0 \cdot \mathbb{I}_{\text{depleted}}$$

### Archetype 3: The Structure Hunter / Cartographer
- **Psychological Profile:** Pure Explorer. Motivated by discovery, rare loot (Armor Trims, Heavy Cores, Elytra), and world completion.
- **Preferred Targets:** Ancient Cities, Trial Chambers, Villages, Woodland Mansions, Nether Fortresses, End Cities.
- **Valuation Weights:**
  - $w_{\text{structure proximity}} = +1.0$: Distance to nearest generated structure ($d \le 150$ blocks).
  - $w_{\text{unlooted}} = +0.95$: Structure must contain intact chests and spawners.
  - $w_{\text{traversability}} = +0.60$: Open terrain or waterways facilitating high-speed flight or boat transit.
  - $c_{\text{permanence}} = 0.0$: Zero value placed on base-building suitability.

$$\mathcal{U}_{\text{hunter}} = 1.0 \cdot S_{\text{structure}} + 0.95 \cdot \mathbb{I}_{\text{unlooted}} + 0.60 \cdot T_{\text{open}} - 0.80 \cdot \mathbb{I}_{\text{looted}}$$

### Archetype 4: The Technical Architect (Redstone / Farm Engineer)
- **Psychological Profile:** High Mastery / Systemic Achiever. Treats the Minecraft world as an engine to be optimized.
- **Preferred Biomes:** Deep Ocean (eliminates surface and cave mob spawning within render distance), Swamps (Witch huts / low-slime rules), Deserts (Husk farms / low passive mob interference), Nether Soul Sand Valleys (Wither Skeleton / Ghast spawn concentration).
- **Valuation Weights:**
  - $w_{\text{mobcap isolation}} = +1.0$: Minimizes unlit subterranean volume within a 128-block spherical radius from the AFK platform.
  - $w_{\text{low y}} = +0.90$: Surface elevation as close to bedrock as possible (exploiting the sub-chunk spawning algorithm: lower Y levels yield higher mob spawning rates).
  - $w_{\text{chunk alignment}} = +0.70$: Natural alignment with slime chunks or bounding boxes of witch huts / nether structures.
  - $c_{\text{entity clutter}} = 0.90$: Rejects chunks with excessive passive mob entities, tile entities, or overlapping village centers.

$$\mathcal{U}_{\text{technical}} = 1.0 \cdot M_{\text{isolation}} + 0.90 \cdot (1 - \hat{Y}_{\text{surface}}) + 0.70 \cdot A_{\text{structure box}} - 0.90 \cdot E_{\text{clutter}}$$

### Archetype 5: The Anarchy Survivor (Concealment Specialist)
- **Psychological Profile:** Paranoia-driven Survivalist. Operates under zero grief rules or theft protection.
- **Valuation Weights:**
  - $w_{\text{spawn distance}} = +1.0$: Distance from world spawn ($d > 50,000$ blocks).
  - $w_{\text{concealment}} = +0.95$: Natural camouflage (deep ocean floor, under ocean monuments with mining fatigue, hollow mountain interiors).
  - $c_{\text{rtp landing zone}} = 1.0$ (Hard Veto): Rejects any location where another player could casually land via `/rtp` (e.g., standard flat plains, hills, or land within the core RTP radius).
  - $c_{\text{trail}} = 1.0$: Any visible entry path or coordinate marker is an immediate disqualifier.

$$\mathcal{U}_{\text{survivor}} = 1.0 \cdot D_{\text{spawn}} + 0.95 \cdot C_{\text{camouflage}} - 1.0 \cdot \mathbb{I}_{\text{rtp reachable}}$$

### Archetype 6: The Competitive Raider / Faction Scout
- **Psychological Profile:** High Killer / Competitor. Seeks confrontation, dominance, and economic looting.
- **Valuation Weights:**
  - $w_{\text{target proximity}} = +1.0$: Prefers landing between $150$ and $400$ blocks from a target base (close enough to infiltrate, far enough to avoid claim-detection alarms or anti-RTP claim protection).
  - $w_{\text{radar signals}} = +0.80$: Detects tile-entity clusters, animal pens, or custom player blocks via client rendering or map markers.
  - $c_{\text{empty wilderness}} = 0.85$: Pristine, untouched wilderness has low utility -> triggers another `/rtp` jump to continue scanning the perimeter ring.

$$\mathcal{U}_{\text{raider}} = 1.0 \cdot P_{\text{target}} + 0.80 \cdot S_{\text{radar}} - 0.85 \cdot \mathbb{I}_{\text{pristine}}$$

### Archetype 7: The Co-op Guild Pioneer (Group Settlement)
- **Psychological Profile:** Socializer / Collaborative Builder. Plays with a dedicated group of friends or guildmates.
- **Valuation Weights:**
  - $w_{\text{spatial capacity}} = +1.0$: Contiguous usable land area ($> 500 \times 500$ blocks without severe mountains or ravines dividing the plots).
  - $w_{\text{biome diversity}} = +0.80$: Multiple biome borders within 250 blocks to support diverse aesthetic tastes among group members.
  - $w_{\text{isolation external}} = +0.90$: High distance from non-guild strangers.
  - $c_{\text{boundary clamping}} = 1.0$: Landing near the server's world border or near foreign claims that restrict outward expansion.

$$\mathcal{U}_{\text{guild}} = 1.0 \cdot A_{\text{capacity}} + 0.80 \cdot B_{\text{diversity}} + 0.90 \cdot D_{\text{strangers}} - 1.0 \cdot \mathbb{I}_{\text{cramped}}$$

---

## 4. Cross-Archetype Valuation Matrix

The following matrix compares normalized valuation weights ($-1.0$ = extreme aversion / veto, $+1.0$ = essential requirement, $0.0$ = indifferent) across all primary environmental dimensions:

| Dimension / Environmental Feature | Homesteader | Extractor | Structure Hunter | Technical | Anarchy Survivor | Raider | Guild Pioneer |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Isolation from Strangers ($d > 1.5\text{k}$)** | **+0.95** | 0.00 | 0.00 | +0.40 | **+1.00** | **-0.85** | **+0.90** |
| **Topographic Flatness ($\sigma_Y < 8$)** | **+0.80** | +0.10 | +0.40 | **+0.75** | -0.20 | 0.00 | **+0.85** |
| **Aesthetic Biome (Plains/Cherry/Forest)** | **+0.85** | -0.20 | +0.20 | -0.30 | -0.40 | 0.00 | **+0.80** |
| **Specialized Biome (Ocean/Desert/Swamp)** | **-0.70** | **+0.85** | +0.50 | **+1.00** | **+0.90** | 0.00 | -0.20 |
| **Raw Surface Ore / Log Density** | +0.40 | **+1.00** | 0.00 | 0.00 | +0.30 | 0.00 | +0.50 |
| **Generated Structure Proximity ($< 200\text{b}$)**| +0.30 | +0.20 | **+1.00** | **+0.70** | **-0.80** | +0.60 | +0.40 |
| **Proximity to Existing Claims** | **-1.00** | -0.20 | 0.00 | -0.50 | **-1.00** | **+1.00** | **-1.00** |
| **Prior Human Traces (Stumps/Torches)** | **-1.00** | **-1.00** | -0.30 | 0.00 | **-1.00** | **+0.80** | **-0.90** |
| **Sub-Chunk Y Optimization (Low Y)** | 0.00 | +0.50 | 0.00 | **+0.90** | **+0.80** | 0.00 | 0.00 |
| **Terrain Hazards (Cliffs/Lava/Dark)** | **-0.90** | -0.40 | -0.20 | -0.50 | -0.30 | 0.00 | **-0.80** |

---

## 5. Spatial Economics: Why Proximity Inflicts Disutility in Minecraft

Classical urban economics posits that spatial clustering generates positive network externalities (reduced transit costs, shared infrastructure, and economic specialization). In a Minecraft survival world, however, **the physical laws of the universe are inverted**:

1. **Land is Functionally Infinite:** A standard Minecraft world encompasses $60,000,000 \times 60,000,000$ blocks ($3.6 \text{ billion chunks}$). Economic rent on open wilderness is zero.
2. **Transportation Cost Approaches Zero:** Nether highways ($8:1$ coordinate compression), blue ice boat channels ($72 \text{ m/s}$), and Elytra flight allow players to traverse thousands of blocks in minutes.
3. **Every Player Is an Autonomous Industrial Economy:** Automated iron farms, crop towers, villager trading halls, and mob grinders produce near-infinite yields. Players do not need physical proximity to barter for raw subsistence materials.

Because the economic drivers of physical clustering do not exist, proximity to strangers on an SMP yields **almost purely negative externalities**:

### A. The 128-Block Despawn Sphere & Mob Cap Sabotage
Minecraft's spawning engine uses a hard $128$-block spherical radius centered on the player. Hostile mobs outside $128$ blocks despawn instantly, and mob caps ($70$ monsters per player) are shared across loaded chunks:
- If Player B builds $100$ blocks away from Player A, their $128$-block despawn spheres heavily overlap.
- If Player B leaves unlit caves beneath their base, hostile mobs spawn there and fail to despawn, filling the regional mob cap and breaking Player A's mob farms, perimeter grinders, or slime farms.
- Villager trading halls and iron farms within $64$ to $96$ blocks of each other merge villages, corrupting iron golem spawning lines and causing villagers to bind to foreign workstations.

### B. The Spatial Footprint of Modern Base Design
A modern SMP base is not a single structure; it is an extensive compound:
- Main residence and storage hub ($64 \times 64$ to $128 \times 128$ blocks).
- Technical farms and livestock pens ($64 \times 64$ blocks).
- Aesthetic perimeters: terraformed hills, custom foliage, perimeter walls, and sightlines.
- Subterranean branch-mining nets spanning hundreds of blocks.

When a stranger settles within view distance ($100$ to $250$ blocks), they cut off future expansion, introduce conflicting architectural styles, and leave unsightly terrain scarring.

### C. The Self-Selection Bias of `/rtp`
The act of dispatching `/rtp` is an explicit declaration: **"Place me in pristine, untouched wilderness."**
If two players wish to collaborate or visit, they use direct targeted mechanisms (`/tpa`, `/warp market`, `/home`). If an `/rtp` engine drops a player within sight of an existing base, the player does not settle; they immediately reroll or walk thousands of blocks away.

```
+─────────────────────────────────────────────────────────────────────────────+
|               THE DUAL-CHANNEL VALUE & RESOURCE MATRIX                      |
+──────────────────────────────────────┬──────────────────────────────────────+
|          /rtp (Wilderness)           |            /tpa (Social)             |
+──────────────────────────────────────┼──────────────────────────────────────+
| • Intent: Clean land acquisition     | • Intent: Voluntary social contact   |
| • Marginal Chunk Cost: ~80 chunks    | • Marginal Chunk Cost: ~0 chunks     |
|   (Cold generation / disk read)      |   (Destination chunk already warm)   |
| • Failure Mode if Crowded:           | • Failure Mode if Isolated:          |
|   Immediate reroll (wasted compute)  |   None (direct targeted link)        |
| • Net Welfare Yield: High per-base   | • Net Welfare Yield: Instantaneous,  |
|   capital investment over time       |   zero-search social satisfaction    |
+──────────────────────────────────────┴──────────────────────────────────────+
```

### D. Targeted Proximity: The Role of `nearplayer` and `nearclaim`
Proximity is transformed from a nuisance into a gameplay feature only when it is **opt-in and intentional**. Dedicated actions like `nearplayer` and `nearclaim` (implemented via `LeafRTPActionAddon`) serve competitive raiding, border scouting, and group gatherings without compromising the virgin wilderness guarantees of the primary `/rtp` queue.

---

## 6. Empirical Telemetry & Game-Theoretic Phenomena

Analysis of public SMP telemetry (including over 10,000 automated test teleports across high-concurrency servers) reveals several critical failure modes in naive candidate selection:

### A. The "Plains Clumping" Artifact
On servers utilizing basic candidate selection, **up to 85% of `/rtp` landings cluster in Plains biomes**, with forests representing under 3% and specialized biomes virtually unrepresented.
- **Root Cause:** Naive plugins enforce crude block blacklists to prevent player suffocation:
  - Water / Lava -> eliminates all Oceans, Rivers, and Beaches (~40% of world volume).
  - Leaves / Logs -> eliminates dense Forests, Dark Forests, and Jungles.
  - Sheer cliffs / Snow -> eliminates Jagged Peaks, Stony Peaks, and Mountain slopes.
- **Consequence:** By filtering blocks reactively rather than using multi-layer vertical column adjustors, naive algorithms funnel the vast majority of candidates into the few biomes that are naturally flat and treeless: **Plains**.
- **Player Impact:** Homesteaders quickly recognize this clumping and immediately reroll away from Plains biomes because they know Plains have the highest player traffic, highest grief rates, and lowest isolation.

### B. Strategic Exploitation of RTP Blind Spots
Because players understand algorithm limitations, defensive builders deliberately establish bases where RTP algorithms cannot land:
1. **Sub-Oceanic Vaults:** Built under deep ocean trenches where surface RTP drops are banned by fluid filters.
2. **Beyond the Outer Radius:** Constructed outside the configured `maxRadius` (e.g., traveling to coordinate $60,000$ when RTP max radius is $25,000$).
3. **Mushroom Fields:** Naturally hostile mob-free zones, completely excluded by plugins that lack biome override configurations.

### C. The Compounding Cost of the Reroll Cascade
When an engine drops a player into an undesirable location ($\mathcal{U}_{\text{site}} < \theta$), the player executes an immediate reroll. The true server cost of a settled player is the **cumulative resource expenditure across all discarded attempts**:

$$\text{Total System Cost} = \sum_{i=1}^{N_{\text{attempts}}} \left( \text{Cost}_{\text{selection}} + \text{Cost}_{\text{handoff}} + \text{Cost}_{\text{view-distance}} \right)$$

- If Plugin A has a "cheap" candidate selection cost ($5 \text{ ms}$) but drops players into oceans, cliffs, or crowded areas 60% of the time, the player must execute an average of $2.5$ teleports to settle. The server incurs $2.5 \times 80 = 200$ chunk loads and $2.5 \times 10.6 \text{ ms} = 26.5 \text{ ms}$ of main-thread handoff time per settled player.
- If Plugin B consumes slightly more background CPU ($15 \text{ ms}$) running Anvil pre-filters and spatial dispersion checks (`uniquePlacements: "AUTO"`), its settlement acceptance rate approaches $95\%$. The player settles on attempt 1, consuming only $80$ chunk loads and $10.6 \text{ ms}$ of main-thread execution.
- **Outcome:** Proactive verification delivers higher aggregate efficiency and protects server tick stability by minimizing the total number of round-trip teleports required to achieve player satisfaction.

---

## 7. Lightweight Behavioral Benchmark Architecture

To measure these dynamics without the deadweight CPU cost and fragility of full client-side survival AI (pathfinding, tree-cutting, obstacle jumping), the harness decouples **decision metrics** from **physical bot execution**.

```
[ Lightweight Bot Connects ]
            │
            ▼
[ Dispatches /rtp Command ]
            │
            ▼
[ Landing Zone Valuation ] ──> Evaluates Biome, Terrain Height, Existing Modifications
            │
            ├─── Unacceptable / Consumed ──► Dispatch Next `/rtp` (Reroll Cascade)
            │
            └─── Desirable / Available
                     │
                     ▼
[ Single Block Interaction ] ──► Marks Chunk Dirty (`ChunkAccess.setUnsaved(true)`)
                     │
                     ▼
[ Session Termination ] ───────► Frees Client Slot for Successor Bot
```

### A. State Signaling via In-World Block Placement
- Upon arrival, the bot inspects its client-side chunk cache ($O(1)$ lookup via `bot.findBlock`).
- If an existing human marker (e.g., `CHEST`, `TORCH`, `COBBLESTONE`) is detected within view distance, a Homesteader agent flags the chunk as consumed and triggers an immediate reroll.
- If the location is accepted, the bot executes a single atomic block placement packet (e.g., placing a `CHEST`).
- **Server Impact:** This single packet forces the server engine to update heightmaps, recalculate local lighting, and flag the chunk as unsaved (`ChunkAccess.setUnsaved(true)`), authentically exercising the server's persistence layer during periodic world autosaves (`/save-all`).

### B. Core Longitudinal Health Metrics

| Metric | Measurement Source | Direction | Diagnostic Value |
|---|---|---|---|
| **Settlement Yield ($\eta_{\text{settle}}$)** | $\frac{\text{Accepted Settlements}}{\text{Total Dispatched Teleports}}$ | Higher | Measures placement viability; inverse reflects reroll waste. |
| **Wasted Chunk Ratio ($W_{\text{chunk}}$)** | $\frac{\text{Chunks Loaded on Rejected Teleports}}{\text{Total Chunks Loaded}}$ | Lower | Quantifies compute and I/O expended on discarded candidates. |
| **Autosave Flush MSPT Delta ($\Delta_{\text{save}}$)** | Server tick sampler during periodic `/save-all` | Lower | Measures disk write amplification caused by wide spatial dispersion. |
| **Interference Probability ($P_{\text{interfere}}$)** | Chebyshev distance to prior disturbance footprints | Lower | Measures frequency of dropping newcomers into defaced or occupied terrain. |
| **Clark-Evans $R$ Over Time** | Nearest-neighbor landing analysis | Near $1.0$ | Tracks whether spatial dispersion degrades into clusters as world ages. |

---

## 8. Architectural Validation in LeafRTP

The specialized subsystems of LeafRTP directly address these behavioral realities and optimize the aggregate welfare of the server ecosystem:

1. **Archimedean Spiral Mapping & `uniquePlacements: "AUTO"` ([ADR-001](../adr/ADR-001-archimedean-spiral-1d-mapping.md)):**  
   Guarantees uniform spatial dispersion ($R = 0.977$ in Paper 26.2 benchmarks) and marks client view-distance footprints as occupied. This eliminates near-neighbor collisions ($0$ pairs within 48 blocks across 4,096 teleports) and prevents the costly reroll cascades caused by Gaussian clustering engines.
2. **Multi-Layer Vertical Clearance & Safe Adjustors (`Fixed`, `Linear`, `Jump`):**  
   Unlike naive engines that check only surface heightmaps (causing the 85% Plains clumping artifact), LeafRTP's vertical adjustors evaluate full vertical column clearance. This enables safe, dry landings under forest canopies, inside mountain valleys, and on multi-tiered terrain, restoring natural biome diversity.
3. **Subspace Anchor Engines (`nearplayer` and `nearclaim` via `LeafRTPActionAddon` / [ADR-095](../adr/ADR-095-subspace-anchor-providers-and-near-teleport-primitives.md)):**  
   Isolates intentional proximity into specialized, opt-in actions. By sourcing candidates from spatial memory without querying third-party claim plugins inline, LeafRTP satisfies raiding and social grouping without compromising the purity of the primary wilderness queue.
4. **Anvil & Linear Region-File Pre-Filtering ([ADR-016](../adr/ADR-016-anvil-subsystem.md), [ADR-077](../adr/ADR-077-multi-format-region-support.md)):**  
   Decompresses raw region files off the main thread to evaluate biomes, terrain surfaces, and structures before chunks are loaded into memory. This eliminates main-thread chunk allocation for rejected candidates, ensuring that sub-5ms latency is delivered without hitching the server tick loop.
5. **Cross-Server Routing & Dedicated Resource Worlds ([ADR-036](../adr/ADR-036-network-mode-multi-server-multi-proxy.md)):**  
   Enables operators to route the Resource Extractor archetype to self-resetting mining dimensions, containing high-entropy terrain scarring and protecting permanent builder worlds from disk bloating and autosave strain.
