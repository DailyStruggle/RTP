package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.FireworkTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.PotionTypeNames;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.SoundTypeNames;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.EnumMap;

public class SoundEffect extends Effect<SoundTypeNames> {

    public SoundEffect() throws IllegalArgumentException {
        super(new EnumMap<>(SoundTypeNames.class));
        EnumMap<SoundTypeNames, Object> data = getData();
        // Sound.values() is an enum-only API. On MC 1.21.3+ Sound is an interface
        // backed by Registry.SOUNDS, so fall back to the first registered sound
        // via reflection. Either way we just need any non-null Sound for typing.
        data.put(SoundTypeNames.TYPE, defaultSound());
        data.put(SoundTypeNames.VOLUME, 100);
        data.put(SoundTypeNames.PITCH, 100);
        data.put(SoundTypeNames.DX, 0.0);
        data.put(SoundTypeNames.DY, 0.0);
        data.put(SoundTypeNames.DZ, 0.0);
        this.data = data;
        this.defaults = data.clone();
    }

    /**
     * Pick any non-null {@link Sound} instance to seed the default. Tries the
     * pre-1.21.3 enum {@code values()} via reflection first, then falls back to
     * the first iterated entry from {@code Registry.SOUNDS}. As a last resort
     * resolves the canonical {@code minecraft:entity.enderman.teleport} key,
     * which has existed since 1.13.
     */
    private static Sound defaultSound() {
        try {
            Object arr = Sound.class.getMethod("values").invoke(null);
            if (arr instanceof Object[] && ((Object[]) arr).length > 0) {
                return (Sound) ((Object[]) arr)[0];
            }
        } catch (Throwable ignored) {
            // Sound is no longer an enum; fall through.
        }
        try {
            for (Object s : org.bukkit.Registry.SOUNDS) {
                if (s instanceof Sound) return (Sound) s;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            Sound s = org.bukkit.Registry.SOUNDS.get(
                    org.bukkit.NamespacedKey.minecraft("entity.enderman.teleport"));
            if (s != null) return s;
        } catch (Throwable ignored) {
            // ignore
        }
        return null;
    }

    @Override
    public void run() {
        if (target instanceof Player) {
            double dx=0, dy=0, dz=0;
            float volume=100, pitch=1000;

            Object o = data.get(SoundTypeNames.VOLUME);
            if(o instanceof Number) volume = ((Number) o).floatValue()/100;

            o = data.get(SoundTypeNames.PITCH);
            if(o instanceof Number) pitch = ((Number) o).floatValue()/100;

            o = data.get(SoundTypeNames.DX);
            if(o instanceof Number) dx = ((Number) o).floatValue();

            o = data.get(SoundTypeNames.DY);
            if(o instanceof Number) dy = ((Number) o).floatValue();

            o = data.get(SoundTypeNames.DZ);
            if(o instanceof Number) dz = ((Number) o).floatValue();

            Location location = ((Player) target).getLocation().add(dx,dy,dz);
            ((Player) target).playSound(location,
                    (Sound) data.get(SoundTypeNames.TYPE),
                    volume, pitch
            );
        } else {
            if (target instanceof Entity) target = ((Entity) target).getLocation();
        }
    }

    @Override
    public String toPermission() {
        return this.data.get(SoundTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(SoundTypeNames.VOLUME).toString().replaceAll("\\.*", "") +
                this.data.get(SoundTypeNames.PITCH).toString().replaceAll("\\.*", "") +
                this.data.get(SoundTypeNames.DX).toString().replaceAll("\\.*", "") +
                this.data.get(SoundTypeNames.DY).toString().replaceAll("\\.*", "") +
                this.data.get(SoundTypeNames.DZ).toString().replaceAll("\\.*", "");
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final SoundTypeNames[] KEY_ORDER = {
            SoundTypeNames.TYPE,
            SoundTypeNames.VOLUME,
            SoundTypeNames.PITCH,
            SoundTypeNames.DX,
            SoundTypeNames.DY,
            SoundTypeNames.DZ
    };
}
