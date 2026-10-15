package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-112: generation results that complete on a Folia region thread continue off it. */
class TeleportPipelineTaskOffRegionTest {
    private RTPServerAccessor previousAccessor;
    private RTPScheduler previousScheduler;
    private RTPServerAccessor accessor;
    private RTPScheduler scheduler;

    @BeforeEach
    void setUp() {
        previousAccessor = RTP.serverAccessor;
        previousScheduler = RTP.scheduler;
        accessor = Mockito.mock(RTPServerAccessor.class);
        scheduler = Mockito.mock(RTPScheduler.class);
        RTP.serverAccessor = accessor;
        RTP.scheduler = scheduler;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = previousAccessor;
        RTP.scheduler = previousScheduler;
    }

    @Test
    @DisplayName("ADR-112: on a Folia region thread the result handling is handed to the async scheduler")
    void foliaRegionThreadHopsToAsync() {
        when(accessor.getPlatform()).thenReturn("Folia");
        when(accessor.isPrimaryThread()).thenReturn(true);
        AtomicBoolean ran = new AtomicBoolean();

        TeleportPipelineTask.continueOffRegion(() -> ran.set(true));

        assertFalse(ran.get(), "must not run on the region thread that completed the result");
        verify(scheduler).runTaskAsynchronously(any(Runnable.class));
    }

    @Test
    @DisplayName("ADR-112: off a tick thread, or on other platforms, the result is handled inline as before")
    void otherwiseRunsInline() {
        when(accessor.getPlatform()).thenReturn("Folia");
        when(accessor.isPrimaryThread()).thenReturn(false);
        AtomicBoolean ran = new AtomicBoolean();
        TeleportPipelineTask.continueOffRegion(() -> ran.set(true));
        assertTrue(ran.get());

        when(accessor.getPlatform()).thenReturn("Paper");
        when(accessor.isPrimaryThread()).thenReturn(true);
        AtomicBoolean ranPaper = new AtomicBoolean();
        TeleportPipelineTask.continueOffRegion(() -> ranPaper.set(true));
        assertTrue(ranPaper.get());
        verify(scheduler, never()).runTaskAsynchronously(any(Runnable.class));
    }

    @Test
    @DisplayName("S-004: a failing hop never strands the result; it is handled inline")
    void failingHopRunsInline() {
        when(accessor.getPlatform()).thenReturn("Folia");
        when(accessor.isPrimaryThread()).thenReturn(true);
        when(scheduler.runTaskAsynchronously(any(Runnable.class))).thenThrow(new IllegalStateException("disabled"));
        AtomicBoolean ran = new AtomicBoolean();

        TeleportPipelineTask.continueOffRegion(() -> ran.set(true));

        assertTrue(ran.get());
    }
}
