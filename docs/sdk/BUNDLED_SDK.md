# Bundled SDK & Addon APIs (`LeafRTP`)

`LeafRTP` consumes the multi-platform LeafCraft SDK (`io.github.dailystruggle:<module>:3.3.2`) as published Maven artifacts from `LeafCraftDev/MC`, shades the runtime classes into `LeafRTP`, and re-exports them (`api` scope) so contributors and addon developers can use every module below without adding extra runtime dependencies.

- **IDE Javadoc & Sources:** Every published `io.github.dailystruggle:<module>` artifact includes `-sources.jar`, `-javadoc.jar`, and embedded Markdown documentation under `META-INF/leaf-sdk/<module>/`.
- **Local Repository Guides:** Every SDK module's `README.md` and ADRs are projected into [`docs/sdk/`](README.md) in this repository for immediate browsing and code search.

## Bundled SDK Modules in This Repository

| Module | Maven Central Coordinate | Bundled & Resupplied | Documented Purpose | Guide |
|---|---|---|---|---|
| `server-api` | `io.github.dailystruggle:server-api:3.3.2` | Yes (`api` + shaded) | Platform-neutral scheduling (`TaskScheduler`), Folia region checks, version gates, and `ServerAccessor` capability SPIs. | [`docs/sdk/server-api/README.md`](server-api/README.md) |
| `commands-api` | `io.github.dailystruggle:commands-api:3.3.2` | Yes (`api` + shaded) | Permission-filtered command tree (`TreeCommand`), `key=value` parameters, tick-budgeted execution, and Brigadier bridge. | [`docs/sdk/commands-api/README.md`](commands-api/README.md) |
| `menu-api` | `io.github.dailystruggle:menu-api:3.3.2` | Yes (`api` + shaded) | Declarative multi-page chest/book menus (`MenuDescriptor`), click actions, session cart, and anvil text prompts. | [`docs/sdk/menu-api/README.md`](menu-api/README.md) |
| `effects-api` | `io.github.dailystruggle:effects-api:3.3.2` | Yes (`api` + shaded) | Prototype-cloned visual/audio effects (`Effect`, `EffectFactory`), permission builders, holograms, and wireframe particles. | [`docs/sdk/effects-api/README.md`](effects-api/README.md) |
| `yaml-api` | `io.github.dailystruggle:yaml-api:3.3.2` | Yes (`api` + shaded) | Zero-dependency, block-comment-preserving YAML parser and atomic writer (`RtpYamlConfig`). | [`docs/sdk/yaml-api/README.md`](yaml-api/README.md) |
| `units-api` | `io.github.dailystruggle:units-api:3.3.2` | Yes (`api` + shaded) | Dependency-free temporal (`20t`, `1d12h`), spatial (`256c`, `2km`), and data-size (`256MB`) parsers. | [`docs/sdk/units-api/README.md`](units-api/README.md) |
| `bit-api` | `io.github.dailystruggle:bit-api:3.3.2` | Yes (`api` + shaded) | Plugin-agnostic Power-On, Continuous, and Initiated Built-In Test (`PBIT` / `CBIT` / `IBIT`) framework (`BitRunner`, `BitCodeRegistry`). | [`docs/sdk/bit-api/README.md`](bit-api/README.md) |
| `config-bit-api` | `io.github.dailystruggle:config-bit-api:3.3.2` | Yes (`api` + shaded) | Declarative YAML `@type` / `@range` / `@options` comment-directive schema validation (`DirectiveValidator`, `MessageKeyParity`). | [`docs/sdk/config-bit-api/README.md`](config-bit-api/README.md) |
| `predict-api` | `io.github.dailystruggle:predict-api:3.3.2` | Yes (`api` + shaded) | Online statistical estimators (`EwmaEstimator`, `BernoulliEstimator`), `AimdLimiter`, `BudgetPacer`, `WaterFillingAllocator`, and `UnitCurve`. | [`docs/sdk/predict-api/README.md`](predict-api/README.md) |
| `metrics-api` | `io.github.dailystruggle:metrics-api:3.3.2` | Yes (`api` + shaded) | Platform-portable TPS, MSPT, tick-budget utilization, heap, and Folia regional telemetry SPI (`Metrics`, `MetricsSnapshot`). | [`docs/sdk/metrics-api/README.md`](metrics-api/README.md) |
| `bstats-api` | `io.github.dailystruggle:bstats-api:3.3.2` | Yes (`api` + shaded) | Dependency-free bStats v2 client (`BStatsService`, `SimplePie`, `AdvancedPie`). | [`docs/sdk/bstats-api/README.md`](bstats-api/README.md) |
| `database-api` | `io.github.dailystruggle:database-api:3.3.2` | Yes (`api` + shaded) | Zero-dependency JDBC connection pool (`MiniConnectionPool`), SQL dialect UPSERT builder, and Redis RESP2 client/pub-sub. | [`docs/sdk/database-api/README.md`](database-api/README.md) |
| `anvil-api` | `io.github.dailystruggle:anvil-api:3.3.2` | Yes (`api` + shaded) | Off-tick read-only `.mca` / `.linear` region-file decoder (`RegionFileReader`, `AnvilReader`) and selective NBT column probes. | [`docs/sdk/anvil-api/README.md`](anvil-api/README.md) |
| `tags-api` | `io.github.dailystruggle:tags-api:3.3.2` | Yes (`api` + shaded) | Dependency-free Minecraft data-pack block-tag JSON loader (`TagResolver`) and block-state predicate compiler (`CompiledUnsafeSet`). | [`docs/sdk/tags-api/README.md`](tags-api/README.md) |
| `schematics-api` | `io.github.dailystruggle:schematics-api:3.3.2` | Yes (`api` + shaded) | Sponge Schematic v2/v3 (`.schem`) and Litematica (`.litematic`) GZIP-NBT decoder and 3D placement planner. | [`docs/sdk/schematics-api/README.md`](schematics-api/README.md) |
| `maps-api` | `io.github.dailystruggle:maps-api:3.3.2` | Yes (`api` + shaded) | Platform-neutral 128x128 in-game cartography and high-resolution PNG/BMP/Web-tile rendering SPI (`MapCanvas`, `HeatmapRenderer`). | [`docs/sdk/maps-api/README.md`](maps-api/README.md) |

## Full SDK Catalog

See [`docs/sdk/README.md`](README.md) for the complete 5-layer architecture overview across all 16 SDK modules.
