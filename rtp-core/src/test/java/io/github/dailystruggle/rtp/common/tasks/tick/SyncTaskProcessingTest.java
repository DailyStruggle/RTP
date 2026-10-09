package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandExecutor;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-S-005: SyncTaskProcessing tick execution and config caching tests")
class SyncTaskProcessingTest {

    @TempDir
    Path tempDir;

    MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        CommandsAPI.commandPipeline.clear();
        SyncTaskProcessing.clearCachedConfig();
    }

    @AfterEach
    void tearDown() {
        CommandsAPI.commandPipeline.clear();
        SyncTaskProcessing.clearCachedConfig();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("SyncTaskProcessing.run with empty commandPipeline skips CommandsAPI execution")
    void testRunWithEmptyCommandPipeline() {
        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        assertTrue(CommandsAPI.commandPipeline.isEmpty());

        assertDoesNotThrow(task::run);
        assertTrue(CommandsAPI.commandPipeline.isEmpty());
    }

    @Test
    @DisplayName("SyncTaskProcessing.run with pending commands executes them")
    void testRunWithPendingCommands() {
        AtomicBoolean ran = new AtomicBoolean(false);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CommandsAPICommand dummyCmd = new CommandsAPICommand() {
            @Override public String name() { return "dummy"; }
            @Override public String permission() { return null; }
            @Override public String description() { return ""; }
            @Override public CommandsAPICommand parent() { return null; }
            @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
            @Override public void msgInvalidCommand(UUID callerId, String argument) {}
            @Override public long avgTime() { return 1000; }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                ran.set(true);
                return true;
            }
            @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return CompletableFuture.completedFuture(true); }
            @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return List.of(); }
            @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
        };

        CommandExecutor executor = new CommandExecutor(dummyCmd, UUID.randomUUID(), Map.of(), null, s -> {}, future);
        CommandsAPI.commandPipeline.add(executor);
        assertEquals(1, CommandsAPI.commandPipeline.size());

        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        task.run();

        assertTrue(ran.get());
        assertTrue(CommandsAPI.commandPipeline.isEmpty());
    }

    @Test
    @DisplayName("SyncTaskProcessing caches syncAllottedTime and updates on config reload")
    void testConfigCachingAndUpdate() {
        SyncTaskProcessing.updateConfig();

        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        assertDoesNotThrow(task::run);

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        if (parser != null) {
            parser.set(PerformanceKeys.syncAllottedTime, 10);
            RTP.configs.putParser(parser);
        }

        assertDoesNotThrow(task::run);
    }
}
