# CHECKLIST — Fabric obf/unobf split (effects-api-ADR-006 + rtp-fabric-ADR-009)

**Effective Issue:** Implement the obf/unobf split across `effects-api` and `rtp-fabric-common` so deobf MC 26.x runtimes link cleanly while 1.20/1.21.x intermediary runtimes remain unaffected.

**Mode:** [CODE]

**Source ADRs:**
- `effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md` (Accepted 2026-05-09)
- `rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md` (Accepted 2026-05-09)

**Blocking decisions (Rule D-005):**
- [x] Flip `effects-api-ADR-006` Status from `Proposed` → `Accepted (2026-05-09)`.
- [x] Flip `rtp-fabric-ADR-009` Status from `Proposed` → `Accepted (2026-05-09)`.

---

## Phase A — Acceptance & prerequisites

- [x] A1. User approved both ADRs; Status lines flipped to `Accepted (2026-05-09)`.
- [x] A2. `.\gradlew projects` → BUILD SUCCESSFUL in 11s (2026-05-09); lists both `:rtp-fabric:rtp-fabric-common-unobf` and `:effects-api:effects-api-fabric-unobf`.
- [x] A3. Baseline-equivalent sweep folded into Phase G2–G6 (single combined invocation 2026-05-09 → BUILD SUCCESSFUL in 20s, all 10 tasks UP-TO-DATE).

---

## Phase B — `rtp-fabric-common-unobf` module (ADR-009 steps 1–2)

