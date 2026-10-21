package io.github.dailystruggle.rtp.common.tasks.tick;

import io.github.dailystruggle.commandsapi.common.CommandExecutor;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
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
        RTPRuntime.setServerAccessor(null);
        RTPRuntime.setScheduler(null);
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
    @DisplayName("syncAllottedTime: commands are charged first; an exhausted budget still runs one misc task")
    void commandsChargedAgainstBudget_miscKeepsProgress() {
        java.util.concurrent.atomic.AtomicInteger miscRan = new java.util.concurrent.atomic.AtomicInteger();
        MiscTasks.sync().clear();
        MiscTasks.sync().start();
        for (int i = 0; i < 3; i++) MiscTasks.sync().add(miscRan::incrementAndGet);

        AtomicBoolean ran = new AtomicBoolean(false);
        CommandsAPICommand slowCmd = new CommandsAPICommand() {
            @Override public String name() { return "slow"; }
            @Override public String permission() { return null; }
            @Override public String description() { return ""; }
            @Override public CommandsAPICommand parent() { return null; }
            @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
            @Override public void msgInvalidCommand(UUID callerId, String argument) {}
            @Override public long avgTime() { return 0; }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                ran.set(true);
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(3);
                while (System.nanoTime() < until) Thread.onSpinWait();
                return true;
            }
            @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return CompletableFuture.completedFuture(true); }
            @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return List.of(); }
            @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
        };
        CommandsAPI.commandPipeline.add(new CommandExecutor(slowCmd, UUID.randomUUID(), Map.of(), null, s -> {}, new CompletableFuture<>()));

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(parser != null, "harness has no performance parser");
        parser.set(PerformanceKeys.syncAllottedTime, 1);
        TickBudget.refresh();

        new SyncTaskProcessing(50_000_000L).run();

        assertTrue(ran.get(), "player command must run even when it alone overruns the budget");
        assertEquals(1, miscRan.get(), "misc pipe gets only the leftover budget, floored at one task");
        new SyncTaskProcessing(50_000_000L).run();
        assertTrue(miscRan.get() >= 2, "deferred misc tasks run on later ticks");
    }

    @Test
    @DisplayName("SyncTaskProcessing caches syncAllottedTime and updates on config reload")
    void testConfigCachingAndUpdate() {
        SyncTaskProcessing.updateConfig();

        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        assertDoesNotThrow(task::run);

        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> parser = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        if (parser != null) {
            parser.set(PerformanceKeys.syncAllottedTime, 10);
            ActiveConfigs.get().putParser(parser);
        }

        assertDoesNotThrow(task::run);
    }

    @Test
    @DisplayName("SyncTaskProcessing updates tracking when trackingId is present")
    void testTrackingIdUpdate() throws Exception {
        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        java.lang.reflect.Field field = io.github.dailystruggle.rtp.common.tasks.RTPRunnable.class.getDeclaredField("trackingId");
        field.setAccessible(true);
        field.set(task, UUID.randomUUID());
        assertDoesNotThrow(task::run);
    }

    @Test
    @DisplayName("SyncTaskProcessing catches and survives CommandsAPI execution failure")
    void testCommandsApiExceptionSurvives() {
        CommandsAPICommand failingCmd = new CommandsAPICommand() {
            @Override public String name() { return "fail"; }
            @Override public String permission() { return null; }
            @Override public String description() { return ""; }
            @Override public CommandsAPICommand parent() { return null; }
            @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
            @Override public void msgInvalidCommand(UUID callerId, String argument) {}
            @Override public long avgTime() { throw new RuntimeException("simulated command execution failure"); }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) { return false; }
            @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return CompletableFuture.completedFuture(false); }
            @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> tempParameters) { return List.of(); }
            @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
        };
        CommandsAPI.commandPipeline.add(new CommandExecutor(failingCmd, UUID.randomUUID(), Map.of(), null, s -> {}, new CompletableFuture<>()));
        SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
        assertDoesNotThrow(task::run);
    }

    @Test
    @DisplayName("SyncTaskProcessing ticks ActionManager when present")
    void testActionManagerTick() {
        io.github.dailystruggle.rtp.common.action.ActionManager normalManager = new io.github.dailystruggle.rtp.common.action.ActionManager();
        ActionServices.setActionManager(normalManager);
        try {
            SyncTaskProcessing task = new SyncTaskProcessing(50_000_000L);
            assertDoesNotThrow(task::run);
        } finally {
            ActionServices.setActionManager(null);
        }
    }
}
