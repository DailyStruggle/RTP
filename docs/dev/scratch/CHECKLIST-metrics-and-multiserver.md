# Implementation Checklist — Metrics + Multi-Server (state-preservation working note)

> **Effective Issue (1-line)**: implement RTP runtime metrics SPI and proxy/multi-server support, in that order, against the design captured in `docs/dev/METRICS_PLAN.md` and `docs/dev/MULTI_SERVER_PLAN.md`.
>
> **Mode when executing**: `[CODE]` for build steps, `[ADVANCED_CHAT]` or `[CODE]` for design/doc steps.
>
> **Blocking decisions awaiting user approval (Rule D-005)**:
> - ADR-025 (multi-server proxy support) must be drafted and approved **before** any code under M-NET-Phase-0 ships.
> - Any `rtp-api` / `rtp-core` SPI shape change inside the metrics work needs explicit D-005 approval at its M-METRICS-Phase-M0 review item.
>
> **Scope rules**: per `AGENTS.md > Stay-On-Task Policy`, incidental bugs go to `POTENTIAL_BUGS.md`, not inline fixes. Per `AGENTS.md > SESSION ARTIFACTS & CLEANUP`, **delete this file once the entire checklist is submitted** (or after the proxy beta release, whichever comes first).
>
> **How to resume after disconnect**: read this file top-to-bottom, re-verify the last `[x]` item still holds (file exists, build green, test still passes), then continue from the first `[ ]`. Tick boxes only after the verification command in the row succeeds.

---

## Conventions

- Each row carries a verification artifact (test name, file path, command, or external URL) so a fresh agent can confirm it without re-deriving context.
- Box state legend: `[ ]` not started, `[~]` in progress, `[x]` verified done, `[!]` blocked or paused (always paired with a Notes line).
- Numbered hierarchy is stable — never renumber. Insert sub-items as `1.1.1`, etc.
- Sequencing rule: items in different sections may run in parallel where dependencies allow; within a section, run top-to-bottom unless a dependency is explicit.

---

## Section A — Metrics Plan, Phase M0 (SPI shape, docs only)

Source-of-truth: [`docs/dev/METRICS_PLAN.md`](../METRICS_PLAN.md).

- [ ] **A1.** Confirm the M0 metric catalogue is a *strict superset* of `rtp test full`'s current console output. Verification: open `rtp test full` invocation site (`grep` for `rtp test full` in `rtp-core/src/main/java/...`), diff fields against `MetricsSnapshot` proposal in `METRICS_PLAN.md > Metric Catalogue (v1)`. Record any missing field by amending the plan **before** writing code.
- [ ] **A2.** Decide `avgPipelineMs` window length and reset semantics (open item in `METRICS_PLAN.md > Open Items / Follow-Ups`). Verification: amend the plan with the resolved value; reference this checklist row in the plan diff message.
- [ ] **A3.** Decide `databaseLatencyMs` cadence (every-write vs. dedicated probe). Verification: same as A2.
- [ ] **A4.** Decide Folia per-region detail opt-in default (`metrics.folia.includeRegions`). Verification: same as A2.
- [ ] **A5.** D-005 review of the proposed `MetricsSnapshot` shape (no platform imports; record-style; addons can depend on it). Verification: brief written approval from the user; record decision date in the plan's *Resolved* section.

> **Gate**: Section A complete before any Section B code lands.

---

## Section B — Metrics Plan, Phase M1 (core + Paper + Spigot fallback)

