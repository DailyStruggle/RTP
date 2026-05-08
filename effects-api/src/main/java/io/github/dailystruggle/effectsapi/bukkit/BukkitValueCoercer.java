package io.github.dailystruggle.effectsapi.bukkit;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.effectsapi.common.spi.TypeKey;
import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.List;

/**
 * Bukkit-side implementation of {@link ValueCoercer}. Owns the leaf
 * operations of <em>effects-api-ADR-002</em>'s adaptive reading order
 * (token → typed value), keeping {@code effectsapi.common.Effect} free of
 * {@code org.bukkit.*} imports per <em>effects-api-ADR-004</em>.
 *
 * <p>The bodies of {@code resolveSound}, {@code resolveNamedColor}, and
 * {@code resolveViaRegistry} were relocated here verbatim from the previous
 * {@code Effect.java} so legacy behaviour is preserved exactly. The existing
 * {@code EffectsApiAdaptiveReadingOrderTest} regression guard exercises the
 * full ladder unchanged.
 */
public final class BukkitValueCoercer implements ValueCoercer {

    @Override
    public TypeKey classify(Object def) {
        if (def == null) return TypeKey.STRING;
        if (def instanceof String) return TypeKey.STRING;
        if (def instanceof Boolean) return TypeKey.BOOLEAN;
        if (def instanceof Long) return TypeKey.LONG;
        if (def instanceof Integer) return TypeKey.INT;
        if (def instanceof Double) return TypeKey.DOUBLE;
        if (def instanceof Float) return TypeKey.FLOAT;
        if (def instanceof Color) return TypeKey.COLOR;
        if (def instanceof Particle) return TypeKey.PARTICLE;
        if (def instanceof PotionEffectType) return TypeKey.POTION_EFFECT;
        if (def instanceof Material) return TypeKey.MATERIAL;
        if (def instanceof World) return TypeKey.WORLD;
        // Sound is registry-backed on 1.21.3+ (interface, not enum) — match by
        // name as well as instanceof to cover both eras.
        if (def instanceof Sound || def.getClass().getName().equals("org.bukkit.Sound")) return TypeKey.SOUND;
        return TypeKey.UNKNOWN;
    }

    @Override
    public boolean canParse(TypeKey type, String raw) {
        if (raw == null) return false;
        switch (type) {
            case STRING:
                return true;
            case BOOLEAN: {
                String upper = raw.toUpperCase();
                return upper.equals("TRUE") || upper.equals("FALSE");
            }
            case INT:
            case LONG:
                try { Long.parseLong(raw); return true; }
                catch (NumberFormatException nfe) { return false; }
            case DOUBLE:
            case FLOAT:
                try {
                    float f = Float.parseFloat(raw);
                    return !Float.isNaN(f) && !Float.isInfinite(f);
                } catch (NumberFormatException nfe) { return false; }
            case COLOR:
                if (resolveNamedColor(raw) != null) return true;
                try { Integer.parseInt(raw, 16); return true; }
                catch (NumberFormatException nfe) { return false; }
            case SOUND:
                return resolveSound(raw) != null;
            case PARTICLE:
                return resolveViaRegistry(Particle.class, raw) != null
                        || tryEnumValueOf(Particle.class, raw);
            case POTION_EFFECT:
                return resolveViaRegistry(PotionEffectType.class, raw) != null
                        || tryStaticGetByName(PotionEffectType.class, raw);
            case MATERIAL:
                return resolveViaRegistry(Material.class, raw) != null
                        || tryEnumValueOf(Material.class, raw);
            case WORLD:
                // World lookup requires a live server; canParse must not block
                // (S-005). Treat as non-parsable in canParse and let parse
                // throw at runtime if a token actually needs it.
                return false;
            case UNKNOWN:
            default:
                return false;
        }
    }

