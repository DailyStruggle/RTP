package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.DualSparkline;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetricSparklineResolver tests")
class MetricSparklineResolverTest {

    @TempDir
    Path tempDir;

    private MetricSparklineResolver resolver;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        resolver = new MetricSparklineResolver();
    }

    @Test
    void testSpecValidation() {
        assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(null));

        ChartSpec wrongSpec = ChartSpec.of(ChartSpec.Kind.REGION_BIOMES, "default");
        assertThrows(ChartSpecResolver.UnresolvableChartSpecException.class, () -> resolver.resolve(wrongSpec));
    }

    @Test
    void testResolveValidSpec() throws Exception {
        ChartSpec spec = ChartSpec.of(ChartSpec.Kind.METRIC_SPARKLINE, "default");
        ChartSpecResolver.Resolution resolution = resolver.resolve(spec);

        assertNotNull(resolution);
        assertNotNull(resolution.renderer());
        assertNotNull(resolution.model());
        assertTrue(resolution.model() instanceof DualSparkline);

        DualSparkline model = (DualSparkline) resolution.model();
        assertEquals("MSPT (ms)", model.labelA());
        assertEquals("Heap (MB)", model.labelB());
        assertTrue(model.aMax() >= 50.0);
        assertTrue(model.bMax() >= 256.0);
    }
}
