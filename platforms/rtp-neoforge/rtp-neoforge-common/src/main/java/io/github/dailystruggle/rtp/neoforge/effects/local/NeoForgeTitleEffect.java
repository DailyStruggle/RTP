package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.logging.Level;

/**
 * NeoForge TITLE effect (effects-api-ADR-003), compiled Mojmap in
 * {@code rtp-neoforge-common}. Sends a vanilla title overlay via the Mojmap
 * clientbound title packets, dispatched to the player's connection by
 * reflection (the {@code connection} field name / {@code send} method drift
 * across the line). S-004 cosmetic.
 */
public class NeoForgeTitleEffect extends Effect<NeoForgeTitleKeys> {

    public NeoForgeTitleEffect() throws IllegalArgumentException {
        super(new EnumMap<>(NeoForgeTitleKeys.class));
        EnumMap<NeoForgeTitleKeys, Object> d = getData();
        d.put(NeoForgeTitleKeys.TITLE, "");
        d.put(NeoForgeTitleKeys.SUBTITLE, "");
        d.put(NeoForgeTitleKeys.FADE_IN, 10);
        d.put(NeoForgeTitleKeys.STAY, 70);
        d.put(NeoForgeTitleKeys.FADE_OUT, 20);
        this.data = d;
        this.defaults = d.clone();
    }

    @Override
    public void run() {
        if (!(target instanceof ServerPlayer)) return;
        ServerPlayer player = (ServerPlayer) target;

        String titleStr = String.valueOf(data.getOrDefault(NeoForgeTitleKeys.TITLE, ""));
        String subStr   = String.valueOf(data.getOrDefault(NeoForgeTitleKeys.SUBTITLE, ""));
        int fadeIn  = numAsInt(data.get(NeoForgeTitleKeys.FADE_IN),  10);
        int stay    = numAsInt(data.get(NeoForgeTitleKeys.STAY),     70);
        int fadeOut = numAsInt(data.get(NeoForgeTitleKeys.FADE_OUT), 20);

        boolean sent = NeoForgeConnection.send(player,
                new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        if (!titleStr.isEmpty()) {
            sent &= NeoForgeConnection.send(player,
                    new ClientboundSetTitleTextPacket(Component.literal(titleStr)));
        }
        if (!subStr.isEmpty()) {
            sent &= NeoForgeConnection.send(player,
                    new ClientboundSetSubtitleTextPacket(Component.literal(subStr)));
        }
        // S-004: surface a connection-resolution miss once instead of dropping
        // the title silently (cosmetic only; teleport already completed).
        if (!sent) warnOnce(NeoForgeConnection.failureReason());
    }

    private static volatile boolean WARNED;

    private static void warnOnce(String reason) {
        if (WARNED) return;
        synchronized (NeoForgeTitleEffect.class) {
            if (WARNED) return;
            WARNED = true;
        }
        RTP.log(Level.WARNING, "[RTP][NeoForge] TITLE effect could not be delivered: "
                + (reason == null ? "player connection unavailable" : reason)
                + " (cosmetic; teleport unaffected).");
    }

    @Override
    public String toPermission() {
        return data.get(NeoForgeTitleKeys.TITLE) + "."
                + data.get(NeoForgeTitleKeys.SUBTITLE) + "."
                + data.get(NeoForgeTitleKeys.FADE_IN) + "."
                + data.get(NeoForgeTitleKeys.STAY) + "."
                + data.get(NeoForgeTitleKeys.FADE_OUT);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final NeoForgeTitleKeys[] KEY_ORDER = {
            NeoForgeTitleKeys.TITLE, NeoForgeTitleKeys.SUBTITLE,
            NeoForgeTitleKeys.FADE_IN, NeoForgeTitleKeys.STAY, NeoForgeTitleKeys.FADE_OUT
    };

    private static int numAsInt(Object o, int fallback) {
        return (o instanceof Number) ? ((Number) o).intValue() : fallback;
    }
}
