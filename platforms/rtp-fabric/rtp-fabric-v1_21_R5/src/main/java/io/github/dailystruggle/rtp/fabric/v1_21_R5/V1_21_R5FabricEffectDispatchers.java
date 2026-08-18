package io.github.dailystruggle.rtp.fabric.v1_21_R5;

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
 * Loom-compiled effect dispatchers for MC 1.21.5-1.21.10. Sibling of
 * {@code V1_21_R11FabricEffectDispatchers}; same shape, mapped against
 * the 1.21.5 MC jar. See that class for rationale.
 */
final class V1_21_R5FabricEffectDispatchers {

    private V1_21_R5FabricEffectDispatchers() {}

    static void install() {
        try {
            FabricEffectRuntime.registerSound(V1_21_R5FabricEffectDispatchers::playSound);
            FabricEffectRuntime.registerParticle(V1_21_R5FabricEffectDispatchers::sendParticle);
            FabricEffectRuntime.registerPotion(V1_21_R5FabricEffectDispatchers::applyPotion);
        } catch (NoClassDefFoundError ncdfe) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 1.21.5+] effects-api not on classpath; skipping effect dispatcher registration: "
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
        // 1.20.5+ ctor shape: MobEffectInstance(Holder<MobEffect>, int, int, boolean, boolean, boolean).
        Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect);
        player.addEffect(new MobEffectInstance(holder, duration, amplifier, ambient, visible, showIcon));
    }

    private static void sendParticle(ServerPlayer recipient,
                                     net.minecraft.core.particles.ParticleOptions options,
                                     double x, double y, double z,
                                     int count,
                                     double dx, double dy, double dz, double speed) {
        ServerLevel level = (ServerLevel) recipient.level();
        if (level == null) return;
        // 1.21.5 added the overrideLimiter boolean to the targeted overload.
        level.sendParticles(recipient, options,
                /* longDistance   */ false,
                /* overrideLimiter*/ false,
                x, y, z, count, dx, dy, dz, speed);
    }
}
