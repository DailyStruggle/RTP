# Marketplace Conversion, Storefront Salience, and Buyer-Segment Research

> **Status**: Living Reference Document
> **Scope**: Cross-resource (`resource/*` Minecraft plugins and mods, `leaf-video/`, `corpus-atlas/`, `discord-support-bot/`, and public SDKs)
> **Audience**: Core Developers, Release Maintainers, Storefront Authors
> **Related Documents**: [`ADR-108`](../adr/ADR-108-all-mit-licensing-pro-as-support.md), [`ADR-126`](../adr/ADR-126-multi-product-monorepo-release-architecture.md), [`ADR-130`](../adr/ADR-130-shallow-ast-storefront-architecture-and-salience-governance.md), [`LICENSING.md`](LICENSING.md), [`PUBLISHING.md`](PUBLISHING.md), [`RULES.md`](RULES.md) (`Rule D-006`), [`AI_USAGE.md`](AI_USAGE.md)

---

## 1. Purpose and Scope

This document records empirical research on digital-marketplace conversion baselines, binomial sample-size power, review cold-start curves, technical copy length, AI-content disclosure effects, and Minecraft server-hosting billing structures.

Because `docs/dev/` is shared across monorepo products and Option A per-resource projections ([`ADR-126`](../adr/ADR-126-multi-product-monorepo-release-architecture.md)), this reference serves two cross-resource functions:

1. **Anti-overfitting guard against small-$N$ storefront noise**: Prevents maintainers or AI agents from diagnosing a listing's price, copy, or engineering merit as "broken" before view counts cross statistical power thresholds.
2. **Evidence-backed storefront and channel calibration**: Separates folk marketing intuitions ("short copy always wins", "cold start is a total deadlock", "AI disclosure kills sales", "panel renters never pay for storage") from peer-reviewed experiments, large-sample landing-page benchmarks, and live marketplace telemetry.

---

## 2. Conversion Baselines and Binomial Sample-Size Power

Raw storefront view counters (such as BuiltByBit's `Views`, Modrinth's page hits, or GitHub traffic views) count unfiltered impressions - including moderator audits, author checks, bots, and casual *"New Resources"* feed clicks - rather than unique qualified buyers. Evaluating early paid listings requires testing against empirical paid-software baselines using exact binomial confidence intervals.

### 2.1 Empirical Web and Paid-Software Conversion Baselines

| Benchmark / Dataset | Sample Scope | Observed Conversion Rate | Source |
|---|---|---|---|
| **Contentsquare Digital Experience Benchmark** | `46 billion` web sessions across industries | `2.35%` all-page average (includes free signups and low-friction retail) | Contentsquare (2024/2025) |
| **Cold-browse paid software (no free checkout path)** | Cold click-to-paid-purchase across digital software listings | `0.30% - 1.00%` typical (`1` purchase per `100 - 333` raw views) | Industry SaaS / digital-download benchmarks; MECLABS |
| **MECLABS / MarketingExperiments Paid Split Test** | `N = 1,440` paid clicks across two treatment arms (*Long Copy vs. Short Copy Tested*) | Winning, `+50%` ROI profitable arm converted at **`0.69%`** overall; one ad group recorded **`0` sales in `523` clicks (`0.00%`)** from sample variance alone | MECLABS / MarketingExperiments |
| **BuiltByBit Marketplace Traffic & Paid Backend Listings** | `~1.48M` monthly visits x `7.53` pages/visit (`~11.1M` monthly pageviews) across `8,256+` plugins plus setups, builds, and configs (2026) | High-velocity player gameplay plugins reach hundreds of sales (e.g. *Bloods Gems SMP*: `490` purchases at `$18.00`), whereas niche backend/optimization listings routinely accumulate single-digit sales slowly (e.g. `minecraft-server-optimization.121555`: `5` lifetime purchases) | Similarweb / Semrush traffic estimates & live BuiltByBit resource counters (Nov 2026) |

### 2.2 Exact Clopper-Pearson Zero-Purchase Bounds ("Rule of Three")