- [ ] **B1.** Create `rtp-core/src/main/java/.../metrics/` package skeleton: `Metrics`, `MetricsSnapshot` (immutable record), `PipelineHistogram`, `HeapSampler`. Verification: `:rtp-core:compileJava` green; ArchUnit guard run shows no platform imports inside the new package.
- [ ] **B2.** Implement `PipelineHistogram` (256-sample ring buffer, never resets — straw-man, confirmed in A2). Verification: new unit test under `rtp-core/src/test/.../metrics/PipelineHistogramTest.java` covers fill/wrap/percentile semantics.
- [ ] **B3.** Wire `PipelineHistogram` into `TeleportPipelineTask` completion path. **Additive only** — no existing assertion changes. Verification: `:rtp-core:test --tests "*TeleportPipelineTask*"` green; histogram count increments observable via a new test that runs one synthetic pipeline.
- [ ] **B4.** Implement `HeapSampler` via `ManagementFactory.getMemoryMXBean()`. Verification: returns sane values in unit test; no platform imports.
- [ ] **B5.** Implement `Metrics.snapshot()` aggregating B1–B4 plus delegated platform binding via `RTP.serverAccessor.getMetricsBinding()`. Verification: snapshot is immutable (record); calling twice with no state change returns equal snapshots.
- [ ] **B6.** Add `getMetricsBinding()` to `AbstractServerAccessor` with default `null`-returning impl so adapters opt in incrementally. Verification: existing adapters (`SpigotServerAccessor`, `PaperServerAccessor`, `FoliaServerAccessor`, `FabricServerAccessor`) still compile.
- [ ] **B7.** Implement `PaperMetricsBinding` in `rtp-paper-common` wrapping `Bukkit.getTPS()` / `Bukkit.getAverageTickTime()` (Paper API confirmed — these are Paper-only methods). Verification: `:rtp-paper:rtp-paper-common:compileJava` green; smoke unit test against a mocked `Bukkit`.
- [ ] **B8.** Implement `SpigotTpsSampler` in `rtp-spigot-common`: 1-tick repeating task on `RTP.scheduler` recording `System.nanoTime()` per fire; three EMAs (1m / 5m / 15m); convert to TPS as `1e9 / movingAverageNanos`, clamp `[0, 20]`. Verification: unit test feeds a synthetic clock, asserts converged TPS within tolerance after each window length.
- [ ] **B9.** Runtime-detect Paper at adapter init (reflective probe for `Bukkit.getTPS`); skip Spigot sampler when Paper is present. Verification: log line at `INFO` confirms which path was chosen on adapter startup; smoke test on a Paper devstack.
- [ ] **B10.** Wire `rtp test full` to print `MetricsSnapshot.toString()` (replace the ad-hoc dump). **Strict superset** — must not drop any line that operators currently rely on. Verification: diff the output before/after on a local devstack; run `:rtp-core:test --tests "*RtpTestFull*"` if such a test exists, otherwise add one.
- [ ] **B11.** Update `InfoCmd` to render the *Health — server* and *Health — pipeline* groups from `Metrics.snapshot()` per `METRICS_PLAN.md > /rtp info Surface`. Verification: extend `InfoCmdTest` with the *one snapshot per invocation* assertion (no N×region calls); existing tests remain green.
- [ ] **B12.** Add `rtp.info` colour-band thresholds to `messages.yml`. Verification: defaults render green/yellow/red per the plan; missing keys fall back to a hard-coded default without throwing.
- [ ] **B13.** bStats — *Configuration adoption* chart group only (`platform`, `assembly_variant`, `database_backend`, `region_shapes_in_use`, `safety_features_enabled`, `addons_loaded`, `lite_features_dropped`). Reads `Metrics.snapshot()` cached values; no parallel sampling. Verification: chart IDs centralised in a new `BStatsChartIds` constants class; all chart lambdas covered by a unit test asserting they don't include `serverId`-equivalent strings.
- [ ] **B14.** TRACEABILITY.md — add rows for any new REQ-traceable test introduced in B2/B3/B11. Verification: `check_traceability.sh` clean.

> **Gate**: Section B verified green on a Paper *and* Spigot 1.20.1 devstack before Section C begins.

---

## Section C — Metrics Plan, Phase M2 (Folia + Fabric)

