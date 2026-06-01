package io.github.dailystruggle.effectsapi.fabric_unobf;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.FabricParticleEffect;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.FabricPotionEffect;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.FabricSoundEffect;
import io.github.dailystruggle.effectsapi.fabric_unobf.LocalEffects.FabricTitleEffect;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fabric-side initializer for {@link EffectFactory}, per effects-api-ADR-003
 * + ADR-004.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>Bind the per-platform {@link io.github.dailystruggle.effectsapi.common.spi.ValueCoercer}
 *       so {@link io.github.dailystruggle.effectsapi.common.Effect}'s adaptive
 *       reading order resolves Mojmap registry tokens (sounds / particles /
 *       potions) instead of failing with the unbound-coercer guard from
 *       ADR-004.</li>
 *   <li>Register the Phase-1 Fabric concrete effects (SOUND / PARTICLE /
 *       TITLE / POTION). Glide and Firework are out of scope for Phase-1.</li>
 * </ol>
 *
 * <p>Idempotent: a second call to {@link #registerAll()} is a no-op.
 */
public final class FabricEffectsInitializer {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private FabricEffectsInitializer() {}

    /**
     * Bind the {@link FabricValueCoercer} and register the Phase-1 Fabric
     * concrete effects with {@link EffectFactory}. Safe to call multiple
     * times — second and subsequent invocations are no-ops.
     *
     * <p>Must be called <em>after</em> {@code MinecraftServer} is up
     * (so {@code BuiltInRegistries} is fully populated for default-value
     * resolution) — typically from a {@code ServerLifecycleEvents.SERVER_STARTED}
     * listener in {@code RTPFabricMod}.
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) return;

        // ADR-004: per-platform leaf operations live in FabricValueCoercer.
        EffectFactory.setCoercer(new FabricValueCoercer());

        EffectFactory.addEffect("SOUND",    new FabricSoundEffect());
        EffectFactory.addEffect("PARTICLE", new FabricParticleEffect());
        EffectFactory.addEffect("TITLE",    new FabricTitleEffect());
        EffectFactory.addEffect("POTION",   new FabricPotionEffect());
    }
}
