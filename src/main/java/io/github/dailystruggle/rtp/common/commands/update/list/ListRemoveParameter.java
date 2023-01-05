package io.github.dailystruggle.rtp.common.commands.update.list;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import org.simpleyaml.configuration.file.YamlFile;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

public class ListRemoveParameter extends CommandParameter {
    private final Supplier<Set<String>> values;
    private final YamlFile file;
    private final String key;

    public ListRemoveParameter(YamlFile file, String key) {
        super("rtp.update", "add items to a list", (uuid, s) -> true);
        this.values = () -> new HashSet<>(file.getStringList(key));
        this.file = file;
        this.key = key;
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return values.get();
    }
}
