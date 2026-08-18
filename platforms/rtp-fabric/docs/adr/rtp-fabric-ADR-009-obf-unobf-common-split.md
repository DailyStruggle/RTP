# rtp-fabric-ADR-009 — Obf/unobf split of `rtp-fabric-common`: introduce sibling `rtp-fabric-common-unobf`

- **Status:** Accepted (2026-05-09)
- **Supersedes:** —
- **Superseded by:** —
- **Related:**
  - **Companion (mandatory pair):** `effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md` — the corresponding decision in the `effects-api` subtree. Both ADRs must be accepted together; neither is meaningful alone.
  - `rtp-fabric-ADR-001-multiversion-submodule-layout.md` — establishes the per-MC submodule pattern (`rtp-fabric-common` + `rtp-fabric-vXX_YY_R1`). This ADR adds an obf/unobf axis orthogonal to that per-MC axis.
  - `rtp-fabric-ADR-002-platform-in-scope.md section 4 Build Discipline` — the Loom allow-list. This ADR adds **one** new Loom-using module (`rtp-fabric-common-unobf`); the allow-list grows from `{rtp-fabric-common, rtp-fabric-vXX_YY_R1*, rtp-plugin, effects-api}` to additionally include `rtp-fabric-common-unobf`.
  - `rtp-fabric-ADR-007-mojmap-name-decoupling.md` — the prior decision to use unobfuscated Loom in `rtp-fabric-v26_1_R1`; this ADR generalises the same Loom-mode to a version-agnostic common carrier.
  - `effects-api-ADR-003-platform-split-bukkit-fabric.md` — the in-module `common/ + bukkit/ + fabric/` split that effects-api-ADR-006 refines.
  - `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md` Phase 5 / Phase 5C — the bring-up work this ADR unblocks.
  - `.junie/AGENTS.md` *Architecture Boundaries*, *Pre-Flight Checklist*, *Propose Before Implementation (Rule D-005)*.

---

## Context

`rtp-fabric-common` is compiled under **intermediary** Loom mappings (Loom default for the 1.20 / 1.21.x family). Loom rewrites every `net.minecraft.*` reference in the resulting bytecode to `net.minecraft.class_NNNN` form so the Fabric loader's intermediary→named remap step relinks those references against the running MC version's mojmap (or yarn) names. That contract holds on every MC version whose Fabric loader injects an intermediary→runtime remap step.

It does **not** hold on a deobfuscated runtime (a Fabric server jar that is already in mojmap form, e.g. the dev/deobf builds for MC 26.1.2 +). On a deobf runtime, `class_NNNN` constant-pool entries point at classes that simply do not exist; the JVM throws `NoClassDefFoundError` at first link. The `rtp-fabric-v26_1_R1` submodule already documents and works around this by pinning Loom 1.15 in **unobfuscated** mode (`rtp-fabric-ADR-007`), so its bytecode references mojmap names directly and links cleanly on a deobf 26.x runtime.

The remaining hazard is `rtp-fabric-common`. Its classpath is shared by every Fabric runtime, including deobf 26.x, and any class in it that names `net.minecraft.*` is an intermediary leak on the deobf side. The companion `effects-api-ADR-006` resolves the equivalent leak inside `effects-api/fabric/*` by introducing a sibling `effectsapi/fabric_unobf/` source set inside `effects-api`. The same structural fix is required for `rtp-fabric-common` so the new effects-api unobf carrier has somewhere to dispatch to from the v26.x version-adapter side.

### What we considered first, and why it was discarded

The Phase 5 checklist's original framing of item 22 was "mirror typed bodies into `rtp-fabric-v26_1_R1/`" — a per-version mirror. Two problems:

