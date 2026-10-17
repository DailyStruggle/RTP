package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.effects.CommandEffect;
import io.github.dailystruggle.effectsapi.common.effects.DeathEffect;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge counterpart of {@code rtp.fabric.effects.FabricEffectsInitializer}
 * (effects-api-ADR-003/004/007). Binds the NeoForge {@link NeoForgeValueCoercer}
 * and registers the Phase-1 NeoForge concrete effects (SOUND / PARTICLE /
 * TITLE / POTION / COMMAND / DEATH / KILL) with {@link EffectFactory}.
 *
 * <p>Unlike the Fabric initializer, these prototypes are compiled Mojmap (no
 * Loom remap) in {@code rtp-neoforge-common}, so their static initializers do
 * not reference Fabric-intermediary {@code class_NNNN} types and they never
 * type {@code ResourceLocation} (renamed to {@code Identifier} in 1.21.11) -
 * the two linkage failures that make the Fabric effects carrier
 * unusable on NeoForge.</p>
 *
 * <p>Idempotent: a second call to {@link #registerAll()} is a no-op. Must run
 * after the server is up so {@code BuiltInRegistries} is populated.</p>
 */
public final class NeoForgeEffectsInitializer {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private NeoForgeEffectsInitializer() {}

    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) return;

        EffectFactory.setCoercer(new NeoForgeValueCoercer());
        NeoForgeHandles.register();

        EffectFactory.addEffect("SOUND",    new NeoForgeSoundEffect());
        EffectFactory.addEffect("PARTICLE", new NeoForgeParticleEffect());
        EffectFactory.addEffect("TITLE",    new NeoForgeTitleEffect());
        EffectFactory.addEffect("POTION",   new NeoForgePotionEffect());
        registerHandleEffects();
    }

    /**
     * Prototypes that act only through {@link NeoForgeHandles} (no registry lookups), so they
     * register without a bootstrapped server. Without DEATH / KILL here, a {@code DEATH} token
     * is skipped as an unknown effect type.
     */
    static void registerHandleEffects() {
        EffectFactory.addEffect("COMMAND",  new CommandEffect());
        EffectFactory.addEffect("DEATH",    new DeathEffect());
        EffectFactory.addEffect("KILL",     new DeathEffect());
    }
}
