# rtp-fabric-ADR-007 — Decouple `FabricVersionAdapter` from Mojmap type names

- **Status:** Accepted (D-005 approval recorded 2026-05-06; implementation landed in the same session — SPI swap, common-side caller migration, and migration of all 4 existing adapters; R11 port + smoke test deferred to a follow-up session)
- **Date:** 2026-05-06
- **Supersedes:** none — refines `rtp-fabric-ADR-001` (multiversion submodule layout)
- **Scope:** `rtp-fabric-common` (interface refactor) and every per-version
  adapter submodule that implements it: `rtp-fabric-v1_20_R1`,
  `rtp-fabric-v1_21_R1`, `rtp-fabric-v1_21_R5`, `rtp-fabric-v1_21_R11`
  (currently excluded), and any future `vXX_YY_RN`.

## Context

`rtp-fabric-ADR-001` established the per-MC-version submodule pattern:
`rtp-fabric-common` exposes the platform-internal SPI
`io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter`, and
each `rtp-fabric-vXX_YY_RN` submodule supplies one implementation
compiled against that MC release's Loom-mapped Mojmap jar. The bootstrap
in `RTPFabricMod` reflectively selects the correct adapter at runtime
based on `SharedConstants.getCurrentVersion().getName()`.

The pattern's load-bearing assumption is that **Mojmap type names are
stable across MC point releases on the SPI's interface footprint**. The
1.21.5 → 1.21.11 transition broke that assumption, hard:

| 1.21.5 Mojmap symbol | 1.21.11 Mojmap symbol | Intermediary | Breaks SPI? |
|----------------------|-----------------------|--------------|-------------|
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` | `class_2960` | **yes** — appears in `blockKey`, `biomeKeyAt` |
| `net.minecraft.server.level.TicketType` (record `(long, boolean, TicketUse)`) | `net.minecraft.server.level.TicketType` (record `(long, int)`) | `class_3230` | no (internal to adapter) |
| `TicketType.TicketUse` (inner enum) | **removed** (replaced by `int` flag bitfield) | `class_3230$class_10558` | no (internal to adapter) |
| `TicketType.NO_TIMEOUT` | `NO_EXPIRATION` | `field_55598` | no (internal to adapter) |

Confirmed via `javap` on the user's
`~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-merged-1.21.11-...-v2.jar`
and yarn 1.21.11+build.4 docs (cross-checked with intermediary jar
disassembly). Full symbol-by-symbol evidence is in the working note
`docs/dev/scratch/CHECKLIST-fabric-1.21.11-multiversion.md` sections 1-2.

Why the rename hits us, mechanically:

`rtp-fabric-common` is compiled **once**, against a fixed Mojmap snapshot
(today: 1.21.5). Its compiled bytecode literally references
`net.minecraft.resources.ResourceLocation` in every method signature
that mentions a registry key. When the `v1_21_R11` submodule (compiled
against 1.21.11 Mojmap, where the symbol is `Identifier`) attempts to
override those methods, javac reads common's class files and tries to
resolve `ResourceLocation` on the R11 classpath — where it does not
exist. Loom does **not** auto-remap project-dependency JARs per
consumer's mappings; remapping is a publication-time step driven by
`remapJar`, not a per-consumer view. Result:
`cannot access ResourceLocation: class file ... not found`. The same
breakage will recur for every Mojmap rename that hits the SPI surface.

The 1.21.11 → 26.1 transition (Mojang's "Mojmap-as-source"
deobfuscation pass) prompted this rename pass and is expected to
produce more renames through the 26.1 release line. The
per-submodule-per-rename pattern would force a new
`rtp-fabric-vXX_YY_RN` whenever **any** Mojmap symbol on the interface
footprint changes — linear cost in MC releases, with bytecode bloat in
the shaded plugin jar.

The currently-excluded `rtp-fabric-v1_21_R11` module (settings.gradle
include and `rtp-plugin/build.gradle` runtime entry both commented out
with rationale; `RTPFabricMod.adapterFqnFor` falls through to the
S-006 "no adapter installed" path on a 1.21.11 runtime) is the
immediate forcing function. Re-enabling it requires an interface that
does not bake the renamed symbol into its compiled signature.

## Decision

`FabricVersionAdapter`'s public interface footprint shall not reference
any `net.minecraft.*` types whose Mojmap name has been observed to
change, or is plausibly at risk of changing, across point releases.
Mojmap-stable types may continue to appear directly.

Concretely, two Mojmap-name-stable approaches are on the table:

- **5.a — Lightweight wrapper records in `rtp-fabric-common`** that
  carry the underlying MC object as an `Object` payload, with each
  per-version adapter casting on entry and wrapping on exit. Type
  safety is preserved everywhere except at the seam (the cast inside
  the adapter, which is local).
- **5.b — Raw `Object` everywhere on the SPI**, with adapters and
  common-side callers responsible for their own casts. Smallest
  interface footprint; weakest type guarantees.

This ADR adopts **5.a (wrapper records)**. The wrap/unwrap cost is
trivial (one allocation per call site, immediately escape-analysable
for inlined paths) and concentrated entirely in the adapter
implementations. Common-side callers (`FabricRTPWorld`,
`FabricRTPChunk`, biome/safety predicates) keep typed handles and
cannot accidentally pass an `Object` of the wrong shape. This matches
the project's existing pattern for `RTPLocation` /
`RTPWorld` / `RTPChunk` in `rtp-api`: typed handles whose payload is
platform-specific.

### SPI surface after the refactor

> **Update (2026-06-01).** Three of the wrappers below were later removed as
> dead code once their SPI methods proved to have no live callers anywhere in
> the tree: `blockKey` (registry-key lookup), `biomeKeyAt`, `hasChunk`, and
> `airState` were deleted from `FabricVersionAdapter` and every adapter
> implementation, which in turn made `RTPBlockHandle`, `RTPBlockStateHandle`,
> and `RTPRegistryKey` unused — those three records were deleted. The wrapper
> seam itself is unchanged for the still-live chunk-load / ticket path:
> `RTPLevelHandle` (param of `requestFullChunkAsync`, `getChunkFull`,
> `applyTicket`, `releaseTicket`) and `RTPChunkHandle` (their return) remain.
> The decision and rationale below are preserved as the original record.

Wrapper inventory, derived from the **current** signatures of
`FabricVersionAdapter` (not the broader `FabricRTP*` surface):

| Wrapper (in `rtp-fabric-common`) | Wraps | Used in SPI methods |
|----------------------------------|-------|---------------------|
| `RTPLevelHandle` (record) | `ServerLevel` | `biomeKeyAt`, `getChunkFull`, `hasChunk`, `applyTicket`, `releaseTicket` |
| `RTPBlockHandle` (record) | `Block` | `blockKey` |
| `RTPBlockStateHandle` (record) | `BlockState` | `airState` (return) |
| `RTPChunkHandle` (record) | `ChunkAccess` | `getChunkFull` (return) |

Two SPI types remain Mojmap-direct because they are
**Mojmap-stable** across 1.20 → 26.1 (verified via `javap`):

- `net.minecraft.core.BlockPos` — same FQN since pre-1.20.
- (No others — `ServerLevel`, `Block`, `BlockState`, `ChunkAccess` are
  all wrapped above to be safe; we have no evidence they will rename,
  but the cost of pre-emptively wrapping them is one record each and
  the upside is total Mojmap-immunity for the interface.)

For `ResourceLocation` / `Identifier`, the most direct fix is to
**drop the MC type entirely** and return a project-owned record:

```java
public record RTPRegistryKey(String namespace, String path) {
    public String key() { return namespace + ":" + path; }
}
```

Every common-side caller of `blockKey` / `biomeKeyAt` already consumes
the result as a string (the `namespace:path` form is what the upstream
`rtp-core` config keys use). This eliminates the
`ResourceLocation`/`Identifier` rename hazard entirely and shrinks the
adapter's exposure: the adapter constructs the record from whichever
MC type its mapping snapshot uses.

### Refactored `FabricVersionAdapter` shape (illustrative)

```java
public interface FabricVersionAdapter {
    String mcVersion();

