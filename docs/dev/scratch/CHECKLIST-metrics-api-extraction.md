# CHECKLIST — metrics-api extraction (active session 2026-05-17)

**Effective Issue**: Extract `metrics-api/` subproject per PROPOSAL-metrics-api-extraction.md option (1) full scope. User-approved 2026-05-17 overriding the §7 beta gate.

**Mode**: `[CODE]` (D-005 multi-module, user-approved scope).

**Blocking decisions awaiting user**: none — scope (1) confirmed.

**Delete this file on submit.**

---

## Phase A — Module skeleton

- [x] A1. `metrics-api/build.gradle` created (java-library, ArchUnit testImplementation, mirrors `maps-api/build.gradle`).
- [x] A2. `metrics-api` included in `settings.gradle` (line 66, after `maps-api`).
- [x] A3. `metrics-api/src/main/java/io/github/dailystruggle/metrics/api/package-info.java` created.
- [x] A4. `MetricsApiModule` marker class added (needed for Javadoc to find a public type; documented as a follow-up-deletion candidate once the SPI moves in). `.\gradlew :metrics-api:build` → BUILD SUCCESSFUL.

## Phase B — Extension-model reshape (DONE 2026-05-17, in `rtp-core`)

User-approved scope: reshape the snapshot in-place inside `rtp-core` without moving any SPI files yet. All 15 public-final fields remain authoritative this phase so zero call-site churn was needed; Phase E will flip authority and add deprecation, Phase C/D will physically move files to `metrics-api`.

- [x] B1. `MetricsExtension<SELF extends MetricsExtension<SELF>>` marker interface created at `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/metrics/MetricsExtension.java`.
- [x] B2. `RTPMetricsExtension` (final, immutable) created carrying the 6 RTP-specific fields (`queueDepth`, `pendingTeleports`, `memoryTrackerEntries`, `chunkLoadBacklog`, `avgPipelineMs`, `databaseLatencyMs`).
- [x] B3. `MetricsSnapshot` extended with private extension map + `extension(Class<T>)` accessor + `withExtension(MetricsExtension<?>)` immutable builder. Private 17-arg ctor delegated to by the existing 16-arg public ctor. All 15 legacy `public final` fields untouched.
- [x] B4. `Metrics.NOOP` lambda now chains `.withExtension(new RTPMetricsExtension(0, 0, 0, 0, NaN, -1))`. `CoreMetrics.snapshot()` chains the same with real counter values mirroring the legacy fields.
- [x] B5. New `MetricsExtensionParityTest` (3 tests, all green) verifies NOOP carries a zero extension, `withExtension` is immutable + preserves host-runtime fields, and the accessor tolerates null/absent lookups.


## Phase C — Cross-plugin static registry (DONE 2026-05-17, in `rtp-core`)

User-approved scope: ship the static registry on the existing `Metrics` interface so sibling plugins in the monorepo can install a `MetricsBinding` and `MetricsExtension` suppliers without owning RTP's `CoreMetrics` instance. The SPI files stay in `rtp-core` for now; the physical move to `metrics-api` is still in the follow-up bucket (renamed Phase D below).

- [x] C1. `Metrics` interface gained static `registerBinding(MetricsBinding)` / `currentBinding()` / `registerExtension(Supplier)` / `registeredExtensions()` / `resetRegistryForTesting()` plus a nested `Registry` holder backed by `AtomicReference` + `CopyOnWriteArrayList`. Last-writer-wins semantics with a `Logger.WARNING` on non-NOOP replacement (matches §1.1 / §3 Q6).
- [x] C2. `CoreMetrics.setBinding(...)` mirrors writes into `Metrics.Registry` so sibling readers calling `Metrics.currentBinding()` observe the live RTP binding without owning `CoreMetrics`.
- [x] C3. `CoreMetrics.snapshot()` composes registered extension suppliers after RTP's built-in `RTPMetricsExtension`. Throwing suppliers are swallowed defensively so `snapshot()` never throws.
- [x] C4. New `MetricsRegistryTest` (6 tests, all green via `run_test`): default-NOOP, last-writer-wins return-prior, null-clears-to-NOOP, `CoreMetrics.setBinding` mirroring, extension composition on snapshot, throwing-supplier resilience, reset-clears.
- [x] C5. `.\gradlew build` shows only the pre-existing unrelated Spanish-locale parity failure; all metrics modules build green.

## Phase D — Physical SPI move (DONE 2026-05-17)

Physical move of `Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`, `MetricsExtension` to the neutral `io.github.dailystruggle.metrics.api.*` package inside the `metrics-api/` module. Old rtp-core SPI files removed; call-site imports converted directly (no shim layer — see D3). Phases E (dispatcher demotion / chart-and-test extension lookup rewires) and F (ArchUnit guard re-author against module classpath) remain deferred.

