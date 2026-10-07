package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SparkHookTest {

    @Test
    @DisplayName("start command appends --thread for a non-blank selector")
    void startCommandWithThreads() {
        assertEquals("spark profiler start --timeout 90 --thread *",
                SparkHook.buildStartCommand(90L, 0L, "*"));
        assertEquals("spark profiler start --timeout 90 --only-ticks-over 50 --thread Server",
                SparkHook.buildStartCommand(90L, 50L, " Server "));
    }

    @Test
    @DisplayName("start command omits --thread for an empty or null selector")
    void startCommandWithoutThreads() {
        assertEquals("spark profiler start --timeout 60", SparkHook.buildStartCommand(60L, 0L, ""));
        assertEquals("spark profiler start --timeout 60", SparkHook.buildStartCommand(60L, 0L, "  "));
        assertEquals("spark profiler start --timeout 60 --only-ticks-over 5",
                SparkHook.buildStartCommand(60L, 5L, null));
    }

    @Test
    @DisplayName("profile watch reports a new file once its size is stable across two polls")
    void newFileReportedWhenStable(@TempDir Path dir) throws Exception {
        long stop = System.currentTimeMillis();
        SparkHook.ProfileWatch watch = new SparkHook.ProfileWatch(dir, stop);
        assertNull(watch.poll());

        Path saved = dir.resolve("profile-2026-10-06_20.07.00.sparkprofile");
        Files.write(saved, new byte[10]);
        assertNull(watch.poll()); // first sighting
        assertEquals(saved, watch.poll());
    }

    @Test
    @DisplayName("profile watch keeps waiting while the file is still growing")
    void growingFileNotReported(@TempDir Path dir) throws Exception {
        SparkHook.ProfileWatch watch = new SparkHook.ProfileWatch(dir, System.currentTimeMillis());
        Path saved = dir.resolve("profile-a.sparkprofile");
        Files.write(saved, new byte[10]);
        assertNull(watch.poll());
        Files.write(saved, new byte[10], StandardOpenOption.APPEND);
        assertNull(watch.poll()); // size changed since last poll
        assertEquals(saved, watch.poll());
    }

    @Test
    @DisplayName("profile watch ignores empty files")
    void emptyFileNotReported(@TempDir Path dir) throws Exception {
        SparkHook.ProfileWatch watch = new SparkHook.ProfileWatch(dir, System.currentTimeMillis());
        Files.createFile(dir.resolve("profile-empty.sparkprofile"));
        assertNull(watch.poll());
        assertNull(watch.poll());
    }

    @Test
    @DisplayName("profile watch ignores profiles that existed before the stop")
    void preExistingFileIgnored(@TempDir Path dir) throws Exception {
        long stop = System.currentTimeMillis();
        Path old = dir.resolve("profile-old.sparkprofile");
        Files.write(old, new byte[64]);
        Files.setLastModifiedTime(old, FileTime.fromMillis(stop - 60_000L));
        Files.write(dir.resolve("unrelated.txt"), new byte[64]);

        SparkHook.ProfileWatch watch = new SparkHook.ProfileWatch(dir, stop);
        assertNull(watch.poll());
        assertNull(watch.poll());

        Path saved = dir.resolve("profile-new.sparkprofile");
        Files.write(saved, new byte[32]);
        Files.setLastModifiedTime(saved, FileTime.fromMillis(stop + 1_000L));
        assertNull(watch.poll());
        assertEquals(saved, watch.poll());
    }

    @Test
    @DisplayName("profile watch accepts a file already present but written after the stop")
    void fileWrittenBeforeSnapshotButAfterStopAccepted(@TempDir Path dir) throws Exception {
        long stop = System.currentTimeMillis() - 5_000L;
        Path saved = dir.resolve("profile-racing.sparkprofile");
        Files.write(saved, new byte[16]);
        Files.setLastModifiedTime(saved, FileTime.fromMillis(stop + 500L));

        SparkHook.ProfileWatch watch = new SparkHook.ProfileWatch(dir, stop);
        assertNull(watch.poll());
        assertEquals(saved, watch.poll());
    }
}
