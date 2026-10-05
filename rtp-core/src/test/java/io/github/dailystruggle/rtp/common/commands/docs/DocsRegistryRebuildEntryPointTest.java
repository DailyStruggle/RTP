package io.github.dailystruggle.rtp.common.commands.docs;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.EditorSessionManager;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocsRegistry startup/reload entry point feeds /rtp docs and the editor payload (ADR-045, ADR-104, S-004, S-005)")
class DocsRegistryRebuildEntryPointTest {

    /** Runs every task inline; counts async submissions to prove the rebuild is routed off-thread. */
    private static final class InlineScheduler implements RTPScheduler {
        int asyncSubmissions;

        @Override public TrackedRTPTask runTaskAsynchronously(Runnable task) { asyncSubmissions++; task.run(); return null; }
        @Override public void runTask(Runnable task) { task.run(); }
        @Override public void runTaskLater(Runnable task, long delay) { task.run(); }
        @Override public Object runTaskTimer(Runnable task, long delay, long period) { return new Object(); }
        @Override public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) { return new Object(); }
        @Override public void cancelTask(Object task) { }
        @Override public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) { }
        @Override public void runTask(RTPLocation location, Runnable task) { task.run(); }
        @Override public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) { task.run(); }
        @Override public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) { return new Object(); }
        @Override public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) { task.run(); }
    }

    @TempDir Path dataFolder;

    private RTPServerAccessor previousAccessor;
    private RTPScheduler previousScheduler;
    private InlineScheduler scheduler;

    @BeforeEach
    void setUp() {
        previousAccessor = RTP.serverAccessor;
        previousScheduler = RTP.scheduler;
        RTP.serverAccessor = new MockRTPServerAccessor(dataFolder.toFile());
        scheduler = new InlineScheduler();
        RTP.scheduler = scheduler;
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = previousAccessor;
        RTP.scheduler = previousScheduler;
    }

    @Test
    @DisplayName("rebuildFromDataFolder indexes <dataFolder>/docs via an async task; the editor payload carries it")
    void rebuildFromDataFolder_feedsRawSourcesAndEditorPayload() throws IOException {
        Path admin = dataFolder.resolve("docs").resolve("admin");
        Files.createDirectories(admin);
        Files.writeString(admin.resolve("HELLO.md"), "# Hello\nShipped doc.");

        DocsRegistry.rebuildFromDataFolder(dataFolder.toFile());

        assertEquals(1, scheduler.asyncSubmissions, "rebuild must be routed through RTP.scheduler async");
        Map<String, String> raw = DocsRegistry.getInstance().getAllRawSources();
        assertEquals(Map.of("admin/HELLO.md", "# Hello\nShipped doc."), raw);

        String payload = new EditorSessionManager().createPayloadJson(Map.of("config.yml", "a: 1"));
        assertTrue(payload.contains("\"docs\":{\"admin/HELLO.md\":\"# Hello\\nShipped doc.\"}"),
                "editor payload must embed the indexed doc");
    }

    @Test
    @DisplayName("Missing docs folder warns instead of throwing and leaves an initialized, empty registry")
    void rebuildFromDataFolder_missingFolder_doesNotThrow() {
        assertDoesNotThrow(() -> DocsRegistry.rebuildFromDataFolder(dataFolder.resolve("absent").toFile()));
        assertDoesNotThrow(() -> DocsRegistry.rebuildFromDataFolder(null));

        assertTrue(DocsRegistry.getInstance().getAllRawSources().isEmpty());
        assertTrue(DocsRegistry.getInstance().getAll().containsKey("index"), "synthetic index still served");
    }
}
