package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.selection.region.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class EditorSessionManagerCoverageTest {

    @Test
    void testSessionLifecycleAndCap() {
        AtomicLong time = new AtomicLong(1_000_000L);
        EditorSessionManager manager = new EditorSessionManager(time::get);

        // Test creation and retrieval
        String token1 = manager.createSession("{\"payload\": 1}");
        assertNotNull(token1);
        assertEquals(32, token1.length());
        assertEquals("{\"payload\": 1}", manager.getSession(token1));

        // Test getActiveTokens
        Set<String> active = manager.getActiveTokens();
        assertTrue(active.contains(token1));

        // Test removeSession
        assertTrue(manager.removeSession(token1));
        assertNull(manager.getSession(token1));
        assertFalse(manager.removeSession(token1));

        // Test null checks
        assertNull(manager.getSession(null));
        assertFalse(manager.removeSession(null));
        assertThrows(NullPointerException.class, () -> manager.createSession(null));

        // Test TTL eviction (SESSION_TTL_MILLIS = 30 mins = 1,800,000 ms)
        String token2 = manager.createSession("{\"payload\": 2}");
        assertEquals("{\"payload\": 2}", manager.getSession(token2));
        time.addAndGet(EditorSessionManager.SESSION_TTL_MILLIS + 1000L);
        assertNull(manager.getSession(token2));
        assertFalse(manager.getActiveTokens().contains(token2));

        // Test MAX_SESSIONS cap (32)
        time.set(2_000_000L);
        String firstToken = null;
        for (int i = 0; i < EditorSessionManager.MAX_SESSIONS + 5; i++) {
            time.incrementAndGet();
            String tok = manager.createSession("{\"index\": " + i + "}");
            if (i == 0) firstToken = tok;
        }
        assertTrue(manager.getActiveTokens().size() <= EditorSessionManager.MAX_SESSIONS);
        assertNull(manager.getSession(firstToken)); // Oldest should be evicted
    }

    @Test
    void testVisualizationPayload(@TempDir Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(tempDir.toFile());
        io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setServerAccessor(accessor);
        try {
            EditorSessionManager manager = EditorSessionManager.getInstance();
            assertNotNull(manager);

            // null region
            Map<String, Object> emptyPayload = manager.generateVisualizationPayload(null);
            assertTrue(emptyPayload.isEmpty());

            // non-MemoryShape region (null shape)
            io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                    "dummyRegion", null, null, null, false, false, 100, 100, 10, 10, 0.0, 1L, null, false);
            Region nonMemoryRegion = new Region("dummyRegion", settings, true, null);
            Map<String, Object> fallbackPayload = manager.generateVisualizationPayload(nonMemoryRegion);
            assertNotNull(fallbackPayload);
            assertEquals("dummyRegion", fallbackPayload.get("region"));
        } finally {
            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setServerAccessor(null);
        }
    }

    @Test
    void testCreatePayloadJsonAndCanonicalize() {
        EditorSessionManager manager = EditorSessionManager.getInstance();

        Map<String, String> configs = new HashMap<>();
        configs.put("config.yml", "teleportDelay: 5\n");
        configs.put("definitions/regions/default.yml", "shape: Circle\nradius: 1000\n");

        String payloadJson = manager.createPayloadJson(configs);
        assertNotNull(payloadJson);
        assertTrue(payloadJson.contains("config.yml") || payloadJson.contains("teleportDelay"));

        Map<String, Object> channel = Map.of("type", "ws", "port", 8080);
        String payloadWithChannel = manager.createPayloadJson(configs, channel);
        assertNotNull(payloadWithChannel);
    }

    @Test
    void testExportLocalEditorHtml(@TempDir Path tempDir) throws IOException {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        Path htmlFile = tempDir.resolve("editor_export.html");

        Map<String, String> configs = new HashMap<>();
        configs.put("config.yml", "teleportDelay: 10\n");

        manager.exportLocalEditorHtml(htmlFile, configs);
        assertTrue(Files.exists(htmlFile));
        String content = Files.readString(htmlFile);
        assertNotNull(content);
        assertFalse(content.isEmpty());

        // Test with liveFeed flag
        Path htmlFileLive = tempDir.resolve("editor_export_live.html");
        manager.exportLocalEditorHtml(htmlFileLive, configs, true);
        assertTrue(Files.exists(htmlFileLive));
    }

    @Test
    void testConcurrentExportLocalEditorHtml(@TempDir Path tempDir) throws Exception {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        Path htmlFile = tempDir.resolve("concurrent_editor_export.html");

        int threadCount = 8;
        int iterationsPerThread = 4;
        java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int t = 0; t < threadCount; t++) {
                final int threadIdx = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        Map<String, String> configs = new HashMap<>();
                        configs.put("config.yml", "teleportDelay: " + (threadIdx * 100 + i) + "\n");
                        manager.exportLocalEditorHtml(htmlFile, configs, (i & 1) == 0);
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(Files.exists(htmlFile));
        String content = Files.readString(htmlFile);
        assertFalse(content.isEmpty());
        try (var stream = Files.list(tempDir)) {
            assertTrue(stream.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "No leftover .tmp files should remain after concurrent exports");
        }
    }

    @Test
    void testBuildWorldBiomesJson() {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        String jsonFull = manager.buildWorldBiomesJson(true);
        assertNotNull(jsonFull);

        String jsonCoarse = manager.buildWorldBiomesJson(false);
        assertNotNull(jsonCoarse);
    }
}
