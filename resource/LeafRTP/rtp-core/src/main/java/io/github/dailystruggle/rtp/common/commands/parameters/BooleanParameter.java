package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/** A {@link CommandParameter} whose valid values are {@code "true"} and {@code "false"}. */
public class BooleanParameter extends CommandParameter {

  /**
   * Constructs a boolean parameter.
   *
   * @param permission  permission node required to use this parameter
   * @param description human-readable description shown in help/tab-complete
   * @param isRelevant  predicate that determines whether this parameter applies for a given sender
   */
  public BooleanParameter(
      String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
    super(permission, description, isRelevant);
  }

  @Override
  public Set<String> values() {
    Set<String> res = new HashSet<>();
    res.add("true");
    res.add("false");
    return res;
  }
}
