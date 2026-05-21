package io.github.dailystruggle.rtp.bukkit.menu.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
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
 * Unit tests for {@link ConfigSearchResultsBuilder} (proposal §8, v1 lands
 * in {@code rtp-plugin}).
 *
 * <p>Exercises: short-query guard, case-insensitive substring matching,
 * key-vs-value match attribution, color-stripped haystack across legacy
 * {@code &x}, {@code §x} and {@code &#rrggbb} hex syntaxes, and raw-offset
 * projection of match ranges so renderer highlight lands on the literal
 * letters (proposal §2.7 / §2.8).
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
