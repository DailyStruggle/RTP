package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Common sound effect implementation.
 */
public class SoundEffect extends Effect<SoundEffect.SoundKeys> {
    public enum SoundKeys { TYPE, VOLUME, PITCH, DX, DY, DZ }

    public SoundEffect(Object defaultSound) {
        super(new EnumMap<>(SoundKeys.class));
        data.put(SoundKeys.TYPE, defaultSound);
        data.put(SoundKeys.VOLUME, 100);
        data.put(SoundKeys.PITCH, 100);
        data.put(SoundKeys.DX, 0.0);
        data.put(SoundKeys.DY, 0.0);
        data.put(SoundKeys.DZ, 0.0);
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        double dx = 0, dy = 0, dz = 0;
        float volume = 1.0f, pitch = 1.0f;

        Object o = data.get(SoundKeys.VOLUME);
        if (o instanceof Number) volume = ((Number) o).floatValue() / 100;
        o = data.get(SoundKeys.PITCH);
        if (o instanceof Number) pitch = ((Number) o).floatValue() / 100;
        o = data.get(SoundKeys.DX);
        if (o instanceof Number) dx = ((Number) o).doubleValue();
        o = data.get(SoundKeys.DY);
        if (o instanceof Number) dy = ((Number) o).doubleValue();
        o = data.get(SoundKeys.DZ);
        if (o instanceof Number) dz = ((Number) o).doubleValue();

        Object type = data.get(SoundKeys.TYPE);
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        if (ph != null) {
            ph.playSound(type, volume, pitch, dx, dy, dz);
        } else {
            LocationHandle lh = HandleRegistry.wrapLocation(target);
            if (lh != null) lh.playSound(type, volume, pitch);
        }
    }

    @Override
    public String toPermission() {
        return data.get(SoundKeys.TYPE).toString().replaceAll("\\.*", "") +
               data.get(SoundKeys.VOLUME).toString().replaceAll("\\.*", "") +
               data.get(SoundKeys.PITCH).toString().replaceAll("\\.*", "") +
               data.get(SoundKeys.DX).toString().replaceAll("\\.*", "") +
               data.get(SoundKeys.DY).toString().replaceAll("\\.*", "") +
               data.get(SoundKeys.DZ).toString().replaceAll("\\.*", "");
    }

    @Override
    public void setData(String... data) {
        applyByType(SoundKeys.values(), data);
    }
}