1. **Duplication scales with MC bumps.** Each new deobf MC line (26.1, 26.2, 26.3, …) would require a fresh mirror of the same NM-typed surface. The structural cost is N×M (N versions, M leaked files); the structural fix is one-time.
2. **`effects-api-ADR-006` already needs a single carrier on the unobf side.** A per-version mirror would force `effects-api`'s unobf carrier to hop through every `rtp-fabric-vXX_YY_R1` adapter individually, defeating the point of `rtp-fabric-common` as the version-agnostic glue.

The structural fix — a sibling `rtp-fabric-common-unobf` Gradle module compiled under unobfuscated 1.15 Loom and on the classpath only of deobf-MC version-adapter modules — is therefore strictly cheaper from N=2 onward, and cleanly pairs with `effects-api-ADR-006`'s sibling `fabric_unobf/` source set.

### Constraint inherited from `effects-api-ADR-003`

> "try not to create more root level directories, we can do this within effects-api like we did with commands-api"

That constraint is **scoped to `effects-api/`** (its companion ADR-006 honours it by using a sibling source set inside the existing module). It does **not** apply to the `rtp-fabric/` subtree, where multi-version siblings (`rtp-fabric-common`, `rtp-fabric-v1_20_R1`, `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11`, `rtp-fabric-v26_1_R1`) already coexist as Gradle siblings under `rtp-fabric/`. Adding `rtp-fabric-common-unobf` follows the existing pattern in that subtree and adds **no new root-level directory**.

## Decision

Introduce a new sibling Gradle module `rtp-fabric/rtp-fabric-common-unobf/` compiled under unobfuscated 1.15 Loom, mirroring the Loom configuration already validated by `rtp-fabric-v26_1_R1`. Move the **`net.minecraft.*`-touching, version-agnostic** glue out of `rtp-fabric-common` into `rtp-fabric-common-unobf`, leaving `rtp-fabric-common` responsible only for code that either (a) does not name `net.minecraft.*` at all, or (b) names it through the intermediary path needed by 1.20 / 1.21.x adapters.

### Target module shape

```
rtp-fabric/                                              (existing — unchanged)
  rtp-fabric-common/                                     ← intermediary-Loom carrier (existing)
    src/main/java/io/github/dailystruggle/rtp/fabric/
      events/      scheduling/   commands/   …          ← KEPT (no NM leak / intermediary-safe)
      worlds/FabricWorld.java                            ← KEPT (intermediary-Loom, used by 1.20/1.21.x)
      effects/FabricEffectsHandler.java                  ← KEPT (intermediary-side bootstrap;
                                                          calls effectsapi.fabric.FabricEffectRuntime
                                                          per effects-api-ADR-006)
  rtp-fabric-common-unobf/                               ← NEW — unobfuscated 1.15 Loom carrier
    build.gradle                                         (mirrors rtp-fabric-v26_1_R1 Loom block:
                                                          plugin id 'net.fabricmc.fabric-loom',
                                                          NO `mappings` line, plain
                                                          implementation/compileOnly)
    src/main/java/io/github/dailystruggle/rtp/fabric/unobf/
      worlds/FabricWorldUnobf.java                       (mojmap-bytecode mirror of FabricWorld
                                                          for deobf 26.x version-adapters)
      effects/FabricEffectsHandlerUnobf.java             (mojmap-bytecode bootstrap that names
                                                          effectsapi.fabric_unobf.FabricEffectRuntimeUnobf
                                                          per effects-api-ADR-006)
      …                                                  (any further NM-typed common glue surfaces
                                                          one entry at a time, as Phase 5C work
                                                          uncovers them; the surface is intentionally
                                                          minimal at landing time)
  rtp-fabric-v1_20_R1/                                   ← KEPT — depends on rtp-fabric-common only
  rtp-fabric-v1_21_R1/                                   ← KEPT — depends on rtp-fabric-common only
  rtp-fabric-v1_21_R5/                                   ← KEPT — depends on rtp-fabric-common only
  rtp-fabric-v1_21_R11/                                  ← KEPT — depends on rtp-fabric-common only
  rtp-fabric-v26_1_R1/                                   ← UPDATED — adds dependency on
                                                          rtp-fabric-common-unobf so its
                                                          V26_1_R1FabricVersionAdapter can name
                                                          FabricWorldUnobf / FabricEffectsHandlerUnobf
                                                          without crossing the obf↔unobf line.
```

