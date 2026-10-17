package io.github.dailystruggle.rtp.common.search;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.importer.AbstractForeignConfigImporter;
import io.github.dailystruggle.rtp.common.importer.ImportResult;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Enhanced Search and Importer - merged fuzzy and synonym matching integration")
class EnhancedSearchAndImporterFuzzyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        RTP.configs = new Configs(tempDir.toFile());
        Files.createDirectories(tempDir.resolve("regions"));
    }

    private void seedEconomy(String body) throws IOException {
        Files.writeString(tempDir.resolve("economy.yml"), body);
        ConfigParser<EconomyKeys> economyParser =
                new ConfigParser<>(EconomyKeys.class, "economy.yml", "1.0", tempDir.toFile(),
                        new YamlFileDatabase(tempDir.toFile()), "en");
        RTP.configs.configParserMap.put(EconomyKeys.class, economyParser);
    }

    private void seedRegion(String fileName, String body) throws IOException {
        Files.writeString(tempDir.resolve("regions").resolve(fileName), body);
        MultiConfigParser<RegionKeys> mcp =
                new MultiConfigParser<>(RegionKeys.class, "regions", "1.0", tempDir.toFile());
        RTP.configs.multiConfigParserMap.put(RegionKeys.class, mcp);
    }

    @Test
    @DisplayName("In-game search discovers synonyms like 'dinero' and 'cost' for 'price'")
    void testInGameSearchSynonyms() throws IOException {
        seedEconomy("""
                price: 50.0
                priceOther: 75.0
                refund: true
                version: "1.0"
                """);

        // Search with Spanish synonym 'dinero'
        List<Hit> hits = ConfigSearchResultsBuilder.search("dinero", RTP.configs);
        assertFalse(hits.isEmpty(), "Searching 'dinero' must return synonym hits for price");
        Hit priceHit = hits.stream()
                .filter(h -> h.keyName().equalsIgnoreCase("price"))
                .findFirst().orElse(null);
        assertNotNull(priceHit, "expected price hit when searching 'dinero'");
        assertEquals(65, priceHit.score(), "Synonym match must yield score 65");
        assertTrue(priceHit.matchReason().startsWith("Similar:"), "Match reason should describe similarity");

        // Search with English synonym 'cost'
        List<Hit> costHits = ConfigSearchResultsBuilder.search("cost", RTP.configs);
        assertFalse(costHits.isEmpty(), "Searching 'cost' must return synonym hits for price");
        assertTrue(costHits.stream().anyMatch(h -> h.keyName().equalsIgnoreCase("price")));
    }

    @Test
    @DisplayName("In-game search discovers typo matches like 'raduis' for 'radius' with ranking")
    void testInGameSearchTypoToleranceAndRanking() throws IOException {
        seedRegion("default.yml", """
                shape:
                  name: "CIRCLE"
                  radius: 1000
                version: "1.0"
                """);

        // Typo search 'raduis'
        List<Hit> typoHits = ConfigSearchResultsBuilder.search("raduis", RTP.configs);
        assertFalse(typoHits.isEmpty(), "Searching typo 'raduis' must find 'shape.radius'");
        Hit radiusHit = typoHits.stream()
                .filter(h -> h.keyName().equalsIgnoreCase("shape.radius"))
                .findFirst().orElse(null);
        assertNotNull(radiusHit);
        assertTrue(radiusHit.score() >= 20, "Fuzzy match must carry fuzzy score");
        assertTrue(radiusHit.matchReason().startsWith("Similar:") || radiusHit.matchReason().startsWith("Fuzzy ~"),
                "Match reason should indicate similarity or fuzzy match: " + radiusHit.matchReason());

        // Exact search 'radius' should rank higher than fuzzy typo
        List<Hit> exactHits = ConfigSearchResultsBuilder.search("radius", RTP.configs);
        assertFalse(exactHits.isEmpty());
        Hit topHit = exactHits.get(0);
        assertTrue(topHit.score() >= 80, "Exact/Key match should score >= 80");
    }

    private static class DummyImporter extends AbstractForeignConfigImporter {
        @Override public String sourceName() { return "dummy"; }
        @Override public List<String> directoryAliases() { return List.of("dummy"); }
        @Override public List<String> indicatorFiles() { return List.of("config.yml"); }
        @Override public boolean canImport(Path sourcePluginDir) { return true; }
        @Override public ImportResult importConfiguration(Path src, Path dest, boolean overwrite) {
            return new ImportResult(true, "dummy", List.of(), List.of(), List.of(), List.of());
        }

        public Object testFindValue(RtpYamlSection section, String... candidateKeys) {
            return findValueFuzzy(section, candidateKeys);
        }
    }

    @Test
    @DisplayName("AbstractForeignConfigImporter.findValueFuzzy resolves synonym and typo keys")
    void testImporterSynonymAndTypoFallback() {
        DummyImporter importer = new DummyImporter();
        RtpYamlConfig config = new RtpYamlConfig();

        // 1. Direct synonym: foreign config has 'cost: 35', importer asks for 'price'
        config.set("cost", 35);
        Object priceVal = importer.testFindValue(config, "price");
        assertNotNull(priceVal, "importer should resolve 'cost' when searching for 'price'");
        assertEquals(35, priceVal);

        // 2. Multilingual synonym: foreign config has 'dinero: 120', importer asks for 'price'
        config.set("dinero", 120);
        Object dineroVal = importer.testFindValue(config, "price");
        assertNotNull(dineroVal);

        // 3. Typo tolerance: foreign config has 'raduis: 2500', importer asks for 'radius'
        config.set("raduis", 2500);
        Object radiusVal = importer.testFindValue(config, "radius");
        assertNotNull(radiusVal, "importer should resolve 'raduis' typo when searching for 'radius'");
        assertEquals(2500, radiusVal);
    }
}
