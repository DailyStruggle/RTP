package io.github.dailystruggle.rtp.common.commands.config.list;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.Set;
import java.util.function.Supplier;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

public class ListAddParameter extends CommandParameter {
  private final Supplier<Set<String>> values;
  private final RtpYamlConfig file;
  private final String key;

  public ListAddParameter(Supplier<Set<String>> values, RtpYamlConfig file, String key) {
    super("rtp.update", "add items to a list", (uuid, s) -> true);
    this.values = values;
    this.file = file;
    this.key = key;
  }

  // todo: store and update
  @Override
  public Set<String> values() {
    return values.get();
  }
}