    @Nullable RTPRegistryKey blockKey(RTPBlockHandle block);
    @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, BlockPos pos);

    CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz);
    boolean hasChunk(RTPLevelHandle level, int cx, int cz);

    RTPBlockStateHandle airState();

    default CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) { /* … */ }
    default CompletableFuture<Void> releaseTicket(RTPLevelHandle level, int cx, int cz) { /* … */ }
    default void tickRefresh() {}
}
```

Wrapper records sketch:

```java
public record RTPLevelHandle(Object level) {
    public <T> T as(Class<T> type) { return type.cast(level); }
}
public record RTPBlockHandle(Object block) { /* … */ }
public record RTPBlockStateHandle(Object state) { /* … */ }
public record RTPChunkHandle(Object chunk) { /* … */ }
```

Each adapter does a single localised cast on entry:

```java
@Override
public boolean hasChunk(RTPLevelHandle level, int cx, int cz) {
    ServerLevel sl = level.as(ServerLevel.class);
    return sl.getChunkSource().hasChunk(cx, cz);
}
```

`BlockPos` stays Mojmap-direct in the SPI (stable since pre-1.20). If
it ever renames, this ADR's pattern extends to it without further
design work.

### Ratings table (kept for context)

| Option | Effort | Long-term cost | Type safety | Verdict |
|--------|--------|----------------|-------------|---------|
| Per-rename submodule (status quo) | low per release | linear; bytecode bloat | full | rejected |
| **5.a wrapper records (this ADR)** | high one-time | constant | full inside adapters and common | **accepted** |
| 5.b raw `Object` on SPI | medium | constant | none at the seam | rejected |
| Yarn-mappings throughout | medium | yarn renames too | full but vs. yarn | rejected |
| Reflection on intermediary names | medium | intermediary numbers also drift | none | rejected |

## Consequences

- **Positive.**
  - SPI is immune to Mojmap renames on the wrapped types. Adding
    `rtp-fabric-v1_21_R11` (and any 26.1+ submodule) becomes a
    same-shape port — copy R5, swap the casts, ship. No common-side
    recompilation required.
  - `ResourceLocation` / `Identifier` rename eliminated at the SPI
    surface; common-side callers consume `RTPRegistryKey` /
    `String key()` directly, matching the `rtp-core` config-key form.
  - Reduces the per-MC-release maintenance multiplier and supports
    the *Current Development Focus* goal of stabilising Fabric across
    1.20.x → 1.21.x → 26.1.
- **Negative.**
  - Cross-cutting refactor of every adapter method (low per-method
    cost, but four submodules × ~7 methods + common-side callers).
  - One record allocation per SPI call. All call sites are off the
    server tick hot loop or are immediately escape-analysed (the
    wrapper escapes only into a local `as()` cast).
  - Slight indirection at the seam: `level.as(ServerLevel.class)`
    rather than the bare `ServerLevel` parameter.
- **Neutral.**
  - Per-version submodules remain — this ADR does not reverse
    `rtp-fabric-ADR-001`. We still need one submodule per MC release
    that changes the **internal** API surface (e.g.
    `addRegionTicket` → `addTicketWithRadius` from R1 → R5, or the
    `TicketType` record-shape change in R11). Those changes live
    inside the adapter and never reach common.
  - No behavioural change. S-001…S-007 invariants are untouched.

## Implementation plan (for the post-approval session)

Mirrors steps 11–14 of `CHECKLIST-fabric-1.21.11-multiversion.md`:

1. Add wrapper records (`RTPLevelHandle`, `RTPBlockHandle`,
   `RTPBlockStateHandle`, `RTPChunkHandle`, `RTPRegistryKey`) under
   `rtp-fabric-common/src/main/java/.../fabric/version/`.
2. Refactor `FabricVersionAdapter` signatures.
3. Update common-side callers (`FabricRTPWorld`, biome/safety
   predicates, registry callers) to construct/consume wrappers.
4. Migrate `V1_20_R1`, `V1_21_R1`, `V1_21_R5` adapters method-by-method.
   Compile + run existing per-version tests on each.
5. Port `V1_21_R11FabricVersionAdapter` to the new interface; re-enable
   its `settings.gradle` include and `rtp-plugin/build.gradle` runtime
   entry; remove the rationale comments.
6. Smoke-test on a real 1.21.11 Fabric server (kept-cache cycle +
   anvil-prefilter cycle, mirroring `rtp-fabric-ADR-005`'s template).
7. Add a `LESSONS_LEARNED.md` entry: *Mojang renames Mojmap symbols
   across 1.21.x point releases; project-dependency JARs are not
   Loom-auto-remapped per consumer.*
8. Delete `docs/dev/scratch/CHECKLIST-fabric-1.21.11-multiversion.md`.

## Verification

- `:rtp-fabric:rtp-fabric-common:compileJava` BUILD SUCCESSFUL after
  the SPI swap.
- `:rtp-fabric:rtp-fabric-v1_20_R1:compileJava`,
  `:rtp-fabric:rtp-fabric-v1_21_R1:compileJava`,
  `:rtp-fabric:rtp-fabric-v1_21_R5:compileJava` BUILD SUCCESSFUL with
  the migrated adapters.
- `:rtp-fabric:rtp-fabric-v1_21_R11:compileJava` BUILD SUCCESSFUL once
  re-included — the compile failure that is the forcing function for
  this ADR no longer reproduces.
- All existing `rtp-fabric` tests stay green; no new behavioural
  tests required because the refactor is shape-only.
- Operator-side smoke test: kept-cache and anvil-prefilter cycles
  green on a 1.21.11 Fabric server (mirroring the template from
  `rtp-fabric-ADR-005`).

## References

- `rtp-fabric-ADR-001-multiversion-submodule-layout.md` — establishes
  the per-version submodule pattern this ADR refines.
- `rtp-fabric-ADR-003`, `-004`, `-005`, `-006` — prior ticket-flow
  fixes whose adapter implementations will be migrated to the new
  interface shape unchanged in behaviour.
- `docs/dev/scratch/CHECKLIST-fabric-1.21.11-multiversion.md` — full
  session brain-dump, javap evidence, and option ranking. To be
  deleted on completion of the implementation plan.
- `docs/dev/MULTI_PLATFORM_PLAN.md` — Fabric phase status; this ADR
  unblocks 1.21.11 support.
- `.junie/AGENTS.md` *Architecture Boundaries* and *Propose Before
  Implementation (Rule D-005)* — the gate this ADR satisfies.
