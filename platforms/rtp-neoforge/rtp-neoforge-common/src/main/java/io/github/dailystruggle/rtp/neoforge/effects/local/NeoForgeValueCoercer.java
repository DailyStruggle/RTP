package io.github.dailystruggle.rtp.neoforge.effects.local;

import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;

import java.util.Arrays;
import java.util.List;

/**
 * NeoForge counterpart of {@code effectsapi.fabric.FabricValueCoercer}
 * (effects-api-ADR-004), compiled Mojmap in {@code rtp-neoforge-common} so it
 * carries no Fabric-intermediary or {@code ResourceLocation}/{@code Identifier}
 * bytecode references (both fail to link on the NeoForge 1.21.11 runtime).
 * Registry resolution is delegated to the fully-reflective
 * {@link NeoForgeRegistryResolver}.
 *
 * <p>{@code BuiltInRegistries}, {@code Registry}, {@code SoundEvent},
 * {@code ParticleType} and {@code MobEffect} keep their Mojmap names across the
 * 1.21.x line, so they are referenced directly. S-005: pure in-memory registry
 * lookups; S-004: {@link #parse} throws (never returns null) on a miss.</p>
 */
public final class NeoForgeValueCoercer implements ValueCoercer {

    @Override
    public TypeKey classify(Object def) {
        if (def == null) return TypeKey.STRING;
        if (def instanceof String) return TypeKey.STRING;
        if (def instanceof Boolean) return TypeKey.BOOLEAN;
        if (def instanceof Long) return TypeKey.LONG;
        if (def instanceof Integer || def instanceof Short || def instanceof Byte) return TypeKey.INT;
        if (def instanceof Double) return TypeKey.DOUBLE;
        if (def instanceof Float) return TypeKey.FLOAT;
        if (def instanceof SoundEvent) return TypeKey.SOUND;
        if (def instanceof ParticleType<?>) return TypeKey.PARTICLE;
        if (def instanceof MobEffect) return TypeKey.POTION_EFFECT;
        String n = def.getClass().getName();
        if (n.startsWith("net.minecraft.sounds.") && n.endsWith("SoundEvent")) return TypeKey.SOUND;
        if (n.startsWith("net.minecraft.core.particles.")) return TypeKey.PARTICLE;
        if (n.startsWith("net.minecraft.world.effect.") && n.endsWith("MobEffect")) return TypeKey.POTION_EFFECT;
        return TypeKey.UNKNOWN;
    }

    @Override
    public boolean canParse(TypeKey type, String raw) {
        if (raw == null) return false;
        switch (type) {
            case STRING:  return true;
            case BOOLEAN: return raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false");
            case INT:     return tryInt(raw);
            case LONG:    return tryLong(raw);
            case DOUBLE:  return tryDouble(raw);
            case FLOAT:   return tryDouble(raw);
            case SOUND:    return NeoForgeRegistryResolver.resolve(BuiltInRegistries.SOUND_EVENT, raw) != null;
            case PARTICLE: return NeoForgeRegistryResolver.resolve(BuiltInRegistries.PARTICLE_TYPE, raw) != null;
            case POTION_EFFECT: return NeoForgeRegistryResolver.resolve(BuiltInRegistries.MOB_EFFECT, raw) != null;
            case COLOR:
            case MATERIAL:
            case WORLD:
            case UNKNOWN:
            default:
                return false;
        }
    }

    @Override
    public Object parse(TypeKey type, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("NeoForgeValueCoercer.parse: raw token is null for type=" + type);
        }
        switch (type) {
            case STRING:  return raw;
            case BOOLEAN: return Boolean.parseBoolean(raw);
            case INT:     return Integer.parseInt(raw);
            case LONG:    return Long.parseLong(raw);
            case DOUBLE:  return Double.parseDouble(raw) / 100.0d;            // ADR-002 legacy /100
            case FLOAT:   return ((float) Double.parseDouble(raw)) / 100.0f;  // ADR-002 legacy /100
            case SOUND: {
                SoundEvent v = NeoForgeRegistryResolver.resolve(BuiltInRegistries.SOUND_EVENT, raw);
                if (v == null) throw new IllegalArgumentException("Unknown sound: " + raw);
                return v;
            }
            case PARTICLE: {
                ParticleType<?> v = NeoForgeRegistryResolver.resolve(BuiltInRegistries.PARTICLE_TYPE, raw);
                if (v == null) throw new IllegalArgumentException("Unknown particle: " + raw);
                return v;
            }
            case POTION_EFFECT: {
                MobEffect v = NeoForgeRegistryResolver.resolve(BuiltInRegistries.MOB_EFFECT, raw);
                if (v == null) throw new IllegalArgumentException("Unknown potion effect: " + raw);
                return v;
            }
            case COLOR:
            case MATERIAL:
            case WORLD:
            case UNKNOWN:
            default:
                throw new IllegalArgumentException(
                        "NeoForgeValueCoercer.parse: unsupported type " + type + " for token " + raw
                                + " (Phase-1 scope; see effects-api-ADR-003).");
        }
    }

    @Override
    public Object resolveReflective(Class<?> targetType, String raw) {
        if (targetType == null || raw == null) return null;
        if (SoundEvent.class.isAssignableFrom(targetType))
            return NeoForgeRegistryResolver.resolve(BuiltInRegistries.SOUND_EVENT, raw);
        if (ParticleType.class.isAssignableFrom(targetType))
            return NeoForgeRegistryResolver.resolve(BuiltInRegistries.PARTICLE_TYPE, raw);
        if (MobEffect.class.isAssignableFrom(targetType))
            return NeoForgeRegistryResolver.resolve(BuiltInRegistries.MOB_EFFECT, raw);
        if (targetType.isEnum()) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object v = Enum.valueOf((Class<Enum>) targetType.asSubclass(Enum.class), raw.toUpperCase());
                return v;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        try {
            return targetType.getMethod("valueOf", String.class).invoke(null, raw);
        } catch (Throwable ignored) { /* fall through */ }
        try {
            return targetType.getMethod("getByName", String.class).invoke(null, raw);
        } catch (Throwable ignored) { /* fall through */ }
        return null;
    }

    @Override
    public List<TypeKey> readingOrder() {
        return Arrays.asList(
                TypeKey.BOOLEAN, TypeKey.INT, TypeKey.LONG, TypeKey.DOUBLE, TypeKey.FLOAT,
                TypeKey.SOUND, TypeKey.PARTICLE, TypeKey.POTION_EFFECT,
                TypeKey.STRING);
    }

    private static boolean tryInt(String raw) {
        try { Integer.parseInt(raw); return true; } catch (NumberFormatException e) { return false; }
    }
    private static boolean tryLong(String raw) {
        try { Long.parseLong(raw); return true; } catch (NumberFormatException e) { return false; }
    }
    private static boolean tryDouble(String raw) {
        try { Double.parseDouble(raw); return true; } catch (NumberFormatException e) { return false; }
    }
}
