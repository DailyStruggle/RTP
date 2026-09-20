package io.github.dailystruggle.rtp.common.server;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("DatabaseProcessing lifecycle and execution tests")
class DatabaseProcessingTest {

    @TempDir
    Path tempDir;

    private RTPScheduler mockScheduler;
    private DatabaseAccessor mockAccessor;
    private RTPScheduler originalScheduler;
    private DatabaseAccessor originalAccessor;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        originalScheduler = RTP.scheduler;
        originalAccessor = RTP.getInstance().databaseAccessor;

        mockScheduler = mock(RTPScheduler.class);
        mockAccessor = mock(DatabaseAccessor.class);

        RTP.scheduler = mockScheduler;
        RTP.getInstance().databaseAccessor = mockAccessor;
        DatabaseProcessing.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseProcessing.kill();
        RTP.scheduler = originalScheduler;
        RTP.getInstance().databaseAccessor = originalAccessor;
    }

    @Test
    void testStartAndRunTask() {
        Object mockTask = new Object();
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(mockScheduler.runTaskTimerAsynchronously(runnableCaptor.capture(), anyLong(), anyLong()))
                .thenReturn(mockTask);

        DatabaseProcessing.start();
        verify(mockScheduler).runTaskTimerAsynchronously(any(Runnable.class), eq(100L), eq(100L));

        Runnable scheduledRunnable = runnableCaptor.getValue();
        assertNotNull(scheduledRunnable);

        // Execute the scheduled task
        scheduledRunnable.run();
        verify(mockAccessor, atLeastOnce()).processQueries(Long.MAX_VALUE);

        // Clear task
        DatabaseProcessing.clear();
        verify(mockScheduler).cancelTask(mockTask);
    }

    @Test
    void testKillPreventsExecution() {
        Object mockTask = new Object();
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(mockScheduler.runTaskTimerAsynchronously(runnableCaptor.capture(), anyLong(), anyLong()))
                .thenReturn(mockTask);

        DatabaseProcessing.start();
        Runnable scheduledRunnable = runnableCaptor.getValue();

        DatabaseProcessing.kill();
        scheduledRunnable.run();

        // After kill(), runnable should return immediately without processing
        verify(mockAccessor, never()).processQueries(anyLong());
    }
}
