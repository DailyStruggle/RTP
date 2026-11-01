# ADR-130 - Shallow-AST Storefront Architecture, Heading-Vector Scoping, and Document Salience Governance

**Status:** Accepted
**Date:** 2026-10-28
**Extends:** [ADR-108](ADR-108-all-mit-licensing-pro-as-support.md), [ADR-115](ADR-115-package-dag-disentanglement-and-decision-preservation-hypergraph.md), [ADR-126](ADR-126-multi-product-monorepo-release-architecture.md), [corpus-atlas-ADR-001](../../corpus-atlas/docs/adr/corpus-atlas-ADR-001-scope-preserving-ast-synthesis-and-zero-open-cards.md), [corpus-atlas-ADR-002](../../corpus-atlas/docs/adr/corpus-atlas-ADR-002-method-scoped-ast-macro-comprehension-and-shorthand-ir.md)

## Context

The `DailyStruggle/RTP` monorepo publishes multiple server plugins and addons (`LeafRTP`, `LeafTreeGen`, `LeafSleep`, `LeafConfig`, `LeafSleuth`, `LeafRTPGuiAddon`) across Modrinth, Hangar, SpigotMC, BuiltByBit, and GitHub ([ADR-126](ADR-126-multi-product-monorepo-release-architecture.md)). Each product compiles its marketplace listings from a single tagged source (`docs/publishing/FRONT_PAGE.md`) via `scripts/release/build_front_pages.py` under `Rule D-006` (first-person plain engineering voice, concrete numbers, zero sales hype).

However, empirical inspection of how external search engines (Google Search, Gemini AI Overviews, Perplexity, marketplace search) and our own shallow Markdown/HTML AST indexer (`corpus-atlas/build_doc_index.py`) reconstruct `FRONT_PAGE.md` exposed a structural failure mode: **a plugin designed to work out of the box can be indexed and summarized as a hyper-technical benchmark pitch or have its resolved fuzzer bugs indexed as current product defects.**

Combining the **Google Content Warehouse API architecture** (`CompositeDoc`, `PerDocData`, `Goldmine`, `SalientTermsSet`), Google's **Heading-Vector Passage Ranking patents** (`US9959315B1`, `US20160078102A1`), mobile-first `<details>` indexing behavior, and the Hoare precondition prefixing rules proven in [`corpus-atlas-ADR-001`](../../corpus-atlas/docs/adr/corpus-atlas-ADR-001-scope-preserving-ast-synthesis-and-zero-open-cards.md) and [`corpus-atlas-ADR-002`](../../corpus-atlas/docs/adr/corpus-atlas-ADR-002-method-scoped-ast-macro-comprehension-and-shorthand-ir.md), four concrete indexing mechanisms govern how storefront pages are interpreted:

### 1. Heading-Vector Ancestor Inheritance (`US9959315B1`, `US20160078102A1`)

Search engines and RAG chunkers parse HTML and Markdown into a shallow heading hierarchy (`<h1>`–`<h4>` / `#`–`####`). Every extracted passage inherits an ancestor **Heading Vector** (`[H1 Product Title] > [H2 Section] > [H3 Subheading]`) that tells the passage ranker and LLM synthesizer what topic the passage belongs to.

- **Failure Mode (Scope Collapse Across `<details>` and `---`):** Horizontal rules (`---` / `<hr>`), bold paragraphs (`**Everyday commands**`, `**Q: ...**`), and `<details><summary>` / `[SPOILER]` containers **do not open or close a heading scope** in standard DOM/Markdown AST splitters. Any text inside or after them inherits the preceding `## H2` heading until the next `## H2` appears.
- **Observed Impact on `LeafRTP`:** Because Section 6 (`Commands, permissions, configuration files`, `FAQ`, `Support`, 13.7 KB) followed a `---` separator with no `## H2` heading, shallow AST rebuilds attached all commands, permissions, config tables, and 14 operator FAQs under **`## What paying gets you`** (on `bbb-pro`) or **`## Why I made it`** (on Modrinth, Hangar, Spigot, and `bbb-lite`). Similarly, unheaded `<details>` blocks for `How I test it, and what the tests have found` inside `## How it works` caused historical fuzzer bugs to be indexed under **`## How it works`**.

