package io.github.dailystruggle.effectsapi.fabric_unobf;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.effects.GlideEffect;
import io.github.dailystruggle.effectsapi.common.effects.GlideKeepAlive;
import io.github.dailystruggle.effectsapi.common.effects.SoundEffect;
import io.github.dailystruggle.effectsapi.common.effects.ParticleEffect;
import io.github.dailystruggle.effectsapi.common.effects.PotionEffect;
import io.github.dailystruggle.effectsapi.common.effects.TitleEffect;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.particles.ParticleTypes;

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
 *   <li>Register the Fabric concrete effects. FIREWORK and NOTE are not
 *       registered: their handle methods have no implementation yet.</li>
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
     * times - second and subsequent invocations are no-ops.
     *
     * <p>Must be called <em>after</em> {@code MinecraftServer} is up
     * (so {@code BuiltInRegistries} is fully populated for default-value
     * resolution) - typically from a {@code ServerLifecycleEvents.SERVER_STARTED}
     * listener in {@code RTPFabricMod}.
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) return;

        // Register the Mojmap-native handle provider. The shared
        // rtp.fabric.effects.FabricHandles is Loom intermediary-remapped
        // (ServerPlayer -> class_3222) and does NOT link on the deobf MC 26.x
        // runtime, so wrapPlayer there raised NoClassDefFoundError the moment a
        // common effect (SOUND / PARTICLE / ...) fired. FabricHandlesUnobf is
        // compiled with no mappings step and names Mojang types natively.
        FabricHandlesUnobf.register();
        ServerTickEvents.END_SERVER_TICK.register(server -> GlideKeepAlive.tick());

        // ADR-004: per-platform leaf operations live in FabricValueCoercer.
        EffectFactory.setCoercer(new FabricValueCoercer());

        EffectFactory.addEffect("SOUND",    new SoundEffect(SoundEvents.PLAYER_LEVELUP));
        EffectFactory.addEffect("PARTICLE", new ParticleEffect(ParticleTypes.EXPLOSION));
        EffectFactory.addEffect("TITLE",    new TitleEffect());
        EffectFactory.addEffect("POTION",   new PotionEffect(MobEffects.BLINDNESS.value()));
        EffectFactory.addEffect("GLIDE",    new GlideEffect());
        EffectFactory.addEffect("COMMAND",  new io.github.dailystruggle.effectsapi.common.effects.CommandEffect());
        EffectFactory.addEffect("DROP_INVENTORY", new io.github.dailystruggle.effectsapi.common.effects.DropInventoryEffect());
        EffectFactory.addEffect("DROP_EXP", new io.github.dailystruggle.effectsapi.common.effects.DropExpEffect());
        EffectFactory.addEffect("DROP_EXPERIENCE", new io.github.dailystruggle.effectsapi.common.effects.DropExpEffect());
        EffectFactory.addEffect("DEATH", new io.github.dailystruggle.effectsapi.common.effects.DeathEffect());
        EffectFactory.addEffect("KILL", new io.github.dailystruggle.effectsapi.common.effects.DeathEffect());
    }
}
