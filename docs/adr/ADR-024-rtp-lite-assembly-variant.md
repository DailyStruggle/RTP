# ADR-024 — RTP-lite Assembly Variant

**Status:** Accepted (amended 2026-05-11 — language options + claim-plugin integrations in scope for lite)
**Date:** 2026-04-30

## 2026-05-11(b) amendment — language options in scope for lite

The original decision below scoped lite as **English-only** — `LanguageBootstrap`,
`LanguageCmd`, `lang/**`, and `language.yml` were dropped from the lite assembly,
and the locale-aware ConfigParser path (ADR-020) was unreachable on lite.
Operators wanting non-English message strings, or wanting to use `/rtp lang` to
hot-switch locales, were routed to the full edition. As of 2026-05-11 that scope
is widened: **the multilingual bootstrap (ADR-020) ships in lite**.

Concretely:

- `lang/**` and `language.yml` ship in the lite jar.
- `LanguageBootstrap.resolve(pluginDirectory)` is invoked unconditionally from
  `Configs#reloadConfigs()`; no edition-specific bootstrap call is required, and
  the lite bootstrap (`RTPBukkitLitePlugin.onEnable`) inherits the behavior the
  moment the resources are on the classpath.
- `LanguageCmd` (the user-facing `/rtp lang` subcommand) lives in `rtp-core` and
  is registered through the shared command tree, so it is available on lite
  identically to Pro.
- The lite `messages.yml` is no longer trimmed: lite ships the full
  `messages.yml` from `rtp-plugin/src/main/resources`. Lite-irrelevant keys
  (e.g. `notEnoughMoney`, PAPI status, SQL-persistence messages) are simply
  unreachable at runtime because the corresponding subsystems are not wired —
  the keys' presence in the file is harmless and matches the "lite contains the
  featureset, simply not configured by default" stance.
- The `liteJarStructureCheck` audit no longer forbids `lang/**` or
  `language.yml`.

Bullets below referring to "Multilingual support (ADR-020)" as a lite drop,
and to `lang/**` / `language.yml` / trimmed `messages.yml` as packaging drops,
are superseded by this amendment. The remainder of the ADR (SQL/Redis drops,
Folia drop, login cache, visitor mode, economy/Vault, etc.) still applies.

---

## 2026-05-11 amendment — claim-plugin integrations in scope for lite

The original decision below scoped lite as having no claim-plugin softdepend
integrations (ADR-019) — operators wanting GriefDefender, GriefPrevention,
Lands, WorldGuard, Towny, Factions, HuskTowns, or RedProtect compatibility were
routed to the full edition. As of 2026-05-11 that scope is widened: **the
bundled claim-plugin integrations ship in lite**. Pro remains the superset
target for large-scale deployments (proxy networks, Folia regionized servers,
SQL/Redis persistence); claim-protection support is a baseline operator
expectation on the small Spigot/Paper servers that lite primarily serves, and
withholding it pushed those operators to Pro for an integration that has zero
runtime cost when no claim plugin is installed.

Concretely:

- `ClaimIntegrations.setup` is invoked from `RTPBukkitLitePlugin.onEnable`
  (deferred tick+1, mirroring the full bootstrap).
- The lite `plugin.yml` declares `softdepend: [ GriefDefender, GriefPrevention,
  Towny, HuskTowns, Factions, Lands, RedProtect, WorldGuard ]`.
- `integrations.yml` ships in the lite jar so `ClaimIntegrations#buildParser`
  can load its defaults from the classpath.
- Vault/economy wiring stays Pro-only — lite still ships no `economy.yml` and
  the Vault soft-dep is not declared in the lite descriptor.

Bullets below referring to "claim-plugin softdepend integrations" as a lite
drop, and to `integrations.yml` as a packaging drop, are superseded by this
amendment. The remainder of the ADR (SQL/Redis drops, Folia drop, locale,
login cache, visitor mode, etc.) still applies.

---

## 2026-05-07 amendment — Fabric in scope for lite

The original decision below scoped lite as **Spigot+Paper only**, with Folia and
Fabric routed to the full edition. As of 2026-05-07 that scope is widened:
**Fabric is supported in lite**. Folia remains full-only.

Concretely:

- `RTPFabricMod` and `rtp-fabric-common` classes (`io/github/dailystruggle/rtp/fabric/**`)
  ship in the lite jar.
- `fabric.mod.json` ships in the lite jar so Fabric Loader discovers
  `RTPFabricMod` as the entrypoint.
- **JDBC drivers stay stripped from lite** (no H2, SQLite, MySQL, or PostgreSQL).
  Fabric+lite operators land on `FabricDatabaseHandler.setupDatabase`'s flat-file
  YAML fallback (a loud warning is logged) — consistent with lite's "no SQL"
  stance below.