- [ ] **C1.** Implement `FoliaMetricsBinding` in `rtp-folia-common` with the `max` (mspt/tickBudget) / `mean` (tps*) defaults from `METRICS_PLAN.md > Folia Aggregation`. Verification: `:rtp-folia:*:test` green; per-region detail accessible via `MetricsSnapshot.foliaRegions()` and gated by `metrics.folia.includeRegions`.
- [ ] **C2.** Implement `FabricMetricsBinding` in `rtp-fabric-common` using the server tick callback chain wired in `MULTI_PLATFORM_PLAN.md > Step E2`. Verification: `:rtp-fabric:rtp-fabric-common:test` green; smoke test on Fabric dev server confirms non-zero TPS in the snapshot.
- [ ] **C3.** Update `InfoCmd` to render the *Health — cache* per-region table behind `/rtp info verbose`. Verification: command no longer requires platform-specific code paths beyond what `MetricsBinding` exposes.
- [ ] **C4.** bStats — *Runtime health* chart group (`region_count`, `cache_pool_health`, `tps_buckets`, `mspt_buckets`, `pipeline_latency_buckets`, `memory_tracker_pressure`, `chunk_load_backlog_pressure`, `s005_violations_recent`). Verification: bucketisation happens once per snapshot, not per chart fetch.
- [ ] **C5.** TRACEABILITY.md row updates as needed. Verification: `check_traceability.sh` clean.

> **Gate**: dual-runtime smoke test — one JAR loads on Paper and Fabric, `/rtp info verbose` prints sane health on both. Coordinates with `MULTI_PLATFORM_PLAN.md > Phase 2 acceptance gate`.

---

## Section D — Multi-Server Plan, Phase 0 (docs-only, D-005 gate)

Source-of-truth: [`docs/dev/MULTI_SERVER_PLAN.md`](../MULTI_SERVER_PLAN.md).

- [ ] **D1.** Draft `docs/adr/ADR-025-multi-server-proxy-support.md` using `docs/adr/ADR-TEMPLATE.md`. Must reference: D1–D4 from the plan, durable-transport requirement (D2), reuse of `AbstractSQLDatabaseAccessor` (D3), env-var HMAC (D4 v1). Must **not** supersede ADR-022. Verification: ADR file present; INDEX.md and MAP.md updated to list it; user approval recorded in the ADR's *Status* field.
- [ ] **D2.** Author REQ-RTP-NET-001…005 in final `shall` phrasing (style per `RULES.md`). Insert into `docs/dev/REQUIREMENTS.md` next to existing REQ-RTP-NET section if any, or at end. Verification: `RULES.md` style check passes; no temporal phrasing; no implementation actions.
- [ ] **D3.** Add glossary entries to `docs/dev/GLOSSARY.md`: *backend*, *proxy*, *reservation token*, *transport*, *network snapshot*, *backend selector*, *network wait queue*. Verification: each term has a one-paragraph definition; no overlap with existing entries.
- [ ] **D4.** Update `MULTI_SERVER_PLAN.md > Phase 0` checklist to tick the items above. Verification: each row references the artifact it produced (ADR-025 path, REQ IDs, glossary entries).
- [ ] **D5.** Curve plot generation script under `scripts/` (matplotlib) producing PNGs/SVGs for `linear` / `exponential` / `logarithmic` / `sigmoid` / `step` / `power` at the documented defaults. Verification: script runs reproducibly on a fresh checkout (Python venv + `requirements.txt`); outputs land in `docs/admin/proxies/loadbalancing/` (subdirectory under the existing stub).
- [ ] **D6.** Author `docs/admin/proxies/LOAD_BALANCING.md` embedding the plots from D5 + a plain-language explanation of each curve. Verification: rendered correctly on GitHub; cross-linked from `docs/admin/proxies/INDEX.md`.

> **Gate**: D-005 user approval on the full Phase-0 deliverable bundle (ADR + REQs + GLOSSARY + admin doc) before Section E begins.

---

## Section E — Multi-Server Plan, Phase 1 (core SPI; no proxy adapter yet)

