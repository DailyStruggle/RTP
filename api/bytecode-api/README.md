# bytecode-api

Zero-classloading JVM `.class` constant-pool, configuration-key, and causal-graph bytecode introspection library (`leafconfig-ADR-001`, `leaffuzz-ADR-001`, `ADR-125`, `ADR-126`).

## Coordinates

- **Gradle project:** `:bytecode-api`
- **Maven coordinate:** `io.github.dailystruggle:bytecode-api`
- **Package root:** `io.github.dailystruggle.bytecode`
- **Java baseline:** Java 17 (classfile 61)
- **Runtime dependencies:** `:yaml-api`, `:server-api`, `:config-bit-api` (zero `rtp-core`, `rtp-api`, or platform SDK dependencies)

## Public Surface

| Class | Responsibility |
|---|---|
| `BytecodeConfigScanner` | Zero-classloading `.class` constant-pool and bytecode scanner that discovers configuration key literals (`READ_BY_CLASS`), call sites, field flows, guard predicates, and `Enum` constant sets. |
| `BytecodeCausalGraphScanner` | Zero-classloading `.class` causal-graph mapper and two-order causal auditor (`CausalGraphReport`, `CausalChainSlice`, `CausalFinding`, descriptor-aware `S-004`/`S-005`/`F-001..F-003` checks). |
| `JarConfigScanner` | Three-plane `.jar` configuration, plugin/mod descriptor (`plugin.yml`, `paper-plugin.yml`, `fabric.mod.json`, `neoforge.mods.toml`), and bytecode harvester plus stripped-comment restorer. |
| `SchemaInferrer` | Deterministic schema, reload-tier, provenance, and 2D spatial gizmo synthesizer combining `@type`/`@range`/`@options` directives with bytecode call-site and enum metadata. |
| `ServerConfigAtlas` | Cross-plugin configuration AST, bytecode-only key indexer, entity-binding, conflict detector, and fuzzy search atlas. |
| `LlmConfigDocSynthesizer` | Configurable AST-grounded LLM documentation synthesizer consuming bytecode call sites and inferred schemas. |
| `LlmCausalChainAuditor` | Closed-world AST-grounded LLM causal-chain auditor and fault explainer for `BytecodeCausalGraphScanner` findings. |

## Build & Verification

```bash
./gradlew :bytecode-api:test --console=plain -q
```