Both carriers share **source-level** intent (every type in `rtp-fabric-common-unobf` has the same source body its `rtp-fabric-common` counterpart had at relocation time, where one exists) but differ in their **bytecode** form because they are compiled under different Loom configurations:

| Module | Loom config | Constant-pool form | Loads on |
|---|---|---|---|
| `rtp-fabric-common`        | intermediary (existing) | `net/minecraft/class_NNNN` | 1.20 / 1.21.x runtimes; **fails** on deobf 26.x |
| `rtp-fabric-common-unobf`  | unobfuscated 1.15 (mirrors `rtp-fabric-v26_1_R1`) | mojmap (`net/minecraft/server/level/ServerPlayer`, …) | deobf 26.x runtimes; **fails** on 1.20 / 1.21.x intermediary runtimes |

Each carrier is on the classpath of every Fabric runtime; correctness comes from **only naming the appropriate carrier from runtime-selected dispatch code**, never from both at once.

### Dispatch policy

`RTPFabricMod` already resolves a per-version `FabricVersionAdapter` by FQN string at server start (per `rtp-fabric-ADR-001`). The same string-FQN dispatch picks the correct common carrier:

- `V1_20_R1FabricVersionAdapter`, `V1_21_R1FabricVersionAdapter`, `V1_21_R5FabricVersionAdapter`, `V1_21_R11FabricVersionAdapter` → name only `rtp-fabric-common` symbols (existing behaviour, unchanged).
- `V26_1_R1FabricVersionAdapter` (and any future deobf-MC adapter) → name only `rtp-fabric-common-unobf` symbols when an unobf-bytecode counterpart exists; otherwise fall back to `rtp-fabric-common` for intermediary-safe glue.
- The fall-through `RTPFabricMod.onInitialize` → `FabricEffectsHandler.setupEffects(server)` path keeps naming `rtp-fabric-common`'s `FabricEffectsHandler`, which in turn names `effectsapi.fabric.FabricEffectRuntime` (the obf carrier from `effects-api-ADR-006`). It is reached **only** when no version-adapter claims `installEffectsWiring` — which the deobf v26.x adapter will, once Phase 5C lands.

The unobf carrier named on a 1.20/1.21 runtime is only reachable via per-version adapters that themselves don't load on those runtimes (because `rtp-fabric-v26_1_R1` is absent from a 1.20/1.21 server's runtime classpath — version-adapter selection is by MC version match before any class is loaded). The obf carrier named on a deobf 26.x runtime is only reachable when the v26 adapter returns `false` from `installEffectsWiring`, which it never will once Phase 5C lands.

### `settings.gradle` change

```
include ':rtp-fabric:rtp-fabric-common-unobf'
```

(One line, alphabetically adjacent to the existing `:rtp-fabric:rtp-fabric-common` include.)

### `rtp-fabric/rtp-fabric-common-unobf/build.gradle` shape

Mirrors `rtp-fabric/rtp-fabric-v26_1_R1/build.gradle` exactly, including the **Java 25** toolchain. The earlier draft of this ADR specified Java 21 (the project baseline), reasoning that the carrier is version-agnostic and so should be loadable from every deobf-MC runtime at Java ≥ 21. Implementation on 2026-05-09 disproved that: `com.mojang:minecraft:26.1.2` ships as Java-25 bytecode, and Loom must read the Minecraft jar at *configuration time* to assemble the compile classpath — Loom configuration itself fails under Java 21 with `UnsupportedClassVersionError` on `com/mojang/minecraft` classes, before any source in this module is compiled. The toolchain is therefore pinned to Java 25, matching `rtp-fabric-v26_1_R1`. The forward-compatibility argument still holds at the *consumer* level: every deobf-26.x runtime is itself Java 25+, and Java 21 callers (the pre-26 v-adapters) never name symbols from this module — they name only `rtp-fabric-common`, per the *Dispatch policy* section above. The Java-25 toolchain pin is a build-time requirement, not a runtime classpath constraint, and contributors without JDK 25 see only this module (and `rtp-fabric-v26_1_R1`) fail toolchain provisioning; the rest of the build remains on Java 21.

