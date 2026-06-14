package io.github.dailystruggle.effectsapi.common.effects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.spi.HandleRegistry;
import io.github.dailystruggle.effectsapi.common.spi.LocationHandle;
import io.github.dailystruggle.effectsapi.common.spi.PlayerHandle;

import java.util.EnumMap;

/**
 * Common particle effect implementation.
 */
public class ParticleEffect extends Effect<ParticleEffect.ParticleKeys> {
    public enum ParticleKeys { TYPE, NUMBER, DX, DY, DZ, SPEED }

    public ParticleEffect(Object defaultParticle) {
        super(new EnumMap<>(ParticleKeys.class));
        data.put(ParticleKeys.TYPE, defaultParticle);
        data.put(ParticleKeys.NUMBER, 1);
        data.put(ParticleKeys.DX, 0.0);
        data.put(ParticleKeys.DY, 0.0);
        data.put(ParticleKeys.DZ, 0.0);
        data.put(ParticleKeys.SPEED, 0.0);
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        int count = 1;
        double dx = 0, dy = 0, dz = 0, speed = 0;

        Object o = data.get(ParticleKeys.NUMBER);
        if (o instanceof Number) count = ((Number) o).intValue();
        o = data.get(ParticleKeys.DX);
        if (o instanceof Number) dx = ((Number) o).doubleValue();
        o = data.get(ParticleKeys.DY);
        if (o instanceof Number) dy = ((Number) o).doubleValue();
        o = data.get(ParticleKeys.DZ);
        if (o instanceof Number) dz = ((Number) o).doubleValue();
        o = data.get(ParticleKeys.SPEED);
        if (o instanceof Number) speed = ((Number) o).doubleValue();

        Object type = data.get(ParticleKeys.TYPE);
        PlayerHandle ph = HandleRegistry.wrapPlayer(target);
        if (ph != null) {
            ph.spawnParticle(type, count, dx, dy, dz, speed);
        } else {
            LocationHandle lh = HandleRegistry.wrapLocation(target);
            if (lh != null) lh.spawnParticle(type, count, dx, dy, dz, speed);
        }
    }

    @Override
    public void setData(String... data) {
        applyByType(ParticleKeys.values(), data);
    }

    @Override
    public String toPermission() {
        return data.get(ParticleKeys.TYPE).toString().replaceAll("\\.*", "") +
               data.get(ParticleKeys.NUMBER).toString().replaceAll("\\.*", "");
    }
}