- The `liteJarStructureCheck` audit no longer forbids `io/github/dailystruggle/rtp/fabric/**`
  or `fabric.mod.json`.
- Folia (`io/github/dailystruggle/rtp/folia/**`) remains forbidden in lite —
  Folia operators continue to use the full edition.

The rest of the original ADR (drops, alternatives, consequences) still applies
to the lite edition; only the "Spigot+Paper only" framing is superseded.

---

## Context

The full RTP plugin bundles features that a sizeable share of operators do not use: SQL/Redis persistence (H2, SQLite, MySQL, PostgreSQL, Jedis), a Folia adapter, the anvil pre-filter (ADR-016), block-tag / state-predicate safety parsing (ADR-017), the login reserve cache (ADR-023), claim-plugin integrations (ADR-019), economy / Vault, multilingual support (ADR-020), bundled documentation, and a number of advanced `performance.yml` toggles (`visitorEnabled`, `loginCacheEnabled`, `loginCacheCap`, `effectParsing`, `onEventParsing`).

These features carry two costs:

1. **JAR size.** Bundled SQL drivers, Jedis, the localized `lang/**` tree, and the in-JAR `docs/**` copy account for a non-trivial fraction of the shaded artifact.
2. **Support load.** Each toggle, integration, and locale-aware migration is a documented source of operator questions and edge cases (`docs/dev/LESSONS_LEARNED.md`, `docs/admin/RUNBOOK.md`, `docs/adr/ADR-020`, `docs/adr/ADR-023`).

A second audience — small servers that want random-teleport behavior with the minimum possible operator-facing complexity — is poorly served by the full JAR.

## Decision

Ship two build variants from the same source tree, both produced on every `:rtp-plugin:assemble` run:

- **RTP** (lite, default) — produced by `:rtp-plugin:shadowLiteJar` as the unclassified `RTP-<version>.jar`.
- **RTP-Pro** (full) — produced by the existing `:rtp-plugin:shadowJar` as `RTP-Pro-<version>.jar`.

The lite artifact takes the unclassified default name because lite is the recommended starting point for new operators; Pro is the opt-in upgrade for users who need SQL persistence, Folia, claim integrations, multilingual support, etc. Both Bukkit `plugin.yml` descriptors keep `name: RTP` so the plugin data folder is interchangeable across editions.

RTP-lite:

1. **Build mechanism.** A new Gradle source set `rtp-plugin/src/lite/{java,resources}` with its own bootstrap class `io.github.dailystruggle.rtp.bukkit.lite.RTPBukkitLitePlugin` and its own `plugin.yml`. ShadowJar exclude rules physically remove lite-droppable classes and resources from the lite artifact. No Java-level conditional compilation.
2. **Drops, runtime.**
   - Persistent storage backends: `H2DatabaseAccessor`, `SQLiteDatabaseAccessor`, `MySQLDatabaseAccessor`, `PostgreSQLDatabaseAccessor`, `AbstractSQLDatabaseAccessor`, `RedisManager`, and the corresponding shaded driver jars (`com.h2database`, `org.xerial:sqlite-jdbc`, `mysql`, `org.postgresql`, `redis.clients`). Lite uses `YamlFileDatabase` (or an in-memory no-op accessor) only.
   - Shutdown-flush lifecycle (`docs/architecture/10`).
   - Tags module (`rtp-tags`).
   - Folia adapter (`platforms/rtp-folia/**`). Lite is Spigot+Paper only.
   - Economy / Vault hook + `economy.yml`.
   - Login reserve cache (ADR-023): `LoginCacheTask`, `RegionQueueManager.enableLoginCache`/`disableLoginCache`, the join-prime hook in `OnEventTeleports`, and `PerformanceKeys.loginCacheEnabled` / `loginCacheCap`.
   - Visitor / observation mode: `PerformanceKeys.visitorEnabled` and the `Region.isObservationalModeEnabled` branch.
   - `effectParsing` startup permission parse (`BukkitEffectsHandler` permission-tree build).
   - `onEventParsing` startup permission parse — fully removed, with no replacement boolean. Lite does not ship a `firstJoinTeleport` toggle. Operators who need first-join teleport behavior use the full edition.
   - Multilingual support (ADR-020): `LanguageBootstrap`, `LanguageCmd`, `lang/**`, `language.yml`, `ConfigParser.detectAndPreserveLocaleMismatch` invocation. Locale hardcoded to English.
   - Claim-plugin softdepend integrations (ADR-019): GriefDefender, GriefPrevention, Lands, WorldGuard, Towny, Factions, HuskTowns, RedProtect.
