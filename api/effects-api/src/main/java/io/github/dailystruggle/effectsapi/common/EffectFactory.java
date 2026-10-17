package io.github.dailystruggle.effectsapi.common;

import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Factory and registry for {@link Effect} prototypes, keyed by name.
 *
 * <p>Platform-neutral as of effects-api-ADR-003: the static Bukkit-effect
 * registration block previously living here has moved to
 * {@code io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer}.
 * Fabric (and any other platform) registers its own concrete effects via
 * {@link #addEffect(String, Effect)} from a parallel platform initializer.
 */
public class EffectFactory {
    //map name to effect type
    //  this is a runtime solution for effectively a switch statement, to allow addition and removal
    private static final ConcurrentHashMap<String, Effect<?>> effectMap = new ConcurrentHashMap<>();

    private static final Set<String> warnedUnknownTypes = ConcurrentHashMap.newKeySet();

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Clear the cache of warned unknown effect types. Primarily for tests.
     */
    public static void clearWarnedUnknownTypes() {
        warnedUnknownTypes.clear();
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
        effectMap.putIfAbsent(effectName.toUpperCase(), effect);
    }

    public static Enumeration<String> listEffects() {
        return effectMap.keys();
    }

    public static void removeEffect(String effectName) {
        effectMap.remove(effectName.toUpperCase());
    }

    /**
     * @return a snapshot of all registered effect names. Useful for platform
     * initializers that need to iterate (e.g. permission registration).
     */
    public static Collection<String> registeredNames() {
        return new ArrayList<>(effectMap.keySet());
    }

    @Nullable
    public static <T extends Enum<T>> Effect<T> buildEffect(String name) {
        Effect<T> effect;
        try {
            effect = (Effect<T>) effectMap.get(name.toUpperCase()).clone();
        } catch (Throwable throwable) {
            Logger.getLogger(EffectFactory.class.getName()).log(Level.WARNING, throwable, () -> "Failed to build effect: " + name);
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
