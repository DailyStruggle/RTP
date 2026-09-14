package io.github.dailystruggle.commandsapi.common;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.commandsapi.common.parameters.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@DisplayName("Comprehensive tests for CommandsAPI, CommandParameter, and common parameters")
class CommonParametersAndAPITest {

    enum SampleEnum {
        FIRST, SECOND, THIRD
    }

    @Test
    @DisplayName("CommandsAPI lifecycle, execution, message sink, and defaults")
    void testCommandsAPILifecycle() {
        UUID caller = UUID.randomUUID();

        // Fallback message method
        List<String> messages = new ArrayList<>();
        Consumer<String> defaultMsg = CommandsAPI.messageMethodFor(caller, messages::add);
        defaultMsg.accept("hello");
        assertEquals(List.of("hello"), messages);

        // Custom MessageSink
        List<String> sinkMessages = new ArrayList<>();
        MessageSink customSink = (id, msg) -> sinkMessages.add(id + ":" + msg);
        CommandsAPI.setMessageSink(customSink);

        Consumer<String> routedMsg = CommandsAPI.messageMethodFor(caller, messages::add);
        routedMsg.accept("world");
        assertEquals(List.of(caller + ":world"), sinkMessages);

        // Reset to default sink
        CommandsAPI.setMessageSink(null);
        messages.clear();
        CommandsAPI.messageMethodFor(caller, messages::add).accept("after reset");
        assertEquals(List.of("after reset"), messages);

        // Command execution pipeline
        CommandsAPI.commandPipeline.clear();
        assertEquals(0, CommandsAPI.commandPipeline.size());

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
            @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, Consumer<String> messageMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) { return CompletableFuture.completedFuture(true); }
            @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) { return List.of(); }
            @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
        };

        CommandExecutor executor = new CommandExecutor(dummyCmd, caller, Map.of(), null, s -> {}, future);

        CommandsAPI.commandPipeline.add(executor);
        assertEquals(1, CommandsAPI.commandPipeline.size());

        CommandsAPI.execute();
        assertTrue(ran.get());
        assertTrue(future.isDone());

        // Drain pipeline while empty
        long remaining = CommandsAPI.execute();
        assertEquals(0, remaining);
    }

    @Test
    @DisplayName("BooleanParameter, IntegerParameter, FloatParameter, CoordinateParameter, EnumParameter")
    void testParameterImplementations() {
        UUID caller = UUID.randomUUID();

        // 1. BooleanParameter
        BooleanParameter boolParam = new BooleanParameter("perm.bool", "bool desc", (u, s) -> true);
        assertEquals("perm.bool", boolParam.permission());
        assertEquals("bool desc", boolParam.description());
        assertEquals(Set.of("true", "false"), boolParam.values());
        assertTrue(boolParam.relevantValues(caller).containsAll(List.of("true", "false")));

        // 2. IntegerParameter
        IntegerParameter intParam = new IntegerParameter("perm.int", "int desc", (u, s) -> true, 0);
        assertEquals(Set.of("0"), intParam.values());
        assertTrue(intParam.relevantValues(caller).contains("0"));

        // 3. FloatParameter
        FloatParameter floatParam = new FloatParameter("perm.float", "float desc", (u, s) -> true, 0.0);
        assertEquals(Set.of("0"), floatParam.values());
        assertTrue(floatParam.relevantValues(caller).contains("0"));

        // 4. CoordinateParameter
        CoordinateParameter coordParam = new CoordinateParameter("perm.coord", "coord desc", (u, s) -> true);
        assertEquals(Set.of("~", "-~", "0"), coordParam.values());
        assertTrue(coordParam.relevantValues(caller).contains("~"));

        // 5. EnumParameter
        EnumParameter<SampleEnum> enumParam = new EnumParameter<>(
                "perm.enum", "enum desc", (u, s) -> true, SampleEnum.class);
        Set<String> enumVals = enumParam.values();
        assertTrue(enumVals.contains("FIRST"));
        assertTrue(enumVals.contains("SECOND"));
        assertTrue(enumVals.contains("THIRD"));
    }

    @Test
    @DisplayName("CommandParameter subParams default behavior and custom overrides")
    void testCommandParameterSubParams() {
        CommandParameter baseParam = new CommandParameter("perm", "desc", (u, s) -> true) {
            @Override
            public Set<String> values() {
                return Set.of("v1", "v2");
            }
        };

        assertNull(baseParam.subParams("v1"));
        assertNull(baseParam.subParams("unknown"));
    }

    @Test
    @DisplayName("CommandExecutor default run invocation with target onCommand")
    void testCommandExecutorRun() {
        UUID caller = UUID.randomUUID();
        AtomicBoolean targetRan = new AtomicBoolean(false);
        CommandsAPICommand target = new CommandsAPICommand() {
            @Override public String name() { return "target"; }
            @Override public String permission() { return null; }
            @Override public String description() { return ""; }
            @Override public CommandsAPICommand parent() { return null; }
            @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
            @Override public void msgInvalidCommand(UUID callerId, String argument) {}
            @Override public long avgTime() { return 0; }
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                targetRan.set(true);
                return true;
            }
            @Override
            public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, Consumer<String> messageMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) {
                return CompletableFuture.completedFuture(true);
            }
            @Override
            public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) {
                return List.of();
            }
            @Override
            public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) {
                return List.of();
            }
        };

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        CommandExecutor executor = new CommandExecutor(target, caller, Map.of(), null, s -> {}, future);
        executor.run();

        assertTrue(targetRan.get());
        assertTrue(future.isDone());
        assertTrue(future.join());
    }
}
