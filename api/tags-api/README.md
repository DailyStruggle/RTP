# tags-api

Platform-neutral Minecraft data-pack block-tag JSON parser, cycle-safe `#namespace:tag` resolver (`io.github.dailystruggle.rtp.tags`), block-state string parser (`io.github.dailystruggle.rtp.api.block`), and safety-list token/predicate compiler (`io.github.dailystruggle.rtp.api.safety`). Pure Java 17 with a hand-rolled JSON reader (zero Gson, Jackson, Bukkit, Fabric, NeoForge, `rtp-api`, or `rtp-core` dependencies), allowing any standalone plugin or mod (e.g. `TreeGen`, `WorldGuardLite`) to resolve vanilla and custom data-pack block tags across both `<= 1.20` (`tags/blocks`) and `>= 1.21` (`tags/block`) directory layouts and evaluate `#tag[key=value,level>=n] - SUBTRACTED` block predicates.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:tags-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:tags-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:tags-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:tags-api:3.3.1'
}
```

## Surface

### Tag JSON Loading & Resolution (`io.github.dailystruggle.rtp.tags`)

- `TagResolver` - resolves an ordered `List<TagSource>` into an immutable `Map<String, Set<String>>` (`TagResolver.resolve(sources)`), merging files by `namespacedId` (`replace=true` vs append), expanding `#namespace:path` tag references to a fixed point with cycle protection, and normalizing material identifiers to uppercase local names (`OAK_LEAVES`).
- `TagSource` - pull-based interface (`List<TagFile> loadBlockTags()`) for supplying tag definitions from disk, jars, or memory.
- `DiskTagSource` - filesystem `TagSource` (`new DiskTagSource(Path dataRoot[, BiConsumer<String, Throwable> rejectionSink])`) that scans `<dataRoot>/<namespace>/tags/{block,blocks}/**/*.json` and reports malformed files to the optional `rejectionSink`.
- `TagFile` - immutable DTO `(String namespacedId, boolean replace, List<String> values)` with convenience factories `TagFile.of(...)` and `TagFile.empty(namespacedId)`.
- `TagFileParser` - parses a raw tag JSON string into a `TagFile` (`TagFileParser.parse(namespacedId, json)`), supporting string entries, `#` tag references, and `{ "id": "...", "required": ... }` object entries.
- `TinyJsonReader` - minimal, auditable JSON subset parser (`TinyJsonReader.parse(json)`) returning standard `Map<String, Object>`, `List<Object>`, `String`, `Boolean`, and `Long` values or throwing `TinyJsonReader.JsonParseException`.

### Block-State & Safety-List Predicate Compiler (`io.github.dailystruggle.rtp.api.block`, `io.github.dailystruggle.rtp.api.safety`)

- `BlockStateString` - immutable parser for `namespace:id[key=value,...]` strings (`BlockStateString.parse(token)`, `BlockStateString.split(token)`), exposing `head()`, `namespace()`, `path()`, `properties()`, `isAir()`, and `canonical()`.
- `SafetyToken` - parsed safety-list token (`MATERIAL` or `TAG`) supporting wildcard `*`, bracketed `StatePredicate` lists, and `- subtraction` clauses (`#minecraft:slabs - OAK_SLAB`).
- `SafetyTokenParser` - stateless ADR-017 grammar parser (`SafetyTokenParser.parse(raw)`, `SafetyTokenParser.parseAll(rawTokens)`) returning `ParseResult(accepted, rejected)` with never-silent `Rejection(rawToken, reason)` diagnostics.
- `StatePredicate` - immutable AND-combined block-state predicate supporting case-insensitive `key=value` string equality and `NumericComparison` range operators (`>=`, `<=`, `>`, `<`).
- `CompiledUnsafeSet` - bucketed, immutable evaluator (`CompiledUnsafeSet.compile(tokens)`, `withTagsExpanded(tagSnapshot)`, `isUnsafe(materialName, liveTagMembership, liveProperties)`, `hasWildcardStatePredicate()`).
- `SafetyCompilationCache` - thread-safe `SoftReference`-backed memoization cache (`SafetyCompilationCache.getOrCompile(rawTokens[, tagSnapshot], rejectionSink)`) that fires the rejection callback once per distinct token set.

## Usage

```java
import io.github.dailystruggle.rtp.api.block.BlockStateString;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.tags.DiskTagSource;
import io.github.dailystruggle.rtp.tags.TagFile;
import io.github.dailystruggle.rtp.tags.TagResolver;
import io.github.dailystruggle.rtp.tags.TagSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

// 1. Resolve custom canopy and slab tags from defaults + world datapack:
TagSource builtInDefaults = () -> List.of(
        TagFile.of("minecraft:leaves", false, List.of("minecraft:oak_leaves", "minecraft:birch_leaves")),
        TagFile.of("minecraft:slabs", false, List.of("minecraft:oak_slab", "minecraft:stone_slab")),
        TagFile.of("treegen:canopy", false, List.of("#minecraft:leaves", "minecraft:vine")));

TagSource datapackSource = new DiskTagSource(
        Path.of("world/datapacks/custom_trees/data"),
        (path, err) -> System.err.println("Invalid tag file " + path + ": " + err.getMessage()));

Map<String, Set<String>> resolvedTags = TagResolver.resolve(List.of(builtInDefaults, datapackSource));

// 2. Compile block-tag, state-predicate, numeric-range, and subtraction rules against the tag snapshot:
CompiledUnsafeSet hazardRules = SafetyCompilationCache.getOrCompile(
        List.of(
                "LAVA[level<=3]",
                "#minecraft:slabs[waterlogged=true] - OAK_SLAB",
                "*[lit=true]"),
        resolvedTags,
        rej -> System.err.println("Rejected rule " + rej.rawToken() + ": " + rej.reason()));

// 3. Parse a block-state string and evaluate against compiled rules:
BlockStateString state = BlockStateString.parse("minecraft:stone_slab[type=bottom,waterlogged=true]");
boolean blocked = hazardRules.isUnsafe(state.path().toUpperCase(), state.properties()); // true
```

## Build

```
./gradlew :tags-api:build
```

## Docs

- ADR-017: [`../../docs/adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md`](../../docs/adr/ADR-017-block-tags-and-state-predicates-in-safety-lists.md)
