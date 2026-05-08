package io.github.dailystruggle.effectsapi.fabric;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.FabricParticleEffect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.FabricPotionEffect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricParticleKeys;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricPotionKeys;
import net.minecraft.SharedConstants;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the May-2026 token-parity report: legacy effects.yml
 * configs ship registry tokens as uppercase enum-style identifiers
 * ("PORTAL", "BLINDNESS") inherited from the Spigot {@code Particle} /
 * {@code PotionEffectType} enums. On Fabric, those keys are lowercase
 * ResourceLocations ({@code minecraft:portal}, {@code minecraft:blindness}),
 * so {@link FabricValueCoercer} must accept either casing or the legacy
 * Bukkit-side configuration silently degrades to the effect's default value.
 *
 * <p>Symptom prior to fix: {@code FabricParticleEffect} log-warned
 * "ignored 1 token(s) that matched no remaining key: [PORTAL]" and
 * {@code FabricPotionEffect} silently kept its default speed potion in
 * place of the configured BLINDNESS — both produced because
 * {@code resolveRegistry} did not retry the registry lookup with a
 * lowercased path.
 */
class FabricLegacyUppercaseTokenTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        EffectFactory.setCoercer(new FabricValueCoercer());
    }

    @Test
    void coercer_acceptsUppercaseParticleToken() {
        FabricValueCoercer coercer = new FabricValueCoercer();
        assertTrue(coercer.canParse(TypeKey.PARTICLE, "PORTAL"),
                "Uppercase legacy particle name must resolve via lowercased fallback");
        Object parsed = coercer.parse(TypeKey.PARTICLE, "PORTAL");
        assertInstanceOf(ParticleType.class, parsed);
    }

    @Test
    void coercer_acceptsUppercasePotionToken() {
        FabricValueCoercer coercer = new FabricValueCoercer();
        assertTrue(coercer.canParse(TypeKey.POTION_EFFECT, "BLINDNESS"),
                "Uppercase legacy potion name must resolve via lowercased fallback");
        Object parsed = coercer.parse(TypeKey.POTION_EFFECT, "BLINDNESS");
        assertInstanceOf(MobEffect.class, parsed);
    }

    @Test
    void particleEffect_setDataAcceptsUppercaseLegacyToken() {
        FabricParticleEffect effect = new FabricParticleEffect();
        Object before = effect.getData().get(FabricParticleKeys.TYPE);
        effect.setData("PORTAL", "32", "1", "1", "1", "0");
        Object after = effect.getData().get(FabricParticleKeys.TYPE);
        assertNotNull(after);
        assertNotSame(before, after, "PORTAL token must override the default HAPPY_VILLAGER");
    }

    @Test
    void coercer_acceptsLegacyEnumStyleSoundToken() {
        // Bukkit's Sound enum names use underscores (ENTITY_ENDERMAN_TELEPORT)
        // where Mojmap ResourceLocation paths use dots (entity.enderman.teleport).
        // FabricValueCoercer must translate `_` -> `.` on the lowercased path.
        FabricValueCoercer coercer = new FabricValueCoercer();
        assertTrue(coercer.canParse(TypeKey.SOUND, "ENTITY_ENDERMAN_TELEPORT"),
                "Legacy underscored sound name must resolve via dotted-path fallback");
        Object parsed = coercer.parse(TypeKey.SOUND, "ENTITY_ENDERMAN_TELEPORT");
        assertInstanceOf(SoundEvent.class, parsed);
    }

    @Test
    void potionEffect_setDataAcceptsUppercaseLegacyToken() {
        FabricPotionEffect effect = new FabricPotionEffect();
        Object before = effect.getData().get(FabricPotionKeys.TYPE);
        effect.setData("BLINDNESS", "200", "0");
        Object after = effect.getData().get(FabricPotionKeys.TYPE);
        assertNotNull(after);
        assertNotSame(before, after, "BLINDNESS token must override the default speed potion");
    }
}
