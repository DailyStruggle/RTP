# config-bit-api

Declarative YAML configuration schema, cross-key constraint, reference-token, and locale key-parity Built-In Test (`PBIT`) library (`io.github.dailystruggle.rtp.common.bit.config`). Pure Java 17 layered strictly on `:yaml-api`, `:bit-api`, and `:units-api` with zero platform or `rtp-core` dependencies, enabling any standalone plugin or mod (e.g. `OnePlayerSleep`, `WorldGuardLite`) to validate operator YAML files against bundled defaults and emit structured `BitFinding` diagnostics on startup or reload.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:config-bit-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:config-bit-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:config-bit-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:config-bit-api:3.3.1'
}
```

## Surface

- `DirectiveSchemaParser` - immutable parser and record for ADR-064 YAML block-comment directives (`@type`, `@options`, `@range`, `@pattern`, `@unit`, `@default`, `@source`). Provides `parse(String)`, `parse(List<String>)`, and `parseAll(RtpYamlConfig)`.
- `DirectiveValidator` - validates a `ConfigSnapshot.Document` against its bundled default YAML text (`validate(document, bundledText, codes)` / `check(snapshot, path, resourceLoader)`), checking type mismatches, unknown enum options (with `Vocabulary` typo suggestions), numeric range bounds, regex patterns, and unknown top-level keys.
- `ConfigConstraint` - declarative ordering constraint (`lower < upper` or `lower <= upper`) between two spatial distance keys inside a config block (`evaluate(ConfigSnapshot)`), normalizing unit suffixes (`4096b` vs `64c`) via `DistanceParser`.
- `GenericConstraintCheck` - reusable evaluator over a collection of `ConfigConstraint` rules (`GenericConstraintCheck.of(constraints...).evaluate(snapshot)`).
- `ReferenceSettingCheck` - validator for `@<file>` cross-file setting inheritance references (`checkDocument(...)`, `evaluate(snapshot, documents, nestedSections)`, `snapshotResolver(snapshot)`).
- `MessageKeyParity` - read-only key-set parity verifier between baseline English `.yml` / `.lang.yml` resources and bundled `lang/<locale>/` trees over a `ResourceReader` (`directory(Path)`, `classpath(ClassLoader)`, `of(Map)`).
- `ConfigSnapshot` - side-effect-free, read-only directory or in-memory YAML snapshot (`ConfigSnapshot.read(Path)`, `ConfigSnapshot.of(Map)`, `withOverlay(Map)`), resolving localized keys via `.lang.yml` rename maps without mutating running state.
- `DirectiveCodes` - `BitCode` mapping for directive validation findings (`DirectiveCodes.DEFAULT` under namespace `CFG`, or caller-supplied codes).
- `MessageParityCodes` - `BitCode` mapping and finding translator (`MessageParityCodes.DEFAULT`) for `MessageKeyParity.Gap` and operator message-file checks (`bundledFindings(...)`, `operatorFindings(...)`).

## Usage

```java
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.bit.config.ConfigConstraint;
import io.github.dailystruggle.rtp.common.bit.config.ConfigSnapshot;
import io.github.dailystruggle.rtp.common.bit.config.DirectiveCodes;
import io.github.dailystruggle.rtp.common.bit.config.DirectiveValidator;
import io.github.dailystruggle.rtp.common.bit.config.GenericConstraintCheck;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Standalone plugin (e.g. WorldGuardLite) running PBIT over its plugin data folder:
ConfigSnapshot snapshot = ConfigSnapshot.read(Path.of("plugins/WorldGuardLite"));
DirectiveValidator validator = new DirectiveValidator(DirectiveCodes.DEFAULT);

List<BitFinding> findings = new ArrayList<>();
findings.addAll(validator.check(snapshot, "config.yml", MyPlugin.class.getClassLoader()));

// Enforce minRadius < maxRadius across spatial units (e.g. "16c" vs "1024b")
GenericConstraintCheck boundsCheck = GenericConstraintCheck.of(
        new ConfigConstraint("config.yml", "claim", "minRadius", "maxRadius",
                false, SpatialUnit.BLOCK, DirectiveCodes.CFG_OUT_OF_RANGE));
findings.addAll(boundsCheck.evaluate(snapshot));
```

## Build

```
./gradlew :config-bit-api:build
```

## Docs

- ADR-064: [`../../docs/adr/ADR-064-config-comment-format-summary-line-as-menu-hover.md`](../../docs/adr/ADR-064-config-comment-format-summary-line-as-menu-hover.md)
- ADR-121: [`../../docs/adr/ADR-121-built-in-test-for-configuration-and-the-action-engine.md`](../../docs/adr/ADR-121-built-in-test-for-configuration-and-the-action-engine.md)
