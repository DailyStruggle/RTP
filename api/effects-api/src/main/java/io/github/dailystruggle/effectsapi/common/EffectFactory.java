package io.github.dailystruggle.effectsapi.common;

import io.github.dailystruggle.effectsapi.common.effects.CommandEffect;
import io.github.dailystruggle.effectsapi.common.effects.DeathEffect;
import io.github.dailystruggle.effectsapi.common.effects.DropExpEffect;
import io.github.dailystruggle.effectsapi.common.effects.DropInventoryEffect;
import io.github.dailystruggle.effectsapi.common.effects.FireworkEffect;
import io.github.dailystruggle.effectsapi.common.effects.GlideEffect;
import io.github.dailystruggle.effectsapi.common.effects.NoteEffect;
import io.github.dailystruggle.effectsapi.common.effects.ParticleEffect;
import io.github.dailystruggle.effectsapi.common.effects.PotionEffect;
import io.github.dailystruggle.effectsapi.common.effects.SoundEffect;
import io.github.dailystruggle.effectsapi.common.effects.TitleEffect;
import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import io.github.dailystruggle.server.api.ServerAccessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Factory and registry for {@link Effect} prototypes, keyed by name.
 *
 * <p>Per ADR-116, {@link #registerBuiltInEffects()} provides the single canonical
 * registration entry point for all platform-neutral built-in effects across
 * Bukkit, Fabric (obf + 26.x unobf), and NeoForge.
 */
public class EffectFactory {
    /** Canonical built-in effect keys required across every supported platform (ADR-116). */
    public static final Set<String> BUILT_IN_EFFECT_KEYS = Set.of(
            "TITLE", "GLIDE", "COMMAND", "DEATH", "DROP_INVENTORY", "DROP_EXP",
            "SOUND", "PARTICLE", "POTION", "FIREWORK", "NOTE"
    );

    /** Canonical built-in effect aliases registered alongside {@link #BUILT_IN_EFFECT_KEYS}. */
    public static final Set<String> BUILT_IN_EFFECT_ALIASES = Set.of(
            "DROP_EXPERIENCE", "KILL"
    );

    //map name to effect type
    //  this is a runtime solution for effectively a switch statement, to allow addition and removal
    private static final ConcurrentHashMap<String, Effect<?>> effectMap = new ConcurrentHashMap<>();

    private static final Set<String> warnedUnknownTypes = ConcurrentHashMap.newKeySet();

    private static final Set<String> warnedUnsupportedPrimitives = ConcurrentHashMap.newKeySet();

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Clear the cache of warned unknown effect types so reloading effects
     * re-enables warnings for reintroduced unknown effect types.
     */
    public static void clearWarnedUnknownTypes() {
        warnedUnknownTypes.clear();
    }

    /**
     * Report once per {@code (platform, primitive)} pair that an effect primitive
     * is unsupported or could not execute on the active runtime (ADR-116 Decision 3, S-004).
     */
    public static void reportUnsupportedPrimitive(
            @NotNull String platform, @NotNull String primitive, @NotNull String reason) {
        String key = platform.toUpperCase(Locale.ROOT) + ":" + primitive.toUpperCase(Locale.ROOT);
        if (warnedUnsupportedPrimitives.add(key)) {
            Effect.warn("[" + platform + "] Effect primitive '" + primitive
                    + "' could not be executed: " + reason);
        }
    }

    /**
     * @return immutable snapshot of {@code PLATFORM:PRIMITIVE} keys reported via
     *         {@link #reportUnsupportedPrimitive(String, String, String)}.
     */
    @NotNull
    public static Set<String> unsupportedPrimitives() {
        return Collections.unmodifiableSet(new TreeSet<>(warnedUnsupportedPrimitives));
    }

    /** Clear the reported unsupported-primitive set (for reloads and tests). */
    public static void clearUnsupportedPrimitives() {
        warnedUnsupportedPrimitives.clear();
    }

    /**
     * Per-platform leaf operations of the ADR-002 adaptive reading order.
     * Bound at platform-init time via {@link #setCoercer(ValueCoercer)} -
     * {@code BukkitEffectsInitializer.registerAll()} on the Bukkit family,
     * {@code FabricEffectsInitializer.registerAll()} on Fabric. See
     * effects-api-ADR-004.
     */
    private static final AtomicReference<ValueCoercer> COERCER = new AtomicReference<>();

    /**
     * Bind the per-platform {@link ValueCoercer}. Idempotent: a second call
     * replaces the first (initializers are themselves idempotent per ADR-003).
     * Must be invoked before any {@link Effect} subclass attempts to coerce
     * a string token.
     */
    public static void setCoercer(@NotNull ValueCoercer coercer) {
        COERCER.set(coercer);
    }

    /**
     * Retrieve the bound {@link ValueCoercer}.
     *
     * @throws IllegalStateException if no coercer has been bound yet - addons
     *         calling effect coercion before the platform initializer ran
     *         (S-006: never null, never silent).
     */
    @NotNull
    public static ValueCoercer getCoercer() {
        ValueCoercer c = COERCER.get();
        if (c == null) {
            throw new IllegalStateException(
                    "[effects-api] No ValueCoercer bound. The platform initializer "
                    + "(BukkitEffectsInitializer.registerAll() or "
                    + "FabricEffectsInitializer.registerAll()) must run before any "
                    + "Effect coerces tokens. See effects-api-ADR-004.");
        }
        return c;
    }

    public static void addEffect(String effectName, Effect<?> effect) {
        effectMap.putIfAbsent(effectName.toUpperCase(Locale.ROOT), effect);
    }

    /**
     * Register or replace an effect prototype under {@code effectName}.
     */
    public static void putEffect(String effectName, Effect<?> effect) {
        effectMap.put(effectName.toUpperCase(Locale.ROOT), effect);
    }

    /**
     * Single canonical registration entry point for all platform-neutral built-in
     * effects ({@link #BUILT_IN_EFFECT_KEYS} plus {@link #BUILT_IN_EFFECT_ALIASES})
     * using platform-neutral default tokens (ADR-116 Decision 3).
     *
     * <p>Uses {@link #addEffect(String, Effect)} ({@code putIfAbsent}) so any
     * platform-specific prototype registered prior to this call is preserved.
     */
    public static void registerBuiltInEffects() {
        COERCER.compareAndSet(null, ValueCoercer.DEFAULT);
        addEffect("TITLE", new TitleEffect());
        addEffect("GLIDE", new GlideEffect());
        addEffect("COMMAND", new CommandEffect());
        addEffect("DEATH", new DeathEffect());
        addEffect("KILL", new DeathEffect());
        addEffect("DROP_INVENTORY", new DropInventoryEffect());
        addEffect("DROP_EXP", new DropExpEffect());
        addEffect("DROP_EXPERIENCE", new DropExpEffect());
        addEffect("SOUND", new SoundEffect());
        addEffect("PARTICLE", new ParticleEffect());
        addEffect("POTION", new PotionEffect());
        addEffect("FIREWORK", new FireworkEffect());
        addEffect("NOTE", new NoteEffect());
    }

    /**
     * Canonical registration entry point for all built-in effects, binding the
     * supplied platform-typed default values for {@code SOUND}, {@code PARTICLE},
     * {@code POTION}, {@code FIREWORK}, and {@code NOTE} (ADR-116 Decision 3).
     */
    public static void registerBuiltInEffects(
            @Nullable Object defaultSound,
            @Nullable Object defaultParticle,
            @Nullable Object defaultPotion,
            @Nullable Object defaultFireworkType,
            @Nullable Object defaultFireworkColor,
            @Nullable Object defaultInstrument) {
        registerBuiltInEffects();
        if (defaultSound != null) {
            putEffect("SOUND", new SoundEffect(defaultSound));
        }
        if (defaultParticle != null) {
            putEffect("PARTICLE", new ParticleEffect(defaultParticle));
        }
        if (defaultPotion != null) {
            putEffect("POTION", new PotionEffect(defaultPotion));
        }
        if (defaultFireworkType != null || defaultFireworkColor != null) {
            putEffect("FIREWORK", new FireworkEffect(
                    defaultFireworkType != null ? defaultFireworkType : "BALL",
                    defaultFireworkColor != null ? defaultFireworkColor : "WHITE"));
        }
        if (defaultInstrument != null) {
            putEffect("NOTE", new NoteEffect(defaultInstrument));
        }
    }

    public static Enumeration<String> listEffects() {
        return effectMap.keys();
    }

    public static void removeEffect(String effectName) {
        effectMap.remove(effectName.toUpperCase(Locale.ROOT));
    }

    /**
     * @return a snapshot of all registered effect names. Useful for platform
     * initializers that need to iterate (e.g. permission registration).
     */
    public static Collection<String> registeredNames() {
        return new ArrayList<>(effectMap.keySet());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends Enum<T>> Effect<T> buildEffect(String name) {
        Effect<T> effect;
        try {
            effect = (Effect<T>) effectMap.get(name.toUpperCase(Locale.ROOT)).clone();
        } catch (Throwable throwable) {
            ServerAccessors.log(Level.WARNING, "Failed to build effect: " + name, throwable);
            return null;
        }
        return effect;
    }

    /**
     * @param <T> type of effect to build
     * @param name name of effect to build
     * @param data what data the effect should have
     * @return a newly constructed effect, or null if there's no effect by that name
     */
    @Nullable
    public static <T extends Enum<T>> Effect<T> buildEffect(String name, EnumMap<T, Object> data) {
        Effect<T> effect = buildEffect(name);
        if (effect == null) return null;
        effect.setData(data);
        return effect;
    }

    /**
     * Build effects from a collection of permission node strings.
     *
     * <p>Platform-neutral entry point introduced by effects-api-ADR-003; the
     * Bukkit-typed overload (taking {@code PermissionAttachmentInfo}) lives in
     * {@code BukkitEffectsInitializer}.
     *
     * @param permissionPrefix - which permission prefix to filter on, for contextual effects
     * @param permissionNodes  - permission node strings (e.g. {@code rtp.effect.particle.flame.10})
     * @return all effects constructed
     */
    public static List<Effect<?>> buildEffects(@NotNull String permissionPrefix, @NotNull final Collection<String> permissionNodes) {
        List<Effect<?>> res = new ArrayList<>();
        if (!permissionPrefix.endsWith(".")) permissionPrefix += ".";

        for (String node : permissionNodes) {
            if (node == null || !node.startsWith(permissionPrefix)) continue;

            String raw = node.substring(permissionPrefix.length()).trim();
            if (raw.isEmpty()) continue;

            int firstDot = -1;
            int firstSpace = -1;
            boolean escaping = false;

            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (escaping) {
                    escaping = false;
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '.' && firstDot == -1) {
                    firstDot = i;
                    break;
                } else if (Character.isWhitespace(c) && firstSpace == -1) {
                    firstSpace = i;
                    break;
                }
            }

            String type;
            String[] args;

            if (firstSpace != -1) {
                // Whitespace-delimited effect syntax (e.g. "COMMAND CONSOLE say hi" or "DEATH false")
                type = raw.substring(0, firstSpace);
                String rest = raw.substring(firstSpace).trim();
                args = rest.isEmpty() ? new String[0] : WHITESPACE_RUN.split(rest);
            } else if (firstDot != -1) {
                // Dot-delimited permission syntax (e.g. "COMMAND.CONSOLE.say\ a\.b")
                type = raw.substring(0, firstDot);
                String rest = raw.substring(firstDot + 1);
                args = splitUnescapedDots(rest);
            } else {
                // Bare effect name (e.g. "DROP_INVENTORY")
                type = raw;
                args = new String[0];
            }

            String typeUpper = type.toUpperCase(Locale.ROOT);
            Effect<?> proto = effectMap.get(typeUpper);
            if (proto == null) {
                if (warnedUnknownTypes.add(typeUpper)) {
                    Effect.warn("[EffectFactory] Unknown effect type: \"" + typeUpper + "\" in node: " + node);
                }
                continue;
            }

            Effect<?> effect = proto.clone();
            if (args.length > 0) {
                effect.setData(args);
            }
            res.add(effect);
        }

        return res;
    }

    private static String[] splitUnescapedDots(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        List<String> list = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaping) {
                current.append('\\').append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '.') {
                list.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (escaping) {
            current.append('\\');
        }
        list.add(current.toString());
        return list.toArray(new String[0]);
    }
}
