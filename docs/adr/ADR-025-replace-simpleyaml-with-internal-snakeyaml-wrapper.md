# ADR-025 — Replace SimpleYaml with an Internal Comment-Preserving YAML Library

**Status:** Proposed
**Date:** 2026-05-01 (initial) / 2026-05-15 (decision reversed: in-house parser
chosen over a SnakeYAML-backed wrapper; see *Decision History* below)

> **File name note.** This ADR's filename
> (`ADR-025-replace-simpleyaml-with-internal-snakeyaml-wrapper.md`) reflects the
> original 2026-05-01 direction. The decision was reversed on 2026-05-15 in
> response to two new constraints that the original ADR had not weighed:
> (1) SnakeYAML's `processComments` does not actually preserve comments through
> a `set + save` round-trip in the way `/rtp config` requires (the comment-
> attachment is node-keyed and heuristic, not lexical-position-stable); and
> (2) SnakeYAML cannot be assumed present on the proxy classpath (Velocity does
> not export it; BungeeCord's version is unpinned and unstable for plugin use),
> so any SnakeYAML-based plan would require shading into every assembly variant
> including the proxy modules planned in MULTI_SERVER_PLAN.md. The filename is
> kept for traceability; the canonical title is the H1 above. A rename is
> deferred to avoid breaking inbound links from CHANGELOG / TRACEABILITY / spec
> Appendix A12 until after acceptance.

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

> **Context update (2026-05-15).** The paragraphs above describe the
> shape of the problem as understood on 2026-05-01. Two facts surfaced
> since then have made the original "wrap SnakeYAML" framing the wrong
> one (preserved here so the reader can follow the reasoning; the
> *Decision* section that follows acts on the updated understanding):
>
> 1. SnakeYAML's `processComments` API exposes comments on the AST but
>    its emitter does **not** preserve their lexical position across a
>    `set + save` cycle — the comment-attachment is node-keyed and
>    heuristic. A comment-preserving save for the
>    `CONFIG_COMMAND_SPEC.md` Appendix A12 contract therefore cannot be
>    delegated to SnakeYAML; the surgical line-edit save layer has to
>    be written in-house regardless of substrate.
> 2. The proxy modules planned in
>    [`MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md) (Phase 1+,
>    Velocity and BungeeCord) cannot rely on a runtime-provided
>    SnakeYAML: Velocity does not export `org.yaml.snakeyaml` to
>    plugins, and BungeeCord's bundled version is unpinned. Any
>    SnakeYAML-based plan therefore needs to shade SnakeYAML into every
>    proxy assembly variant, which contradicts the lite-variant
>    footprint promise of ADR-024 by extension.
>
> Combined with the 2026-05-15 YAML feature audit (see
> [`docs/dev/scratch/YAML_FEATURE_AUDIT_RESULTS.md`](../dev/scratch/YAML_FEATURE_AUDIT_RESULTS.md)),
> which bounds the YAML subset RTP actually uses to a tractable
> hand-rollable grammar, the conclusion is to replace simpleyaml with
> an in-house parser rather than a SnakeYAML wrapper.

## Decision

Remove the `me.carleslc.Simple-YAML` dependency entirely and replace it
with a small, **in-house** YAML parser/serializer in `rtp-core`,
scoped to the subset of YAML the project actually uses. **No
third-party YAML library is shaded into any assembly variant; no
runtime-provided YAML library is consumed either.** The substrate
becomes 100% RTP-owned code.

This replaces the 2026-05-01 plan to wrap SnakeYAML. Rationale for the
reversal is preserved under *Decision History*.

### Scope: which YAML features the in-house library supports

Bounded by the 2026-05-15 *YAML Feature Audit* over 187 shipped `.yml`
files (see `docs/dev/scratch/YAML_FEATURE_AUDIT_RESULTS.md`, to be
folded into this ADR on acceptance).

**Supported (reader and writer):**

- Block-style mappings (nested, arbitrary depth).
- Block-style sequences of scalars (`- item`).
- Scalar types: integer, double, boolean, `null`/empty.
- Bare (unquoted) string scalars.
- Double-quoted string scalars with standard escape sequences
  (`\"`, `\\`, `\n`, `\t`, `\uXXXX`). Audited usage: 822 occurrences.
- Single-quoted string scalars with YAML's doubled-quote escape (`''`).
  Audited usage: 7 occurrences.
- Above-line (block) comments at arbitrary indentation, preserved
  across a load/mutate/save round-trip with surgical line-level edits
  (see *Comment preservation strategy* below).

**Tolerated on input, not preserved across write-back:**

- Inline (same-line, trailing) `# …` comments. The parser must not
  fail on these — admins who hand-edit may write them — but a
  subsequent `/rtp config set` that rewrites the line is permitted to
  drop the trailing comment. This matches the 2026-05-14 A12 scope
  decision (block-only comment preservation) and the user's
  2026-05-15 preference for the above-line idiom.

**Explicitly rejected on input (parser fails fast with a configurable
`UNSUPPORTED_YAML_FEATURE` reason code, per REQ-RTP-F-013 /
REQ-RTP-S-007):**

- Anchors `&name` and aliases `*name` (0 audited real occurrences;
  the 64 raw hits were all Bukkit color codes `&a`/`&c` inside quoted
  strings, which are *not* anchors — they're string content).
- Merge keys `<<:` (0 audited occurrences).
- Flow-style mappings `{…}` or sequences `[…]` at value position
  (0 audited real occurrences; the 234 raw hits were `[placeholder]`
  substrings inside quoted strings or comments).
- Explicit tags `!Tag` / `!!type` (0 audited occurrences).
- Multi-document separators `---` / `...` (0 audited occurrences).
- Block-scalar indicators `|` and `>` (4 audited occurrences, all
  `usage: |` in `plugin.yml`, see *plugin.yml carve-out* below).

The fail-fast error reports the offending line and column and the
specific unsupported feature, in a `messages.yml`-resolved key
(REQ-RTP-F-013).

### `plugin.yml` carve-out

`plugin.yml` is loaded by the Bukkit platform's plugin loader, not by
RTP's configuration layer. It is never read or written by `rtp-core`,
never mutated by `/rtp config`, and never round-tripped through the
in-house parser. Its `usage: |` block-scalar fields therefore do not
need to be supported by the in-house parser. The audit results
documented this as the only place block scalars appear in the
shipped tree.

### Comment preservation strategy

The reader produces a comment-aware AST: each `Node` carries its
source line/column and the list of `^# …` comment lines immediately
preceding it at the same or lesser indentation. The writer is **not
an emitter** in the YAML-library sense; it performs **surgical
line-range edits on the on-disk text**:

- `set(key, value)` locates the key's value-line range in the source
  text (cached from the parse) and rewrites only the value portion of
  that line, leaving every other byte of the file untouched —
  including all above-line comment blocks and any inline trailing
  comment that happens to remain.
- New keys appended to a mapping go after the last existing key of
  that mapping, with one blank line of separation; no existing
  content is reflowed.
- Removed keys delete only the key's own line range and its directly
  attached above-line comment block (the block-comment "ownership"
  rule from the A12 scope decision).
- Lists are rewritten item-by-item with a stable diff; comment-only
  lines between items are preserved.

This is the approach the comparison scratch note
(`docs/dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md`)
identified as the only robust path for A12 — and it is the same work
required regardless of which parser produces the AST. Owning the
parser too just removes the shaded dependency.

### New abstraction package

`io.github.dailystruggle.rtp.common.configuration.yaml`:

- `RtpYamlConfig` — interface exposing the surface enumerated in
  *Context* (load, save, get, set, getKeys(deep), getSection, options
  with `copyDefaults`, `setComment`).
- `RtpYamlSection` — interface exposing only `getKeys(boolean)`,
  `get`, `getSection`, `set`. Bukkit's `ConfigurationSection` shape
  is **not** mirrored.
- `RtpYamlReader` / `RtpYamlWriter` — internal classes implementing
  the lexer + recursive-descent parser and the surgical line-edit
  writer. Estimated size: ~300–500 LoC reader, ~300–500 LoC writer +
  comment-bind walker, plus a focused test suite.
- `RtpYamlNode` family — comment-aware AST nodes carrying
  line/column source positions and attached above-line comment
  blocks.

### Migration and build changes

1. **Migration of 13 production files and 6 test files** by mechanical
   import substitution and type rename
   (`org.simpleyaml.configuration.file.YamlFile` →
   `RtpYamlConfig`, etc.).
2. **Build changes:**
    - Remove `api('me.carleslc.Simple-YAML:Simple-Yaml:1.8.4')` from
      `rtp-core/build.gradle`.
    - Leave `compileOnly 'org.yaml:snakeyaml:2.3'` and
      `testRuntimeOnly` declarations untouched. SnakeYAML is *not*
      promoted to `implementation` and *not* shaded.
    - Remove both `relocate 'me.carleslc.Simple-YAML', ...` lines
      from `rtp-plugin/build.gradle`.
    - Remove the now-unused `relocate 'org.yaml.snakeyaml', ...`
      lines from `rtp-plugin/build.gradle` (pro and lite). They were
      added in anticipation of shading SnakeYAML; under this revised
      decision they relocate nothing in `rtp-plugin` and would become
      a maintenance trap.
    - **Lite, pro, Fabric, and (future) proxy assembly variants all
      benefit equally** — no shaded YAML in any of them.
3. **Shipped-defaults rewrite (one-time, mechanical):** the 228
   inline trailing comments in shipped `.yml` defaults are converted
   to above-line block comments before the in-house parser ships.
   This aligns shipped defaults with the A12 block-only scope, with
   the user's 2026-05-15 preference for the above-line idiom, and
   gives `view` hover-text full comment coverage for every default
   parameter without depending on inline-comment recovery.
4. **Comment-preservation regression test** added before any
   production migration: `RtpYamlBlockCommentRoundTripTest` covering
   pre-key block comments at arbitrary nesting, comments surviving a
   `set()` rewrite of a *different* key in the same mapping, and
   golden-file byte-for-byte idempotence over every shipped `.yml`
   in `rtp-plugin/src/main/resources/`.
5. **Subset-rejection tests** `RtpYamlUnsupportedFeatureTest`: one
   case per rejected feature (anchor, alias, merge key, flow map,
   flow sequence, tag, doc separator, block scalar), each asserting
   a precise line/column error and a configurable `messages.yml` key.
6. **Deep-keys parity test** `RtpYamlDeepKeysParityTest` locks in
   dotted-path semantics of `getKeys(true)` matching SimpleYaml's
   prior output.

### Cross-platform applicability

Because the parser is JDK-only, **all** assembly variants get the
same substrate at zero shaded cost:

| Variant | YAML substrate before | YAML substrate after |
|---|---|---|
| Pro `rtp-plugin` shadowJar | simpleyaml shaded (~250 KB) | in-house, 0 KB third-party |
| Lite `rtp-plugin` shadowJar | platform SnakeYAML (deferred) | in-house, 0 KB third-party |
| Fabric (`rtp-fabric`) | undecided per ADR-022 | in-house, 0 KB third-party — decision closed |
| Proxy Velocity (planned, MULTI_SERVER_PLAN Phase 1+) | n/a; SnakeYAML not exported by Velocity | in-house, 0 KB third-party — proxy YAML question closed |
| Proxy BungeeCord (planned) | n/a; SnakeYAML version unpinned | in-house, 0 KB third-party |

The Fabric "out of scope" carve-out from the 2026-05-01 version of
this ADR is **no longer needed** under the revised decision; the
Fabric mod jar gets the same in-house parser as every other variant.

### Decision History

- **2026-05-01 (original Proposed):** Replace simpleyaml with a thin
  wrapper around `org.yaml:snakeyaml:2.3`. Hand-rolled YAML was
  considered and rejected with: *"Out of the question for any
  non-trivial config; subtle YAML edge cases (anchors, multi-line,
  quoting) are not worth re-implementing."*
- **2026-05-15 (this revision):** Decision reversed in favor of an
  in-house parser. Two new constraints surfaced after the original
  ADR:
  1. **The library doesn't deliver the feature we need.** Neither
     simpleyaml's `loadWithComments` nor SnakeYAML's
     `processComments` survives a `set + save` round-trip in the way
     `/rtp config` and Appendix A12 require. The comment-attachment
     is node-keyed and heuristic; inline comments on rewritten
     scalars are dropped, list-interior comments are reordered, and
     `setSection` wholesale replacement drops interior comments
     entirely (see
     `docs/dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md`).
     The comment-preserving save layer has to be written in-house
     against the AST regardless of which parser produces the AST.
     Once that fact is accepted, the off-the-shelf parser is buying
     a lexer only — not a feature.
  2. **SnakeYAML is not classpath-stable across our planned assembly
     surface.** MULTI_SERVER_PLAN.md Phase 1+ adds Velocity and
     BungeeCord proxy modules. Velocity does not export SnakeYAML
     to plugins; BungeeCord bundles an unpinned version. The
     original 2026-05-01 plan — "Spigot/Paper/Folia ship SnakeYAML
     at runtime, so the lite jar shades nothing" — does not
     generalize to the proxy axis. Either we shade SnakeYAML into
     every variant (rejecting the lite-variant footprint promise of
     ADR-024 by extension), or we stop depending on it.
- **YAML feature audit (2026-05-15)** confirmed that the subset of
  YAML the project actually uses is small enough that the
  original rejection's premise — *"subtle YAML edge cases"* — does
  not apply. Anchors, merge keys, flow style, tags, and multi-doc
  separators have **zero occurrences** in 187 shipped `.yml` files;
  block scalars exist only in `plugin.yml`, which is not parsed by
  RTP at all.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Stay on SimpleYaml | Issue explicitly asks to remove the dependency; ~250 KB of shaded third-party code we do not control; does not actually solve the A12 comment-preservation problem (see comparison scratch note for the v2/v3 failure modes). |
| Internal wrapper around `org.yaml:snakeyaml:2.3` (original 2026-05-01 plan) | Reversed 2026-05-15. (1) SnakeYAML's `processComments` is node-keyed and heuristic — it does not survive a `set + save` round-trip for the comment-preservation contract A12 needs, so we'd have to write a surgical-edit save layer in-house *anyway*. (2) SnakeYAML is not available on the Velocity classloader and is unpinned on BungeeCord, so the proxy modules planned in MULTI_SERVER_PLAN would force shading into every assembly variant — including the lite variant, breaking ADR-024's footprint promise. (3) The YAML feature audit (2026-05-15) showed the subset we actually use is small enough that owning the parser too removes a third-party dependency at low marginal cost. |
| BoostedYAML (`dev.dejvokep:boosted-yaml`) | Solves comment preservation cleanly but adds a new ~250 KB shaded dependency. The user requirement is "reduce footprint to just RTP code" — adding a different third-party library defeats that goal. Same proxy-classpath problem as SnakeYAML. |
| Sponge Configurate | Larger conceptual shift (`CommentedConfigurationNode` idiom), heavier shaded footprint, and changes idioms across many files. Cost outweighs benefit for the small surface we use. |
| SnakeYAML Engine (YAML 1.2) | Equivalent footprint to SnakeYAML 1.x, same comment-preservation gap, same proxy-classpath problem. |
| Module split (separate `rtp-core-yaml` module that ships SnakeYAML in only some shadowJars) | A defensible compromise if we still wanted a vendored parser. Rejected for the same reason as the SnakeYAML wrapper: the work we'd save (the lexer/parser) is small once the comment-preserving save layer is in-house anyway, and the module-split approach still leaves the proxy variants paying ~330 KB of shaded SnakeYAML each. |
| Hand-rolled YAML parser **(this ADR's chosen direction as of 2026-05-15)** | Chosen. The 2026-05-01 rejection (*"subtle YAML edge cases — anchors, multi-line, quoting — are not worth re-implementing"*) was reasoning about *full-YAML* compliance; the audit shows zero usage of the genuinely complex features and bounds the subset to block mappings + block sequences + scalars + above-line comments. |

## Consequences

- **Positive:**
    - Removes ~250 KB of shaded simpleyaml + Simple-Configuration from
      every produced jar, with **no replacement third-party YAML
      library shaded**. Net change against today is approximately
      **−250 KB** in every shadowJar variant.
    - YAML substrate is 100% RTP-owned (interface + single
      implementation), trivially mockable in tests, free of upstream
      release cadence risk, and free of CVE responsibility for a
      third-party YAML library we'd otherwise have to track.
    - License surface in shaded code drops from three Apache-2.0
      artifacts to **zero** for YAML.
    - The proxy axis (MULTI_SERVER_PLAN Phase 1+) gets a clean,
      already-decided substrate — no follow-up ADR is needed to pick
      between "shade SnakeYAML into every proxy jar" and
      "use a different parser on the proxy."
    - The Fabric out-of-scope carve-out from the 2026-05-01 version
      of this ADR is closed; the same substrate ships on Fabric.
    - The lite variant (ADR-024) gets *more* footprint headroom, not
      less — its previous platform-deferral was a coincidence of
      Spigot/Paper/Folia bundling SnakeYAML, not a guarantee.
    - Comment preservation for the A12 contract is structurally
      possible (text-surgery on the on-disk file) where it was
      structurally impossible against a library emitter.
    - `view` hover-text gets reliable above-line-comment recovery
      for every default parameter once the shipped-defaults rewrite
      converts the 228 inline trailing comments to above-line form.
- **Negative / Trade-offs:**
    - We own the parser (~300–500 LoC reader, ~300–500 LoC
      surgical-edit writer, plus a comment-binding walker). Larger
      LoC count than the SnakeYAML-wrapper plan would have produced.
      Mitigated by: (a) the audit-bounded subset, (b) golden-file
      idempotence tests against every shipped `.yml`, and (c) the
      fact that we were already committing to write the surgical-
      edit save layer in either plan.
    - One-time shipped-defaults rewrite: 228 inline trailing
      comments become above-line block comments. Mechanical edit,
      no behavior change, but adds ~30 minutes of manual review per
      file family to confirm the comment-key attachment is
      preserved by the parser. Mitigated by golden-file tests.
    - Locale defaults under `lang/<locale>/` (110+ files) inherit
      the same rewrite. We commit to keeping translations in the
      above-line idiom going forward; the translation guidelines
      in `messages.yml` admin docs need a short note.
    - Slim `RtpYamlSection` API means future code that wants
      additional Bukkit-`ConfigurationSection`-style methods (e.g.,
      `getStringList`, `getInt(default)`) must explicitly extend
      the interface. Intentional and aligned with the
      user directive ("slimmer is better").
    - Tests that today instantiate `YamlFile` directly to seed YAML
      fixtures must migrate. Most can write a `String` to disk and
      read it back through the new abstraction; no test logic
      changes.
    - Addons (`RTP_ExampleAddon`, `RTP_Glide`) consume `rtp-api`
      only, and `rtp-api` does not import `org.simpleyaml.*`
      (verified). They are unaffected.
    - Admins who hand-edit their config with unsupported YAML
      features (anchors, merge keys, flow style, tags) get a
      fail-fast parse error on next load instead of the previous
      silent simpleyaml behavior. This is intended (REQ-RTP-F-013
      configurable message), but it is a user-visible behavior
      change; the implementation PR's release notes must call it
      out.

## References

- Issue: *"scope the refactor required to remove our dependency on
  simpleyaml"* — May 2026. Follow-up issue updates (2026-05-14 /
  2026-05-15) narrowed the comment-preservation scope to above-line
  block comments and reversed the substrate decision from a SnakeYAML
  wrapper to an in-house parser.
- `rtp-core/build.gradle` line 14 (simpleyaml dependency declaration —
  to be removed by this ADR's implementation PR).
- `rtp-plugin/build.gradle` lines 185–186 (pro shadowJar simpleyaml
  relocation) and 386–387 (lite shadowJar simpleyaml relocation) — all
  four lines removed; the anticipatory `org.yaml.snakeyaml` relocations
  are also removed since SnakeYAML is no longer shaded.
- `rtp-core/.../configuration/ConfigParser.java` lines 857, 871, 879
  (`setComment` call sites).
- `rtp-core/.../configuration/LanguageBootstrap.java` line 106
  (`setComment` call site).
- ADR-020 — Language Bootstrap and Locale-Aware ConfigParser (the
  comment-preservation requirement originated here).
- ADR-024 — RTP-lite Assembly Variant. Under this revised decision the
  lite variant *also* drops simpleyaml; the previous platform-deferral
  of SnakeYAML becomes irrelevant (no SnakeYAML dependency anywhere).
  The lite footprint promise is **strengthened**, not relaxed.
- [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md)
  — Fabric Platform In Scope (renumbered from project-wide ADR-022 on
  2026-05-05). The "Fabric YAML packaging is deferred to a follow-up
  ADR" carve-out is **closed** by this revision: the in-house parser
  ships on Fabric like every other variant.
- [`CONFIG_COMMAND_SPEC.md`](../dev/CONFIG_COMMAND_SPEC.md) §2.4 +
  Appendix A row A12 — the comment-preservation contract this ADR
  enables. Block-only scope per 2026-05-14; inline comments tolerated
  on input but not preserved across write-back.
- [`MULTI_SERVER_PLAN.md`](../dev/MULTI_SERVER_PLAN.md) — proxy-axis
  constraint that drove (alongside the A12 feature gap) the reversal
  from a SnakeYAML wrapper to an in-house parser.
- [`docs/dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md`](../dev/scratch/CONFIG_COMMENT_PRESERVATION_COMPARISON.md)
  — v1/v2/v3 failure-mode analysis that motivated the surgical
  line-edit save strategy.
- [`docs/dev/scratch/YAML_FEATURE_AUDIT_RESULTS.md`](../dev/scratch/YAML_FEATURE_AUDIT_RESULTS.md)
  — 2026-05-15 audit over 187 shipped `.yml` files; bounds the parser
  subset.
- SnakeYAML 2.x comment APIs (`LoaderOptions#setProcessComments`,
  `DumperOptions#setProcessComments`, `CommentLine`, `MappingNode`) —
  retained for reference because the *failure modes* of those APIs
  (node-keyed, heuristic comment placement; emitter does not preserve
  lexical position) are the technical motivation for not adopting
  them.
