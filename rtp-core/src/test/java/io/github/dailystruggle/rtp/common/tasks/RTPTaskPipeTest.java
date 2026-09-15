package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RTPTaskPipeTest {

    @TempDir
    File tempDir;

    static class DummyPipe extends RTPTaskPipe {
        boolean executed = false;

        @Override
        public boolean execute() {
            executed = true;
            return true;
        }

        @Override
        public boolean execute(long availableTime) {
            executed = true;
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
    }

    @Test
    void initialSizeIsZero() {
        DummyPipe pipe = new DummyPipe();
        assertEquals(0, pipe.size());
    }

    @Test
    void addIncreasesSize() {
        DummyPipe pipe = new DummyPipe();
        pipe.add(() -> {});
        assertEquals(1, pipe.size());
        pipe.add(() -> {});
        assertEquals(2, pipe.size());
    }

    @Test
    void clearEmptiesQueueAndResetsStop() {
        DummyPipe pipe = new DummyPipe();
        pipe.add(() -> {});
        pipe.stop();
        assertEquals(0, pipe.size());

        pipe.clear();
        assertEquals(0, pipe.size());
    }

    @Test
    void startResetsStopFlag() {
        DummyPipe pipe = new DummyPipe();
        pipe.stop();
        pipe.start();
        pipe.add(() -> {});
        assertEquals(1, pipe.size());
    }

    @Test
    void stopCancelsEnqueuedRTPRunnablesAndClearsQueue() {
        DummyPipe pipe = new DummyPipe();
        AtomicBoolean ran1 = new AtomicBoolean(false);
        AtomicBoolean ran2 = new AtomicBoolean(false);

        RTPRunnable rtpRunnable = new RTPRunnable(() -> ran1.set(true));
        Runnable normalRunnable = () -> ran2.set(true);

        pipe.add(rtpRunnable);
        pipe.add(normalRunnable);
        assertEquals(2, pipe.size());
        assertFalse(rtpRunnable.isCancelled());

        pipe.stop();

        assertTrue(rtpRunnable.isCancelled());
        assertEquals(0, pipe.size());
    }

    @Test
    void executeMethodsInvokeImplementation() {
        DummyPipe pipe = new DummyPipe();
        assertFalse(pipe.executed);
        assertTrue(pipe.execute());
        assertTrue(pipe.executed);

        pipe.executed = false;
        assertTrue(pipe.execute(1000L));
        assertTrue(pipe.executed);
    }
}
