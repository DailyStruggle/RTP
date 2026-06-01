# rtp-fabric-ADR-010 — Typed block-tag snapshot via per-version SPI (reflection demoted to fallback)

- **Status:** Accepted (2026-05-10)
- **Scope:** `rtp-fabric` (all per-version submodules + `rtp-fabric-common`)
- **Supersedes:** none. Refines the reflective fallback established by the 2026-05-09 / 2026-05-10 patches inside `FabricServerAccessor.buildBlockTagSnapshot()`.
- **Related:** `rtp-fabric-ADR-001` (multiversion submodule layout), `rtp-fabric-ADR-007` (Mojmap name decoupling), `rtp-fabric-ADR-009` (obf/unobf common split), `effects-api-ADR-006` (analogous Fabric obf/unobf split for effects).

## Context

`FabricServerAccessor.buildBlockTagSnapshot()` produces the runtime's `minecraft:block` tag → block-id snapshot consumed by `SafetyTokenExpander` to flatten `#tag` tokens in `safety.airBlocks` / `safety.unsafeBlocks`. Until this ADR the only implementation was a reflective walk over `BuiltInRegistries.BLOCK`, with three layered fallbacks added inside a 48-hour window:

1. 2026-05-09 — added an intermediary-only entry probe (`net.minecraft.class_7923`) for production 1.21.x runtimes where `net.minecraft.core.registries.BuiltInRegistries` does not resolve.
2. 2026-05-10 (a) — added Mojang ↔ intermediary method-name fallback for `getKey` / `builtInRegistryHolder` / `tags` / `location` / `getNamespace` / `getPath` (`method_10221`, `method_40142`, `method_40228`, `method_29177`, `method_12836`, `method_12832`).
3. 2026-05-10 (b) — added a signature-based last-resort recovery for `ResourceLocation`'s zero-arg `String` getters when neither known name resolves.

Each patch corrected a real production warning (and POTENTIAL_BUGS entries 2026-05-09 and the resolved follow-up confirm them), but the cumulative shape is fragile: every new Minecraft version is a fresh game of name-matching whack-a-mole, the warning was leaking obfuscated class names to operators, and the path runs at every `/rtp reload`.

The user observation that triggered this ADR — *"shouldn't we be doing per-server implementations instead of reflection?"* — is correct. The Fabric platform already runs a per-version SPI (`FabricVersionAdapter`) used for `blockKey`, `biomeKeyAt`, `requestFullChunkAsync`, ticket management, and effects dispatch. The block-tag snapshot was the only registry-walking operation still routed through reflection in `rtp-fabric-common`.

## Decision

Add a typed SPI method:

```java
default @Nullable Map<String, Set<String>> snapshotBlockTags() {
    return null;
}
```

to `FabricVersionAdapter`. Each per-version submodule that compiles against Loom-mapped Minecraft types implements the method with a direct, typed walk of `BuiltInRegistries.BLOCK`, inverting each block's `builtInRegistryHolder().tags()` stream into the `namespace:path → upper-case "namespace:path"` multimap shape documented on `RTPServerAccessor.blockTagSnapshot()`. No reflection on the typed path.

`FabricServerAccessor.buildBlockTagSnapshot()` consults the registered adapter via `FabricVersionAdapterRegistry.peek()`. If the adapter returns a non-null result (including an empty map — meaning "registry walked, but tag bindings not yet attached"), that result is returned. If the adapter returns `null` or throws, the existing reflective walk runs as a last-resort fallback. The fallback path is preserved verbatim — no behavioural change for runtimes without a typed adapter (e.g. the `rtp-fabric-v26_1_R1` deobfuscated bring-up stub).

### Implementations

| Module | MC range | Identifier type | Notes |
| --- | --- | --- | --- |
| `rtp-fabric-v1_21_R1` | 1.21.0 – 1.21.4 | `ResourceLocation` | Reference implementation. |
| `rtp-fabric-v1_21_R5` | 1.21.5 – 1.21.10 | `ResourceLocation` | Identical body to R1 modulo unrelated chunk-ticket APIs. |
| `rtp-fabric-v1_21_R11` | 1.21.11+ | `Identifier` | Mojang renamed `ResourceLocation` → `Identifier` in 1.21.11; the typed body uses the new name and Loom remaps it against the running mapping at compile time. |
| `rtp-fabric-v1_20_R1` | 1.20.x | n/a | Inherits SPI default → reflective fallback. May be promoted to typed in a future revision; not required for current production runtimes. |
| `rtp-fabric-v26_1_R1` | 26.1.x | n/a | Stub adapter; inherits SPI default → reflective fallback (which already handles the obf/unobf 26.1 surface). |

