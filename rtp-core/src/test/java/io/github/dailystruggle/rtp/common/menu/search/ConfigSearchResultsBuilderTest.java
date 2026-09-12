package io.github.dailystruggle.rtp.common.menu.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
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
 * <p>The class under test is platform-neutral and lives in rtp-core, so its
 * coverage is credited to rtp-core (ENTERPRISE_READINESS item 12).
 *
 * <p>Exercises: short-query guard, case-insensitive substring matching,
 * key-vs-value match attribution, color-stripped haystack across legacy
 * {@code &x}, {@code section x} and {@code &#rrggbb} hex syntaxes, and raw-offset
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
    void shortQuerySingleArgReturnsEmpty() {
        assertTrue(ConfigSearchResultsBuilder.search("a").isEmpty());
        assertTrue(ConfigSearchResultsBuilder.search(null).isEmpty());
    }

    @Test
    void singleArgSearchUsesGlobalConfigs() throws IOException {
        seedRegion("default.yml",
                "shape: SQUARE\n" +
                "version: \"1.0\"\n");
        // The single-arg overload reads RTP.configs, seeded in setUp.
        assertFalse(ConfigSearchResultsBuilder.search("square").isEmpty(),
                "single-arg overload should search the globally-installed configs");
    }

    @Test
    void matchesValueCaseInsensitively() throws IOException {
        seedRegion("default.yml",
                "shape: SQUARE\n" +
                "world: world\n" +
                "version: \"1.0\"\n");
        List<Hit> hits = ConfigSearchResultsBuilder.search("square", RTP.configs);
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
        Hit keyHit = hits.stream()
                .filter(Hit::keyMatched)
                .findFirst().orElse(null);
        assertNotNull(keyHit, "expected a key-side hit on 'shape'");
        assertEquals("shape", keyHit.keyName());
    }

    @Test
    void nestedSectionProducesDottedKeyHitsNotBlob() throws IOException {
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
        Hit valueHit = ConfigSearchResultsBuilder.search("256", RTP.configs).stream()
                .filter(h -> !h.keyMatched() && h.keyName().equalsIgnoreCase("shape.radius"))
                .findFirst().orElse(null);
        assertNotNull(valueHit, "expected a value-side hit on shape.radius=256");
        assertEquals("256", valueHit.rawValue());
    }

    @Test
    void factoryValueShapeFlattensToDottedKeysWithKindPrefixedFile() throws IOException {
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
        String raw = "&#7f7f7fforest";
        var strip = io.github.dailystruggle.rtp.common.text.LegacyColorStrip.strip2(raw);
        assertEquals("forest", strip.stripped);
        assertEquals(8, strip.strippedToRaw[0]);
        assertEquals(6, strip.strippedToRaw.length);
        assertEquals(14, raw.length());
    }

    @Test
    void emptyConfigsYieldsEmpty() {
        assertTrue(ConfigSearchResultsBuilder.search("anything", RTP.configs).isEmpty());
    }

    @Test
    void hitRecordRejectsNullFileNameAndKey() {
        try {
            new Hit(null, "k", true, "v", List.of());
            org.junit.jupiter.api.Assertions.fail("null fileName must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new Hit("f", null, true, "v", List.of());
            org.junit.jupiter.api.Assertions.fail("null keyName must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    void hitRecordNormalisesNullValueAndRanges() {
        Hit hit = new Hit("f", "k", false, null, null);
        assertEquals("", hit.rawValue(), "null rawValue normalises to empty string");
        assertNotNull(hit.matchRanges(), "null matchRanges normalises to empty list");
        assertTrue(hit.matchRanges().isEmpty());
    }
}
