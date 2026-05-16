package io.github.dailystruggle.rtp.common.commands.config.list;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

public class ListRemoveParameter extends CommandParameter {
  private final Supplier<Set<String>> values;
  private final RtpYamlConfig file;
  private final String key;

  public ListRemoveParameter(RtpYamlConfig file, String key) {
    super("rtp.update", "remove items from a list", (uuid, s) -> true);
    this.values = () -> new HashSet<>(file.getStringList(key));
    this.file = file;
    this.key = key;
  }

  // todo: store and update
  @Override
  public Set<String> values() {
    return values.get();
  }
}