    @Override
    public Object parse(TypeKey type, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("[effects-api] null token");
        }
        switch (type) {
            case STRING:
                return raw.toUpperCase();
            case BOOLEAN:
                return Boolean.parseBoolean(raw.toUpperCase());
            case INT:
            case LONG:
                try {
                    return Integer.parseInt(raw.toUpperCase());
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                            "[effects-api] expected integer but got \"" + raw + "\"", nfe);
                }
            case DOUBLE:
            case FLOAT:
                try {
                    // Legacy ADR-002 coercion: tokens land as percent / 100.
                    return Float.parseFloat(raw.toUpperCase()) / 100;
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                            "[effects-api] expected float but got \"" + raw + "\"", nfe);
                }
            case COLOR: {
                String upper = raw.toUpperCase();
                Color named = resolveNamedColor(upper);
                if (named != null) return named;
                try {
                    return Color.fromRGB(Integer.parseInt(upper, 16));
                } catch (NumberFormatException nfe) {
                    throw new IllegalArgumentException(
                            "[effects-api] expected Color (named constant or 6-digit hex RRGGBB) but got \""
                                    + raw + "\"", nfe);
                }
            }
            case SOUND: {
                Object o = resolveSound(raw.toUpperCase());
                if (o == null) throw new IllegalArgumentException("unknown sound - " + raw);
                return o;
            }
            case PARTICLE: {
                Object o = tryEnumValueOfObj(Particle.class, raw.toUpperCase());
                if (o != null) return o;
                o = resolveViaRegistry(Particle.class, raw);
                if (o != null) return o;
                throw new IllegalArgumentException("unknown particle - " + raw);
            }
            case POTION_EFFECT: {
                Object o = tryStaticGetByNameObj(PotionEffectType.class, raw.toUpperCase());
                if (o != null) return o;
                o = resolveViaRegistry(PotionEffectType.class, raw);
                if (o != null) return o;
                throw new IllegalArgumentException("unknown potion effect - " + raw);
            }
            case MATERIAL: {
                Object o = tryEnumValueOfObj(Material.class, raw.toUpperCase());
                if (o != null) return o;
                o = resolveViaRegistry(Material.class, raw);
                if (o != null) return o;
                throw new IllegalArgumentException("unknown material - " + raw);
            }
            case UNKNOWN:
            case WORLD:
            default:
                throw new IllegalArgumentException(
                        "[effects-api] cannot parse \"" + raw + "\" — type "
                                + type + " has no Bukkit parser");
        }
    }

    @Override
    public Object resolveReflective(Class<?> targetType, String raw) {
        if (targetType == null || raw == null) return null;
        try {
            return targetType.getMethod("valueOf", String.class).invoke(null, raw.toUpperCase());
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // fall through
        } catch (InvocationTargetException ite) {
            // valueOf threw — not a match
        }
        try {
            Object r = targetType.getMethod("getByName", String.class).invoke(null, raw);
            if (r != null) return r;
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // fall through
        } catch (InvocationTargetException ite) {
            // not a match
        }
        return resolveViaRegistry(targetType, raw);
    }

    @Override
    public List<TypeKey> readingOrder() {
        // Informational only; ADR-002 currently iterates defaults in declaration
        // order. Listed cheapest-first for future cache-tuning.
        return Arrays.asList(
                TypeKey.STRING, TypeKey.BOOLEAN, TypeKey.INT, TypeKey.LONG,
                TypeKey.DOUBLE, TypeKey.FLOAT,
                TypeKey.COLOR, TypeKey.SOUND, TypeKey.PARTICLE,
                TypeKey.POTION_EFFECT, TypeKey.MATERIAL, TypeKey.WORLD);
    }

    // --- legacy resolvers (relocated verbatim from Effect.java) -----------

    /**
     * Resolve a Bukkit value from a string by walking the {@link Registry}
     * surface reflectively. Used as a fallback when {@code valueOf} /
     * {@code getByName} both fail — covers the 1.21.3+ {@link Sound} migration
     * and the expected 26.1 migration of {@code Particle}/{@code EntityType}/
     * {@code Biome} from enums to registry-backed interfaces.
     */
    private static Object resolveViaRegistry(Class<?> targetType, String token) {
        if (token == null || token.isEmpty() || targetType == null) return null;
        String raw = token.trim();
        NamespacedKey key;
        if (raw.contains(":")) {
            key = NamespacedKey.fromString(raw.toLowerCase());
        } else {
            key = NamespacedKey.minecraft(raw.toLowerCase().replace('_', '.'));
        }
        if (key == null) return null;
        for (java.lang.reflect.Field f : Registry.class.getFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!Registry.class.isAssignableFrom(f.getType())) continue;
            java.lang.reflect.Type generic = f.getGenericType();
            if (!(generic instanceof ParameterizedType)) continue;
            java.lang.reflect.Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            if (args.length == 0 || !(args[0] instanceof Class<?>)) continue;
            if (!targetType.isAssignableFrom((Class<?>) args[0])) continue;
            try {
                Registry<?> registry = (Registry<?>) f.get(null);
                if (registry == null) continue;
                Object v = registry.get(key);
                if (v != null) return v;
            } catch (Throwable ignored) {
                // try next registry
            }
        }
        return null;
    }

    /**
     * Resolve a named Bukkit {@link Color} constant by reflecting on
     * {@link Color}'s {@code public static final Color} fields.
     */
    private static Color resolveNamedColor(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            java.lang.reflect.Field f = Color.class.getField(token.toUpperCase());
            int mods = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mods)
                    && java.lang.reflect.Modifier.isPublic(mods)
                    && Color.class.isAssignableFrom(f.getType())) {
                Object val = f.get(null);
                if (val instanceof Color) return (Color) val;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            // not a named constant
        }
        return null;
    }

    /**
     * Resolve an {@link org.bukkit.Sound} from a string token in a way that
     * works on both legacy (enum) and modern (registry-backed interface)
     * Bukkit/Paper/Folia.
     */
    private static Object resolveSound(String token) {
        if (token == null) return null;
        try {
            return Sound.class.getMethod("valueOf", String.class)
                    .invoke(null, token.toUpperCase());
        } catch (Throwable ignored) {
            // fall through to registry lookup
        }
        String raw = token.trim();
        NamespacedKey key;
        if (raw.contains(":")) {
            key = NamespacedKey.fromString(raw.toLowerCase());
        } else {
            String keyPath = raw.toLowerCase().replace('_', '.');
            key = NamespacedKey.minecraft(keyPath);
        }
        if (key == null) return null;
        try {
            return Registry.SOUNDS.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- small reflective helpers for canParse paths ---------------------

    private static boolean tryEnumValueOf(Class<?> target, String raw) {
        return tryEnumValueOfObj(target, raw.toUpperCase()) != null;
    }

    private static Object tryEnumValueOfObj(Class<?> target, String raw) {
        try {
            return target.getMethod("valueOf", String.class).invoke(null, raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean tryStaticGetByName(Class<?> target, String raw) {
        return tryStaticGetByNameObj(target, raw) != null;
    }

    private static Object tryStaticGetByNameObj(Class<?> target, String raw) {
        try {
            return target.getMethod("getByName", String.class).invoke(null, raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * String coercion variant matching the legacy {@code Effect#fixData}'s
     * Color path: extracts the part after a {@link CommandsAPI} parameter
     * delimiter and parses it as 6-digit hex. Reserved for callers
     * that need the legacy behaviour outside the normal {@code parse} path.
     */
    @SuppressWarnings("unused")
    public static Color legacyColorFromDelimitedString(String val) {
        String str = val;
        if (str.contains(String.valueOf(CommandsAPI.parameterDelimiterAlt)))
            str = str.substring(str.indexOf(CommandsAPI.parameterDelimiterAlt) + 1);
        else if (str.contains(String.valueOf(CommandsAPI.parameterDelimiter)))
            str = str.substring(str.indexOf(CommandsAPI.parameterDelimiter) + 1);
        return Color.fromRGB(Integer.parseInt(str, 16));
    }
}