### 2. Prefix & First-Sentence Window Salience (`PerDocData.bodyWordsToTokensRatioBegin`, `Goldmine`, Hoare Precondition Slot)

Google's `PerDocData.bodyWordsToTokensRatioBegin` and `Goldmine` passage scorer assign disproportionate weight to the opening tokens of a document and the **first sentence** following each heading or bold bullet label. This is the prose equivalent of [`corpus-atlas-ADR-002`](../../corpus-atlas/docs/adr/corpus-atlas-ADR-002-method-scoped-ast-macro-comprehension-and-shorthand-ir.md), where `@requires` Hoare preconditions must be hoisted ahead of a method body to survive prefix truncation.

- **Failure Mode (Defect-First Bullet Leads):** When a test-finding bullet leads with a defect label (`- **Main-thread chunk load on arrival (spark).**`) and Sentence 1 narrates the pre-fix bug while Sentence 3 states `"The fix keeps the ticket..."`, shallow snippet extractors (`extract_doc_snippet`) and passage rankers index the historical defect as how the plugin currently works.
- **Failure Mode (Missing Automation Precondition on `## How it works`):** Listing internal pipeline stages (spatial memory, region-file pre-filtering, shuffled selection, or marginal MSPT regression) under `## How it works` without an opening sentence stating that those stages run automatically in the background behind standard operator settings leads AI summarizers to tell users they must manually tune the engine.

### 3. Full-Weight `<details>` Indexing & Document Salience Skew (`CompositeDoc`, `SalientTermsSet`)

Under mobile-first indexing, Google indexes text inside `<details>` / `[SPOILER]` accordions at **100% document weight** when computing `SalientTermsSet` (what the page is globally about), while de-prioritizing hidden accordion text for primary search snippets.

- **Failure Mode (Asymmetric Summarization & "Comparative Hard Sell" Classification):** In an earlier pass on `LeafRTP`, user-facing `Features` and `How it works` were trimmed to short summaries linking to `docs/site/`, while 15.8 KB of benchmark methodology, three competitor comparison tables (naming rival plugins over 30 times), and fuzzer post-mortems were kept inline on `FRONT_PAGE.md` inside `<details>` blocks. As a result, **~70% of the page's body text** (~18,000 of ~25,500 chars) consisted of stress-test and systems-audit prose, and **only ~21%** covered installing, configuring, and using the plugin. To a document classifier or LLM synthesizer, a page that spends 70% of its tokens dissecting benchmarks and rival plugins reads as a hard-sell pitch for systems engineers rather than an accessible server plugin.

### 4. High-IDF Entity Dominance & Negation Blindness (`SalientTermsSet`, `Webref`, BM25)

Entity and term-salience models weight high-IDF (rare) technical tokens (`space-filling curves`, `Hilbert`, `rejection sampling`, `SCHED_FIFO`, `Jazzer`, `RSA-2048 sequence counters`, `Zstandard decompressor`, `old-generation maxHeapPercent`, `SplitMix64`, `Charikar SimHash`) 5–10x higher than everyday operator words (`easy`, `circle`, `radius`, `safe`, `report`).

- **Failure Mode (Negation Bleed in Setup Passages):** Bag-of-words and shallow passage vectors do not invert term co-occurrence under negation. Writing *"Q: Is it complicated to set up? A: No. You never configure space-filling curves or math settings"* injects `configure` + `space-filling curves` into the setup FAQ vector.
- **Failure Mode (Unqualified Stress-Harness Overrides):** Mentioning benchmark-only overrides (such as `period: 1`) without a same-sentence guard warning operators to keep shipped defaults on live servers causes RAG extractors to surface torture-test settings as production recommendations.

---

## Decision

Every product storefront page (`plugins/<Product>/docs/publishing/FRONT_PAGE.md`, addon `FRONT_PAGE.md` files, and product `README.md` overviews) in the monorepo shall be structured for accurate **shallow-AST reconstruction**, **heading-vector attribution**, and **usability-first document salience**:

### 1. Invariant 3-Zone / Explicit `## H2` Hierarchy (Zero Orphaned `<details>` Scopes)

Every product `FRONT_PAGE.md` follows a fixed 3-Zone progression where every functional topic opens its own explicit `## H2` heading:

| Zone | Target Audience | Required `H1` / `H2` / `H3` Sections | Primary Question Answered |
|------|-----------------|--------------------------------------|---------------------------|
| **Zone 1: Verified Outcomes & Universal Guarantees** (Above the Fold) | 100% of visitors (evaluators, beginners, power users) | `# <Name>: <Plain Category>`<br>`## What it is` (category, commands, out-of-the-box default, hero UI image)<br>`### The short version` (1 headline + 5 outcome bullets; secondary items in `More highlights`) | *What is this, is it easy and safe to use, and why pick it?* |
| **Zone 2: Operational On-Ramp & Zero-Friction Setup** | Action-oriented server admins ready to deploy or switch | `## Common setups` (3 concrete recipes)<br>`## Install` (3 steps: drop jar, run command, optional 1–2 setting tweak or visual wizard)<br>`## Features` (thematic summary linking to `docs/site/features.md`) | *How do I install and configure it in under two minutes?* |
| **Zone 3: Architectural Proof, Story & Scoped Reference** | Skeptics, Folia/network architects, and operators looking up reference tables | `## Performance` (1 summary table + 1 chart; link out for raw tables/rig)<br>`## How it works` (automation precondition lead + 3–4 plain stage bullets)<br>`## Testing and quality gates` (4 plain verification bullets + link to `docs/site/quality-gates.md`)<br>`## Why I made it` (plain problem/evolution narrative)<br>`## What paying gets you` (`kind: promo`, BuiltByBit only)<br>`## Commands, configuration, and FAQ` (explicit `H2` wrapping `<details>` blocks for Commands/Permissions/Config, FAQ, and Support)<br>`## Links` | *Why should I trust the numbers, and where do I look up commands or answers?* |

- **Explicit `H2` Boundary Invariant:** `<details>` and `---` shall never be used as top-level section transitions without an explicit `## H2` heading. Reference `<details>` blocks (`Commands, permissions, configuration files`, `FAQ`, `Support`) must sit under `## Commands, configuration, and FAQ` so they never inherit `## Why I made it` or `## What paying gets you`. Testing and verification summaries must sit under `## Testing and quality gates` so they never inherit `## How it works`.

### 2. Usability & Safety First in `### The short version` (Zone 1 Balance)

1. **Five Balanced Outcome Bullets:** The 5 visible bullets in `### The short version` use the form `- [**Adjective**](link): measured fact.` and must lead with **Ease of use** and **Safety / Guardrails** before raw throughput:
   - **Ease / Setup (`Easy` / `Zero-config` / `Visual`):** Visual web editor (`/rtp editor`), 60-second setup wizard (`/rtp admin setup`), or single-key mode (` radius` / `governor.mode`).
   - **Safety / Guardrails (`Safe` / `Contraption-safe`):** Terrain/hazard verification, claim-plugin integrations, or redstone/bystander immunity.
   - **Speed / Accuracy (`Fast` / `Accurate`):** One canonical headline metric (e.g., `5 ms median from /rtp to arrival on Paper`, `40 TP/s Paper / 180 TP/s Folia`).
   - **Portability / Transparency (`Portable` / `Explainable` / `Self-verifying`):** Zero-friction config migration (`/rtp config import`) or human-readable diagnostic proof.
   - **Platform Coverage (`One jar`):** Multi-platform support (`Paper`, `Spigot`, `Folia`, `Fabric`, `NeoForge`, `Velocity`).
2. **No Duplicate Benchmark Pitch in Zone 1/2:** Do not stack multiple benchmark-throughput bullets in `### The short version`, and do not repeat raw benchmark run counts in `## Common setups`.

### 3. Hoare Precondition Prefixing for Sections and Bullets (Contract-First Lead Sentences)