```groovy
plugins {
    id 'net.fabricmc.fabric-loom'
}

ext {
    // Pin to the lowest deobf MC line that consumes this carrier so Loom
    // resolves a usable mojmap-bytecode artifact at compile time. The
    // bytecode is forward-compatible with later deobf 26.x lines because
    // every consumer (rtp-fabric-v26_*_R1) ships its own pinned MC.
    minecraftVersion       = '26.1.2'
    loaderVersion          = '0.18.4'
    fabricApiVersion       = '0.143.5+26.1'
    permissionsApiVersion  = '0.3.1'
}

java {
    toolchain {
        // Java 25 (NOT the project baseline of 21) — required because
        // com.mojang:minecraft:26.1.2 is Java-25 bytecode and Loom reads it
        // at configuration time. See the prose above for the full rationale.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${minecraftVersion}"
    // No `mappings` line — 26.1+ ships deobfuscated.
    implementation "net.fabricmc:fabric-loader:${loaderVersion}"
    implementation "net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}"
    compileOnly "me.lucko:fabric-permissions-api:${permissionsApiVersion}"

    api project(':rtp-api')
    api project(':rtp-core')
    api project(':commands-api')
    api project(':rtp-anvil')
    // Compile-only against rtp-fabric-common so the unobf carrier can reuse
    // platform-neutral types (interfaces, base classes that don't name NM)
    // without dragging intermediary bytecode into the unobf jar.
    compileOnly project(':rtp-fabric:rtp-fabric-common')
    // Compile-only against effects-api so FabricEffectsHandlerUnobf can name
    // the unobf carrier (effectsapi.fabric_unobf.*). The unobf source set
    // from effects-api-ADR-006 is the actual artifact consumed at runtime;
    // see that ADR for the source-set wiring.
    compileOnly project(':effects-api')
}
```

The Loom version, MC version, loader version, and fabric-api version are intentionally identical to `rtp-fabric-v26_1_R1`'s. Drift between the two is a maintenance hazard called out in *Negative / costs* below.

### `rtp-fabric/rtp-fabric-v26_1_R1/build.gradle` change

One line added:

```groovy
dependencies {
    …
    api project(':rtp-fabric:rtp-fabric-common-unobf')   // NEW — provides FabricWorldUnobf etc.
    …
}
```

No other v-modules change.

### What this ADR explicitly does not change

