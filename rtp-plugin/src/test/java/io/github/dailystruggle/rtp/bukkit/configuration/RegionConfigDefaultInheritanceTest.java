package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.CoreRtpRoot;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-073 integration regression: the bundled {@code regions/default.yml} ships
 * {@code shape: "@config"} / {@code vert: "@config"}, which must inherit the
 * {@code config.yml#defaults.shape} / {@code defaults.vert} blocks at load time.
 *
 * <p>This drives the real bundled resources (extracted by {@code reloadConfigs()})
 * through {@link RegionConfigLoader#load}, reproducing the operator-reported
 * "Shape for region default was invalid. Falling back to SQUARE." + "null vert"
 * failure when inheritance is broken. A passing run proves the {@code @config}
 * reference resolves to the documented CIRCLE/LINEAR defaults rather than falling
 * back to a generic default.
 */
public class RegionConfigDefaultInheritanceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Installs a real RTP + Configs and runs reloadConfigs(), extracting the
        // bundled config.yml and regions/default.yml from the rtp-plugin classpath.
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setPlatform("paper");
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    @DisplayName("bundled default region inherits shape/vert from config.yml#defaults via @config")
    @SuppressWarnings("unchecked")
    void defaultRegionInheritsShapeAndVertFromConfigDefaults() {
        ConfigParser<ConfigKeys> config =
                (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
        assertNotNull(config, "config.yml parser must be provisioned");
        assertNotNull(config.getData(ConfigKeys.defaults),
                "config.yml#defaults block must be read into the parser data map");

        MultiConfigParser<RegionKeys> regions =
                (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        assertNotNull(regions, "regions MultiConfigParser must be provisioned");
        ConfigParser<RegionKeys> def = regions.getParser("default");
        assertNotNull(def, "bundled regions/default.yml must be extracted and parsed");

        // The version bump fix (regions MultiConfigParser version aligned with the
        // bundled regions/default.yml) keeps the raw "@config" token in the region
        // parser's data; before the fix a spurious version-mismatch migration dropped
        // the shape/vert scalar keys, so getConfigValue returned null and never resolved.
        assertEquals("@config", String.valueOf(def.getData(RegionKeys.shape)),
                "region must retain the raw '@config' shape token after load");
        assertEquals("@config", String.valueOf(def.getData(RegionKeys.vert)),
                "region must retain the raw '@config' vert token after load");

        RegionSettings settings = RegionConfigLoader.load(def);
        assertNotNull(settings.shape(), "shape must resolve");
        assertNotNull(settings.vert(), "vert must resolve");
        assertEquals("CIRCLE", settings.shape().name.toUpperCase(),
                "shape must inherit config.yml#defaults.shape (CIRCLE), not fall back to SQUARE");
        assertInstanceOf(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.CircleOptimizedDualLayer.class,
                settings.shape(),
                "default CIRCLE shape must resolve to CircleOptimizedDualLayer");
        assertEquals("LINEAR", settings.vert().name.toUpperCase(),
                "vert must inherit config.yml#defaults.vert (LINEAR)");
    }

    @Test
    @DisplayName("bundled default configs load and execute commands without unexpected warnings or errors")
    @SuppressWarnings("unchecked")
    void defaultConfigsAndCommandsEmitNoUnexpectedWarningsOrErrors() {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        assertNotNull(accessor, "Server accessor must be installed");

        // Force resolution of default region settings
        MultiConfigParser<RegionKeys> regions =
                (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
        assertNotNull(regions);
        ConfigParser<RegionKeys> def = regions.getParser("default");
        assertNotNull(def);
        RegionSettings settings = RegionConfigLoader.load(def);
        assertNotNull(settings);

        // Check for any warnings or errors emitted during config extraction/loading/resolution
        List<String> configWarningsOrErrors = accessor.logMessages.stream()
                .filter(m -> m.startsWith("WARNING:") || m.startsWith("SEVERE:"))
                .toList();
        assertTrue(configWarningsOrErrors.isEmpty(),
                "Loading and resolving default configs must not emit warnings or errors: " + configWarningsOrErrors);

        // Execute commands under default config
        CoreRtpRoot root = new CoreRtpRoot(null, null);
        accessor.registerCommands(root, "rtp");

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(playerId, "testPlayer", null);
        accessor.addPlayer(player);

        accessor.executeCommand(player.uuid(), "rtp info");
        accessor.executeCommand(player.uuid(), "rtp help");
        accessor.executeCommand(player.uuid(), "rtp version");

        List<String> allWarningsOrErrors = accessor.logMessages.stream()
                .filter(m -> m.startsWith("WARNING:") || m.startsWith("SEVERE:"))
                .toList();
        assertTrue(allWarningsOrErrors.isEmpty(),
                "Executing default commands must not emit warnings or errors: " + allWarningsOrErrors);
    }

    @Test
    @DisplayName("startup config loading warns on unrecognized properties in shape and vert")
    void startupConfigLoadingWarnsOnUnrecognizedProperties() throws Exception {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.logMessages.clear();

        Path regionDir = tempDir.resolve("definitions").resolve("regions");
        Files.createDirectories(regionDir);
        Path badRegion = regionDir.resolve("unrecognized.yml");
        String yaml = """
                version: "1.1"
                world: "[0]"
                shape:
                  name: CIRCLE
                  radius: 300
                  unknownShapeKeyXYZ: 123
                vert:
                  name: LINEAR
                  unknownVertKeyXYZ: 456
                """;
        Files.writeString(badRegion, yaml);

        RTP.configs.reloadAction();

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("Unrecognized property 'unknownShapeKeyXYZ'")
                && m.contains("GenericMemoryShapeParams")),
                "Expected warning for unrecognized shape property: " + accessor.logMessages);

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("Unrecognized property 'unknownVertKeyXYZ'")
                && m.contains("GenericVerticalAdjustorKeys")),
                "Expected warning for unrecognized vert property: " + accessor.logMessages);
    }

    @Test
    @DisplayName("startup config loading warns and autocorrects perceptible typos in properties and names")
    void startupConfigLoadingWarnsOnTypos() throws Exception {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.logMessages.clear();

        Path regionDir = tempDir.resolve("definitions").resolve("regions");
        Files.createDirectories(regionDir);
        Path typoRegion = regionDir.resolve("typo.yml");
        String yaml = """
                version: "1.1"
                world: "[0]"
                shape:
                  name: cirlce
                  raduis: 300
                vert:
                  name: lienar
                """;
        Files.writeString(typoRegion, yaml);

        RTP.configs.reloadAction();

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("shape 'cirlce' was not recognized, but closely matches 'CIRCLE'")
                && m.contains("Autocorrecting to 'CIRCLE'")),
                "Expected warning for shape name typo: " + accessor.logMessages);

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("vert 'lienar' was not recognized, but closely matches 'LINEAR'")
                && m.contains("Autocorrecting to 'LINEAR'")),
                "Expected warning for vert name typo: " + accessor.logMessages);

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("Property 'raduis' in GenericMemoryShapeParams was not recognized, but closely matches 'radius'")
                && m.contains("Autocorrecting to 'radius'")),
                "Expected warning for property typo: " + accessor.logMessages.stream().filter(m -> m.startsWith("WARNING:")).toList());
    }

    @Test
    @DisplayName("startup config loading warns on invalid shape and vert names and falls back to defaults")
    void startupConfigLoadingWarnsOnInvalidShapeAndVert() throws Exception {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.logMessages.clear();

        Path regionDir = tempDir.resolve("definitions").resolve("regions");
        Files.createDirectories(regionDir);
        Path invalidRegion = regionDir.resolve("invalid.yml");
        String yaml = """
                version: "1.1"
                world: "[0]"
                shape:
                  name: COMPLETELY_BOGUS_SHAPE
                vert:
                  name: COMPLETELY_BOGUS_VERT
                """;
        Files.writeString(invalidRegion, yaml);

        RTP.configs.reloadAction();

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("shape 'COMPLETELY_BOGUS_SHAPE' is invalid")
                && m.contains("Falling back to CIRCLE")),
                "Expected warning for invalid shape name: " + accessor.logMessages);

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("vert 'COMPLETELY_BOGUS_VERT' is invalid")
                && m.contains("Falling back to LINEAR")),
                "Expected warning for invalid vert name: " + accessor.logMessages);
    }

    @Test
    @DisplayName("startup config loading warns on unknown config-default reference")
    void startupConfigLoadingWarnsOnUnknownReference() throws Exception {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.logMessages.clear();

        Path regionDir = tempDir.resolve("definitions").resolve("regions");
        Files.createDirectories(regionDir);
        Path badRefRegion = regionDir.resolve("bad-ref.yml");
        String yaml = """
                version: "1.1"
                world: "[0]"
                shape: "@nonexistent"
                vert: "@config"
                """;
        Files.writeString(badRefRegion, yaml);

        RTP.configs.reloadAction();

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("unknown config-default reference '@nonexistent'")),
                "Expected warning for unknown reference: " + accessor.logMessages);
    }

    @Test
    @DisplayName("startup config loading warns when region has unreadable shape or vert")
    void startupConfigLoadingWarnsOnUnreadableShapeAndVert() throws Exception {
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.logMessages.clear();

        Path regionDir = tempDir.resolve("definitions").resolve("regions");
        Files.createDirectories(regionDir);
        Path emptyRegion = regionDir.resolve("empty-shape-vert.yml");
        String yaml = """
                version: "1.1"
                world: "[0]"
                """;
        Files.writeString(emptyRegion, yaml);

        RTP.configs.reloadAction();

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("Region 'empty-shape-vert' had no readable shape; falling back to the default")),
                "Expected warning for missing shape: " + accessor.logMessages);

        assertTrue(accessor.logMessages.stream().anyMatch(m ->
                m.startsWith("WARNING:")
                && m.contains("Region 'empty-shape-vert' had no readable vert; falling back to the default")),
                "Expected warning for missing vert: " + accessor.logMessages);
    }

    @Test
    @DisplayName("clean initial startup boot warns when pre-existing config on disk contains unrecognized properties")
    void cleanInitialStartupBootWarnsOnPreExistingInvalidConfig(@TempDir Path freshDir) throws Exception {
        RTPTestSetup.cleanUp();
        try {
            Path regionDir = freshDir.resolve("definitions").resolve("regions");
            Files.createDirectories(regionDir);
            Path customRegion = regionDir.resolve("preexisting-bad.yml");
            String yaml = """
                    version: "1.1"
                    world: "[0]"
                    shape:
                      name: CIRCLE
                      unrecognizedPreExistingKey: 999
                    vert:
                      name: LINEAR
                    """;
            Files.writeString(customRegion, yaml);

            MockRTPServerAccessor freshAccessor = RTPTestSetup.install(freshDir.toFile());
            freshAccessor.setPlatform("paper");
            RTP.configs.reloadRegions();

            assertTrue(freshAccessor.logMessages.stream().anyMatch(m ->
                    m.startsWith("WARNING:")
                    && m.contains("Unrecognized property 'unrecognizedPreExistingKey'")
                    && m.contains("GenericMemoryShapeParams")),
                    "Expected warning on clean startup boot for unrecognized property: " + freshAccessor.logMessages);
        } finally {
            RTPTestSetup.cleanUp();
            RTPTestSetup.install(tempDir.toFile());
        }
    }
}
