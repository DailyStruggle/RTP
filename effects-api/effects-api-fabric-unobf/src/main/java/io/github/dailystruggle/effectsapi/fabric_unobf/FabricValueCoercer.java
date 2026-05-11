package io.github.dailystruggle.effectsapi.fabric_unobf;

import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;

import java.util.Arrays;
import java.util.List;

/**
 * Fabric-side {@link ValueCoercer} implementation per
 * <em>effects-api-ADR-004</em>. Resolves logical {@link TypeKey} tokens
 * against Mojmap built-in registries:
 *
 * <ul>
 *   <li>{@link TypeKey#SOUND}    → {@link BuiltInRegistries#SOUND_EVENT}</li>
 *   <li>{@link TypeKey#PARTICLE} → {@link BuiltInRegistries#PARTICLE_TYPE}</li>
 *   <li>{@link TypeKey#POTION_EFFECT} → {@link BuiltInRegistries#MOB_EFFECT}</li>
 *   <li>Primitives (STRING / BOOLEAN / INT / LONG / DOUBLE / FLOAT) — parsed
 *       directly without registry lookup.</li>
 * </ul>
 *
 * <p>Phase-1 scope per the ADR-003 implementation checklist: COLOR, MATERIAL
 * and WORLD return {@code false} from {@link #canParse} and throw on
 * {@link #parse}. They are not consumed by any Fabric concrete effect today
 * ({@code FabricSoundEffect}/{@code FabricParticleEffect}/
 * {@code FabricTitleEffect}/{@code FabricPotionEffect}); a follow-up ADR
 * adds them when Fabric grows a {@code FabricFireworkEffect} or a
 * GLIDE-equivalent.
 *
 * <p>S-005: all lookups are pure in-memory registry queries; no chunk I/O.
 * S-004: {@link #parse(TypeKey, String)} throws {@link IllegalArgumentException}
 * with a human-readable diagnostic on miss (never silently returns null).
 */