- [x] B1. Added `include ':rtp-fabric:rtp-fabric-common-unobf'` to `settings.gradle` adjacent to `:rtp-fabric:rtp-fabric-common` (with rationale comment).
- [x] B2. Created `rtp-fabric/rtp-fabric-common-unobf/build.gradle` per ADR-009 (unobfuscated 1.15 Loom, no `mappings` line, MC `26.1.2`, loader `0.18.4`, fabric-api `0.143.5+26.1`, perms-api `0.3.1`). **Deviation from ADR-009 text**: toolchain is Java 25 (not Java 21 as the ADR's `build.gradle` shape states) because `com.mojang:minecraft:26.1.2` is Java-25 bytecode and Loom cannot read it under Java 21 — mirrors v26_1_R1's rationale. See *Notes* below.
- [x] B3. Created empty source tree `rtp-fabric/rtp-fabric-common-unobf/src/main/java/io/github/dailystruggle/rtp/fabric/unobf/` + placeholder `package-info.txt`.
- [x] B4. Configure phase emits `> Configure project :rtp-fabric:rtp-fabric-common-unobf`.
- [x] B5. `.\gradlew :rtp-fabric:rtp-fabric-common-unobf:build` → **BUILD SUCCESSFUL in 34s** on empty source tree (Loom NO-SOURCE compile + jar).

---

## Phase C — `effects-api` nested sibling module (ADR-006 § amendment 2026-05-09 + steps 2–4)

**Mechanism change (2026-05-09):** ADR-006 step 2 originally called for a `fabricUnobf` source set inside `effects-api/build.gradle`. Implementation discovery: `fabric-loom` owns project-level MC + mappings config; a single Gradle project cannot host both intermediary (1.21.1) and unobfuscated (26.1.2) mappings simultaneously. ADR-006 amended in-place to use a **nested sibling Gradle module** `effects-api/effects-api-fabric-unobf/` instead, mirroring the `rtp-fabric/rtp-fabric-common-unobf/` precedent set by ADR-009. C1–C2 below reflect the amended mechanism.

- [x] C1. Added `':effects-api:effects-api-fabric-unobf'` to `settings.gradle` (with rationale comment cross-referencing ADR-006 amendment + ADR-009).
- [x] C1a. Created `effects-api/effects-api-fabric-unobf/build.gradle` per ADR-006 amendment (unobfuscated 1.15 Loom, MC `26.1.2`, loader `0.18.4`, fabric-api `0.143.5+26.1`, perms-api `0.3.1`, Java 25 toolchain, `compileOnly` edges to `:effects-api` and `:rtp-api`).
- [x] C2. Created empty source tree `effects-api/effects-api-fabric-unobf/src/main/java/io/github/dailystruggle/effectsapi/fabric_unobf/` + placeholder `package-info.txt`.
- [x] C2a. `.\gradlew :effects-api:effects-api-fabric-unobf:build` → **BUILD SUCCESSFUL in 43s** on empty source tree (Loom NO-SOURCE compile + jar; configures cleanly alongside all existing modules).
- [x] C3. Mirrored the 11 NM-typed files from `effects-api/src/main/java/.../effectsapi/fabric/` into `effects-api/effects-api-fabric-unobf/src/main/java/.../effectsapi/fabric_unobf/` (2026-05-09). Originals kept in the obf module per ADR-006 step 5 (thinning gated on Phase E3) to honour the C3 caution: `FabricEffectsHandler.setupEffects` (rtp-plugin) still names `effectsapi.fabric.FabricEffectRuntime`, and the v1_20_R1/v1_21_R1/v1_21_R5/v1_21_R11 dispatcher modules likewise. Package declarations rewritten; intra-set imports rewritten; `FabricEffectRuntime` → `FabricEffectRuntimeUnobf` rewritten in moved bodies. Helper script: `scripts/mirror_fabric_unobf.ps1`.
- [x] C4. Added `FabricEffectRuntimeUnobf.java` to the new module — same source body as `effectsapi.fabric.FabricEffectRuntime`, package `effectsapi.fabric_unobf`, class renamed to `FabricEffectRuntimeUnobf`. Compiled under unobf 1.15 Loom so its NM constant-pool refs survive on deobf 26.x.
- [x] C5. `.\gradlew :effects-api:build :effects-api:effects-api-fabric-unobf:build :rtp-plugin:compileJava` → **BUILD SUCCESSFUL in 18s** (2026-05-09). MC 1.21.1 → 26.1.2 mojmap drift surfaced and fixed in the relocated bodies: `net.minecraft.resources.ResourceLocation` → `Identifier` (4 files: `FabricRegistryCompat`, `FabricValueCoercer`, `LocalEffects/FabricPotionEffect`, `LocalEffects/FabricSoundEffect`); `ServerPlayer#serverLevel()` → `(ServerLevel) player.level()` (1 site in `FabricSoundEffect#invokeSoundPacket`). Obf path (`effectsapi.fabric.*`) untouched, so 1.20/1.21.x runtimes compile unchanged.

---

## Phase D — `rtp-fabric-common-unobf` mirror surfaces (ADR-009 step 5)

- [x] D1. Mirrored 3 NM-typed surfaces (2026-05-09): `FabricRTPChunk` → `unobf/world/FabricRTPChunkUnobf`, `FabricRTPWorld` → `unobf/world/FabricRTPWorldUnobf`, `FabricRTPPlayer` → `unobf/player/FabricRTPPlayerUnobf`. Originals kept in `rtp-fabric-common` per ADR-009 step 5 (thinning gated on Phase E3) so v1_20/v1_21.x adapters keep linking against the obf carrier. Helper script: `scripts/mirror_rtp_fabric_unobf.ps1`. MC 1.21.1 → 26.1.2 mojmap drift fixed in the relocated bodies: `ChunkPos.x/.z` → record-component `x()/z()` (FabricRTPChunkUnobf:75-78); `ServerLevel#getForcedChunks().size()` removed (returns `numForceLoaded()` mirroring V26_1_R1 pattern); `ResourceKey<Biome>.location()` → `.identifier()` plus registry-fallback lambda (FabricRTPWorldUnobf:1265-1291); `getMaxBuildHeight/getMinBuildHeight` → `getMaxY/getMinY` (FabricRTPWorldUnobf:1336-1354); `GameProfile.getName()` → record component `name()` (FabricRTPPlayerUnobf:53-65); direct `Permissions.getPermissionValue` → reflective dispatch (sidesteps overload resolution against absent `class_1297`, mirrors V26_1_R1FabricRTPPlayer pattern).
- [x] D2. Added `FabricEffectsHandlerUnobf` (mojmap mirror of `FabricEffectsHandler`) under `unobf/effects/`. Class + intra-set imports renamed; names `effectsapi.fabric_unobf.FabricEffectRuntimeUnobf` and `effectsapi.fabric_unobf.FabricEffectsInitializer`. **EffectsResolver dispatch is reflective** because `EffectsResolver` lives in `rtp-plugin` and `rtp-fabric-common-unobf` must not depend on `rtp-plugin` (one-way dependency: rtp-plugin → common-unobf, never reverse). Reflection mirror is the same shape used for perms-api in `FabricRTPPlayerUnobf` and effectively isolates the cross-module link to a `Class.forName` resolved at first dispatch.
- [x] D3. No further NM-typed surfaces required by current Phase 5C state — `FabricEffectsHandlerUnobf` is the only NM-typed call site that the v26.x adapter needs to install at SERVER_STARTED, and `FabricRTPWorldUnobf`/`FabricRTPChunkUnobf`/`FabricRTPPlayerUnobf` cover the remaining direct-NM hot paths the pipeline touches (world, chunk, player). Additional surfaces (e.g. `FabricVersionAdapterRegistry`, `FabricAnvilColumnProbeAdapter` siblings) remain on the obf-carrier path and reach 26.x only through the v26 adapter's own Mojmap-typed shims — no new common-unobf entries required.
- [x] D4. `.\gradlew :rtp-fabric:rtp-fabric-common-unobf:build :rtp-fabric:rtp-fabric-common:build :effects-api:build :effects-api:effects-api-fabric-unobf:build :rtp-plugin:compileJava` → **BUILD SUCCESSFUL in 25s** (2026-05-09). Both common modules + both effects-api modules + rtp-plugin compile cleanly side-by-side.

---

## Phase E — Wire the v26.x consumer (ADR-009 steps 4 + 7)

- [x] E1. Added `api project(':rtp-fabric:rtp-fabric-common-unobf')` edge plus `compileOnly project(':effects-api:effects-api-fabric-unobf')` to `rtp-fabric/rtp-fabric-v26_1_R1/build.gradle` (2026-05-09). Edge is `api` because the v26 adapter directly names `FabricEffectsHandlerUnobf` from a Mojmap-typed shim; the effects-api-fabric-unobf edge stays `compileOnly` mirroring the obf-side `compileOnly project(':effects-api')` shape.
- [x] E2. `.\gradlew :rtp-fabric:rtp-fabric-v26_1_R1:build` → **BUILD SUCCESSFUL in 51s** (2026-05-09).
- [x] E3. Wired `V26_1_R1FabricVersionAdapter.installEffectsWiring(Object server)` (2026-05-09): casts to `MinecraftServer` (S-006 fail-loud `RTP.log(WARNING)` on cast miss → returns `false` to allow caller fall-through), invokes `FabricEffectsHandlerUnobf.setupEffects(mc)`, returns `true` to suppress the obf-carrier fallback per the `FabricVersionAdapter#installEffectsWiring` contract. Idempotent on re-start (delegated to `FabricEffectsHandlerUnobf.AlreadyHooked.flip()`).
- [x] E4. Verified pre-26 adapters (`V1_20_R1`, `V1_21_R1`, `V1_21_R5`, `V1_21_R11`) unchanged — none override `installEffectsWiring`, so the SPI default (`return false`) keeps the obf-carrier path live for them. `.\gradlew :rtp-fabric:rtp-fabric-v1_20_R1:build :rtp-fabric:rtp-fabric-v1_21_R1:build :rtp-fabric:rtp-fabric-v1_21_R5:build :rtp-fabric:rtp-fabric-v1_21_R11:build` → **BUILD SUCCESSFUL in 39s** (2026-05-09).

---

## Phase F — Regression tests (ADR-006 + ADR-009 step 6)

- [x] F1. Added `EffectsApiFabricCarriersDisjointTest` at `effects-api/src/test/.../effectsapi/fabric/` (2026-05-09). Source-scan based (no ASM dep). Two cases: `effectsapi.fabric.*` does not import `fabric_unobf.*`, and vice versa. Path-resolution fix: `effects-api-fabric-unobf` is **nested inside** `effects-api/`, not a sibling — `projectDir().resolve("effects-api-fabric-unobf/...")`.
- [x] F2. Added `RtpFabricCarriersDisjointTest` at `rtp-fabric/rtp-fabric-common/src/test/.../rtp/fabric/unobf/` (2026-05-09). Three cases: common does not import `rtp.fabric.unobf.*`; common-unobf does not import obf carrier `rtp.fabric.*` (excluding the `.unobf` subpackage); pre-26 adapter modules (`v1_20_R1`/`v1_21_R1`/`v1_21_R5`/`v1_21_R11`) do not import `rtp.fabric.unobf.*`. **Surfaced 3 real Phase D regressions** that the test correctly flagged: `FabricRTPPlayerUnobf`/`FabricRTPChunkUnobf`/`FabricRTPWorldUnobf` were importing obf-carrier `FabricLegacyText`, `FabricPaletteNormalizer`, `FabricAnvilColumnProbeAdapter`. Per user direction (option 1), fixed by mirroring all 3 utility classes into `rtp-fabric-common-unobf/.../unobf/{anvil,tools}/` and redirecting the 3 consumer imports. **Mojmap drift fix in `FabricLegacyText`**: `new HoverEvent(Action, Component)` and `new ClickEvent(Action, String)` no longer compile under MC 26.x mojmap (sealed records); switched to reflective construction via `Constructor.newInstance` with the existing best-effort try/catch fallback retained for runtime drift.
- [x] F3. Both tests run green: `.\gradlew :effects-api:test --tests "...EffectsApiFabricCarriersDisjointTest" :rtp-fabric:rtp-fabric-common:test --tests "...RtpFabricCarriersDisjointTest"` → **BUILD SUCCESSFUL in 16s** (2026-05-09).

---

## Phase G — Verification gates (must all be green before submit)

- [x] G1. `.\gradlew projects` lists `:rtp-fabric:rtp-fabric-common-unobf` (and `:effects-api:effects-api-fabric-unobf`); BUILD SUCCESSFUL in 11s (2026-05-09).
- [x] G2. `:rtp-fabric:rtp-fabric-common-unobf:build` → UP-TO-DATE in combined sweep (BUILD SUCCESSFUL in 20s, 2026-05-09); fresh BUILD SUCCESSFUL recorded at D4 (25s).
- [x] G3. `:rtp-fabric:rtp-fabric-common:build` → UP-TO-DATE in combined sweep (2026-05-09); unchanged behaviour confirmed (no source edits since Phase D landed).
- [x] G4. `v1_20_R1 / v1_21_R1 / v1_21_R5 / v1_21_R11 :build` → UP-TO-DATE in combined sweep (2026-05-09); fresh BUILD SUCCESSFUL recorded at E4 (39s).
- [x] G5. `:rtp-fabric:rtp-fabric-v26_1_R1:build` → UP-TO-DATE in combined sweep (2026-05-09) with `api project(':rtp-fabric:rtp-fabric-common-unobf')` + `compileOnly project(':effects-api:effects-api-fabric-unobf')` edges; fresh BUILD SUCCESSFUL recorded at E2 (51s).
- [x] G6. `:effects-api:build :rtp-plugin:compileJava` → UP-TO-DATE in combined sweep (2026-05-09); fresh BUILD SUCCESSFUL recorded at C5 / D4.
- [x] G7. `EffectsApiFabricCarriersDisjointTest` + `RtpFabricCarriersDisjointTest` pass (2026-05-09). Combined full-sweep build `:rtp-fabric-common-unobf :rtp-fabric-common v1_20_R1 v1_21_R1 v1_21_R5 v1_21_R11 v26_1_R1 :effects-api :effects-api-fabric-unobf :rtp-plugin:compileJava` after Phase F changes → **BUILD SUCCESSFUL in 26s**.
- [ ] G8. Manual regression: `/rtp` on a 1.21.1 (intermediary) runtime continues to fire effects and teleport (obf path unchanged from consumer perspective). *(Deferred to admin smoke if local Fabric runtime unavailable.)*
- [ ] G9. Manual smoke (deferred to admin, post-Phase 5C): `/rtp` on deobf MC 26.1.2 with `effectParsing: true` and a sound configured under `rtp.effect.postteleport` produces audible feedback + post-teleport title splash.

---

## Phase H — Documentation & housekeeping (ADR-006 + ADR-009 steps 8–11)

- [ ] H1. CHANGELOG entry under the current unreleased version describing the obf/unobf module split (no addon migration; `rtp-fabric-common[-unobf]` and `effectsapi.fabric*` are non-API). Follow CHANGELOG hygiene rule — diff against last released tag, not working tree.
- [ ] H2. Cross-reference `docs/dev/POTENTIAL_BUGS.md` rows for `class_2596` / `class_7923` when closing the work.
- [ ] H3. Update `.junie/AGENTS.md` *Domain Analogies & Aliases* if a new informal alias emerges (e.g., "obf carrier" / "unobf carrier" / "common-unobf module"). Add only if it appears in chat/issues, per *Self-Updating Protocol*.
- [ ] H4. Update `docs/dev/TRACEABILITY.md` if any moved class is referenced by a REQ-* row (mechanical path update; no requirement-text change).
- [ ] H5. Update `rtp-fabric-ADR-002 §4` Loom allow-list note to include `rtp-fabric-common-unobf` (one new entry).
- [ ] H6. Append Phase 5C entry referencing this checklist to `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md` (or fold this checklist into Phase 5C if user prefers single-file tracking).
- [ ] H7. (Follow-up, not a blocker) Move shared MC/loader/fabric-api/perms-api version pins of `rtp-fabric-common-unobf` and `rtp-fabric-v26_1_R1` to a single `gradle.properties` / `versions.gradle` location — recorded as drift mitigation in ADR-009 *Negative / costs*.

---

## Phase I — Submit

- [ ] I1. All Phase A–G boxes ticked or explicitly deferred under `### Notes` with reason.
- [ ] I2. Delete this checklist file (`docs/dev/scratch/CHECKLIST-fabric-obf-unobf-split.md`) as part of the submitting commit, per *Checklist-Based State Tracking*.
- [ ] I3. Submit summary references the completed checklist; any unchecked item is called out under `### Notes`.

---

## Notes / open questions

- **Phase B Java toolchain deviation (2026-05-09) — RESOLVED 2026-05-09:** ADR-009's `build.gradle shape` block was patched to specify `JavaLanguageVersion.of(25)` (matching `rtp-fabric-v26_1_R1`) with full rationale prose: `com.mojang:minecraft:26.1.2` ships as Java-25 bytecode and Loom reads it at configuration time, so Java 21 fails with `UnsupportedClassVersionError` before any source compiles. Implementation now matches ADR text — no deviation outstanding.
- **Phase C mechanism change (2026-05-09) — RESOLVED via ADR-006 amendment 2026-05-09:** ADR-006's original "second source set inside `effects-api/build.gradle`" mechanism was discovered to be unimplementable (project-level Loom owns mappings; cannot host both intermediary 1.21.1 and unobfuscated 26.1.2 in one project). ADR-006 amended in-place to specify a nested sibling Gradle module `effects-api/effects-api-fabric-unobf/` mirroring the `rtp-fabric/rtp-fabric-common-unobf/` precedent. C1–C2 in this checklist reflect the amended mechanism; C3–C5 (file relocations + post-build verification) remain to be done in a follow-up session.
- ADR-006 step 5 ("thinning" of `fabric/FabricEffectRuntime` to delegate to `fabric_unobf/FabricEffectRuntimeUnobf` only on deobf 26.x) is gated on Phase E3 wiring being live; do not perform the thinning before Phase E completes.
- Loom config drift between `rtp-fabric-common-unobf` and `rtp-fabric-v26_1_R1` is the principal maintenance hazard — H7 mitigates but is not a blocker for the initial PR.
- S-004 / S-005 / S-006 invariants are unchanged by this split; verify no dispatch try-block in `RTPFabricMod` is relaxed during Phase E.
