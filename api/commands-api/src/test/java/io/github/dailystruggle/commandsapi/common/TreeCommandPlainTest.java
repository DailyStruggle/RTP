package io.github.dailystruggle.commandsapi.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TreeCommandPlainTest {

  private static class SimpleTreeCmd implements TreeCommand {
    private final String name;
    private final String perm;
    private final Map<String, CommandParameter> params = new HashMap<>();
    private final Map<String, CommandsAPICommand> subCommands = new HashMap<>();
    Map<String, List<String>> lastParamValues = null;

    SimpleTreeCmd(String name, String perm) {
      this.name = name;
      this.perm = perm;
    }

    @Override
    public Map<String, CommandParameter> getParameterLookup() {
      return params;
    }

    @Override
    public Map<String, CommandsAPICommand> getCommandLookup() {
      return subCommands;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String permission() {
      return perm;
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
    public long avgTime() {
      return 0L;
    }

    @Override
    public List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
      return List.of();
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      this.lastParamValues = parameterValues;
      return true;
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand, Consumer<String> messageMethod) {
      this.lastParamValues = parameterValues;
      messageMethod.accept("executed root");
      return true;
    }

    @Override
    public void msgInvalidCommand(UUID callerId, String argument, Consumer<String> messageMethod) {
      messageMethod.accept("invalid command: " + argument);
    }
  }

  @Test
  @DisplayName("TreeCommand parameter addition, subCommand lookup, tab completion, and execution")
  void testTreeCommandExecutionAndTabComplete() {
    SimpleTreeCmd root = new SimpleTreeCmd("root", "root.perm");
    UUID caller = UUID.randomUUID();

    // Add integer parameter
    IntegerParameter intParam = new IntegerParameter("param.int", "Integer parameter", (u, s) -> true, 10, 20, 30);
    root.addParameter("radius", intParam);
    assertEquals(intParam, root.getParameterLookup().get("radius"));

    // Add sub-command
    SimpleTreeCmd sub = new SimpleTreeCmd("reload", "root.reload");
    root.addSubCommand(sub);
    assertEquals(sub, root.getCommandLookup().get("RELOAD"));

    // Tab completion at root
    List<String> tabEmpty = root.onTabComplete(caller, perm -> true, new String[]{""});
    assertTrue(tabEmpty.contains("radius="));
    assertTrue(tabEmpty.contains("reload"));
    assertTrue(tabEmpty.contains("help"));

    // Tab completion with partial param
    List<String> tabParam = root.onTabComplete(caller, perm -> true, new String[]{"rad"});
    assertTrue(tabParam.contains("radius="));

    // Tab completion with value delimiter
    List<String> tabValues = root.onTabComplete(caller, perm -> true, new String[]{"radius="});
    assertTrue(tabValues.contains("radius=10"));
    assertTrue(tabValues.contains("radius=20"));
    assertTrue(tabValues.contains("radius=30"));

    // Execution with valid parameter
    List<String> messages = new ArrayList<>();
    CompletableFuture<Boolean> res = root.onCommand(caller, perm -> true, messages::add, new String[]{"radius=20"});
    assertTrue(res.join());
    assertNotNull(root.lastParamValues);
    assertEquals(List.of("20"), root.lastParamValues.get("radius"));
    assertTrue(messages.contains("executed root"));

    // Execution with invalid command / sub command delegation
    CompletableFuture<Boolean> subRes = root.onCommand(caller, perm -> true, messages::add, new String[]{"reload"});
    CommandsAPI.execute();
    assertTrue(subRes.join());

    // Execution with trailing delimiter
    CompletableFuture<Boolean> badParamRes = root.onCommand(caller, perm -> true, messages::add, new String[]{"radius="});
    assertFalse(badParamRes.join());

    // Execution with permission denied
    CompletableFuture<Boolean> deniedRes = root.onCommand(caller, perm -> false, messages::add, new String[]{"radius=20"});
    assertFalse(deniedRes.join());
  }
}
