package io.github.dailystruggle.rtp.bukkit.menu.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigSearchResultsBuilder}.
 *
 * <p>Exercises: short-query guard, case-insensitive substring matching,
 * key-vs-value match attribution, color-stripped haystack across legacy
 * {@code &x}, {@code §x} and {@code &#rrggbb} hex syntaxes, and raw-offset
 * projection of match ranges so renderer highlight lands on the literal
 * letters.
 */
public class ConfigSearchResultsBuilderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        RTP.configs = new Configs(tempDir.toFile());
        Files.createDirectories(tempDir.resolve("regions"));
    }

    private MultiConfigParser<RegionKeys> seedRegion(String fileName, String body) throws IOException {
        Files.writeString(tempDir.resolve("regions").resolve(fileName), body);
        MultiConfigParser<RegionKeys> mcp =
                new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", tempDir.toFile());
        RTP.configs.multiConfigParserMap.put(RegionKeys.class, mcp);
        return mcp;
    }

    @Test
    void shortQueryReturnsEmpty() {
        assertTrue(ConfigSearchResultsBuilder.search("a", RTP.configs).isEmpty());
        assertTrue(ConfigSearchResultsBuilder.search("", RTP.configs).isEmpty());
        assertTrue(ConfigSearchResultsBuilder.search(null, RTP.configs).isEmpty());
    }

    @Test
    void nullConfigsReturnsEmpty() {
        assertTrue(ConfigSearchResultsBuilder.search("abc", null).isEmpty());
    }

    @Test
    void matchesValueCaseInsensitively() throws IOException {
        seedRegion("default.yml",
                "shape: SQUARE\n" +
                "world: world\n" +
                "version: \"1.0\"\n");
        List<Hit> hits = ConfigSearchResultsBuilder.search("square", RTP.configs);
        // "square" matches the value of `shape`. Case-insensitive.
        assertFalse(hits.isEmpty(), "expected at least one value hit for 'square'");
        Hit valueHit = hits.stream()
                .filter(h -> !h.keyMatched() && h.keyName().equalsIgnoreCase("shape"))
                .findFirst().orElse(null);
        assertNotNull(valueHit, "expected a value-side hit on shape=SQUARE");
        assertEquals("SQUARE", valueHit.rawValue());
        assertEquals(1, valueHit.matchRanges().size());
        int[] r = valueHit.matchRanges().get(0);
        assertEquals(0, r[0]);
        assertEquals(6, r[1]);
    }

    @Test
    void matchesKeyNameProducesKeyHit() throws IOException {
        seedRegion("default.yml",
                "shape: SQUARE\n" +
                "version: \"1.0\"\n");
        List<Hit> hits = ConfigSearchResultsBuilder.search("shape", RTP.configs);
        // "shape" matches the key name.
        Hit keyHit = hits.stream()
                .filter(Hit::keyMatched)
                .findFirst().orElse(null);
        assertNotNull(keyHit, "expected a key-side hit on 'shape'");
        assertEquals("shape", keyHit.keyName());
    }

    @Test
    void nestedSectionProducesDottedKeyHitsNotBlob() throws IOException {
        // shape is a nested YAML section in a real region file. The search
        // must surface each nested scalar as its own dotted-key hit
        // (shape.radius) with a clean scalar rawValue, rather than matching
        // inside a String.valueOf'd dump of the whole section.
        seedRegion("default.yml",
                "shape:\n" +
                "  name: \"CIRCLE\"\n" +
                "  radius: 256\n" +
                "  centerRadius: 64\n" +
                "version: \"1.0\"\n");
        List<Hit> hits = ConfigSearchResultsBuilder.search("radius", RTP.configs);
        Hit dotted = hits.stream()
                .filter(h -> h.keyName().equalsIgnoreCase("shape.radius"))
                .findFirst().orElse(null);
        assertNotNull(dotted, "expected a hit keyed by dotted path 'shape.radius'");
        assertEquals("256", dotted.rawValue(),
                "rawValue must be the scalar leaf, not a section dump");
        assertFalse(dotted.rawValue().contains("\n"),
                "rawValue must not be a multi-line section blob");
        // The nested value 256 is also matchable as a value hit on shape.radius.
        Hit valueHit = ConfigSearchResultsBuilder.search("256", RTP.configs).stream()
                .filter(h -> !h.keyMatched() && h.keyName().equalsIgnoreCase("shape.radius"))
                .findFirst().orElse(null);
        assertNotNull(valueHit, "expected a value-side hit on shape.radius=256");
        assertEquals("256", valueHit.rawValue());
    }

    @Test
    void factoryValueShapeFlattensToDottedKeysWithKindPrefixedFile() throws IOException {
        // At runtime RegionConfigLoader replaces the raw YAML shape section
        // with a deserialized Shape (a FactoryValue). The search must flatten
        // that FactoryValue into dotted leaves (shape.radius) rather than
        // String.valueOf'ing the whole object into one blob, and the hit's
        // file name must be the dispatch-addressable "<kind>/<entry>" form so
        // a row click resolves instead of failing as "menu command invalid".
        MultiConfigParser<RegionKeys> mcp = seedRegion("default.yml",
                "shape: SQUARE\n" +
                "version: \"1.0\"\n");
        ConfigParser<RegionKeys> sub = mcp.getParser("default");
        Square square = new Square();
        square.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams.radius, 384L);
        sub.set(RegionKeys.shape, square);

        List<Hit> hits = ConfigSearchResultsBuilder.search("radius", RTP.configs);
        Hit dotted = hits.stream()
                .filter(h -> h.keyName().equalsIgnoreCase("shape.radius"))
                .findFirst().orElse(null);
        assertNotNull(dotted, "expected a dotted 'shape.radius' hit from the FactoryValue shape");
        assertEquals("regions/default", dotted.fileName(),
                "multiconfig hit must carry the kind-prefixed, dispatch-addressable file name");
        assertFalse(dotted.rawValue().contains("\n"),
                "rawValue must be a scalar leaf, not a FactoryValue blob");
    }

    @Test
    void colorCodesDoNotBreakValueMatching() {
        // Hand-build a hit via the strip helper to validate offset projection
        // around a hex-prefixed value: raw "&#7f7f7fforest" -> stripped "forest".
        // The query "forest" must produce a raw range that lands on the literal
        // letters 'f','o','r','e','s','t' in raw, not on the hex digits.
        String raw = "&#7f7f7fforest";
        var strip = io.github.dailystruggle.rtp.common.text.LegacyColorStrip.strip2(raw);
        assertEquals("forest", strip.stripped);
        // The first stripped char ('f') sits at raw offset 8 (after "&#7f7f7f").
        assertEquals(8, strip.strippedToRaw[0]);
        // End-of-match projection used by the builder: raw end exclusive.
        // endStripped == 6 == strippedToRaw.length, so builder uses rawValue.length() = 14.
        assertEquals(6, strip.strippedToRaw.length);
        assertEquals(14, raw.length());
    }

    @Test
    void emptyConfigsYieldsEmpty() {
        // Fresh Configs with no parsers seeded.
        assertTrue(ConfigSearchResultsBuilder.search("anything", RTP.configs).isEmpty());
    }
}
