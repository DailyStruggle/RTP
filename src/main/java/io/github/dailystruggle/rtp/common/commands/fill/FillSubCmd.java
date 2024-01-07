package io.github.dailystruggle.rtp.common.commands.fill;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import org.jetbrains.annotations.Nullable;

public abstract class FillSubCmd extends BaseRTPCmdImpl {
    public FillSubCmd( @Nullable CommandsAPICommand parent ) {
        super( parent );
        addParameter( "region", new RegionParameter( "rtp.fill", "fill a specific region", ( uuid, s ) -> true) );
    }

    @Override
    public String permission() {
        return "rtp.fill";
    }
}