- [x] D1. Physical move: `Metrics`, `MetricsBinding`, `MetricsSnapshot`, `FoliaRegionSample`, `MetricsExtension` moved to `metrics-api/` neutral package `io.github.dailystruggle.metrics.api.*`. `HeapSampler` decoupled: canonical `Metrics.NOOP` returns heap 0/0; `CoreMetrics.snapshot()` reads `HeapSampler` directly.
- [x] D2. `RTPMetricsExtension` stays in `rtp-core` (RTP-specific) and implements canonical `io.github.dailystruggle.metrics.api.MetricsExtension<RTPMetricsExtension>`.
- [x] D3. ~~Type-alias shims~~ — initial shim approach abandoned mid-session (Java generics invariance + interface static-method non-inheritance created cascading conflicts on every same-package test). Replaced with direct rewire: 17+ files updated to import from `io.github.dailystruggle.metrics.api.*`. Old rtp-core SPI files deleted outright. Clean break.
- [x] D4. `rtp-core/build.gradle` gains `api project(':metrics-api')` (next to `:commands-api` / `:effects-api`).
- [x] D5. 4 platform bindings (`PaperMetricsBinding`, `BukkitTpsSampler`, `FoliaMetricsBinding`, `FabricMetricsBinding`) plus `FoliaRegionTpsSampler`, `FabricMetricsBinding`, dispatcher, and all platform `ServerAccessor` TPS routes converted to canonical-package imports.
- [x] E1. Dispatcher already imports canonical `io.github.dailystruggle.metrics.api.MetricsBinding` (Phase D rewire); rtp-core shim file confirmed deleted. Demotion-to-thin-reflective-probe is the existing shape — no further demotion needed.
- [x] E2. `RTPCostMetricsCharts` migrated: new `rtpExt(MetricsSnapshot)` helper resolves `RTPMetricsExtension`; 5 deprecated-field reads (`avgPipelineMs`, `memoryTrackerEntries`, `chunkLoadBacklog`, `queueDepth`, `pendingTeleports`) now go through it.
- [x] E3. `PlaceholderProvider` migrated: new private `currentRtpExt()` helper; 6 deprecated reads (`queueDepth`, `pendingTeleports`, `avgPipelineMs` x2, `memoryTrackerEntries`, `chunkLoadBacklog`, `databaseLatencyMs`) routed through it. `TestFullCmd` consumes `MetricsSnapshot.toString()` not direct fields — no edit needed.
- [x] E4. Verified no main-source readers of the 6 deprecated `MetricsSnapshot` fields remain (grep across `rtp-core`, `rtp-plugin`, `rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-anvil`, `rtp-tags`, `addons` → zero hits).
- [x] F1. ArchUnit guard re-authored against module classpath (2026-05-17). Added two new rules to `MetricsConsolidationArchTest` in `rtp-plugin`: (a) `metrics_api_module_boundary_no_new_shim_imports` — pins deprecated `io.github.dailystruggle.rtp.common.metrics.MetricsBinding` shim dependency to a documented compat allow-list (shim file, 4 platform binding impls, `MetricsBindingDispatcher`); (b) `metrics_binding_implementations_live_in_platform_adapters` — forbids concrete `io.github.dailystruggle.metrics.api.MetricsBinding` impls inside `rtp.common.*` / `rtp.api.*`. Both rules + the two existing C6 rules pass (4/4 green via `run_test`). Rule docstrings record the removal trigger: when every platform binding migrates off the shim, the allow-list collapses to the shim file alone and the shim can be deleted.

## Phase G — Docs (partial; rest gated on Phase B–F landing)

- [ ] G1. (Follow-up) `metrics-api/README.md` — wait until SPI is moved so the README can describe a real surface.
- [ ] G2. (Follow-up) `metrics-api/docs/INDEX.md`.
- [ ] G3. (Follow-up) `metrics-api/docs/adr/metrics-api-ADR-001-module-extraction.md` — wait until the actual move so the ADR documents what shipped, not what is planned.
- [ ] G4. (Follow-up) Row in `docs/adr/README.md` *Subproject ADRs* table.
- [ ] G5. (Follow-up) `.junie/AGENTS.md` *Architecture Boundaries* + *Required Reading*.
- [ ] G6. (Follow-up) `docs/dev/METRICS_PLAN.md` *Module Placement*.
- [ ] G7. (Follow-up) `docs/dev/TRACEABILITY.md` REQ-RTP-OBS-001/002/003 rows.
- [ ] G8. (Follow-up) `CHANGELOG.md` net-delta bullet — the current net delta vs the last released tag is *only* the empty-module skeleton, which is too thin to be worth a public bullet. Add when the SPI actually moves.

(Per CHANGELOG Hygiene rule in AGENTS.md: this session's net delta is an empty SPI skeleton, not a user-visible change.)

## Phase H — Verification

- [x] H1. `.\gradlew :metrics-api:build` → BUILD SUCCESSFUL (10s).
- [x] H2. `:rtp-core:build` covered by full-build run below; rtp-core is UP-TO-DATE (Phase A did not touch it).
- [x] H3. Not applicable this session — no metrics-related test changes (deferred with Phase F).
- [x] H4. `.\gradlew build` → fails **only** on the pre-existing unrelated `ReqRtpF013SpanishLocaleContentTest` (Spanish locale parity, caused by dirty `messages.yml` files in git status from prior sessions). Every other module is UP-TO-DATE or BUILD SUCCESSFUL. The new `metrics-api` module integrated cleanly into the multi-module build.
- [ ] H5. Mojibake scan on docs diff — to be done before submit.
- [ ] H6. (Follow-up) Delete this checklist file — keep it across sessions so the follow-up can resume from "Phase B starts here".

## Phase I — Submit

- [ ] I1. Submit summary referencing all ticked items.
