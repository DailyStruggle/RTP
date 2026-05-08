package io.github.dailystruggle.rtp.fabric.v1_20_R1;

import io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.logging.Level;

/**
 * Loom-compiled effect dispatchers for MC 1.20.x. Sibling of the R1 / R5 /
 * R11 dispatchers; same {@code ClientboundSoundPacket(Holder, ...)} shape
 * (Mojang switched the constructor to {@code Holder<SoundEvent>} in 1.19.3),
 * single-{@code boolean} {@code sendParticles} arity.
 */
final class V1_20_R1FabricEffectDispatchers {

    private V1_20_R1FabricEffectDispatchers() {}

    static void install() {
        try {
            FabricEffectRuntime.registerSound(V1_20_R1FabricEffectDispatchers::playSound);
            FabricEffectRuntime.registerParticle(V1_20_R1FabricEffectDispatchers::sendParticle);
            FabricEffectRuntime.registerPotion(V1_20_R1FabricEffectDispatchers::applyPotion);
        } catch (NoClassDefFoundError ncdfe) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 1.20.x] effects-api not on classpath; skipping effect dispatcher registration: "
                            + ncdfe.getMessage());
        }
    }

    private static void playSound(ServerPlayer player,
                                  SoundEvent sound,
                                  SoundSource source,
                                  double x, double y, double z,
                                  float volume, float pitch) {
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        ServerLevel sl = (ServerLevel) player.level();
        long seed = sl.getRandom().nextLong();
        ClientboundSoundPacket pkt = new ClientboundSoundPacket(
                holder, source, x, y, z, volume, pitch, seed);
        player.connection.send(pkt);
    }

    private static void applyPotion(ServerPlayer player,
                                    MobEffect effect,
                                    int duration,
                                    int amplifier,
                                    boolean ambient,
                                    boolean visible,
                                    boolean showIcon) {
        // 1.20.x ctor shape: MobEffectInstance(MobEffect, int, int, boolean, boolean, boolean).
        // Replaced by MobEffectInstance(Holder<MobEffect>, …) in 1.20.5.
        player.addEffect(new MobEffectInstance(effect, duration, amplifier, ambient, visible, showIcon));
    }

    private static void sendParticle(ServerPlayer recipient,
                                     net.minecraft.core.particles.ParticleOptions options,
                                     double x, double y, double z,
                                     int count,
                                     double dx, double dy, double dz, double speed) {
        ServerLevel level = (ServerLevel) recipient.level();
        if (level == null) return;
        // 1.20.x targeted overload: single boolean (longDistance).
        level.sendParticles(recipient, options,
                /* longDistance */ false,
                x, y, z, count, dx, dy, dz, speed);
    }
}
