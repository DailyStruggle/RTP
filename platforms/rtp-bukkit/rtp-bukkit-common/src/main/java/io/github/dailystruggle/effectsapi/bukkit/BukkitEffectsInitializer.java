package io.github.dailystruggle.effectsapi.bukkit;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Instrument;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bukkit-side initializer for {@link EffectFactory}.
 *
 * <p>Per effects-api-ADR-003, the platform-neutral {@link EffectFactory} no
 * longer registers concrete effects in a static block. Bukkit-family
 * platforms (Spigot/Paper/Folia) call {@link #registerAll()} during plugin
 * load to populate the factory with the legacy
 * {@code FIREWORK}/{@code NOTE}/{@code PARTICLE}/{@code POTION}/{@code SOUND}/
 * {@code GLIDE}/{@code TITLE} effects. Fabric uses a parallel
 * {@code FabricEffectsInitializer}.
 *
 * <p>Also retains Bukkit-typed convenience methods -
 * {@link #buildEffects(String, Collection)} (overloads on
 * {@link PermissionAttachmentInfo}) and {@link #addPermissions(String)} -
 * which previously lived on {@code EffectFactory} but cannot exist there
 * without a hard Bukkit dependency.
 */
public final class BukkitEffectsInitializer {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private BukkitEffectsInitializer() {}

    /**
     * Schedule an {@link Effect} on the Bukkit main thread. Replaces the
     * prior {@code effect.runTask(plugin)} pattern that relied on
     * {@code Effect extends BukkitRunnable} (decoupled 2026-05-07 - see
     * {@code Effect} class javadoc). If the scheduler rejects the task, the
     * failure is reported and the effect is dropped rather than run on the
     * calling thread.
     */
    public static void runEffect(@NotNull Plugin plugin, @NotNull Effect<?> effect) {
        try {
            Bukkit.getScheduler().runTask(plugin, effect);
        } catch (RuntimeException failure) {
            Effect.warn("Could not schedule effect " + effect.getClass().getSimpleName()
                    + " on the Bukkit scheduler; dropping it ("
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage() + ")");
        }
    }

    /**
     * Register the legacy Bukkit-side effects with {@link EffectFactory}.
     * Idempotent - safe to call multiple times.
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        // Bind the Bukkit-side ValueCoercer before any Effect can be cloned /
        // parsed. Per effects-api-ADR-004, Effect#canParse / #str2Obj /
        // #fixData all delegate to EffectFactory.getCoercer().
        BukkitHandles.register();
        BukkitValueCoercer coercer = new BukkitValueCoercer();
        EffectFactory.setCoercer(coercer);
        int serverVersion = EffectsAPI.getServerIntVersion();
        EffectFactory.registerBuiltInEffects(
                resolveDefaultSound(coercer),
                serverVersion > 8 ? resolveDefaultParticle(coercer) : null,
                resolveDefaultPotion(coercer),
                org.bukkit.FireworkEffect.Type.BALL,
                Color.WHITE,
                Instrument.PIANO);
        if (serverVersion <= 8) {
            EffectFactory.removeEffect("PARTICLE");
        }
        if (serverVersion < 9) {
            EffectFactory.removeEffect("GLIDE");
        }
    }

    private static Object resolveDefaultSound(BukkitValueCoercer coercer) {
        try {
            return Sound.valueOf("ENTITY_PLAYER_LEVELUP");
        } catch (Throwable ignored) {
        }
        try {
            return coercer.parse(io.github.dailystruggle.effectsapi.common.spi.TypeKey.SOUND, "ENTITY_PLAYER_LEVELUP");
        } catch (Throwable ignored) {
            return "ENTITY_PLAYER_LEVELUP";
        }
    }

    private static Object resolveDefaultParticle(BukkitValueCoercer coercer) {
        for (String candidate : new String[]{"EXPLOSION_NORMAL", "POOF", "FLAME"}) {
            try {
                return Particle.valueOf(candidate);
            } catch (Throwable ignored) {
            }
            try {
                if (coercer.canParse(io.github.dailystruggle.effectsapi.common.spi.TypeKey.PARTICLE, candidate)) {
                    return coercer.parse(io.github.dailystruggle.effectsapi.common.spi.TypeKey.PARTICLE, candidate);
                }
            } catch (Throwable ignored) {
            }
        }
        return "EXPLOSION_NORMAL";
    }

    private static Object resolveDefaultPotion(BukkitValueCoercer coercer) {
        try {
            PotionEffectType byName = PotionEffectType.getByName("BLINDNESS");
            if (byName != null) return byName;
        } catch (Throwable ignored) {
        }
        try {
            return coercer.parse(io.github.dailystruggle.effectsapi.common.spi.TypeKey.POTION_EFFECT, "BLINDNESS");
        } catch (Throwable ignored) {
            return "BLINDNESS";
        }
    }

    /**
     * Bukkit-typed convenience: build effects from a player's effective
     * permissions. Adapts to the platform-neutral
     * {@link EffectFactory#buildEffects(String, Collection)}.
     */
    public static List<Effect<?>> buildEffects(@NotNull String permissionPrefix,
                                               @NotNull Collection<PermissionAttachmentInfo> permissions) {
        List<String> nodes = new ArrayList<>(permissions.size());
        for (PermissionAttachmentInfo perm : permissions) {
            if (!perm.getValue()) continue;
            nodes.add(perm.getPermission());
        }
        return EffectFactory.buildEffects(permissionPrefix, nodes);
    }

    /**
     * Bukkit-only: pre-register permission nodes for every registered effect's
     * type-enum values (so permission UIs / LuckPerms see them).
     */
    public static void addPermissions(String permissionPrefix) {
        if (permissionPrefix == null || permissionPrefix.isEmpty()) return;
        if (!permissionPrefix.endsWith(".")) permissionPrefix = permissionPrefix + ".";
        for (String name : EffectFactory.registeredNames()) {
            if (name == null) continue;
            Effect<?> effect = EffectFactory.buildEffect(name);
            Enum<?>[] enumConstants = Objects.requireNonNull(effect).persistentClass.getEnumConstants();
            Map<String, Enum<?>> enumMap = new HashMap<>();
            if (enumConstants.length < 50) for (Enum<?> e : enumConstants) enumMap.put(e.name().toUpperCase(), e);
            Object o = effect.getData().get(enumMap.get("TYPE"));
            if (o instanceof Enum) {
                Enum<?> e = (Enum<?>) o;
                for (Enum<?> key : e.getClass().getEnumConstants()) {
                    if (key == null) continue;
                    Bukkit.getPluginManager().addPermission(new Permission(permissionPrefix + name + "." + key));
                }
            } else if (o instanceof PotionEffectType) {
                for (PotionEffectType key : PotionEffectType.values()) {
                    if (key == null) continue;
                    Bukkit.getPluginManager().addPermission(new Permission(permissionPrefix + name + "." + key));
                }
            }
        }
    }
}
