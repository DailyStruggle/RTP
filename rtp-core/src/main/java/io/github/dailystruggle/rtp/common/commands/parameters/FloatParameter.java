package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

public class FloatParameter extends CommandParameter {
  public FloatParameter(
      String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
    super(permission, description, (uuid, s) -> {
      try {
        Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return false;
      }
      return isRelevant.apply(uuid, s);
    });
  }

  @Override
  public Set<String> values() {
    return new HashSet<>();
  }
}
