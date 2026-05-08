package io.github.dailystruggle.effectsapi.fabric;

import io.github.dailystruggle.effectsapi.common.EffectFactory;
import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.FabricSoundEffect;
import io.github.dailystruggle.effectsapi.fabric.LocalEffects.enums.FabricSoundKeys;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-1 smoke test for the Fabric SOUND effect path per
 * <em>effects-api-ADR-003</em> step 9c of the implementation checklist
 * ({@code docs/dev/scratch/CHECKLIST-effects-api-platform-split.md}).
 *
 * <p>Mirrors the {@code FabricLegacyTextInteractiveTest} bootstrap pattern in
 * {@code rtp-fabric-common}: bootstraps Minecraft so that
 * {@link BuiltInRegistries#SOUND_EVENT} is populated, then exercises the
 * round-trip
 *   token-string &rarr; {@link FabricValueCoercer#parse(TypeKey, String)} &rarr;
 *   {@link SoundEvent} &rarr; {@code FabricSoundEffect#data} entry.
 *
 * <p>This is a <em>shape</em> smoke test — it does not actually emit a sound
 * packet (that requires a live {@code ServerLevel}, which Loom's test JVM does
 * not stand up). It verifies the parts of the effect pipeline we can drive
 * without a server: registry lookup, default-data population, {@code setData}
 * coercion, S-004 no-server diagnostics from {@link FabricEffectRuntime}, and
 * the {@link FabricSoundEffect#run()} guard against a missing target.
 */
class FabricSoundEffectSmokeTest {

    /**
     * MC 1.21.1's {@code BuiltInRegistries} static initializer needs both
     * {@code SharedConstants.tryDetectVersion()} (otherwise
     * {@code FireBlock.bootStrap()} throws "Game version not set") and
     * {@code Bootstrap.bootStrap()}. Both are idempotent.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // ADR-004: bind the Fabric coercer so EffectFactory.setData can resolve
        // SOUND tokens through the same path FabricEffectsInitializer wires up.
        EffectFactory.setCoercer(new FabricValueCoercer());
    }

    @Test
    void valueCoercer_resolvesNamespacedSound() {
        FabricValueCoercer coercer = new FabricValueCoercer();
        assertTrue(coercer.canParse(TypeKey.SOUND, "minecraft:entity.player.levelup"),
                "FabricValueCoercer must accept fully-qualified vanilla sound IDs");
        Object parsed = coercer.parse(TypeKey.SOUND, "minecraft:entity.player.levelup");
        assertInstanceOf(SoundEvent.class, parsed,
                "SOUND coercion must produce a Mojmap SoundEvent");
    }

    @Test
    void valueCoercer_resolvesBareSoundName() {
        FabricValueCoercer coercer = new FabricValueCoercer();
        // Bare "entity.player.levelup" should default to the minecraft: namespace,
        // matching the Bukkit-side NamespacedKey leniency relied on by legacy
        // effects.yml configs.
        Object parsed = coercer.parse(TypeKey.SOUND, "entity.player.levelup");
        assertInstanceOf(SoundEvent.class, parsed);
        SoundEvent expected = BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.tryParse("minecraft:entity.player.levelup"));
        assertSame(expected, parsed,
                "Bare sound name must resolve to the same registry entry as the namespaced form");
    }

    @Test
    void valueCoercer_unknownSoundThrows() {
        FabricValueCoercer coercer = new FabricValueCoercer();
        // S-004: never silently drop — must throw with a diagnostic, not return null.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> coercer.parse(TypeKey.SOUND, "minecraft:not_a_real_sound_xyz"));
        assertTrue(ex.getMessage() != null && ex.getMessage().toLowerCase().contains("sound"),
                "Diagnostic must mention the sound type; got: " + ex.getMessage());
    }

    @Test
    void soundEffect_defaultsArePopulated() {
        FabricSoundEffect effect = new FabricSoundEffect();
        Object type = effect.getData().get(FabricSoundKeys.TYPE);
        assertInstanceOf(SoundEvent.class, type,
                "FabricSoundEffect default TYPE must be a Mojmap SoundEvent");
        assertEquals(100, ((Number) effect.getData().get(FabricSoundKeys.VOLUME)).intValue());
        assertEquals(100, ((Number) effect.getData().get(FabricSoundKeys.PITCH)).intValue());
    }

    @Test
    void soundEffect_setDataAcceptsCoercedSoundToken() {
        FabricSoundEffect effect = new FabricSoundEffect();
        // The token grammar mirrors the Bukkit SoundEffect: TYPE VOLUME PITCH DX DY DZ.
        effect.setData("minecraft:block.note_block.pling", "75", "120", "0", "0", "0");
        Object type = effect.getData().get(FabricSoundKeys.TYPE);
        assertInstanceOf(SoundEvent.class, type, "setData must coerce TYPE through the bound ValueCoercer");
        SoundEvent expected = BuiltInRegistries.SOUND_EVENT.get(
                ResourceLocation.tryParse("minecraft:block.note_block.pling"));
        assertSame(expected, type, "Coerced SoundEvent must come from BuiltInRegistries.SOUND_EVENT");
    }

    @Test
    void soundEffect_runWithoutTargetIsNoOp() {
        FabricSoundEffect effect = new FabricSoundEffect();
        // target is null until setTarget(...) — run() must not throw, satisfying
        // the S-004 "never throw on missing prerequisites in an effect tick" contract.
        assertDoesNotThrow(effect::run,
                "FabricSoundEffect#run() must short-circuit cleanly when target is unset");
    }

    @Test
    void runtime_strictModeThrowsWhenNoServerBound() {
        // The test JVM never calls bindServer(), so SERVER is null. Strict mode
        // must surface that as an IllegalStateException so test authors notice
        // the missing wiring instead of silently dropping the task.
        FabricEffectRuntime strict = new FabricEffectRuntime(true);
        try {
            FabricEffectRuntime.unbindServer(); // defensive: ensure no leak from a sibling test.
            assertNull(FabricEffectRuntime.server(),
                    "Precondition: no MinecraftServer bound at the start of this test");
            assertThrows(IllegalStateException.class,
                    () -> strict.schedule(() -> {}, 0L),
                    "Strict-mode schedule must throw when no server is bound");
        } finally {
            FabricEffectRuntime.unbindServer();
        }
    }

    @Test
    void runtime_lenientModeDropsTaskWhenNoServerBound() {
        FabricEffectRuntime lenient = new FabricEffectRuntime(false);
        // Should not throw; the dropped-task warning goes to System.err and is
        // acceptable for production behaviour (effects firing during shutdown).
        assertDoesNotThrow(() -> lenient.schedule(() -> {
            throw new AssertionError("Task must not run when no server is bound");
        }, 0L));
    }

    /**
     * Verifies the functional dispatch hook contract introduced as the
     * durable replacement for the in-tree reflective resolvers: a per-version
     * Loom adapter (rtp-fabric-v*) registers a {@link FabricEffectRuntime.SoundDispatcher},
     * effects-api consults it from {@link FabricSoundEffect#run()} ahead of the
     * reflective fallback. We can't construct a real {@code ServerPlayer} in
     * the test JVM, so this case exercises only the registry round-trip and
     * the unregister-clears-default contract.
     */
    @Test
    void runtime_soundDispatcher_registerGetUnregister() {
        try {
            assertNull(FabricEffectRuntime.getSoundDispatcher(),
                    "Precondition: no SoundDispatcher registered at start of test");
            FabricEffectRuntime.SoundDispatcher d =
                    (player, sound, source, x, y, z, vol, pitch) -> { /* no-op */ };
            FabricEffectRuntime.registerSound(d);
            assertSame(d, FabricEffectRuntime.getSoundDispatcher(),
                    "registerSound must store the most-recent dispatcher");
        } finally {
            FabricEffectRuntime.registerSound(null);
        }
        assertNull(FabricEffectRuntime.getSoundDispatcher(),
                "registerSound(null) must clear the registration");
    }

    /** Mirror of {@link #runtime_soundDispatcher_registerGetUnregister} for particles. */
    @Test
    void runtime_particleDispatcher_registerGetUnregister() {
        try {
            assertNull(FabricEffectRuntime.getParticleDispatcher(),
                    "Precondition: no ParticleDispatcher registered at start of test");
            FabricEffectRuntime.ParticleDispatcher d =
                    (recipient, opts, x, y, z, count, dx, dy, dz, speed) -> { /* no-op */ };
            FabricEffectRuntime.registerParticle(d);
            assertSame(d, FabricEffectRuntime.getParticleDispatcher(),
                    "registerParticle must store the most-recent dispatcher");
        } finally {
            FabricEffectRuntime.registerParticle(null);
        }
        assertNull(FabricEffectRuntime.getParticleDispatcher(),
                "registerParticle(null) must clear the registration");
    }
}