1. **`## How it works` Automation Precondition:** The first sentence under `## How it works` must explicitly state that all stages run automatically in the background behind standard operator settings (e.g., `CIRCLE` / `SQUARE` and `radius`, or `mode: OBSERVE` / `GOVERN_SAFE`) with no manual tuning.
2. **Fix-First / Invariant-First Bullet Leads:** Wherever test findings, profiler discoveries, or historical post-mortems are listed (on `FRONT_PAGE.md` or in `docs/site/quality-gates.md`), the bold label and **Sentence 1** must state the verified fix or preserved invariant (`- **Player session cleanup (fuzzer, fixed in 3.3.0).** Disconnected player sessions are released immediately...`). The historical defect caught by the tool may only appear in Sentence 2+.
3. **Same-Sentence Benchmark Override Guard:** Any sentence on `FRONT_PAGE.md` that mentions a stress-harness config override (such as `period: 1`) must state in the **same sentence** that it is a benchmark-only setting and that live servers should keep the shipped defaults.

### 4. Document Salience Budget & Plain Operator Vocabulary

1. **>= 70% Usability, Safety & Operator Reference Salience:** Because search engines index `<details>` content at 100% document weight, at least **70% of the body character and token weight** of `FRONT_PAGE.md` shall be dedicated to product identity, ease of setup, safety, features, and operator reference/FAQ—never drowned out by stress-test methodology or competitor failure tables.
2. **Single-Table Cap in `## Performance`:** `## Performance` on `FRONT_PAGE.md` shall contain at most **1 summary comparison table** and **1 visual chart**, with links to `helpers/StressTest*/RESULTS.md` and `PRE_WRITEUP.md` for secondary latency tables, per-plugin control adjustments, hardware/kernel scheduling notes (`SCHED_FIFO`), and extended caveats.
3. **Concise Quality Summary in `## Testing and quality gates`:** `FRONT_PAGE.md` summarizes verification in 4 plain-language bullets (built-in in-game `/rtp test` or `/sleuth test`, automated safety rule suite, stress & memory testing, coverage & API compatibility) and links to `docs/site/quality-gates.md` for the full tool matrix and fuzzer post-mortems.
4. **High-IDF Jargon Quarantine:** High-IDF algorithm, cryptographic, compression, kernel, and JVM heap-pool terms (`space-filling curve`, `Hilbert`, `rejection sampling`, `CIRCLE_NORMAL`, `RSA-2048`, `Zstandard`, `old-generation`, `maxHeapPercent`, `SCHED_FIFO`, `Jazzer`, `Entity.isInWall`) shall not appear in Zone 1, Zone 2, `## Testing and quality gates`, `## Why I made it`, or `## Commands, configuration, and FAQ` on `FRONT_PAGE.md`. Link to `docs/site/how-it-works.md`, `docs/site/why.md`, `docs/site/quality-gates.md`, or subproject ADRs for deep derivations.
5. **Positive Setup Surface (Zero Negation Bleed):** Setup instructions (`## Install`) and setup FAQ answers (`Q: Is it complicated to set up?`) shall state the positive 1–2 setting surface (`CIRCLE`, `radius: 4096b`, `centerRadius: 1024b`, `/rtp editor`, `/rtp admin setup`) and shall never negate internal math or curve jargon.

### 5. Mechanical Enforcement in `build_front_pages.py` and `corpus-atlas`

1. **`scripts/release/build_front_pages.py` AST Gate (`check_shallow_ast_sections`):**
   - Parses every rendered storefront target into shallow AST sections (`H1` / `H2` / `H3` / `<summary>`) and fails `--check` (and CI release workflows) if:
     - `### The short version` lacks ease-of-use (`Easy`) or safety (`Safe`) bullets.
     - `## Performance` exceeds 1 Markdown table or omits a same-sentence benchmark-only guard on `period: 1`.
     - `## How it works` lacks an opening automation precondition sentence.
     - `Commands, permissions, configuration files`, `FAQ`, or `Support` `<details>` blocks inherit `## Why I made it`, `## What paying gets you`, or `## How it works`.
     - Any finding bullet leads Sentence 1 with a historical defect before stating the fix/invariant.
     - Any setup FAQ answer pairs setup/configuration with negated curve/spiral jargon.
     - Any banned high-IDF systems/fuzzer/crypto/GC-pool jargon appears on `FRONT_PAGE.md`.
2. **`corpus-atlas/build_doc_index.py` Shallow-AST Parity:**
   - `split_markdown_sections()` splits `<summary>` blocks into child passages tagged with their parent `H2`/`H3` heading vector (`Parent Heading > Summary Title`), and `extract_doc_snippet()` strips structural HTML tags (`<div>`, `<details>`, `<summary>`) and `---` separators before extracting lead sentences.

