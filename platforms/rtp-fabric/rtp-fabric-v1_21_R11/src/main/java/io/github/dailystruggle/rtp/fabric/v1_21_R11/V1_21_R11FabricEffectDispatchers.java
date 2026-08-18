package io.github.dailystruggle.rtp.fabric.v1_21_R11;

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
 * Loom-compiled effect dispatchers for MC 1.21.11+. Replaces the reflective
 * resolvers in {@code effects-api}'s {@code FabricSoundEffect} /
 * {@code FabricParticleEffect} on this MC version with direct, mapped vanilla
 * calls - no constructor-arity probing, no {@code Holder<SoundEvent>} vs
 * {@code SoundEvent} guesswork, no intermediary-name walks.
 *
 * <p>Sound: builds a {@link ClientboundSoundPacket} from a registry-keyed
 * reference {@link Holder} (never {@code Holder.direct} - direct holders
 * are decoded client-side as one-off custom-pack sounds and produce no
 * audio for built-in {@link SoundEvent}s, which is the user-reported
 * 1.21.11 symptom that motivated the per-version dispatcher work). Sent
 * straight to {@link ServerPlayer#connection} so the packet bypasses the
 * chunk tracker - important because right after a long teleport the
 * player is briefly outside the destination chunk's tracking radius and
 * tracker-broadcast packets are dropped client-side.</p>
 *
 * <p>Particle: uses the targeted
 * {@link ServerLevel#sendParticles(ServerPlayer, net.minecraft.core.particles.ParticleOptions,
 * boolean, boolean, double, double, double, int, double, double, double, double)}
 * overload, so the packet also bypasses the chunk tracker.</p>
 *
 * <p>Called from {@link V1_21_R11FabricVersionAdapter#installEffectsDispatchers()},
 * which the {@code RTPFabricMod} bootstrap invokes once per server start
 * after the version adapter is selected. Idempotent - last-writer-wins on
 * the {@code FabricEffectRuntime} side.</p>
 */
final class V1_21_R11FabricEffectDispatchers {

    private V1_21_R11FabricEffectDispatchers() {}

    private static volatile boolean LOGGED_FIRST_SOUND = false;
    private static volatile boolean LOGGED_FIRST_PARTICLE = false;

    static void install() {
        // Tolerate effects-api being absent at runtime (the rtp-lite assembly
        // strips it). The reference to FabricEffectRuntime below will trigger
        // class loading; if that fails, log and move on - effects-api's own
        // reflective fallback path won't run either (because effects-api
        // itself isn't loaded), so there's nothing to do.
        try {
            FabricEffectRuntime.registerSound(V1_21_R11FabricEffectDispatchers::playSound);
            FabricEffectRuntime.registerParticle(V1_21_R11FabricEffectDispatchers::sendParticle);
            FabricEffectRuntime.registerPotion(V1_21_R11FabricEffectDispatchers::applyPotion);
            // One-shot startup confirmation so deployments can prove the
            // per-version dispatcher path is wired (user reported "no
            // logs at all" symptom on 1.21.11 - without this line there's
            // no positive evidence of registration in the server log).
            RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] effect dispatchers registered "
                    + "(SoundDispatcher + ParticleDispatcher) — "
                    + V1_21_R11FabricEffectDispatchers.class.getName());
        } catch (NoClassDefFoundError ncdfe) {
            RTP.log(Level.FINE,
                    "[RTP][Fabric 1.21.11+] effects-api not on classpath; skipping effect dispatcher registration: "
                            + ncdfe.getMessage());
        }
    }

    private static void playSound(ServerPlayer player,
                                  SoundEvent sound,
                                  SoundSource source,
                                  double x, double y, double z,
                                  float volume, float pitch) {
        // wrapAsHolder returns the registered reference Holder; the client
        // then dispatches the registered SoundEvent. Holder.direct would
        // make the client treat the embedded ResourceLocation as a custom
        // resource-pack path → silent miss for built-in sounds.
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        boolean firstSound = !LOGGED_FIRST_SOUND;
        if (firstSound) {
            LOGGED_FIRST_SOUND = true;
            try {
                RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] sound dispatch #1: "
                        + "soundClass=" + sound.getClass().getName()
                        + " holderKind=" + holder.kind()
                        + " holderRegistered=" + holder.isBound()
                        + " holderKey=" + holder.unwrapKey().map(Object::toString).orElse("<no-key>")
                        + " sourceClass=" + source.getClass().getName()
                        + " source=" + source
                        + " connectionNull=" + (player.connection == null)
                        + " pos=" + x + "," + y + "," + z + " v=" + volume + " p=" + pitch);
            } catch (Throwable t) {
                RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] sound dispatch #1 diag failed: " + t);
            }
        }
        // Avoid touching player.level() / serverLevel() entirely. The user-
        // reported runtime on 1.21.11 throws NoSuchMethodError for
        // class_3222.method_37908() (Entity#level intermediary), even from
        // this Loom-compiled module - apparently because the deployed
        // Fabric Loader's intermediary mapping for that runtime no longer
        // contains the symbol on this concrete subclass. We only used the
        // level for a random seed, which is purely a client-side variation
        // hint; ThreadLocalRandom is fine and avoids the entire trap.
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        ClientboundSoundPacket pkt = new ClientboundSoundPacket(
                holder, source, x, y, z, volume, pitch, seed);
        player.connection.send(pkt);
        if (firstSound) {
            RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] sound packet sent OK (ClientboundSoundPacket, ctor 8-arg holder+seed)");
        }
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
        // Avoid recipient.level() / serverLevel() entirely - both throw
        // NoSuchMethodError on the deployed 1.21.11 runtime even from
        // this Loom-compiled module (intermediary mismatch on
        // class_3222.method_37908 / method_51469). Build the targeted
        // particle packet ourselves and send it through
        // ServerGamePacketListenerImpl, which only needs the recipient
        // and the ParticleOptions - no ServerLevel required.
        //
        // Both flags forced to true: overrideLimiter bypasses the
        // client's particle.density throttle, and longDistance ("alwaysShow"
        // in mapped form) tells the client to render even when the
        // particle origin is outside the normal ~32-block render radius.
        // Without longDistance=true, particles dispatched at the teleport
        // destination are silently culled on every /rtp after the first:
        // the destination chunk has not yet been added to the client's
        // tracker by the time the packet arrives, so the client treats
        // the particle as "far" and drops it. Mirrors Bukkit's force=true
        // semantics (Player#spawnParticle/World#spawnParticle force flag).
        net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket pkt =
                new net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket(
                        options,
                        /* overrideLimiter */ true,
                        /* alwaysShow      */ true,
                        x, y, z,
                        (float) dx, (float) dy, (float) dz,
                        (float) speed,
                        count);
        boolean firstParticle = !LOGGED_FIRST_PARTICLE;
        if (firstParticle) {
            LOGGED_FIRST_PARTICLE = true;
            try {
                RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] particle dispatch #1: "
                        + "optionsClass=" + options.getClass().getName()
                        + " typeClass=" + (options.getType() == null ? "null" : options.getType().getClass().getName())
                        + " connectionNull=" + (recipient.connection == null)
                        + " pos=" + x + "," + y + "," + z + " count=" + count
                        + " offset=" + dx + "," + dy + "," + dz + " speed=" + speed);
            } catch (Throwable t) {
                RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] particle dispatch #1 diag failed: " + t);
            }
        }
        recipient.connection.send(pkt);
        if (firstParticle) {
            RTP.log(Level.FINER, "[RTP][Fabric 1.21.11+] particle packet sent OK (ClientboundLevelParticlesPacket, 2-bool form)");
        }
    }
}
