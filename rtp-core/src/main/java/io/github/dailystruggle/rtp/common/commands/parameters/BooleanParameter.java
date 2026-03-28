package io.github.dailystruggle.rtp.common.commands.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

public class BooleanParameter extends CommandParameter {
    public BooleanParameter( String permission, String description, BiFunction<UUID, String, Boolean> isRelevant ) {
        super( permission, description, isRelevant );
    }

    @Override
    public Set<String> values() {
        Set<String> res = new HashSet<>();
        res.add( "true" );
        res.add( "false" );
        return res;
    }
}
