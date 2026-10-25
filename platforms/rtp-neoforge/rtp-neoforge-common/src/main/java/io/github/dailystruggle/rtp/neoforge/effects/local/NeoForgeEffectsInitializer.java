package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.EffectFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NeoForge counterpart of {@code rtp.fabric.effects.FabricEffectsInitializer}
 * (effects-api-ADR-003/004/007, ADR-116). Binds the NeoForge {@link NeoForgeValueCoercer}
 * and registers the canonical built-in effects with {@link EffectFactory}.
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

        registerHandleEffects();
        EffectFactory.putEffect("SOUND",    new NeoForgeSoundEffect());
        EffectFactory.putEffect("PARTICLE", new NeoForgeParticleEffect());
        EffectFactory.putEffect("TITLE",    new NeoForgeTitleEffect());
        EffectFactory.putEffect("POTION",   new NeoForgePotionEffect());
    }

    /**
     * Prototypes that act only through {@link NeoForgeHandles} (no registry lookups), so they
     * register without a bootstrapped server. Delegates to
     * {@link EffectFactory#registerBuiltInEffects()} (ADR-116) so every built-in effect key is
     * registered once without platform drift.
     */
    static void registerHandleEffects() {
        EffectFactory.registerBuiltInEffects();
    }
}
