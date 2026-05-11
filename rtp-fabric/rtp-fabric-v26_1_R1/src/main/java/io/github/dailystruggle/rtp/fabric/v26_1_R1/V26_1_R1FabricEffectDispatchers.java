package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime;
import io.github.dailystruggle.effectsapi.fabric_unobf.FabricEffectRuntimeUnobf;
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
 * Loom-compiled effect dispatchers for MC 26.1.x. Sibling of the R11
 * dispatchers — same {@code Holder<SoundEvent>} ctor, same 2-boolean
 * targeted {@code sendParticles} overload — recompiled against the 26.1
 * MC jar so any post-1.21.11 mapping drift is absorbed by the compiler
 * rather than reflection.
 *
 * <p>Compiled to Java 25 bytecode (MC 26.1's mandated minimum). Only
 * loaded on Java 25+ JVMs because {@link V26_1_R1FabricVersionAdapter} is
 * resolved by FQN string at bootstrap; the JVM never names this class on
 * a Java 21 runtime.</p>
 */
final class V26_1_R1FabricEffectDispatchers {

    private V26_1_R1FabricEffectDispatchers() {}

    static void install() {
        // Register against the obf-side FabricEffectRuntime for older code paths.
        try {
            FabricEffectRuntime.registerSound(V26_1_R1FabricEffectDispatchers::playSound);
            FabricEffectRuntime.registerParticle(V26_1_R1FabricEffectDispatchers::sendParticle);
            FabricEffectRuntime.registerPotion(V26_1_R1FabricEffectDispatchers::applyPotion);
        } catch (NoClassDefFoundError ncdfe) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 26.1.x] effects-api (obf) not on classpath; skipping obf dispatcher registration: "
                            + ncdfe.getMessage());
        }
        // ADR-006 amendment 2026-05-11 — on the deobf 26.1.2 runtime,
        // FabricEffectsInitializer (fabric_unobf) is the variant used by
        // EffectFactory, so its LocalEffects read FabricEffectRuntimeUnobf
        // (not FabricEffectRuntime). FabricParticleEffect/FabricSoundEffect
        // have reflective fallbacks and worked without this, but
        // FabricPotionEffect strictly requires a registered PotionDispatcher
        // (MobEffectInstance ctor drift across 1.20.4 → 1.20.5 means no
        // safe reflective fallback). Register the same dispatchers against
        // the unobf runtime so all three effect types fire on 26.1.x.
        try {
            FabricEffectRuntimeUnobf.registerSound(V26_1_R1FabricEffectDispatchers::playSound);
            FabricEffectRuntimeUnobf.registerParticle(V26_1_R1FabricEffectDispatchers::sendParticle);
            FabricEffectRuntimeUnobf.registerPotion(V26_1_R1FabricEffectDispatchers::applyPotion);
        } catch (NoClassDefFoundError ncdfe) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 26.1.x] effects-api-fabric-unobf not on classpath; skipping unobf dispatcher registration: "
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
        // 26.1.x targeted overload: 2 booleans (longDistance, overrideLimiter).
        level.sendParticles(recipient, options,
                /* longDistance   */ false,
                /* overrideLimiter*/ false,
                x, y, z, count, dx, dy, dz, speed);
    }
}
