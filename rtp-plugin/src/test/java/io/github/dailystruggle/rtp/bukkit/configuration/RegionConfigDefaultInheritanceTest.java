package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        RTPTestSetup.install(tempDir.toFile());
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
        assertEquals("LINEAR", settings.vert().name.toUpperCase(),
                "vert must inherit config.yml#defaults.vert (LINEAR)");
    }
}
