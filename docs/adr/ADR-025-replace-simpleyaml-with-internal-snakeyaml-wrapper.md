# ADR-025 — Replace SimpleYaml with an Internal SnakeYAML-Backed YAML Wrapper

**Status:** Proposed
**Date:** 2026-05-01

## Context

`rtp-core` depends on `me.carleslc.Simple-YAML:Simple-Yaml:1.8.4` (and its
transitive `Simple-Configuration:1.8.4`) for all YAML configuration I/O.
SimpleYaml is shaded into both shadowJar variants of `rtp-plugin` via:

- `rtp-core/build.gradle` line 14 — `api('me.carleslc.Simple-YAML:Simple-Yaml:1.8.4')`
- `rtp-plugin/build.gradle` lines 186 and 387 — `relocate 'me.carleslc.Simple-YAML', 'io.github.dailystruggle.rtp.Simple-Yaml'`

This adds roughly 250 KB of third-party bytecode (Simple-Yaml ~120 KB +
Simple-Configuration ~130 KB) to every produced jar, plus a relocate rule
whose target package name (`...Simple-Yaml`) is itself unconventional
because of the hyphen in the upstream coordinate.

The actual surface of SimpleYaml that the project consumes is small —
verified by an exhaustive search of `org.simpleyaml.*` imports in
`rtp-core` (1 May 2026):

- **Production code (13 files):** `RTP.java`, `ConfigParser.java`,
  `MultiConfigParser.java`, `LanguageBootstrap.java`, `FactoryValue.java`,
  `YamlFileDatabase.java`, `RegionConfigLoader.java`, `SubConfigCmd.java`,
  `LanguageCmd.java`, `ListCmd.java`, `ListAddParameter.java`,
  `ListRemoveParameter.java` (+ duplicate test path).
- **Test code (6 files):** `ConfigParserUpdateTest`, two
  `YamlFileDatabaseTest` files, `SubConfigCmdTest`, `LanguageCmdTest`,
  `ListParameterTest`.

The methods actually invoked are:

- `YamlFile`: `new YamlFile(File)`, `load()` / `loadWithComments()`,
  `save()`, `get(key)` / `get(key, default)`, `set(key, value)`,
  `getKeys(deep)`, `getConfigurationSection(key)`,
  `options().copyDefaults(true)`, `setComment(key, comment)`.
- `ConfigurationSection` / `MemorySection`: `getKeys(false)`, `get(key)`,
  `getConfigurationSection(key)`, `set(key, value)`.

Nothing else from SimpleYaml is used. SnakeYAML (`org.yaml:snakeyaml:2.3`)
is already on every classpath: provided at runtime by Spigot/Paper/Folia
and declared `compileOnly` in `rtp-core/build.gradle` line 12 (with
`testRuntimeOnly` on line 13).

The only feature SnakeYAML does not offer through a key-addressed API is
**comment preservation**, which `ConfigParser.update()` (lines 857, 871,
879) and `LanguageBootstrap` (line 106) rely on via
`YamlFile.setComment(key, comment)`. SnakeYAML 2.x does, however, parse
and emit comments at the node level (`LoaderOptions.processComments` and
`DumperOptions.processComments`, plus `CommentLine` on `Node`).

The shadowJar build (`rtp-plugin/build.gradle` lines 185 and 386) already
declares `relocate 'org.yaml.snakeyaml', 'io.github.dailystruggle.rtp.snakeyaml'`
in both pro and lite variants, anticipating the case where SnakeYAML must
be shaded.

## Decision

Remove the `me.carleslc.Simple-YAML` dependency entirely and replace it
with a small, internal YAML abstraction implemented on top of
`org.yaml:snakeyaml:2.3`.

1. **New abstraction package** `io.github.dailystruggle.rtp.common.configuration.yaml`:
    - `RtpYamlConfig` — interface exposing only the surface listed in
      *Context* (load, save, get, set, getKeys(deep), getSection, options
      with `copyDefaults`, `setComment`).
    - `RtpYamlSection` — interface exposing only `getKeys(boolean)`,
      `get`, `getSection`, `set`. Bukkit's `ConfigurationSection` shape is
      **not** mirrored.
    - `SnakeYamlConfig` / `SnakeYamlSection` — single concrete
      implementation owning a configured `org.yaml.snakeyaml.Yaml` and a
      recursive `MappingNode` walker that round-trips comments.
2. **Migration of 13 production files and 6 test files** by mechanical
   import substitution and type rename.
3. **Build changes:**
    - Remove `api('me.carleslc.Simple-YAML:Simple-Yaml:1.8.4')` from
      `rtp-core/build.gradle`.
    - Promote `compileOnly 'org.yaml:snakeyaml:2.3'` to `implementation`
      in `rtp-core/build.gradle` so the **pro** shadowJar bundles
      SnakeYAML. The existing `relocate 'org.yaml.snakeyaml', ...` rule on
      line 185 of `rtp-plugin/build.gradle` then takes effect on the
      bundled bytes (it currently no-ops because nothing is shaded).
    - Remove both `relocate 'me.carleslc.Simple-YAML', ...` lines from
      `rtp-plugin/build.gradle`.
    - The **lite** assembly variant (ADR-024) keeps SnakeYAML excluded;
      the lite jar relies on the platform-provided SnakeYAML at runtime,
      same as today.
