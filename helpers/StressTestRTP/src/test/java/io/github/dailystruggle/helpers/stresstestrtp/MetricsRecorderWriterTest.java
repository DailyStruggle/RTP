package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRecorderWriterTest {

    private static MetricsRecorder.Attempt timedOut(MetricsRecorder rec, String label) {
        MetricsRecorder.Attempt a = new MetricsRecorder.Attempt(UUID.randomUUID(), "bot", "world", label,
                System.currentTimeMillis(), 0, 0, 20.0, 50.0, 512);
        rec.onDispatch(a);
        rec.onTimeout(a);
        return a;
    }

    private static List<String> lines(Path csv) throws Exception {
        return Files.readAllLines(csv);
    }

    @Test
    @DisplayName("rows across calls share one writer and are all on disk after flush")
    void rowsShareOneWriter(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("run.csv");
        MetricsRecorder rec = new MetricsRecorder(csv);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(timedOut(rec, "rtp").attemptId);

        rec.flushRows();
        List<String> l = lines(csv);
        assertEquals(MetricsRecorder.CSV_HEADER, l.get(0));
        assertEquals(6, l.size());
        for (int i = 0; i < ids.size(); i++) {
            assertTrue(l.get(i + 1).startsWith(ids.get(i).toString() + ","));
        }
        assertEquals(1, rec.rowWriterOpenCount());
        rec.close();
    }

    @Test
    @DisplayName("close flushes buffered rows; a later row reopens in append mode")
    void closeFlushesAndReopenAppends(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("run.csv");
        MetricsRecorder rec = new MetricsRecorder(csv);
        timedOut(rec, "rtp");
        timedOut(rec, "rtp");
        rec.close();
        rec.close(); // idempotent
        assertEquals(3, lines(csv).size());

        timedOut(rec, "rtp");
        rec.close();
        List<String> l = lines(csv);
        assertEquals(4, l.size());
        assertEquals(MetricsRecorder.CSV_HEADER, l.get(0));
        assertEquals(2, rec.rowWriterOpenCount());
    }

    @Test
    @DisplayName("phase end flushes buffered rows")
    void endPhaseFlushes(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("run.csv");
        MetricsRecorder rec = new MetricsRecorder(csv);
        rec.beginPhase("rtp");
        timedOut(rec, "rtp");
        rec.endPhase("rtp");
        assertEquals(2, lines(csv).size());
        rec.close();
    }

    @Test
    @DisplayName("concurrent writers produce whole rows, one per attempt")
    void concurrentRowsStayWhole(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("run.csv");
        MetricsRecorder rec = new MetricsRecorder(csv);
        int threads = 4, perThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread w = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) timedOut(rec, "rtp");
            });
            workers.add(w);
            w.start();
        }
        start.countDown();
        for (Thread w : workers) w.join();
        rec.close();

        List<String> l = lines(csv);
        assertEquals(1 + threads * perThread, l.size());
        int columns = MetricsRecorder.CSV_HEADER.split(",", -1).length;
        for (String row : l.subList(1, l.size())) {
            assertEquals(columns, row.split(",", -1).length);
        }
        assertEquals(1, rec.rowWriterOpenCount());
    }
}