When a new paid listing has observed $k = 0$ purchases in $N$ raw views, the upper 95% exact Clopper-Pearson confidence bound on the true underlying conversion rate $p$ is approximated by Hanley & Lippman-Hand (1983, *JAMA*):

$$p_{\text{upper}, 95\%} = 1 - (0.05)^{1/N} \approx \frac{3}{N}$$

Conversely, for any true underlying conversion rate $p$, the probability of observing **zero** purchases across $N$ independent views purely by chance is $P(X = 0 \mid N, p) = (1 - p)^N$:

| Raw Views ($N$) | 95% Clopper-Pearson Interval ($k = 0$) | $P(0 \text{ sales})$ if true $p = 0.3\%$ | $P(0 \text{ sales})$ if true $p = 0.5\%$ | $P(0 \text{ sales})$ if true $p = 1.0\%$ | Statistical Interpretation |
|---:|---|---:|---:|---:|---|
| `100` | `[0.00%, 3.62%]` | `74.0%` | `60.6%` | `36.6%` | Pure noise; indistinguishable from `3%+` conversion |
| `264` | `[0.00%, 1.13%]` | `45.2%` | `26.6%` | `7.0%` | **Underpowered** (`LeafAnvil` day-6 snapshot: `> 1 in 4` chance at `p = 0.5%`) |
| `500` | `[0.00%, 0.60%]` | `22.3%` | `8.2%` | `0.7%` | Rejects `p >= 1.0%` ($p < 0.05$), still consistent with `0.3% - 0.5%` |
| `600` | `[0.00%, 0.50%]` | `16.5%` | `4.9%` | `0.2%` | Minimum threshold to reject a `0.5%` baseline at $\alpha = 0.05$ |
| `1,000` | `[0.00%, 0.30%]` | `5.0%` | `0.7%` | `< 0.01%` | Minimum threshold to reject a `0.3%` cold-browse baseline at $\alpha = 0.05$ |

**Operational Rule**: Do not rewrite pricing, architecture positioning, or storefront copy solely in reaction to `0` purchases when $N < 600$ raw views (or $N < 1,000$ for high-ticket / infrastructure tools), unless a specific defect is independently identified.

---

## 3. Empirical Review of Storefront & Conversion Hypotheses

### 3.1 Cold-Start Social Proof (`0` to `5` Verified Reviews)

* **Evidence for a cold-start headwind**:
  * **Northwestern University Medill Spiegel Research Center** (*From Reviews to Revenue*, PowerReviews dataset): Moving a product from `0` reviews to `5` reviews increases conversion rates by **`270%`** on average across categories.
  * **Risk/price interaction**: On higher-priced, higher-consideration items, the `0 -> 5` review lift reaches **`380%`** (`~4.8x` baseline), compared to **`190%`** (`~2.9x`) on lower-priced, low-risk items.
  * **Verified-buyer weighting & rating credibility**: Spiegel found that shoppers heavily discount unverified ratings relative to *Verified Buyer* badges (relevant when a sidebar displays `1 rating` alongside `Purchases: 0`), and purchase probability peaks in the **`4.0 - 4.7` star** band rather than at an unverified `5.00` ("too good to be true" skepticism at tiny $N$).
  * **Audience warmth**: **InsightRaider** (`146,000` Gumroad digital software/creator products, `$65.8M` volume) found that **`42%` of digital product sales originate from the creator's own warm channels** rather than cold marketplace browse discovery, with `77.3%` of revenue concentrated in the top `1%` of established listings.
* **Evidence against treating cold start as an absolute deadlock**:
  * Live BuiltByBit front-page snapshots (Nov 2026) show multiple `Not yet rated` (`0` reviews) listings actively converting across `$9.99 - $18.99` price points (e.g., *MicroBattle Minigame Plugin*: `8` purchases, `0` ratings; *ItemAdder UI Icons*: `16` purchases, `0` ratings; *UNSC Pack*: `16` purchases, `0` ratings; *Server Website Template*: `16` purchases, `0` ratings).
