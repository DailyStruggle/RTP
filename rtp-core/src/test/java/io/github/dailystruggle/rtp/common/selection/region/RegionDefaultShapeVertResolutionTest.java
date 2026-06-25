package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ADR-073 regression: a region whose {@code shape}/{@code vert} carry an unresolvable
 * {@code @config} reference (e.g. a stale {@code config.yml} whose {@code defaults} block
 * predates ADR-073 and lacks the {@code shape}/{@code vert} sub-blocks, so the reference
 * resolves to {@code null}) must still be read with a valid, non-null shape and vert.
 *
 * <p>Before the fix this surfaced as the operator-visible
 * "Shape for region default was invalid. Falling back to SQUARE." warning plus a repeated
 * "invalid state, null vert" {@link IllegalStateException} from {@code PregenState.build}.
 */
@DisplayName("ADR-073 - region with unresolvable @config shape/vert reads a sane default")
class RegionDefaultShapeVertResolutionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Installs a real RTP instance so the shape/vert factories are populated.
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("RegionConfigLoader recovers a valid shape/vert when @config cannot resolve")
    @SuppressWarnings("unchecked")
    void unresolvableConfigReferenceFallsBackToValidShapeAndVert() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "default.yml";

        // Simulate the stale-config scenario: shape/vert inherit via @config but the
        // reference resolves to null (config.yml#defaults has no shape/vert block).
        doReturn(null).when(parser).getConfigValue(any(RegionKeys.class), any());
        doReturn(0.0).when(parser).getConfigValue(eq(RegionKeys.price), any());
        doReturn("default").when(parser).getConfigValue(eq(RegionKeys.override), any());

        RegionSettings settings = RegionConfigLoader.load(parser);
        assertNotNull(settings.shape(),
                "shape must be recovered to a valid default rather than left null");
        assertNotNull(settings.vert(),
                "vert must be recovered to a valid default rather than left null");
    }
}
