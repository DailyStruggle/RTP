package io.github.dailystruggle.effectsapi.common;

import io.github.dailystruggle.effectsapi.common.spi.ValueCoercer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Per-platform leaf operations of the ADR-002 adaptive reading order.
     * Bound at platform-init time via {@link #setCoercer(ValueCoercer)} —
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
     * @throws IllegalStateException if no coercer has been bound yet — addons
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
            //todo: figure out how these are triggered and log how to fix them
            throwable.printStackTrace();
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

            String[] val = node.replace(permissionPrefix, "").split("\\.");
            Effect<?> proto = effectMap.get(val[0].toUpperCase());
            if (proto == null) continue;
            Effect<?> effect = proto.clone();

            if (val.length > 1) effect.setData(Arrays.copyOfRange(val, 1, val.length));
            res.add(effect);
        }

        return res;
    }
}