## Why per-version > reflection here

- **Compile-time verification.** Loom resolves `BuiltInRegistries.BLOCK`, `Registry.getKey`, `Block.builtInRegistryHolder()`, `Holder.Reference.tags()`, `TagKey.location()`, and `ResourceLocation`/`Identifier`'s namespace/path getters against that version's mappings. Mojang renames break the build, not production.
- **No method-name drift.** A typed call site needs no Mojang-vs-intermediary fallback, no signature-based last-resort, no `findMethodAny` helper. The three reflective patches added in 48 hours collapse to a single typed loop.
- **Performance.** Typed iteration replaces ~5 `MethodHandle.invoke` calls per block per `/rtp reload`. Not on the teleport hot path, but a free win.
- **Reuse of existing SPI infrastructure.** `FabricVersionAdapter` and `FabricVersionAdapterRegistry` already split per-version concerns (chunk tickets, biome lookup, chunk-future dispatch). This is the same pattern, applied to one more registry-walking operation.

## Why reflection still has a role (smaller)

- **Bootstrap ordering.** `FabricServerAccessor.buildBlockTagSnapshot()` can be invoked before `FabricVersionAdapterRegistry` has a registered adapter on certain code paths (mod entry vs. server-started). The reflective fallback covers that gap with no behavioural regression.
- **Future MC versions before a per-version module ships.** When 1.21.12 (or 1.22) releases before we cut a matching `rtp-fabric-v1_21_R12` module, the reflective walk keeps `#tag` token expansion *working* (degraded, but working) instead of *broken*. Reflection is the safety net, not the primary path on versions we ship a module for.
- **`v26_1_R1` deobf bring-up.** The 26.1 stub adapter intentionally does not implement `snapshotBlockTags()`; the reflective path in `FabricServerAccessor` (with its three layered fallbacks for the 26.1 obf surface) continues to serve that runtime.

## Failure-mode contract

The SPI return value distinguishes three cases:

| Return | Meaning | Caller behaviour |
| --- | --- | --- |
| `null` | Adapter cannot resolve registry on this runtime. | Fall through to reflective walk. |
| Non-empty map | Typed walk succeeded. | Use directly. |
| Empty map | Registry reachable but tag bindings not yet attached (data-pack load not finished). | Use directly. `SafetyTokenExpander` preserves `#tag` tokens for a later retry — the reflective fallback would yield the same empty result for the same reason, so falling through is wasted work. |

A `Throwable` from the adapter is treated identically to a `null` return: log at `Level.FINE` and fall through to reflection.

## Risks / trade-offs

- **Per-module cost.** ~50 lines per per-version submodule. Mitigated by R1 and R5 sharing an identical body, and R11 differing only in the `ResourceLocation` → `Identifier` rename.
- **Behaviour parity with the reflective fallback.** The typed walk produces the same shape (`namespace:path → upper-case "namespace:path"`) and uppercases via `Identifier#toString()` (`"namespace:path"`) → `toUpperCase()`, matching the reflective path exactly. Verified by the existing `SafetyTokenExpander` consumers continuing to compile and pass.
- **No S-00x impact.** Block-tag expansion is a config-time / `/rtp reload` operation, not on the teleport hot path. S-005 is not engaged.
- **CHANGELOG.** This change lands inside the same `3.0.0-beta.2` unreleased cycle as the initial Fabric block-tag support and the prior reflective patches. Per CHANGELOG hygiene, intra-cycle implementation churn is not described separately; the net delta from `v3.0.0-beta.1` is "Fabric `#tag` token expansion works", which beta.1 already lacked entirely.

## Out of scope

- Promoting `rtp-fabric-v1_20_R1` to a typed implementation. Deferred — current production reports are 1.21.x; 1.20 inherits the reflective fallback.
- Implementing `snapshotBlockTags()` on the `rtp-fabric-v26_1_R1` deobfuscated stub. Deferred until the 26.1 mappings stabilise (`rtp-fabric-ADR-009`).
- Adding a `ServerLifecycleEvents.SERVER_STARTED` rebuild hook to address the *separate* "may not be populated yet" timing warning. That symptom is now distinguishable from a registry-class-missing failure (typed path will return an empty map vs. the reflective path's WARNING spam) and warrants its own follow-up entry in `POTENTIAL_BUGS.md`.
