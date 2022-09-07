package io.github.dailystruggle.rtp.common.commands.update.list;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import org.simpleyaml.configuration.file.YamlFile;

import java.util.Set;
import java.util.function.Supplier;

public class ListAddParameter extends CommandParameter {
    private final Supplier<Set<String>> values;
    private final YamlFile file;
    private final String key;

    public ListAddParameter(Supplier<Set<String>> values, YamlFile file, String key) {
        super("rtp.update","add items to a list", (uuid, s) -> true);
        this.values = values;
        this.file = file;
        this.key = key;
    }

    //todo: store and update
    @Override
    public Set<String> values() {
        return values.get();
    }
}