---

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep 15+ KB of secondary benchmark tables, competitor config audits, and fuzzer post-mortems inline inside `<details>` blocks on `FRONT_PAGE.md` | Google indexes `<details>` content at 100% weight for document-level `SalientTermsSet` classification; keeping 70% of the page's tokens on benchmarks and fuzzer internals causes search engines and AI Overviews to classify the product as a hyper-technical hard sell. |
| Delete technical proofs, fuzzer post-mortems, and competitor controls entirely | Violates `Rule D-006` and Zone 3 transparency. Moving secondary tables and post-mortems into `docs/site/quality-gates.md` and `helpers/StressTestRTP/RESULTS.md` preserves 100% of the engineering evidence one click away without distorting the front-page index profile. |
| Use negated disclaimers in setup FAQs (*"You never configure space-filling curves"*) to reassure beginners | Bag-of-words and shallow passage embeddings do not invert co-occurrence edges under negation; mentioning `space-filling curves` in a setup answer associates setup with complex math. |
| Rely on manual copy review without mechanical AST checks in `build_front_pages.py` | Heading scope collapse (`---` vs `## H2`), defect-first bullet leads, and high-IDF jargon drift back in over iterative edits unless enforced by `build_front_pages.py --check` and unit tests. |

---

## Consequences

- **Positive:**
  - External search engines, AI Overviews, and `corpus-atlas` reconstruct every section of `FRONT_PAGE.md` under its true topic heading (`## Testing and quality gates`, `## Commands, configuration, and FAQ`) with zero orphaned `<details>` blocks.
  - Document-level term salience shifts from ~70% stress-test/fuzzer internals to >70% usability, setup, safety, and operator reference while keeping 100% of the benchmark and fuzzer evidence linked in `docs/site/` and `helpers/StressTestRTP/`.
  - Every new product in the monorepo (`LeafTreeGen`, `LeafSleep`, `LeafConfig`, `LeafSleuth`) inherits a repeatable, mechanically linted blueprint for storefront pages that highlights ease of use and safety first.
- **Negative / Trade-offs:**
  - Deep benchmark methodology tables and fuzzer bug post-mortems live in `helpers/StressTestRTP/RESULTS.md` and `docs/site/quality-gates.md` rather than inline on `FRONT_PAGE.md`, requiring one click for readers who want the full raw tables.
  - Authors adding new sections or FAQs to `FRONT_PAGE.md` must satisfy `check_shallow_ast_sections` in `scripts/release/build_front_pages.py`.

---

## References

- `plugins/LeafRTP/docs/publishing/FRONT_PAGE.md`
- `plugins/LeafRTP/docs/site/quality-gates.md`, `plugins/LeafRTP/docs/site/how-it-works.md`, `plugins/LeafRTP/docs/site/features.md`, `plugins/LeafRTP/docs/site/why.md`
- `plugins/LeafSleuth/docs/publishing/FRONT_PAGE.md`, `plugins/LeafRTP/addons/LeafRTPGuiAddon/FRONT_PAGE.md`
- `scripts/release/build_front_pages.py` (`parse_shallow_ast`, `check_shallow_ast_sections`, `check_zone1_no_jargon`, `check_zone2_onramp`)
- `scripts/tests/test_build_front_pages.py`
- `corpus-atlas/build_doc_index.py` (`split_markdown_sections`, `extract_doc_snippet`)
- [`docs/dev/RULES.md`](../dev/RULES.md) (`Rule D-006`, `Rule D-010`)
- [corpus-atlas-ADR-001](../../corpus-atlas/docs/adr/corpus-atlas-ADR-001-scope-preserving-ast-synthesis-and-zero-open-cards.md), [corpus-atlas-ADR-002](../../corpus-atlas/docs/adr/corpus-atlas-ADR-002-method-scoped-ast-macro-comprehension-and-shorthand-ir.md)
- Google Heading-Vector & Passage Indexing References: `US9959315B1` (*Generating a heading vector for a document*), `US20160078102A1` (*Document scoring based on document content update*), and Google Content Warehouse API (`CompositeDoc`, `PerDocData.bodyWordsToTokensRatioBegin`, `Goldmine`, `SalientTermsSet`).
