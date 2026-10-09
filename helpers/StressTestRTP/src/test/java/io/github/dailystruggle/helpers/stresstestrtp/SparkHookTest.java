package io.github.dailystruggle.helpers.stresstestrtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparkHookTest {

    @Test
    @DisplayName("start command appends --thread for a non-blank selector")
    void startCommandWithThreads() {
        assertEquals("spark profiler start --timeout 90 --thread *",
                SparkHook.buildStartCommand(90L, 0L, "*"));
        assertEquals("spark profiler start --timeout 90 --only-ticks-over 50 --thread Server",
                SparkHook.buildStartCommand(90L, 50L, " Server "));
        assertEquals("spark profiler start --timeout 90 --interval 10 --thread Server",
                SparkHook.buildStartCommand(90L, 0L, "Server", 10L));
    }

    @Test
    @DisplayName("start command emits one --thread per comma entry; names with spaces stay unquoted")
    void startCommandSplitsCommaList() {
        assertEquals("spark profiler start --timeout 60 --thread Server thread --thread Worker-Main",
                SparkHook.buildStartCommand(60L, 0L, " Server  thread , ,Worker-Main,Server thread"));
    }

    @Test
    @DisplayName("start command switches to --regex for a glob entry and escapes exact entries")
    void startCommandGlobUsesRegex() {
        assertEquals("spark profiler start --timeout 60 --regex --thread Server thread --thread RTP-Anvil-IO-.*",
                SparkHook.buildStartCommand(60L, 0L, SparkHook.DEFAULT_THREADS));
        assertEquals("spark profiler start --timeout 60 --regex --thread a\\.b\\(1\\) --thread pool.*",
                SparkHook.buildStartCommand(60L, 0L, "a.b(1),pool*"));
        assertEquals("spark profiler start --timeout 60 --thread *",
                SparkHook.buildStartCommand(60L, 0L, "Server thread,*"));
    }

    @Test
    @DisplayName("default selector regex matches the server thread and every Anvil pool thread, nothing else")
    void defaultSelectorMatchesExpectedThreads() {
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : SparkHook.DEFAULT_THREADS.split(",")) {
            // Mirrors spark's ThreadDumper.Regex: case-insensitive full match.
            patterns.add(Pattern.compile(SparkHook.globToRegex(entry.trim()), Pattern.CASE_INSENSITIVE));
        }
        Predicate<String> sampled = name -> patterns.stream().anyMatch(p -> p.matcher(name).matches());
        assertTrue(sampled.test("Server thread"));
        assertTrue(sampled.test("RTP-Anvil-IO-0"));
        assertTrue(sampled.test("RTP-Anvil-IO-17"));
        assertFalse(sampled.test("Server thread 2"));
        assertFalse(sampled.test("Worker-Main-3"));
        assertFalse(sampled.test("RTP-Anvil-IO"));
    }

    @Test
    @DisplayName("start command omits --thread for an empty or null selector")
    void startCommandWithoutThreads() {
        assertEquals("spark profiler start --timeout 60", SparkHook.buildStartCommand(60L, 0L, ""));
        assertEquals("spark profiler start --timeout 60", SparkHook.buildStartCommand(60L, 0L, "  "));
        assertEquals("spark profiler start --timeout 60 --only-ticks-over 5",
                SparkHook.buildStartCommand(60L, 5L, null));
        assertEquals("spark profiler start --timeout 60 --interval 5",
                SparkHook.buildStartCommand(60L, 0L, null, 5L));
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