4. **Comment-preservation regression test** added before any production
   migration: `RtpYamlCommentRoundTripTest` covering pre-key block
   comments, in-line comments, comments on nested keys, and comments
   surviving a `set()` rewrite + `save()` cycle.
5. **Deep-keys parity test** `RtpYamlDeepKeysParityTest` lock-in dotted-
   path semantics of `getKeys(true)` matching SimpleYaml's prior output.

Fabric platform packaging is **out of scope** for this ADR. The
`rtp-fabric` module remains unstable per ADR-022; once stable, a
follow-up ADR will decide whether SnakeYAML is bundled into the Fabric
mod jar or replaced with another parser.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Stay on SimpleYaml | Issue explicitly asks to remove the dependency; ~250 KB of shaded third-party code we do not control. |
| BoostedYAML (`dev.dejvokep:boosted-yaml`) | Solves comment preservation cleanly but adds a new ~250 KB shaded dependency. The user requirement is "reduce footprint to just RTP code" — adding a different third-party library defeats that goal. |
| Sponge Configurate | Larger conceptual shift (`CommentedConfigurationNode` idiom), heavier shaded footprint, and changes idioms across many files. Cost outweighs benefit for the small surface we use. |
| SnakeYAML Engine (YAML 1.2) | Equivalent footprint to SnakeYAML 1.x. Reasonable, but Spigot/Paper/Folia ship SnakeYAML 1.x at runtime, so using SnakeYAML 1.x lets the lite jar continue to rely on the server-provided copy and shade nothing. Engine is a possible future swap if YAML 1.2 features are ever required. |
| Hand-rolled YAML parser | Out of the question for any non-trivial config; subtle YAML edge cases (anchors, multi-line, quoting) are not worth re-implementing. |

## Consequences

- **Positive:**
    - Removes ~250 KB of shaded third-party code from every produced jar.
    - Eliminates the unconventional `Simple-Yaml` relocation target name.
    - Configuration code path becomes 100% RTP-owned (interface + single
      impl), trivially mockable in tests, and free of the upstream
      release cadence risk of `me.carleslc:Simple-YAML`.
    - License surface in shaded code drops from three Apache-2.0
      artifacts to one (SnakeYAML, already used).
    - Lite variant (ADR-024) is unaffected at runtime: it still defers
      SnakeYAML to the platform.
- **Negative / Trade-offs:**
    - We own the comment-preservation walker (~300–500 LoC). Risk is
      mitigated by the dedicated round-trip test added before migration.
    - Pro shadowJar grows by ~330 KB (bundled SnakeYAML) but net change
      against today is roughly **−170 KB** once Simple-Yaml +
      Simple-Configuration are removed. The net direction is still
      smaller, just smaller by less than the gross removal suggests.
    - Slim `RtpYamlSection` API means future code that wants additional
      Bukkit-`ConfigurationSection`-style methods (e.g., `getStringList`,
      `getInt(default)`) must explicitly extend the interface. This is
      intentional and aligned with the user directive ("slimmer is
      better").
    - Tests that today instantiate `YamlFile` directly to seed YAML
      fixtures must migrate. Most of them can simply write a `String` to
      disk and read it back through the new abstraction; no test logic
      changes.
    - Addons (`RTP_ExampleAddon`, `RTP_Glide`) consume `rtp-api` only,
      and `rtp-api` does not import `org.simpleyaml.*` (verified). They
      are unaffected.

## References

- Issue: *"scope the refactor required to remove our dependency on
  simpleyaml"* — May 2026.
- `rtp-core/build.gradle` line 14 (dependency declaration).
- `rtp-plugin/build.gradle` lines 185–186 (pro shadowJar relocations) and
  386–387 (lite shadowJar relocations).
- `rtp-core/.../configuration/ConfigParser.java` lines 857, 871, 879
  (`setComment` call sites).
- `rtp-core/.../configuration/LanguageBootstrap.java` line 106
  (`setComment` call site).
- ADR-020 — Language Bootstrap and Locale-Aware ConfigParser (the
  comment-preservation requirement originated here).
- ADR-024 — RTP-lite Assembly Variant (lite continues to defer
  SnakeYAML to the platform).
- ADR-022 — Fabric Platform In Scope (Fabric SnakeYAML packaging deferred
  to a follow-up ADR).
- SnakeYAML 2.x comment APIs: `LoaderOptions#setProcessComments`,
  `DumperOptions#setProcessComments`, `CommentLine`, `MappingNode`.
