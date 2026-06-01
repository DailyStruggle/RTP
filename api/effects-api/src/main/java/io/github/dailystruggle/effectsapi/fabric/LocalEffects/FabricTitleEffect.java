package io.github.dailystruggle.effectsapi.fabric.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricTitleKeys;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;

/**
 * Fabric counterpart of {@code BukkitTitleEffect}-equivalent — sends a vanilla
 * title overlay via the Mojmap clientbound title packets. Mirrors the message
 * shape that {@code FabricRTPPlayer#sendTitle} already uses, so timing/render
 * stays consistent across the player-message path and the effect path.
 */
public class FabricTitleEffect extends Effect<FabricTitleKeys> {

    public FabricTitleEffect() throws IllegalArgumentException {
        super(new EnumMap<>(FabricTitleKeys.class));
        EnumMap<FabricTitleKeys, Object> d = getData();
        d.put(FabricTitleKeys.TITLE, "");
        d.put(FabricTitleKeys.SUBTITLE, "");
        d.put(FabricTitleKeys.FADE_IN, 10);
        d.put(FabricTitleKeys.STAY, 70);
        d.put(FabricTitleKeys.FADE_OUT, 20);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        String titleStr = String.valueOf(data.getOrDefault(FabricTitleKeys.TITLE, ""));
        String subStr   = String.valueOf(data.getOrDefault(FabricTitleKeys.SUBTITLE, ""));
        int fadeIn  = numAsInt(data.get(FabricTitleKeys.FADE_IN),  10);
        int stay    = numAsInt(data.get(FabricTitleKeys.STAY),     70);
        int fadeOut = numAsInt(data.get(FabricTitleKeys.FADE_OUT), 20);

        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (!titleStr.isEmpty()) {
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(titleStr)));
        }
        if (!subStr.isEmpty()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subStr)));
        }
    }

    @Override
    public String toPermission() {
        return String.valueOf(data.get(FabricTitleKeys.TITLE)) + "."
                + data.get(FabricTitleKeys.SUBTITLE) + "."
                + data.get(FabricTitleKeys.FADE_IN) + "."
                + data.get(FabricTitleKeys.STAY) + "."
                + data.get(FabricTitleKeys.FADE_OUT);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final FabricTitleKeys[] KEY_ORDER = {
            FabricTitleKeys.TITLE, FabricTitleKeys.SUBTITLE,
            FabricTitleKeys.FADE_IN, FabricTitleKeys.STAY, FabricTitleKeys.FADE_OUT
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }
}