public final class FabricValueCoercer implements ValueCoercer {

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
        // Match by FQN as a defensive fallback for indirect registry holders
        // (e.g. ParticleOptions, Holder<SoundEvent>).
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
            case FLOAT:   return tryDouble(raw); // float ⊂ double for parse-checks
            case SOUND:    return resolveRegistry(BuiltInRegistries.SOUND_EVENT, raw) != null;
            case PARTICLE: return resolveRegistry(BuiltInRegistries.PARTICLE_TYPE, raw) != null;
            case POTION_EFFECT: return resolveRegistry(BuiltInRegistries.MOB_EFFECT, raw) != null;
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
            throw new IllegalArgumentException("FabricValueCoercer.parse: raw token is null for type=" + type);
        }
        switch (type) {
            case STRING:  return raw;
            case BOOLEAN: return Boolean.parseBoolean(raw);
            case INT:     return Integer.parseInt(raw);
            case LONG:    return Long.parseLong(raw);
            case DOUBLE:  return Double.parseDouble(raw) / 100.0d;   // ADR-002 legacy /100
            case FLOAT:   return ((float) Double.parseDouble(raw)) / 100.0f; // ADR-002 legacy /100
            case SOUND: {
                SoundEvent v = resolveRegistry(BuiltInRegistries.SOUND_EVENT, raw);
                if (v == null) {
                    System.err.println("[effects-api] [FabricValueCoercer] SOUND miss for token='" + raw
                            + "'; tried keys: [" + triedKeys(raw) + "]");
                    throw new IllegalArgumentException("Unknown sound: " + raw);
                }
                return v;
            }
            case PARTICLE: {
                ParticleType<?> v = resolveRegistry(BuiltInRegistries.PARTICLE_TYPE, raw);
                if (v == null) {
                    System.err.println("[effects-api] [FabricValueCoercer] PARTICLE miss for token='" + raw
                            + "'; tried keys: [" + triedKeys(raw) + "]");
                    throw new IllegalArgumentException("Unknown particle: " + raw);
                }
                return v;
            }
            case POTION_EFFECT: {
                MobEffect v = resolveRegistry(BuiltInRegistries.MOB_EFFECT, raw);
                if (v == null) {
                    System.err.println("[effects-api] [FabricValueCoercer] POTION_EFFECT miss for token='" + raw
                            + "'; tried keys: [" + triedKeys(raw) + "]");
                    throw new IllegalArgumentException("Unknown potion effect: " + raw);
                }
                return v;
            }
            case COLOR:
            case MATERIAL:
            case WORLD:
            case UNKNOWN:
            default:
                throw new IllegalArgumentException(
                        "FabricValueCoercer.parse: unsupported type " + type
                                + " for token " + raw + " (Phase-1 scope; see effects-api-ADR-003).");
        }
    }

    @Override
    public Object resolveReflective(Class<?> targetType, String raw) {
        if (targetType == null || raw == null) return null;
        // Mojmap registries first — these cover the overwhelmingly common case
        // for un-{classify}'d targetTypes (e.g. Holder<SoundEvent>).
        if (SoundEvent.class.isAssignableFrom(targetType)) return resolveRegistry(BuiltInRegistries.SOUND_EVENT, raw);
        if (ParticleType.class.isAssignableFrom(targetType)) return resolveRegistry(BuiltInRegistries.PARTICLE_TYPE, raw);
        if (MobEffect.class.isAssignableFrom(targetType)) return resolveRegistry(BuiltInRegistries.MOB_EFFECT, raw);

        // Generic reflective fallback — Enum#valueOf, then static valueOf(String), then static getByName(String).
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
        // ADR-002's left-to-right declaration order is what actually drives
        // the cursor; this is informational. Match Bukkit's ordering for
        // cross-platform parity.
        return Arrays.asList(
                TypeKey.BOOLEAN, TypeKey.INT, TypeKey.LONG, TypeKey.DOUBLE, TypeKey.FLOAT,
                TypeKey.SOUND, TypeKey.PARTICLE, TypeKey.POTION_EFFECT,
                TypeKey.STRING
        );
    }

    // ---- helpers --------------------------------------------------------

    private static <T> T resolveRegistry(Registry<T> registry, String raw) {
        if (raw == null) return null;
        // First attempt: as-typed (allows mixed-case namespaces from
        // dependent mods that legitimately register them).
        Identifier key = tryResourceLocation(raw);
        if (key != null) {
            // Cross-version shim: get(Identifier) was renamed to
            // getValue(Identifier) in MC 1.21.2 (Mojmap). A direct call
            // throws NoSuchMethodError on 1.21.2+ servers.
            T resolved = FabricRegistryCompat.resolve(registry, key);
            if (resolved != null) return resolved;
        }
        // Retry with lowercased input — legacy Bukkit-style configs ship
        // tokens like "PORTAL"/"BLINDNESS" inherited from Spigot enums.
        // Mojmap registry keys are lowercase (`minecraft:portal`,
        // `minecraft:blindness`); without this fallback those tokens are
        // silently dropped (see effects-api token-parity issue, May 2026).
        String lower = raw.toLowerCase();
        if (!lower.equals(raw)) {
            Identifier lowerKey = tryResourceLocation(lower);
            if (lowerKey != null) {
                T resolved = FabricRegistryCompat.resolve(registry, lowerKey);
                if (resolved != null) return resolved;
            }
        }
        // Final retry: legacy Bukkit `Sound` enum names use underscores
        // (e.g. ENTITY_ENDERMAN_TELEPORT) where the Mojmap Identifier
        // path uses dots (`entity.enderman.teleport`). Translate `_` → `.`
        // on the lowercased form, but preserve any explicit `namespace:`
        // prefix's separator (only the path component should be dotted).
        String dotted = legacyEnumToPath(lower);
        if (dotted != null && !dotted.equals(lower) && !dotted.equals(raw)) {
            Identifier dottedKey = tryResourceLocation(dotted);
            if (dottedKey != null) {
                T resolved = FabricRegistryCompat.resolve(registry, dottedKey);
                if (resolved != null) return resolved;
            }
        }
        return null;
    }

    /** Diagnostic helper: returns the comma-joined list of keys actually tried. */
    private static String triedKeys(String raw) {
        String lower = raw == null ? null : raw.toLowerCase();
        String dotted = legacyEnumToPath(lower);
        StringBuilder sb = new StringBuilder().append(raw);
        if (lower != null && !lower.equals(raw)) sb.append(", ").append(lower);
        if (dotted != null && !dotted.equals(lower) && !dotted.equals(raw)) sb.append(", ").append(dotted);
        return sb.toString();
    }

    private static String legacyEnumToPath(String lower) {
        if (lower == null || lower.indexOf('_') < 0) return lower;
        int colon = lower.indexOf(':');
        if (colon < 0) {
            return lower.replace('_', '.');
        }
        // Only translate the path part after the namespace separator.
        return lower.substring(0, colon + 1) + lower.substring(colon + 1).replace('_', '.');
    }

    private static Identifier tryResourceLocation(String raw) {
        // Accept both "minecraft:flame" and bare "flame" (defaulting to minecraft:);
        // matches the Bukkit-side NamespacedKey leniency of legacy effects.
        try {
            // 1.21+: tryParse returns null on malformed input rather than throwing.
            Identifier parsed = Identifier.tryParse(raw);
            if (parsed != null) return parsed;
        } catch (Throwable ignored) {
            // Identifier API drift across MC versions; fall through.
        }
        try {
            return Identifier.tryParse("minecraft:" + raw.toLowerCase());
        } catch (Throwable ignored) {
            return null;
        }
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