3. **Drops, packaging.**
   - `lang/**`, `language.yml`, `economy.yml`, `integrations.yml`, `logging.yml` are not shipped.
   - `docs/**` and root markdown (`README.md`, `CHANGELOG.md`, `CONTRIBUTING.md`, `SUPPORT.md`, etc.) are not shipped. `LICENSE` is retained.
   - `performance.yml` is shipped trimmed: no `visitorEnabled`, `loginCacheEnabled`, `loginCacheCap`, `effectParsing`, `onEventParsing`.
   - `safety.yml` is shipped with a flat material allow/deny list (no tag / state-predicate sections).
   - `messages.yml` is shipped trimmed to the keys actually consumed by lite (~6 entries).
4. **Kept.**
   - `rtp-api` surface intact (third-party addons that hook the API still work; they are responsible for not depending on lite-excluded subsystems).
   - `rtp-core` selection: Archimedean spiral mapping (ADR-001), async chunk acquisition, `MemoryTracker` lifecycle, the S-005 stale-chunk guard (`ReqRtpS004NullChunkAttributionTest`-protected logic), and **biomeRecall** as the showcase feature.
   - **Anvil pre-filter (`rtp-anvil`).** Retained in lite — needed to keep selection performance demonstrable on cold-chunk worlds. Iris compatibility benefits directly.
   - **Scan / spatial memory subsystem** (`MemoryShape`, `/rtp scan` command tree). Retained in lite — required for performance comparison against the full edition; removing it would conflate "lite" with "slow" and defeat A/B benchmarking.
   - **Full selection surface.** All shapes (`Circle`, `Square`, `Rectangle`, `Circle_Normal`, `Square_Normal`), all selection modes, and all vertical adjustors remain available in lite. Required so operators can A/B identical region configs across lite and full.
   - **Pregen queue with all tunables.** Per-region queue + `cache cap` + queue tuning keys remain in lite, identical to full.
   - `commands-api` + Brigadier bridge (commands-api-ADR-001).
   - `effects-api` runtime, without the per-permission startup parse.
   - **Spigot and Paper adapters** across all currently supported NMS revisions, to maintain parity with RTP v2's Paper-native chunk-load path. Lite does not ship the Folia adapter; Folia operators must use the full edition. PaperLib is **not** reintroduced (ADR-005 still applies — its sync fallback violates S-005). Paper-native async chunk loading is reached through the existing Paper adapter, not through PaperLib.
   - bStats, configured with a distinct `pluginId` so lite installs are tracked independently.
5. **Bootstrap.** `RTPBukkitLitePlugin.onEnable()` mirrors the surviving steps of `RTPBukkitPlugin.onEnable()` — server-model resolve (Spigot or Paper accessor; no Folia branch), accessor wiring, `RTP` construction, region / queue / `MemoryTracker` setup, anvil pre-filter wiring, scan-task wiring, command registration (including `/rtp scan`), `setupBukkitEvents` — and **omits** every step listed under "Drops, runtime" above. It does not call `LanguageBootstrap`, does not probe `org.sqlite.JDBC`, does not branch on `isFolia()`, does not call `initLoginReserveCache()`, and does not invoke `setupIntegrations()` (claim plugin wiring). The `isPaper()` branch is retained so Paper servers use Paper's native async chunk API, matching v2 behavior.

### Inactive prior ADRs in the lite assembly

The following ADRs remain authoritative for the **full** edition but describe subsystems that are absent from the lite artifact:

- ADR-002 (H2/SQLite over flat-file).
- ADR-017 (Block tags and state predicates in safety lists).
- ADR-019 (Claim plugin integrations folded into plugin).
- ADR-020 (Language bootstrap and locale-aware ConfigParser).
- ADR-022 (Single-JAR multi-loader packaging — Folia/Fabric branches).
- ADR-023 (Login reserve cache).

ADR-024 is **additive**, not a superseder: each prior ADR continues to govern the full edition.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Java-level conditional compilation (annotation-processor stripping, `-ALite=true`) | Java has no preprocessor; APT-based bytecode stripping is fragile, breaks IDE call-site verification, and obscures which classes ship in which artifact. |
| Single shaded JAR with all features behind runtime config flags | Does not reduce JAR size or driver footprint, and does not reduce the operator-facing surface (the toggles themselves generate support load). |
| Two source sets `src/main/java` + `src/lite/java` *with the lite source set duplicating the bootstrap and replacing main entirely* | Adopted in the form below: lite ships a separate bootstrap class (`RTPBukkitLitePlugin`) only, while reusing `rtp-core` and the platform adapters. The maintenance tax is bounded because the lite bootstrap is small and intentionally divergent. |
| Separate Gradle subproject `rtp-plugin-lite` consuming a subset of dependencies | Doubles the build configuration and CI matrix without giving anything that an additional shadow task plus a source set does not already give, since `rtp-plugin/build.gradle` already demonstrates per-task configuration filtering (the Loom `minecraft` configuration strip). |
| Maintain a separate `lite` git branch | Rejected — long-lived divergent branches drift; defeats the purpose of "research which features are candidates to drop". |
| Java 9 module system (`module-info.java` with `requires static`) | Bukkit's classloader history makes JPMS adoption impractical for plugins. |
| Reintroduce PaperLib for the Spigot adapter to bridge Paper's async chunk API | Rejected — ADR-005 removed PaperLib precisely because its sync fallback on Spigot violates S-005. Bundling the Paper adapter directly is the parity-preserving option. |
| Lite as Spigot-only, Paper operators run the Spigot adapter | Rejected — confounds lite-vs-full benchmarks on Paper hosts (Paper-native chunk-load throughput would only appear in the full JAR), and breaks v2 parity for Paper users. |

