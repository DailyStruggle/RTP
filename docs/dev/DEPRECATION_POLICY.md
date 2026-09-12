# Deprecation and API Evolution Policy

> **Scope:** Public API (`rtp-api`, SPI frameworks: `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`), platform carriers, and configuration keys.
> **Related:** ADR-000 (development workflow), ADR-011 (`rtp-api` separate module), ADR-051 (two-tier API extension model), `MULTI_PLATFORM_PLAN.md` (carrier retirement policy).

---

## 1. Principles

1. **Explicit Notice Window:** No public API element, platform carrier, or configuration key shall be removed without prior deprecation notice spanning at least **two minor versions** (or one full major release cycle).
2. **Standard Compiler Annotations:** Every deprecated code element shall carry `@Deprecated(forRemoval = true, since = "<version>")` and a Javadoc `@deprecated` tag documenting the replacement.
3. **Fail-Safe Evolution:** Configuration migrations and carrier retirements shall preserve user data and state with transparent fallbacks and diagnostic warnings before removal.

---

## 2. API Deprecation Lifecycle

```
[ Active / Stable ]
         │
         ▼  (Decision / RFC / ADR)
[ Deprecated ] ─── Annotate @Deprecated(forRemoval = true, since = "X.Y.Z")
         │         Document replacement in Javadoc @deprecated tag
         │         Log deprecation notice in CHANGELOG.md (### Deprecated)
         │
         ▼  (Notice window: minimum 2 minor releases, e.g., X.Y.Z → X.(Y+2).0)
[ Removal ]    ─── Remove bytecode symbol in major bump or scheduled minor release
                   Record removal in CHANGELOG.md (### Removed)
                   Update TRACEABILITY.md and binary compatibility baseline
```

### 2.1 Public API Scope (`rtp-api` and SPI Frameworks)

Public API comprises all public classes, interfaces, and methods in:
- `rtp-api`
- `commands-api`
- `effects-api`
- `maps-api`
- `metrics-api`
- `anvil-api`
- `tags-api`

Internal implementation packages (e.g. `rtp-core`, internal platform carrier adapters `io.github.dailystruggle.rtp.<platform>.v*`) are not public API and may evolve with semantic internal guarantees, but carrier retirements follow the specific carrier lifecycle below.

### 2.2 Requirements for Deprecating an API Symbol

Whenever an API method, field, class, or interface is marked for deprecation:

1. **Annotations:**
   ```java
   @Deprecated(forRemoval = true, since = "3.1.0")
   ```
2. **Javadoc Documentation:**
   The Javadoc block must include a `@deprecated` tag that states:
   - Why the element was deprecated.
   - The exact replacement method or class to migrate to.
   - The planned removal milestone.
   ```java
   /**
    * Resolves chunk availability synchronously.
    *
    * @deprecated Replaced by {@link #getChunkAtAsync(int, int)} to prevent main-thread
    *             chunk loading stalls (S-005). Scheduled for removal in 3.3.0.
    */
   @Deprecated(forRemoval = true, since = "3.1.0")
   CompletableFuture<RTPChunk> getChunkAt(int chunkX, int chunkZ);
   ```
3. **Release Notes:**
   The deprecation must be listed in `CHANGELOG.md` under the release's `### Deprecated` section with migration instructions.

---

## 3. Platform Carrier Retirement Lifecycle

As defined in `MULTI_PLATFORM_PLAN.md` (*Carrier Retirement & Deprecation Policy*):

1. **Carrier Support Window:**
   RTP maintains active carriers for:
   - The three most recent `1.21.x` (or successor current stable) revisions plus the active experimental line (`26.x`).
   - Legacy revisions retaining a non-trivial share of production deployments (e.g. 1.20.1).
2. **Deprecation Notice:**
   - Announce carrier deprecation in `CHANGELOG.md` one minor release ahead of removal.
   - Mark the carrier as `**(deprecated)**` in the platform support matrix (`docs/dev/SUPPORT_MATRIX.md`) and repository README.
3. **Removal:**
   - Drop the carrier submodule (`<platform>-vXX_YY_R1`) from `settings.gradle`.
   - Remove corresponding packaging merges from `rtp-plugin`.
   - Record the removal in `CHANGELOG.md` under `### Removed`.

---

## 4. Configuration Schema Deprecation & Migration

1. **Config Key Renaming & Restructuring:**
   - When a config key moves or is renamed, the parser shall first inspect the old location as a fallback.
   - A single-instance warning is logged on startup (`RTP.log(Level.WARNING, ...)`), advising the operator of the new key path.
   - The on-disk configuration is updated during automatic migration (`CONFIG_LIFECYCLE.md`), rotating the old file to `<file>.yml.old1` while overlaying customized values onto fresh defaults.
2. **Notice Period:**
   - Deprecated configuration keys remain functional via backwards-compatible fallback mapping for at least one major or two minor version cycles before the fallback parser is retired.