- [ ] **E1.** Define `RtpTriggerSource` and `RtpDispatcher` in `rtp-core` (no platform imports). Verification: `:rtp-core:compileJava` green; ArchUnit guard clean.
- [ ] **E2.** Define `BackendSelector` interface and ship a single concrete strategy: configurable weighted average per `MULTI_SERVER_PLAN.md > Configurable Weighted Average`. Includes curve catalogue (`linear` / `exponential` / `logarithmic` / `sigmoid` / `step` / `power`), per-metric `normalize` + `weight`, per-backend weight multiplier, capped retry / cooldown / score-sticking. Verification: pure function of `NetworkSnapshot`; no I/O during `choose()`; unit tests for each curve at `k`/`p`/`threshold` defaults plus boundary inputs.
- [ ] **E3.** Define `NetworkTransport` interface (request/response, broadcast, subscribe). Verification: spec doc inside the package as KDoc; reference `MULTI_SERVER_PLAN.md > Coordinate Resolution Timing`.
- [ ] **E4.** Implement `InMemoryNetworkStateBinding` (single-JVM tests, no-op default). Verification: round-trip request/response in a unit test with two simulated backends.
- [ ] **E5.** Add the network-state member adjacent to `AbstractSQLDatabaseAccessor` per D3. Verification: `network.enabled: false` keeps the member null; member surface (`writeBackendState`, `readNetworkSnapshot`, reservation-token CRUD, network wait-queue CRUD) covered by an interface contract test.
- [ ] **E6.** **No-op test** (REQ-RTP-NET-005 gate): `network.enabled: false` produces byte-identical behaviour to single-server. Verification: dedicated test class; runs in default `:rtp-core:test`.
- [ ] **E7.** `commands-api` proxy-side surface — early TODO from the plan: `ProxySender`, `NetworkAwareCommand` mixin, tab-completion routing. Verification: interfaces defined and contract-tested, with no proxy-platform imports yet.
- [ ] **E8.** `recentPicks` decay implementation (halflife 10s, λ ≈ 0.0693 s⁻¹) integrated as a metric row in the selector. Verification: unit test feeds a synthetic clock, asserts the score returns to background within ~30s after a single pick.
- [ ] **E9.** Update `MULTI_SERVER_PLAN.md > Phase 1` checklist with completed rows. Verification: each row references its test name.

> **Gate**: Phase 1 acceptance — single-JVM tests with two simulated backends green; no-op test green; ArchUnit clean.

---

## Section F — Multi-Server Plan, Phase 2 (Velocity adapter + Redis transport)

- [ ] **F1.** Bootstrap `rtp-proxy/rtp-proxy-common/` and `rtp-proxy-velocity/` modules per `MULTI_SERVER_PLAN.md > Module shape`. Single JAR multi-loader bootstrap extends to the proxy axis (per *Intended Usage & Deployment Model* in the plan). Verification: `:rtp-proxy:rtp-proxy-velocity:build` green; manifest/`velocity-plugin.json` declares the entry point.
- [ ] **F2.** Implement `RedisNetworkStateBinding` (Lettuce, async). Verification: integration test against an embedded Redis (e.g. `embedded-redis`); no blocking `.get()` calls.
- [ ] **F3.** Implement reservation token state machine (`PENDING → CLAIMED → CONSUMED → EXPIRED`) on the network-state member. Idempotent consumption; reaper on `RTP.scheduler.runTaskTimerAsynchronously`. Verification: a dedicated regression suite analogous to `ReqRtpS004NullChunkAttributionTest` covering replay, TTL expiry, orphaned `MemoryTracker` entries.
- [ ] **F4.** Velocity `ServerPreConnectEvent` hook (PostOrder.LATE) that rewrites the target backend per the selector's choice. Verification: integration test against a Velocity dev instance; transfer happens once, no spawn-flash.
- [ ] **F5.** `CommandTriggerSource` wired through the dispatcher; `/rtp` from a backend or the proxy itself flows the same path. Verification: integration test exercises both origins.
- [ ] **F6.** HMAC envelope on every transport packet using `RTP_NET_SECRET` env var (D4 v1). Verification: bad-secret packets are rejected and logged under S-004 attribution; no replay within TTL window.
- [ ] **F7.** Update `InfoCmd` *Health — network* block per `METRICS_PLAN.md > /rtp info Surface > Health — network`. Verification: block appears only when `network.enabled: true`; integration test confirms stale-backend listing.
- [ ] **F8.** bStats *Feature shape* charts (`selection_strategy_shape`, `region_topology`, `trigger_sources`) and the proxy-side chart set. Verification: proxy charts deliberately omit backend-pool shape (anti-fingerprinting).
- [ ] **F9.** **Acceptance**: cross-server `/rtp` round-trip on a Velocity + 2× Paper devstack with Redis. Manual smoke + recorded test transcript in `LESSONS_LEARNED.md`.
- [ ] **F10.** Postgres-vs-Redis comparative benchmark (deferred from earlier; runs *after* both transports are stable). Verification: benchmark script in `scripts/`, results recorded in `LESSONS_LEARNED.md`.