## Consequences

- **Positive.**
  - Lite users cannot mis-configure features that do not exist in the lite JAR. Whole categories of `LESSONS_LEARNED.md` entries (database/shutdown-flush, login-cache promotion races, locale-switch reload mismatches) become unreachable on lite.
  - Smaller artifact: removal of H2/SQLite/MySQL/Postgres/Jedis drivers, the `lang/**` tree, the bundled `docs/**` copy, the Folia adapter, and `rtp-tags` outputs visibly shrinks the JAR. The anvil module and the Paper adapter are retained.
  - **Performance comparability.** Because lite keeps the anvil pre-filter, the scan/spatial-memory subsystem, the full selection surface (shapes / modes / adjustors / pregen knobs), and both the Spigot and Paper adapters, lite-vs-full benchmarks isolate the subsystems lite actually drops (persistence, Folia regionized scheduling, claim hooks, i18n, login cache, visitor mode, permission parsing) rather than conflating them with raw selection throughput or Paper-vs-Spigot chunk-load differences. Paper parity with v2 is preserved.
  - bStats split (distinct `pluginId`) gives empirical evidence of lite-vs-full adoption, informing future scope decisions.
  - The full edition retains every existing feature; no operator is forced to migrate.
- **Negative / Trade-offs.**
  - Two bootstrap classes (`RTPBukkitPlugin`, `RTPBukkitLitePlugin`) must be kept in sync for the steps both editions perform. Mitigation: the shared steps live in helper methods on the bootstrap or in `rtp-core`; both classes call the same helpers. Genuine divergence (login cache, language bootstrap, integrations) belongs only to the full bootstrap and never appears in lite.
  - CI matrix grows: lite needs its own assembly + smoke test. Mitigation: a single `liteJarStructureTest` task that asserts the produced lite JAR contains no classes / resources from the lite-exclude list (`tags/`, `folia/`, `H2*`, `SQLite*`, `MySQL*`, `PostgreSQL*`, `AbstractSQLDatabaseAccessor`, `RedisManager`, `LoginCacheTask`, `lang/`, `docs/`, `economy.yml`, `integrations.yml`, `logging.yml`, `language.yml`). The anvil package (`rtp-anvil`) and the Paper adapter (`platforms/rtp-paper/**`) are allowed in lite.
  - Hidden hard-imports in `rtp-core` to lite-excluded classes would cause `NoClassDefFoundError` at runtime in lite. Mitigation: a pre-flight audit (`search_project` over `rtp-core` for `import io.github.dailystruggle.rtp.tags`, `H2DatabaseAccessor`, `RedisManager`, `import dev.folia`) is required before this ADR is accepted; any hits other than legitimate strategy-pattern wiring are blocking refactors. Imports of `io.github.dailystruggle.rtp.anvil`, `io.papermc`, and `com.destroystokyo.paper` are explicitly allowed because the Paper adapter and anvil module ship in lite.
  - Lite cannot run on Folia; operators on Folia must use the full JAR. Paper operators get full Paper-native behavior via the bundled Paper adapter, matching v2 parity.
  - Iris compatibility on lite is functionally equivalent to full, because the anvil pre-filter is retained in lite.

## References

- `rtp-plugin/build.gradle` — existing ShadowJar configuration with per-configuration filtering (Loom `minecraft` strip) used as the model for `shadowLiteJar`.
- `rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/RTPBukkitPlugin.java` — full bootstrap; lite bootstrap mirrors only the surviving steps.
- ADR-002, ADR-005, ADR-016, ADR-017, ADR-019, ADR-020, ADR-022, ADR-023 — prior ADRs whose subsystems are excluded from lite (or, in the case of ADR-005 / ADR-016, whose conclusions are reaffirmed for lite).
- `docs/dev/LESSONS_LEARNED.md` — entries on database/shutdown-flush, login-cache promotion, and locale-switch reload that motivated the lite scope.
- `docs/dev/REQUIREMENTS.md` §3 — S-001…S-007 prohibitions; lite must satisfy all of them, identically to full.
