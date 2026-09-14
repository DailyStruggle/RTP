package io.github.dailystruggle.commandsapi.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommonParametersAndExecutorTest {

  private enum TestMode {
    ALPHA,
    BETA,
    GAMMA
  }

  @Test
  @DisplayName("BooleanParameter, CoordinateParameter, FloatParameter, and EnumParameter values")
  void testParameterClasses() {
    UUID caller = UUID.randomUUID();

    // BooleanParameter
    BooleanParameter boolParam = new BooleanParameter("perm.bool", "Boolean param", (u, s) -> true);
    assertEquals("perm.bool", boolParam.permission());
    assertEquals("Boolean param", boolParam.description());
    Set<String> boolVals = boolParam.values();
    assertTrue(boolVals.contains("true"));
    assertTrue(boolVals.contains("false"));
    assertEquals(2, boolVals.size());

    // CoordinateParameter
    CoordinateParameter coordParam = new CoordinateParameter("perm.coord", "Coordinate param", (u, s) -> true);
    Set<String> coordVals = coordParam.values();
    assertTrue(coordVals.contains("~"));
    assertTrue(coordVals.contains("-~"));
    assertTrue(coordVals.contains("0"));

    // FloatParameter
    FloatParameter floatParam = new FloatParameter("perm.float", "Float param", (u, s) -> true, 1.25, 3.5, 10.0);
    Set<String> floatVals = floatParam.values();
    assertTrue(floatVals.contains("1.25"));
    assertTrue(floatVals.contains("3.5"));
    assertTrue(floatVals.contains("10"));

    // EnumParameter
    EnumParameter<TestMode> enumParam = new EnumParameter<>("perm.enum", "Enum param", (u, s) -> true, TestMode.class);
    Set<String> enumVals = enumParam.values();
    assertTrue(enumVals.contains("ALPHA"));
    assertTrue(enumVals.contains("BETA"));
    assertTrue(enumVals.contains("GAMMA"));
    assertEquals(3, enumVals.size());
  }

  @Test
  @DisplayName("CommandExecutor runs command with result completion, equals and hashCode")
  void testCommandExecutor() {
    UUID callerId = UUID.randomUUID();
    CompletableFuture<Boolean> fut = new CompletableFuture<>();
    AtomicBoolean executed = new AtomicBoolean(false);

    CommandsAPICommand cmd = new CommandsAPICommand() {
      @Override
      public String name() {
        return "test";
      }

      @Override
      public String permission() {
        return "test.perm";
      }

      @Override
      public String description() {
        return "desc";
      }

      @Override
      public CommandsAPICommand parent() {
        return null;
      }

      @Override
      public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}

      @Override
      public void msgInvalidCommand(UUID callerId, String argument) {}

      @Override
      public long avgTime() {
        return 0L;
      }

      @Override
      public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) {
        return List.of();
      }

      @Override
      public boolean onCommand(UUID caller, Map<String, List<String>> parameterValues, CommandsAPICommand next) {
        executed.set(true);
        return true;
      }

      @Override
      public List<String> onTabComplete(UUID callerId,
                                        java.util.function.Predicate<String> permissionCheckMethod,
                                        String[] args,
                                        int i,
                                        Map<String, CommandParameter> tempParameters) {
        return List.of();
      }

      @Override
      public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                 java.util.function.Predicate<String> permissionCheckMethod,
                                                 java.util.function.Consumer<String> messageMethod,
                                                 String[] args,
                                                 int i,
                                                 Map<String, CommandParameter> tempParameters) {
        executed.set(true);
        return CompletableFuture.completedFuture(true);
      }
    };

    CommandExecutor executor1 = new CommandExecutor(cmd, callerId, Map.of(), null, fut);
    assertEquals(cmd, executor1.command());
    assertEquals(callerId, executor1.callerId());

    CommandExecutor executor2 = new CommandExecutor(cmd, callerId, Map.of(), null, (msg) -> {}, fut);
    assertEquals(executor1, executor2);
    assertEquals(executor1.hashCode(), executor2.hashCode());
    assertTrue(executor1.toString().contains("CommandExecutor"));

    executor1.run();
    assertTrue(fut.isDone());
    assertTrue(fut.join());
    assertTrue(executed.get());

    // Identity equality contract: only command and callerId determine equality, not sinks or futures
    UUID otherCaller = UUID.randomUUID();
    CommandExecutor executor3 = new CommandExecutor(cmd, otherCaller, Map.of(), null, (msg) -> {}, fut);
    assertNotEquals(executor1, executor3, "Executors with different callers should not be equal");
    assertNotEquals(executor1, null, "Executor should not equal null");
    assertNotEquals(executor1, "some-string", "Executor should not equal object of different type");

    CommandsAPICommand otherCmd = new CommandsAPICommand() {
      @Override public String name() { return "other"; }
      @Override public String permission() { return "perm"; }
      @Override public String description() { return "desc"; }
      @Override public CommandsAPICommand parent() { return null; }
      @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
      @Override public void msgInvalidCommand(UUID callerId, String argument) {}
      @Override public long avgTime() { return 0L; }
      @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
      @Override public boolean onCommand(UUID caller, Map<String, List<String>> parameterValues, CommandsAPICommand next) { return true; }
      @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) { return List.of(); }
      @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) {
        return CompletableFuture.completedFuture(true);
      }
    };
    CommandExecutor executorOtherCmd = new CommandExecutor(otherCmd, callerId, Map.of(), null, fut);
    assertNotEquals(executor1, executorOtherCmd, "Executors with different target commands should not be equal");
  }

  @Test
  @DisplayName("CommandsAPI execute drains queued commands and surfaces target exceptions")
  void testCommandsApiExecutePipelineExceptionHandling() {
    // Calling execute on an empty queue safely returns without error
    CommandsAPI.execute();

    CommandsAPICommand throwingCmd = new CommandsAPICommand() {
      @Override public String name() { return "throw"; }
      @Override public String permission() { return "test.perm"; }
      @Override public String description() { return "desc"; }
      @Override public CommandsAPICommand parent() { return null; }
      @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
      @Override public void msgInvalidCommand(UUID callerId, String argument) {}
      @Override public long avgTime() { return 0L; }
      @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
      @Override public boolean onCommand(UUID caller, Map<String, List<String>> parameterValues, CommandsAPICommand next) {
        throw new RuntimeException("pipeline unhandled failure");
      }
      @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) { return List.of(); }
      @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) {
        throw new RuntimeException("pipeline unhandled failure");
      }
    };

    CompletableFuture<Boolean> failureFuture = new CompletableFuture<>();
    CommandExecutor failingExecutor = new CommandExecutor(throwingCmd, UUID.randomUUID(), Map.of(), null, s -> {}, failureFuture);
    CommandsAPI.commandPipeline.add(failingExecutor);

    assertThrows(RuntimeException.class, CommandsAPI::execute, "CommandsAPI.execute should rethrow unhandled command execution exceptions");

    // Multiple commands in pipeline processed until time budget exhausted
    CommandsAPICommand normalCmd = new CommandsAPICommand() {
      @Override public String name() { return "normal"; }
      @Override public String permission() { return null; }
      @Override public String description() { return "desc"; }
      @Override public CommandsAPICommand parent() { return null; }
      @Override public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {}
      @Override public void msgInvalidCommand(UUID callerId, String argument) {}
      @Override public long avgTime() { return 100_000_000L; } // 100ms
      @Override public List<String> help(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod) { return List.of(); }
      @Override public boolean onCommand(UUID caller, Map<String, List<String>> parameterValues, CommandsAPICommand next) { return true; }
      @Override public List<String> onTabComplete(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) { return List.of(); }
      @Override public CompletableFuture<Boolean> onCommand(UUID callerId, java.util.function.Predicate<String> permissionCheckMethod, java.util.function.Consumer<String> messageMethod, String[] args, int i, Map<String, CommandParameter> tempParameters) {
        return CompletableFuture.completedFuture(true);
      }
    };
    CommandsAPI.commandPipeline.add(new CommandExecutor(normalCmd, UUID.randomUUID(), Map.of(), null, s -> {}, new CompletableFuture<>()));
    CommandsAPI.commandPipeline.add(new CommandExecutor(normalCmd, UUID.randomUUID(), Map.of(), null, s -> {}, new CompletableFuture<>()));
    // execute with 1 nanosecond budget processes first command and leaves second because budget exhausted
    long remaining = CommandsAPI.execute(1L);
    assertEquals(1, remaining);
    CommandsAPI.commandPipeline.clear();
  }

  @Test
  @DisplayName("Factory registration, reflection construction, and parameter inspection")
  void testFactory() {
    Factory factory = new Factory();
    assertFalse(factory.contains("dummy"));
    assertNull(factory.getConstructorParameterTypes("dummy"));

    factory.add("string", String.class);
    assertTrue(factory.contains("string"));
    assertTrue(factory.contains("STRING"));

    Enumeration<String> list = factory.list();
    assertTrue(list.hasMoreElements());
    assertEquals("STRING", list.nextElement());

    Object constructed = factory.construct("string", "hello world");
    assertEquals("hello world", constructed);

    Class<?>[] paramTypes = factory.getConstructorParameterTypes("STRING");
    assertNotNull(paramTypes);

    // Interface or class without public constructors returns null
    factory.add("interface", Runnable.class);
    assertNull(factory.getConstructorParameterTypes("INTERFACE"));
  }

  @Test
  @DisplayName("CommandsAPI sink and messageMethodFor routing")
  void testCommandsApiMessageSink() {
    UUID caller = UUID.randomUUID();
    AtomicBoolean sinkCalled = new AtomicBoolean(false);
    AtomicBoolean fallbackCalled = new AtomicBoolean(false);

    // Default sink is null
    CommandsAPI.setMessageSink(null);
    assertNull(CommandsAPI.getMessageSink());

    java.util.function.Consumer<String> method1 = CommandsAPI.messageMethodFor(caller, msg -> fallbackCalled.set(true));
    method1.accept("test fallback");
    assertTrue(fallbackCalled.get());

    // Set custom sink
    CommandsAPI.setMessageSink((target, msg) -> sinkCalled.set(true));
    assertNotNull(CommandsAPI.getMessageSink());

    java.util.function.Consumer<String> method2 = CommandsAPI.messageMethodFor(caller, msg -> {});
    method2.accept("test sink");
    assertTrue(sinkCalled.get());

    CommandsAPI.setMessageSink(null);
  }
}
