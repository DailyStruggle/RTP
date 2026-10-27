package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.common.action.ActionConfigLoader;
import io.github.dailystruggle.rtp.common.action.ActionManager;
import io.github.dailystruggle.rtp.common.action.ActionServices;
import io.github.dailystruggle.rtp.common.action.ActionSimulator;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Simulates an action definition and reports its gate outcomes and recorded commands. */
public class TestActionCmd extends BaseRTPCmdImpl {
  private static final int MIN_PLAYERS = 1;
  private static final int MAX_PLAYERS = 16;
  private static final Set<String> SCRIPT_EVENTS = Set.of("violate", "die", "expire");

  public TestActionCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter("id", new CommandParameter("rtp.test", "action definition id", (uuid, value) ->
        value != null && !value.isBlank()) {
      @Override
      public Set<String> values() {
        return ActionConfigLoader.loadedDefinitions().stream()
            .map(ActionConfigLoader.LoadedDefinition::id)
            .collect(java.util.stream.Collectors.toSet());
      }
    });
    addParameter("players", new IntegerParameter("rtp.test", "synthetic participant count (1-16)",
        (uuid, value) -> parsePlayers(value) > 0, 1, 2, 4, 8, 16));
    addParameter("script", new CommandParameter("rtp.test", "comma-separated simulation events",
        (uuid, value) -> parseScript(value) != null) {
      @Override
      public Set<String> values() {
        return SCRIPT_EVENTS;
      }
    });
  }

  @Override
  public String name() {
    return "action";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "simulates an action definition without dispatching commands";
  }

  @Override
  public CompletableFuture<Boolean> onCommand(
      @NotNull UUID callerId,
      @NotNull java.util.function.Predicate<String> permissionCheckMethod,
      @NotNull java.util.function.Consumer<String> messageMethod,
      @NotNull String[] args,
      int i,
      @Nullable Map<String, CommandParameter> tempParameters) {
    for (int index = i; index < args.length; index++) {
      if (isColonParameter(args[index])) {
        msgBadParameter(callerId, "parameters", args[index], messageMethod);
        return CompletableFuture.completedFuture(false);
      }
    }
    String[] normalized = args;
    if (i < args.length && !args[i].contains("=") && !args[i].contains(":")) {
      normalized = args.clone();
      normalized[i] = "id=" + normalized[i];
    }
    return defaultOnCommand(callerId, permissionCheckMethod, messageMethod, normalized, i, tempParameters);
  }

  @Override
  public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    String actionId = first(parameterValues, "id");
    if (actionId == null || actionId.isBlank()) {
      msgInvalidCommand(callerId, "action requires id=<action>");
      return false;
    }
    int players = parsePlayers(first(parameterValues, "players"));
    if (players < MIN_PLAYERS) {
      msgBadParameter(callerId, "players", first(parameterValues, "players"));
      return false;
    }
    List<String> script = parseScript(joined(parameterValues, "script"));
    if (script == null) {
      msgBadParameter(callerId, "script", first(parameterValues, "script"));
      return false;
    }

    UUID owner = callerId != null ? callerId : RTPAPI.serverId;
    TestSemaphore.Holder holder = TestSemaphore.holderOf(owner);
    boolean parentHeld = holder != null && name().equals(holder.subName);
    if (!parentHeld && !TestSemaphore.tryAcquire(owner, name())) {
      report(owner, "Another test is already running for this caller.", Level.WARNING, null);
      return false;
    }
    if (RTPRuntime.scheduler() == null) {
      if (!parentHeld) TestSemaphore.release(owner, name());
      report(owner, "RTP scheduler is unavailable; action simulation was not run.", Level.WARNING, null);
      return false;
    }

    AtomicBoolean cancelled = new AtomicBoolean();
    Runnable unregister = ActiveTestJobs.register(owner,
        new ActiveTestJobs.Job(name(), () -> cancelled.set(true)));
    try {
      List<String> requestedScript = List.copyOf(script);
      RTPRuntime.scheduler().runTaskAsynchronously(() -> {
        try {
          if (!cancelled.get()) executeSimulation(owner, actionId, players, requestedScript);
        } catch (RuntimeException failure) {
          report(owner, "Action simulation failed: " + failure.getMessage(), Level.WARNING, failure);
        } finally {
          try {
            unregister.run();
          } finally {
            if (!parentHeld) TestSemaphore.release(owner, name());
          }
        }
      });
      return true;
    } catch (RuntimeException failure) {
      try {
        unregister.run();
      } finally {
        if (!parentHeld) TestSemaphore.release(owner, name());
      }
      report(owner, "Unable to schedule action simulation.", Level.WARNING, failure);
      return false;
    }
  }

  /** Execution seam used by the parser regression test. Production invocation is already async. */
  protected void executeSimulation(UUID callerId, String actionId, int players, List<String> script) {
    ActionDefinition definition = actionDefinition(actionId);
    if (definition == null) {
      report(callerId, "Unknown action definition: " + actionId, Level.WARNING, null);
      return;
    }
    ActionSimulator.Result result = ActionSimulator.simulate(definition, players, script);
    for (String line : result.lines()) report(callerId, line, Level.INFO, null);
  }

  private static ActionDefinition actionDefinition(String actionId) {
    ActionManager manager = ActionServices.actionManager();
    if (manager != null) {
      ActionDefinition activeDefinition = manager.getAction(actionId).orElse(null);
      if (activeDefinition != null) return activeDefinition;
    }
    return ActionConfigLoader.loadedDefinitions().stream()
        .filter(loaded -> loaded.id().equalsIgnoreCase(actionId))
        .findFirst()
        .map(loaded -> ActionConfigLoader.parseDefinition(loaded.id(),
            RtpYamlConfig.empty(null).createSection("definition", loaded.values())))
        .orElse(null);
  }

  private void report(UUID callerId, String message, Level level, Throwable failure) {
    String line = "[RTP test/action] " + message;
    if (callerId != null && !callerId.equals(RTPAPI.serverId)) {
      var accessor = RTPRuntime.serverAccessor();
      if (accessor != null) accessor.sendMessage(callerId, line);
    }
    if (failure == null) RTPRuntime.log(level, line);
    else RTPRuntime.log(level, line, failure);
  }

  private static int parsePlayers(@Nullable String value) {
    if (value == null || value.isBlank()) return 2;
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed >= MIN_PLAYERS && parsed <= MAX_PLAYERS ? parsed : -1;
    } catch (NumberFormatException failure) {
      return -1;
    }
  }

  @SuppressWarnings("java:S1168") // Null marks unsupported script tokens; empty is a valid no-op script.
  private static List<String> parseScript(@Nullable String value) {
    if (value == null || value.isBlank()) return List.of();
    String[] tokens = value.split(",", -1);
    List<String> script = new ArrayList<>(tokens.length);
    for (String token : tokens) {
      String event = token.trim().toLowerCase(Locale.ROOT);
      if (!SCRIPT_EVENTS.contains(event)) return null;
      script.add(event);
    }
    return List.copyOf(script);
  }

  private static boolean isColonParameter(@Nullable String value) {
    if (value == null) return false;
    int delimiter = value.indexOf(':');
    if (delimiter <= 0) return false;
    String name = value.substring(0, delimiter).toLowerCase(Locale.ROOT);
    return name.equals("id") || name.equals("players") || name.equals("script");
  }

  private static String first(@Nullable Map<String, List<String>> values, String name) {
    if (values == null) return null;
    List<String> entries = values.get(name);
    return entries == null || entries.isEmpty() ? null : entries.get(0);
  }

  private static String joined(@Nullable Map<String, List<String>> values, String name) {
    if (values == null) return null;
    List<String> entries = values.get(name);
    return entries == null || entries.isEmpty() ? null : String.join(",", entries);
  }
}