> **Gate**: Phase 2 acceptance — F9 transcript + F3 regression suite green.

---

## Section G — Multi-Server Plan, Phase 3 (Postgres transport + Join trigger + BungeeCord)

- [ ] **G1.** `PostgresNetworkStateBinding` using `LISTEN/NOTIFY` and `SELECT ... FOR UPDATE SKIP LOCKED`. Verification: integration test against an embedded Postgres; race-free reservation claim under contention.
- [ ] **G2.** `JoinTriggerSource` wired *proxy-side* per D1. Verification: per-region/world map sourced from proxy config; no per-backend mirror.
- [ ] **G3.** `rtp-proxy-bungee` adapter (BungeeCord + Waterfall). Verification: integration test on a Bungee devstack.
- [ ] **G4.** **Acceptance**: same scenarios as F9, but on Bungee + Postgres.

---

## Section H — Multi-Server Plan, Phase 4 (Generic SQL + hardening + release)

- [ ] **H1.** `GenericSqlNetworkStateBinding` (MySQL/MariaDB polling). Verification: integration test confirms polling fallback latency stays within the load-balancer's `attemptTimeoutMs`.
- [ ] **H2.** Security audit — HMAC, replay protection, schema-version negotiation, kill switch. Verification: dedicated test suite + security review notes in `LESSONS_LEARNED.md`.
- [ ] **H3.** `rtp test full network` aggregator across the network. Verification: integration test exercises three backends with one stale.
- [ ] **H4.** `docs/admin/proxies/` admin docs filled out per `docs/admin/proxies/INDEX.md` planned-pages list. Verification: each row in the planned-pages table now resolves to an authored doc.
- [ ] **H5.** `CHANGELOG.md` entries per phase under *Unreleased*. Verification: PR review includes the changelog diff.
- [ ] **H6.** First public proxy-mode beta release (gated on full audit green). Verification: tagged release; release notes link to ADR-025 and this checklist file's deletion.
- [ ] **H7.** **Delete this checklist file.** Verification: `Test-Path docs\dev\scratch\CHECKLIST-metrics-and-multiserver.md` returns false; no references remain in any committed doc.

---

## Section I — Cross-cutting hygiene (run continuously)

- [ ] **I1.** Each new REQ-traceable test added during B–H is recorded in `TRACEABILITY.md` in the same commit. Verification: `check_traceability.sh` clean before each PR merge.
- [ ] **I2.** Each session that touches an unfamiliar symbol re-reads the *Domain Analogies & Aliases* table in `AGENTS.md` so cache-tier and queue language stays consistent. (No ticking — this is a habit, not a deliverable.)
- [ ] **I3.** Each incidental potential bug discovered during execution lands in `POTENTIAL_BUGS.md` per `AGENTS.md > Stay-On-Task Policy`. Verification: review at end of each phase.
- [ ] **I4.** Every multi-class or cross-module change pauses for D-005 review before code lands.
- [ ] **I5.** No `printStackTrace()` in any new code; all error paths route through `RTP.log(Level.WARNING, msg, e)`.
- [ ] **I6.** No `org.bukkit.*` imports in `rtp-core` or `rtp-api` (existing rule). Verification: ArchUnit guard.
- [ ] **I7.** `network.enabled: false` no-op contract holds at every milestone (REQ-RTP-NET-005). Verification: re-run E6 test before each PR merge in Sections F–H.

---

*Self-update note*: when a row's verification artifact moves (test renamed, file relocated), update the row in the same commit. Do not let the checklist drift from reality. When all sections are submitted and H7 is verified, this file is deleted, not archived — the canonical record lives in the commit history of `MULTI_SERVER_PLAN.md`, `METRICS_PLAN.md`, ADR-025, and `LESSONS_LEARNED.md`.
