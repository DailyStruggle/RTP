package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolver;
import io.github.dailystruggle.rtp.common.commands.maps.ChartSpecResolvers;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("VisualizationDispatch and leaves unit tests")
class VisualizationDispatchTest {

    @TempDir
    Path tempDir;

    private UUID adminViewer;
    private UUID normalViewer;
    private VisualizationDispatch dispatch;
    private List<String> capturedMessages;
    private Consumer<String> messageMethod;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        adminViewer = UUID.randomUUID();
        normalViewer = UUID.randomUUID();
        capturedMessages = new ArrayList<>();
        messageMethod = capturedMessages::add;

        dispatch = new VisualizationDispatch(uuid -> perm -> {
            if (uuid.equals(adminViewer)) return true;
            return false;
        });
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
        MapDispatch.setMapBinding(new NoopMapBinding());
    }

    @Test
    @DisplayName("paintBadLocations input validation and permission gating")
    void paintBadLocations_validations() {
        // Null viewer
        assertFalse(dispatch.paintBadLocations(null, "default", messageMethod));

        // Empty/null region
        assertFalse(dispatch.paintBadLocations(adminViewer, null, messageMethod));
        assertFalse(dispatch.paintBadLocations(adminViewer, "", messageMethod));

        // Lacks admin permission
        assertFalse(dispatch.paintBadLocations(normalViewer, "default", messageMethod));
        assertFalse(capturedMessages.isEmpty());
    }

    private static final class FakeMapBinding implements MapBinding {
        @Override
        public io.github.dailystruggle.mapsapi.MapHandle allocate(io.github.dailystruggle.mapsapi.MapAllocationRequest request) {
            return new io.github.dailystruggle.mapsapi.MapHandle(request.chartId(), request.viewer(), 1);
        }

        @Override
        public <M extends io.github.dailystruggle.mapsapi.model.ChartModel> void renderEphemeral(
                io.github.dailystruggle.mapsapi.MapHandle handle,
                io.github.dailystruggle.mapsapi.render.ChartRenderer<M> renderer,
                M model) {
        }

        @Override
        public <M extends io.github.dailystruggle.mapsapi.model.ChartModel> io.github.dailystruggle.mapsapi.Cancellation bindLive(
                io.github.dailystruggle.mapsapi.MapHandle handle,
                io.github.dailystruggle.mapsapi.render.ChartRenderer<M> renderer,
                java.util.function.Supplier<M> modelSupplier) {
            return new io.github.dailystruggle.mapsapi.Cancellation() {
                private boolean cancelled = false;
                @Override public void cancel() { cancelled = true; }
                @Override public boolean cancelled() { return cancelled; }
            };
        }
    }

    private static io.github.dailystruggle.mapsapi.model.DualSparkline dummyModel() {
        return new io.github.dailystruggle.mapsapi.model.DualSparkline(
                "titleA", new double[]{1.0}, 0.0, 10.0,
                "titleB", new double[]{2.0}, 0.0, 10.0);
    }

    @Test
    @DisplayName("paintBadLocations succeeds when map binding paints successfully")
    void paintBadLocations_success() {
        io.github.dailystruggle.mapsapi.render.ChartRenderer renderer = mock(io.github.dailystruggle.mapsapi.render.ChartRenderer.class);
        ChartSpecResolver.Resolution resolution = new ChartSpecResolver.Resolution(renderer, dummyModel());
        ChartSpecResolvers.register(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, spec -> resolution);

        MapDispatch.setMapBinding(new FakeMapBinding());
        assertTrue(dispatch.paintBadLocations(adminViewer, "default", messageMethod));
    }

    @Test
    @DisplayName("paintBiomes succeeds when map binding paints successfully")
    void paintBiomes_success() {
        io.github.dailystruggle.mapsapi.render.ChartRenderer renderer = mock(io.github.dailystruggle.mapsapi.render.ChartRenderer.class);
        ChartSpecResolver.Resolution resolution = new ChartSpecResolver.Resolution(renderer, dummyModel());
        ChartSpecResolvers.register(ChartSpec.Kind.REGION_BIOMES, spec -> resolution);

        MapDispatch.setMapBinding(new FakeMapBinding());
        assertTrue(dispatch.paintBiomes(adminViewer, "default", messageMethod));
    }

    @Test
    @DisplayName("paintSparkline input validation and success")
    void paintSparkline_lifecycle() {
        assertFalse(dispatch.paintSparkline(null, messageMethod));
        assertFalse(dispatch.paintSparkline(normalViewer, messageMethod));

        io.github.dailystruggle.mapsapi.render.ChartRenderer renderer = mock(io.github.dailystruggle.mapsapi.render.ChartRenderer.class);
        ChartSpecResolver.Resolution resolution = new ChartSpecResolver.Resolution(renderer, dummyModel());
        ChartSpecResolvers.register(ChartSpec.Kind.METRIC_SPARKLINE, spec -> resolution);

        MapDispatch.setMapBinding(new FakeMapBinding());
        assertTrue(dispatch.paintSparkline(adminViewer, messageMethod));
    }

    @Test
    @DisplayName("paintPipeline input validation and success")
    void paintPipeline_lifecycle() {
        assertFalse(dispatch.paintPipeline(null, "default", messageMethod));
        assertFalse(dispatch.paintPipeline(adminViewer, null, messageMethod));
        assertFalse(dispatch.paintPipeline(adminViewer, "", messageMethod));
        assertFalse(dispatch.paintPipeline(normalViewer, "default", messageMethod));

        io.github.dailystruggle.mapsapi.render.ChartRenderer renderer = mock(io.github.dailystruggle.mapsapi.render.ChartRenderer.class);
        ChartSpecResolver.Resolution resolution = new ChartSpecResolver.Resolution(renderer, dummyModel());
        ChartSpecResolvers.register(ChartSpec.Kind.REGION_COMPOSITE, spec -> resolution);

        MapDispatch.setMapBinding(new FakeMapBinding());
        assertTrue(dispatch.paintPipeline(adminViewer, "default", messageMethod));
    }

    @Test
    @DisplayName("VisualizationBadLocationsCmd falls back to selector when region is omitted")
    void badLocationsCmd_fallbackToSelector() {
        AtomicBoolean selectorOpened = new AtomicBoolean(false);
        MenuConcreteCommandLeaves.VisualizationBadLocationsCmd cmd =
                new MenuConcreteCommandLeaves.VisualizationBadLocationsCmd(
                        dispatch,
                        (uuid, msg) -> {
                            selectorOpened.set(true);
                            return true;
                        });

        boolean res = cmd.onCommand(adminViewer, java.util.Collections.emptyMap(), null, messageMethod);
        assertTrue(res);
        assertTrue(selectorOpened.get());
    }

    @Test
    @DisplayName("VisualizationBiomesCmd falls back to selector when region is omitted")
    void biomesCmd_fallbackToSelector() {
        AtomicBoolean selectorOpened = new AtomicBoolean(false);
        MenuConcreteCommandLeaves.VisualizationBiomesCmd cmd =
                new MenuConcreteCommandLeaves.VisualizationBiomesCmd(
                        dispatch,
                        (uuid, msg) -> {
                            selectorOpened.set(true);
                            return true;
                        });

        boolean res = cmd.onCommand(adminViewer, java.util.Collections.emptyMap(), null, messageMethod);
        assertTrue(res);
        assertTrue(selectorOpened.get());
    }

    @Test
    @DisplayName("VisualizationPipelineCmd falls back to selector when region is omitted")
    void pipelineCmd_fallbackToSelector() {
        AtomicBoolean selectorOpened = new AtomicBoolean(false);
        MenuConcreteCommandLeaves.VisualizationPipelineCmd cmd =
                new MenuConcreteCommandLeaves.VisualizationPipelineCmd(
                        dispatch,
                        (uuid, msg) -> {
                            selectorOpened.set(true);
                            return true;
                        });

        boolean res = cmd.onCommand(adminViewer, java.util.Collections.emptyMap(), null, messageMethod);
        assertTrue(res);
        assertTrue(selectorOpened.get());
    }
}