- `rtp-fabric-common` source layout — KEPT. Only files that demonstrably name `net.minecraft.*` and are version-agnostic are candidates for relocation; the bulk of `rtp-fabric-common` (events bridge, scheduler, commands, world-list helpers that don't cross NM) stays put.
- `rtp-fabric-v1_20_R1`, `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11` — UNCHANGED. They remain on `rtp-fabric-common` only.
- The `rtp-fabric-ADR-002 section 4` Loom allow-list is widened by exactly one module (`rtp-fabric-common-unobf`).
- No business logic moves out of `rtp-core` / `rtp-api`. The new module is platform glue only, per *Architecture Boundaries* in `.junie/AGENTS.md`.
- No public addon API surface changes. Addons depend on `rtp-api` and never on `rtp-fabric-common[-unobf]` directly.

## Consequences

### Positive

- Resolves the structural half of the deobf-26.x linkage hazard; together with `effects-api-ADR-006` it closes Phase 5 / item 22 once for every current and future deobf-MC line.
- Per-version v26.x submodules (26.1, 26.2, 26.3, …) thin out to true version-deltas only; the version-agnostic glue lives once, in `rtp-fabric-common-unobf`.
- Honours `rtp-fabric-ADR-002 section 4` — exactly one new Loom-using module, justified by the structural impossibility of compiling intermediary and unobfuscated bytecode in the same source set.
- Unblocks `effects-api-ADR-006`'s checklist step 1 (which explicitly requires this ADR or its equivalent to land first or in lockstep).
- S-005 (no chunk I/O on main thread): unchanged. `FabricWorldUnobf` mirrors `FabricWorld`'s async chunk-load contract from `rtp-fabric-ADR-008`; no new sync chunk-load entry points.
- S-006 (API-before-core null guards): unchanged. The new module hosts no `rtp-api` entry points.
- S-004 (no silent teleport-failure swallow): unchanged. Dispatcher try-blocks in `RTPFabricMod` are not relaxed.

### Negative / costs

- One new Gradle module (`rtp-fabric-common-unobf`) and the corresponding `settings.gradle` include. Build-graph leaf-count grows by one; compile-time impact is negligible (the module is small and version-agnostic).
- Loom config drift between `rtp-fabric-common-unobf` and `rtp-fabric-v26_1_R1` is a maintenance hazard. Mitigation: the four `ext { … }` versions (MC, loader, fabric-api, permissions-api) and the Loom plugin pin in `settings.gradle pluginManagement.plugins {}` should be moved to a single shared location (`gradle.properties` or a `versions.gradle` script) in a follow-up small refactor. Recorded as an implementation note, not a blocker for this ADR.
- A regression test (`RtpFabricCarriersDisjointTest`, mirror of `effects-api-ADR-006`'s `EffectsApiFabricCarriersDisjointTest`) is required to assert that `rtp-fabric-common/*` does not reference any class under `rtp-fabric-common-unobf/*` and vice versa, and that `rtp-fabric-v1_20_R1`, `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11` reference neither directly nor transitively any class under `rtp-fabric-common-unobf/*`.
- Source duplication where a class has both an obf and an unobf counterpart (`FabricWorld` ↔ `FabricWorldUnobf`, `FabricEffectsHandler` ↔ `FabricEffectsHandlerUnobf`). Mitigated by keeping the unobf surface as small as possible and considering source-template generation only if a third Loom regime ever appears.
- The `compileOnly project(':rtp-fabric:rtp-fabric-common')` edge in `rtp-fabric-common-unobf/build.gradle` is a one-way compile-only edge; the runtime classpath of any deobf-MC consumer must continue to ship `rtp-fabric-common` (it does today, transitively through `rtp-fabric-v26_1_R1`'s existing `api project(':rtp-fabric:rtp-fabric-common')` line). No test currently asserts that invariant; the new disjoint-carriers test should also assert "no `net.minecraft.*` reference of either form survives in `rtp-fabric-common-unobf`'s output that is also present in `rtp-fabric-common`'s output."

### Out of scope (separate follow-ups)

- Collapsing `rtp-fabric-common` and `rtp-fabric-common-unobf` behind a shared common ancestor (e.g. extracting the source bodies to a `*.java.in` template and generating both modules from it). Considered, deferred — same rationale as `effects-api-ADR-006`.
- Same-shape obf/unobf split for `commands-api/brigadier/*`. Out of scope; record under `commands-api/docs/adr/` if a deobf 26.x runtime exposes the same hazard there.
- Backporting the unobf carrier to provide deobf-runtime support for 1.20 / 1.21.x dev environments. Not a requirement today.
- Sharing version-pin properties between `rtp-fabric-common-unobf` and `rtp-fabric-v26_1_R1` via a single `gradle.properties` block. Listed under *Negative / costs* as an implementation note; defer to a small follow-up refactor PR after both ADRs land.

## Implementation checklist (will live under Phase 5C of `docs/dev/scratch/CHECKLIST-fabric-26-1-2-bringup.md` once approved)

1. Add `':rtp-fabric:rtp-fabric-common-unobf'` to `settings.gradle`. Confirm `.\gradlew projects` lists the new module.
2. Create `rtp-fabric/rtp-fabric-common-unobf/build.gradle` with the unobfuscated 1.15 Loom block above. Confirm `.\gradlew :rtp-fabric:rtp-fabric-common-unobf:build` is BUILD SUCCESSFUL on an empty source tree.
3. Land **the companion ADR** `effects-api/docs/adr/effects-api-ADR-006-fabric-obf-unobf-split.md` in lockstep (its checklist step 1 references this ADR; this ADR's value is unlocked when its consumer lands).
4. Add `api project(':rtp-fabric:rtp-fabric-common-unobf')` to `rtp-fabric/rtp-fabric-v26_1_R1/build.gradle`. Confirm `.\gradlew :rtp-fabric:rtp-fabric-v26_1_R1:build` still BUILD SUCCESSFUL.
5. Relocate / mirror NM-typed version-agnostic surfaces from `rtp-fabric-common` to `rtp-fabric-common-unobf` one entry at a time, starting with whatever `effects-api-ADR-006`'s `FabricEffectRuntimeUnobf` actually needs to resolve at compile time. Each move is a separate commit with its own bytecode-scan validation.
6. Add a regression test `RtpFabricCarriersDisjointTest` (bytecode/import scan): asserts the disjointness invariants listed under *Negative / costs*.
7. Wire `V26_1_R1FabricVersionAdapter.installEffectsWiring(server)` to name `FabricEffectsHandlerUnobf` and return `true`. Verifies via the existing `FabricVersionAdapter` SPI from Phase 5 item 21.
8. CHANGELOG entry under the current unreleased version describing the obf/unobf module split + addon migration note (none expected; `rtp-fabric-common[-unobf]` is non-API).
9. Cross-reference `docs/dev/POTENTIAL_BUGS.md` rows for `class_2596` / `class_7923` (already logged) when closing the work.
10. Update `.junie/AGENTS.md` *Domain Analogies & Aliases* if a new informal alias emerges (likely "obf carrier" / "unobf carrier" / "common-unobf module" — add only if it appears in chat/issues, per the *Self-Updating Protocol*).
11. Update `docs/dev/TRACEABILITY.md` if any moved class is referenced by a REQ-* row (mechanical path update; no requirement-text change).

## Verification gates (must be green before submit of the implementation PR)

- `.\gradlew projects` lists `:rtp-fabric:rtp-fabric-common-unobf`.
- `.\gradlew :rtp-fabric:rtp-fabric-common-unobf:build` → BUILD SUCCESSFUL.
- `.\gradlew :rtp-fabric:rtp-fabric-common:build` → BUILD SUCCESSFUL (unchanged behaviour).
- `.\gradlew :rtp-fabric:rtp-fabric-v1_20_R1:build :rtp-fabric:rtp-fabric-v1_21_R1:build :rtp-fabric:rtp-fabric-v1_21_R5:build :rtp-fabric:rtp-fabric-v1_21_R11:build` → BUILD SUCCESSFUL (no new dependency edge added to these submodules).
- `.\gradlew :rtp-fabric:rtp-fabric-v26_1_R1:build` → BUILD SUCCESSFUL with the new `api project(':rtp-fabric:rtp-fabric-common-unobf')` edge.
- `.\gradlew :effects-api:build :rtp-plugin:compileJava` → BUILD SUCCESSFUL (no regression in the effects-api or plugin assembly).
- `RtpFabricCarriersDisjointTest` passes.
- Manual smoke (deferred to admin, after Phase 5C ships): `/rtp` on a deobf MC 26.1.2 server with `effectParsing: true` and a sound configured under `rtp.effect.postteleport` produces audible feedback; the post-teleport title splash reaches the client.
- Manual regression: `/rtp` on a 1.21.1 (intermediary) runtime continues to fire effects and continues to teleport (the obf path is unchanged from the consumer's perspective).
