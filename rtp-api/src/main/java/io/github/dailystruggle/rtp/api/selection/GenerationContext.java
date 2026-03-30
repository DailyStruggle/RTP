package io.github.dailystruggle.rtp.api.selection;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public final class GenerationContext {
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final Set<String> biomeNames;

    public GenerationContext(RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        this.sender = sender;
        this.player = player;
        this.biomeNames = biomeNames;
    }

    public RTPCommandSender sender() {
        return sender;
    }

    public RTPPlayer player() {
        return player;
    }

    @Nullable
    public Set<String> biomeNames() {
        return biomeNames;
    }
}