* **Cross-resource takeaway**: Cold start is an empirically documented **`~3x - 4x` initial conversion drag** - especially on high-trust server infrastructure - not a permanent barrier. Routing warm users from established free properties (`LeafRTP`'s `444,474+` SpigotMC downloads, Modrinth, Hangar, Discord) or seeding verified production operators compresses the `0 -> 5` review window fastest.

---

### 3.2 Copy Length, Reading Complexity, and Shallow-AST Salience

* **Where concise, plain-language copy wins (Zone 1 & Zone 2)**:
  * **Unbounce Conversion Benchmark Report** (`41,000` landing pages, `464M` pageviews): For general SaaS and lead-capture pages, median conversion peaks at **`250 - 745` words** written at a **5th-7th grade reading level** (`11.1% - 12.9%` median conversion vs. `2.1% - 5.3%` at college/technical reading levels, with a `-24.3%` negative correlation for dense 3+ syllable terminology).
* **Where deep, evidence-dense copy wins (Zone 3 / Spoilers)**:
  * **MECLABS / MarketingExperiments** (*Long Copy vs. Short Copy Matrix* and *Long Copy Tested*) and **CXL / Nielsen Norman Group** demonstrate that the "short copy wins" heuristic **reverses on high-risk, complex, analytical purchases**. When buyer anxiety about failure or compatibility is high, MECLABS found long analytical copy outperformed short copy nearly **4-to-1** (`+50%` ROI vs. `-66%` ROI).
* **Reconciliation with `ADR-130` (`FRONT_PAGE.md` 3-Zone Architecture)**:
  * Both findings are simultaneously satisfied by [`ADR-130`](../adr/ADR-130-shallow-ast-storefront-architecture-and-salience-governance.md) and `Rule D-006`: keep **Zone 1** (`The short version`) and **Zone 2** (`Common setups`, `Install`, `Features`) plain, outcome-first, and free of internal codec/math jargon for fast skimmers and search-engine salience (`>= 70%` usability/safety weight), while retaining verifiable benchmarks, failure bounds, and reproducibility proofs inside collapsed `[SPOILER]` / `<details>` sections or linked `docs/site/` pages for high-scrutiny technical buyers.
  * Repository control evidence: `LeafRTP` uses this exact engineering-first, benchmark-backed voice and has accrued **`444,474` downloads** on SpigotMC (`spigotmc.org/resources/leafrtp.94812`) alone.

---

### 3.3 AI Content Disclosure and Transparency Interaction

Marketplace platforms (including BuiltByBit and Modrinth) require disclosure when AI tooling assists in development or completion. Two peer-reviewed experimental streams clarify when AI disclosure hurts trust and when that penalty disappears:

| Study | Methodology & Sample | Key Empirical Finding |
|---|---|---|
| **Cicek, Gursoy & Lu (2024)**, *Journal of Hospitality Marketing & Management* (Washington State Univ. & Temple Univ.) | Controlled experiments across `8` product/service categories, $N > 1,000$ U.S. adults | Explicit AI-involvement labels lowered emotional trust and purchase intention across all 8 categories, with the **largest negative effect on high-risk products** where defects carry severe consequences. |
| **Wang (2025)** & **Nguyen (2026)**, *Innovative Marketing* | $2 \times 2$ experimental ANOVA ($N = 400$ in Nguyen 2026) testing AI disclosure x explanatory/evidence transparency | AI disclosure had **no statistically significant negative main effect on purchase intention ($p = 0.794$) when paired with high evidence-based transparency** (interaction $F = 7.907, p = 0.006$). Trust erosion concentrated specifically in *AI disclosure + low verification transparency*. |

* **Cross-resource takeaway**: Because disclosure is mandatory and honest (see [`AI_USAGE.md`](AI_USAGE.md)), never attempt to obscure tooling use. Instead, neutralize the low-transparency penalty on high-trust products (`LeafAnvil`, `LeafSleuth`, `LeafShard`) by pairing disclosure with concrete, falsifiable verification artifacts above or near the fold: open MIT format specs (`LCA-ICD-1.0`, `LCV-ICD-1.0`), automated multi-loader acceptance matrices, PBIT/CBIT self-checks, and non-destructive rollback/backup guarantees (`.mca` preserved untouched, `OBSERVE`-first boot mode).

---

### 3.4 Minecraft Server Hosting Plans and Buyer Incentive Alignment (2026)

Evaluating infrastructure plugins (`LeafAnvil`, `LeafShard`, `LeafSleuth`, `LeafRTP`) requires accurate facts on how Minecraft operators actually pay for disk, RAM, CPU, and backups - avoiding oversimplified assumptions that all game-panel renters have "unlimited free NVMe":

| Hosting Segment / Provider (2026 Plans) | Published Storage & Backup Terms | Operator Pain Points Addressed by Repo Products |
|---|---|---|
| **Capped Game-Panel Plans**: **Nodecraft** (`$9.98/mo` -> **`10 GB SSD`** cap), **ScalaCube** (`$10/mo` -> **`15 GB SSD`** cap), **Hostinger Game Panel** (`$6.99 - $11.99/mo` -> **`50 GB NVMe`** cap), **Lagless** (`$8.00/mo` -> **`50 GB NVMe`** cap), **PebbleHost Budget** (`$1/GB` + paid backup slots) | Hard SSD/NVMe quotas (`10 - 50 GB`) or paid backup-slot add-ons; pre-generating a `10k - 20k` radius across Overworld/Nether/End with `Chunky` rapidly exhausts `15 - 50 GB` quotas | Direct monthly tier-upgrade avoidance (`~50%` `.lca` disk reduction) **plus** live Folia/Paper MSPT & chunk-streaming throughput (`23x - 35x` live streaming, `9.5x - 15.5x` faster shutdown saves) |
| **"Unmetered" Shared Game Panels**: **Apex Hosting** (`$14.99+/mo`), **BisectHosting** (`$5.99+/mo`) | Advertised "Unlimited NVMe" (subject to fair-use policies); priced primarily by RAM tier | Disk quota is rarely the primary pain for the renter; buyer value concentrates on **tick latency (MSPT/TPS)**, **chunk-streaming speed**, **world-save stalls**, and **dupe/lag-machine defense** (`LeafSleuth`, `LeafRTP`, `LeafAnvil` I/O latency) |
| **Casual / Managed One-Click Panels**: **Minehut** (integrated BuiltByBit 1-click install on `2026-10-27`, including free plans) | Restricted container/loader environments; casual session play | Low fit for custom region-storage interceptors or Folia-specific infrastructure; inflates top-of-funnel BuiltByBit browse impressions without matching technical server requirements |
| **Self-Managed VPS & Bare-Metal Nodes**: **Hetzner**, **OVHcloud**, **Hostinger VPS**, **Netcup** | Direct billing per GB of NVMe, snapshot storage, and egress bandwidth; full JVM/loader control | Captures **100% of both storage/backup ROI and CPU/MSPT ROI** (`LeafAnvil`, `LeafShard`, `LeafSleuth`) |
| **Multi-Tenant Hosting Providers & Marketplaces** (B2B): Hosting companies, cloud backup fleets, plugin marketplaces (BuiltByBit, Modrinth, Hangar, CurseForge) | Aggregate petabyte-scale idle-world/backup storage bills; manual moderation queues (`9`-day audit bottlenecks on paid uploads) | Highest B2B willingness-to-pay for fleet-level storage compression (`LeafAnvil`), automated headless `.jar` causal triage (`LeafFuzz` / `bytecode-api`), and zero-hallucination support triage (`discord-support-bot` / `corpus-atlas`) |

* **Observability caveat**: Per InsightRaider's marketplace study, aggregate storefront counters (`Views`, `Purchases`) do not expose viewer referrer or hosting-tier mix. Never assert who viewed a listing without referrer UTM parameters, download-by-loader splits, or bStats/survey telemetry.

---

## 4. Portfolio-Wide Product Positioning Matrix

Combining repository licensing ([`LICENSING.md`](LICENSING.md), [`ADR-108`](../adr/ADR-108-all-mit-licensing-pro-as-support.md)) with the empirical trust and channel findings above yields clear role separation across the monorepo:

| Resource / Pillar | License Model | Primary Buyer & Pain Type | Trust Friction & Best Conversion Lever |
|---|---|---|---|
| **`LeafRTP`**, **`LeafSleep`**, **`LeafTreeGen`**, **`LeafAnvil-Net`** | `100% MIT` (`Pro` = SLA support & sponsorship per `ADR-108`) | Broad Minecraft operators (free adoption) + commercial servers needing SLA support | **Near-zero adoption friction** (`444k+` `LeafRTP` downloads); serves as the **warm audience funnel** (`42%` warm-channel conversion share) into commercial tools |
| **`LeafSleuth`** (`resource/LeafSleuth`) | `LicenseRef-LeafCraft-Commercial` (`MIT` extension SPI) | Commercial SMP / economy server operators fighting live lag machines, dupes, and VPN ban-evaders | **Acute daily pain + low trial risk**: lead storefront copy with **read-only `OBSERVE` boot mode** so operators see real culprits before enabling automated governance |
| **`LeafAnvil`** (`resource/LeafAnvil`) + **`LeafShard`** (`resource/LeafShard`) | `LicenseRef-LeafCraft-Commercial` runtime + `MIT` `LCA-ICD-1.0` & `anvil-api` | **Retail**: Capped-NVMe (`10 - 50 GB`), pre-generated SMP, Folia, and VPS/dedicated operators<br>**B2B**: Hosting panels & large sharded networks | **High existential world-save caution**: lead with **chunk-streaming/save-stall speed** alongside disk quota savings, highlight **untouched `.mca` fallback + MIT offline converter**, and pursue B2B host licensing in parallel with retail |
| **`LeafVideo`** (`resource/LeafVideo` + `MIT` `leaf-video/`) | `LicenseRef-LeafCraft-Commercial` OBS/NLE plugins & B2B SDK + `MIT` `LCV-ICD-1.0` | **Creator B2C**: Streamers/VTubers (`$19 - $39` OBS Multi-Crop & Crash Guard) & editors (`$49 - $99` NLE plugins)<br>**B2B**: FPV/robotics/VR `HARSH_RF_UEP` links | **Immediate personal pain (OBS FPS drop + `.mp4` crash loss)** with zero world-corruption anxiety when paired with one-click `.mp4` / `ProRes` `export-carrier` |
| **`Corpus-Atlas`** + **`discord-support-bot`** + **`LeafFuzz` (`bytecode-api`)** | Open-Core (`MIT` local CLI/MCP & `LeafFuzz` + Commercial SaaS / CI / Marketplace licensing) | Software engineering teams (`$20 - $50/seat/mo` CI gates & token compression), SaaS/game studios (`$49 - $499+/mo` support triage), and plugin marketplaces/hosts | **Highest B2B recurring ceiling**: monetizes mathematical prompt-injection immunity ($P(\text{permute}(x)) = P(x)$), `< 50 ms` causal/race PR gates, `68% - 86%` LLM token reduction, and headless `< 0.5%`-FPR `.jar` moderation triage |

---

## 5. Cross-Resource Storefront & Launch Checklist

When launching or revising any commercial or open-source listing (`resource/<Product>/docs/publishing/FRONT_PAGE.md`, `corpus-atlas/`, `leaf-video/`):

1. **Enforce Sample-Size Discipline Before Pivoting**:
   - Compute the Clopper-Pearson 95% upper bound ($\approx 3 / N$ at $0$ sales) before drawing negative conclusions. Do not treat $N < 600$ raw views as evidence of product, copy, or pricing failure.
2. **Bridge the `0 -> 5` Verified-Review Cold Start Deliberately**:
   - Route existing warm-channel users (`LeafRTP` update notes, Discord announcements, GitHub/Modrinth cross-links where platform rules permit) or issue early verified production licenses so prospective buyers do not see `0 Purchases` next to an unverified rating.
3. **Preserve `ADR-130` 3-Zone Separation (Plain On-Ramp + Deep Proof)**:
   - Keep Zone 1 and Zone 2 focused on operator outcomes, zero-lock-in safety, and 60-second setup at a plain reading level (Unbounce benchmark), while preserving full benchmark tables, mathematical invariants, and post-mortems in Zone 3 spoilers / linked docs for analytical high-risk buyers (MECLABS benchmark).
4. **De-Risk High-Trust Subsystems via Zero-Commitment Entry Modes**:
   - Whenever a product touches persistent world state, moderation enforcement, or media recordings, foreground non-destructive entry paths in Zone 1: untouched `.mca` co-existence + MIT `anvil-api` export (`LeafAnvil`), read-only `OBSERVE` boot mode (`LeafSleuth`), and standard `.mp4`/`ProRes` export (`LeafVideo`).
5. **Pair Mandatory AI Disclosure with Concrete Verification Proof**:
   - Counteract generic "AI slop" marketplace skepticism (Cicek et al., 2024) via high explanatory/verification transparency (Wang, 2025; Nguyen, 2026): link [`AI_USAGE.md`](AI_USAGE.md), cite automated multi-loader test gates, PBIT/CBIT runtime checks, and open MIT format specifications.
6. **Match Benefit Framing to Billing Reality**:
   - Lead Minecraft infrastructure pitches with **live tick/streaming latency (`MSPT`, Folia chunk streaming, save-stall elimination)** that benefits *every* server tier, followed by **hard NVMe/backup quota savings** (`10 - 50 GB` capped panels and VPS/bare-metal nodes).

---

## 6. Primary External References

1. **Hanley, J. A., & Lippman-Hand, A. (1983)**. "If nothing goes wrong, is everything all right? Interpreting zero numerators." *JAMA*, 249(13), 1743-1745. (Exact binomial upper bound / Rule of Three: $3/N$ for 95% CI on zero events.)
2. **Spiegel Research Center, Northwestern University Medill School (2017)**. *How Online Reviews Influence Sales (From Reviews to Revenue)*. PowerReviews retail dataset (`270%` conversion lift from `0` to `5` reviews; `380%` on higher-priced items vs. `190%` on lower-priced items; `4.0 - 4.7` star purchase peak; Verified Buyer weighting).
3. **Contentsquare (2024/2025)**. *Digital Experience Benchmark Report* (`46B` sessions; `2.35%` cross-industry session conversion baseline).
4. **MECLABS / MarketingExperiments**. *Long Copy vs. Short Copy Tested* (`N = 1,440` paid clicks; `0.69%` profitable winning conversion rate, `+50%` vs. `-66%` ROI on high-anxiety analytical offer, and `0 / 523` single-group zero-numerator variance) & *Long Copy vs. Short Copy Matrix*.
5. **Unbounce (2020/2024)**. *Conversion Benchmark Report* (`41,000` landing pages, `464M` views; `250 - 745` word general SaaS sweet spot and reading-grade correlation).
6. **InsightRaider (2025)**. *Gumroad Marketplace Study* (`146,000` digital products, `$65.8M` creator/developer sales; `42%` warm-channel share, `77.3%` top-1% revenue concentration, and non-observability of viewer demographics from raw view counts).
7. **Cicek, M., Gursoy, D., & Lu, L. (2024)**. "Artificial intelligence disclosure in product descriptions: Effects on emotional trust and purchase intention." *Journal of Hospitality Marketing & Management*, Washington State University & Temple University ($N > 1,000$ across 8 product/service categories; risk-moderated trust penalty).
8. **Wang (2025)** & **Nguyen (2026)**. Experimental $2 \times 2$ ANOVA studies on AI disclosure x explanatory transparency (*Innovative Marketing*, $N = 400$; null main penalty $p = 0.794$ under high evidence transparency, interaction $F = 7.907, p = 0.006$).
9. **2026 Minecraft Ecosystem & Hosting Telemetry** (sampled `2026-11-17`): SpigotMC `LeafRTP` resource history (`444,474` downloads); BuiltByBit `LeafAnvil` (`leafanvil.135739`), `minecraft-server-optimization.121555`, front-page `0`-rating paid cohorts, and `2026-10-27` Minehut 1-click integration announcement; published 2026 plan tables for Nodecraft (`10 GB`), ScalaCube (`15 GB`), Hostinger (`50 GB`), Lagless (`50 GB`), PebbleHost, Apex Hosting, and BisectHosting.
