package io.github.dailystruggle.effectsapi.bukkit;

import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.FireworkEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.GlideEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.NoteEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.NoteEffect_1_12;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.ParticleEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.PotionEffect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.SoundEffect;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.bukkit.Bukkit;
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
 * {@code GLIDE} effects. Fabric uses a parallel
 * {@code FabricEffectsInitializer}.
 *
 * <p>Also retains Bukkit-typed convenience methods —
 * {@link #buildEffects(String, Collection)} (overloads on
 * {@link PermissionAttachmentInfo}) and {@link #addPermissions(String)} —
 * which previously lived on {@code EffectFactory} but cannot exist there
 * without a hard Bukkit dependency.
 */
public final class BukkitEffectsInitializer {

    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private BukkitEffectsInitializer() {}

    /**
     * Schedule an {@link Effect} on the Bukkit main thread. Replaces the
     * prior {@code effect.runTask(plugin)} pattern that relied on
     * {@code Effect extends BukkitRunnable} (decoupled 2026-05-07 — see
     * {@code Effect} class javadoc). Falls back to a direct
     * {@link Effect#run()} call if the scheduler rejects the task (e.g. on a
     * Folia region thread under unusual ownership conditions), preserving
     * the resilience of the prior {@code try{runTask} catch{run}} idiom.
     *
     * <p>Special-cases {@link NoteEffect}, which schedules a 1-tick follow-up
     * task in its overridden {@code runTask(Plugin)} — call that directly so
     * the follow-up still fires.
     */
    public static void runEffect(@NotNull Plugin plugin, @NotNull Effect<?> effect) {
        try {
            if (effect instanceof NoteEffect) {
                ((NoteEffect) effect).runTask(plugin);
            } else {
                Bukkit.getScheduler().runTask(plugin, (Runnable) effect);
            }
        } catch (Throwable t) {
            effect.run();
        }
    }

    /**
     * Register the legacy Bukkit-side effects with {@link EffectFactory}.
     * Idempotent — safe to call multiple times.
     */
    public static void registerAll() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        // Bind the Bukkit-side ValueCoercer before any Effect can be cloned /
        // parsed. Per effects-api-ADR-004, Effect#canParse / #str2Obj /
        // #fixData all delegate to EffectFactory.getCoercer().
        EffectFactory.setCoercer(new BukkitValueCoercer());
        EffectFactory.addEffect("FIREWORK", new FireworkEffect());
        if (EffectsAPI.getServerIntVersion() > 16) EffectFactory.addEffect("NOTE", new NoteEffect());
        else EffectFactory.addEffect("NOTE", new NoteEffect_1_12());
        if (EffectsAPI.getServerIntVersion() > 8) EffectFactory.addEffect("PARTICLE", new ParticleEffect());
        EffectFactory.addEffect("POTION", new PotionEffect());
        EffectFactory.addEffect("SOUND", new SoundEffect());
        // Glide requires elytra (1.9+); register conditionally to keep older servers happy.
        if (EffectsAPI.getServerIntVersion() >= 9) EffectFactory.addEffect("GLIDE", new GlideEffect());
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
