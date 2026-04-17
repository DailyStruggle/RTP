package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.effectsapi.LocalEffects.*;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

//factory to give and take effects
//  I did this to centralize effects. Call this instead of switching on every possible effect
public class EffectFactory {
    //map name to effect type
    //  this is a runtime solution for effectively a switch statement, to allow addition and removal
    private static final ConcurrentHashMap<String, Effect<?>> effectMap = new ConcurrentHashMap<>();
    // recall list of permissions added for removal on removeEffect
    // key: effect name
    // val: permission names
    private final static ConcurrentHashMap<String, List<String>> initializedPermissions = new ConcurrentHashMap<>();

    static {
        addEffect("FIREWORK", new FireworkEffect());
        if(EffectsAPI.getServerIntVersion() > 16) addEffect("NOTE", new NoteEffect());
        else addEffect("NOTE",new NoteEffect_1_12());
        if(EffectsAPI.getServerIntVersion()>8) addEffect("PARTICLE", new ParticleEffect());
        addEffect("POTION", new PotionEffect());
        addEffect("SOUND", new SoundEffect());
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
     * build effects from permissions
     *
     * @param permissionPrefix - which permissions to check, for contextual effects
     * @param permissions      - set of permissions, typically from player.getEffectivePermissions()
     * @return all effects constructed
     */
    public static List<Effect<?>> buildEffects(@NotNull String permissionPrefix, @NotNull final Collection<PermissionAttachmentInfo> permissions) {
        List<Effect<?>> res = new ArrayList<>();
        if (!permissionPrefix.endsWith(".")) permissionPrefix += ".";

        for (PermissionAttachmentInfo perm : permissions) {
            if (!perm.getValue()) continue;
            String node = perm.getPermission();
            if (!node.startsWith(permissionPrefix)) continue;

            String[] val = node.replace(permissionPrefix, "").split("\\.");

            Effect<?> effect;
            effect = effectMap.get(val[0].toUpperCase()).clone();
            //todo: convert parameters to data

            if(val.length>1) effect.setData(Arrays.copyOfRange(val,1,val.length));
            res.add(effect);
        }

        return res;
    }

    public static void addPermissions(String permissionPrefix) {
        if(permissionPrefix == null || permissionPrefix.isEmpty()) return;
        if(!permissionPrefix.endsWith(".")) permissionPrefix = permissionPrefix + ".";
        for(String name : effectMap.keySet()) {
            if(name == null) continue;
            Effect<?> effect = EffectFactory.buildEffect(name);
            Enum<?>[] enumConstants = Objects.requireNonNull(effect).persistentClass.getEnumConstants();
            Map<String,Enum<?>> enumMap = new HashMap<>();
            if(enumConstants.length<50)for(Enum<?> e : enumConstants) enumMap.put(e.name().toUpperCase(),e);
            Object o = effect.getData().get(enumMap.get("TYPE"));
            if(o instanceof Enum) {
                Enum<?> e = (Enum<?>) o;
                for(Enum<?> key : e.getClass().getEnumConstants()) {
                    if(key == null) continue;
                    Bukkit.getPluginManager().addPermission(new Permission(permissionPrefix + name + "." + key));
                }
            }
            else if(o instanceof PotionEffectType) {
                for(PotionEffectType key : PotionEffectType.values()) {
                    if(key == null) continue;
                    Bukkit.getPluginManager().addPermission(new Permission(permissionPrefix + name + "." + key));
                }
            }
        }
    }
}
