package io.github.dailystruggle.effectsapi.bukkit.LocalEffects;

import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums.ParticleTypeNames;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;

import java.util.EnumMap;
import java.util.Objects;

public class ParticleEffect extends Effect<ParticleTypeNames> {
    public ParticleEffect() throws IllegalArgumentException {
        super(new EnumMap<>(ParticleTypeNames.class));
        EnumMap<ParticleTypeNames, Object> data = getData();
        data.put(ParticleTypeNames.TYPE, Particle.EXPLOSION_NORMAL);
        data.put(ParticleTypeNames.NUMBER, 1);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        Location location = (Location) target;

        int numParticles = 0;
        Object o = data.get(ParticleTypeNames.NUMBER);
        if(o instanceof Number) numParticles = ((Number) o).intValue();

        Objects.requireNonNull(location.getWorld()).spawnParticle(
                (Particle) data.get(ParticleTypeNames.TYPE),
                location,
                numParticles);
    }

    @Override
    public void setData(String... data) {
        applyByType(KEY_ORDER, data);
    }

    private static final ParticleTypeNames[] KEY_ORDER = { ParticleTypeNames.TYPE, ParticleTypeNames.NUMBER };

    @Override
    public String toPermission() {

        return this.data.get(ParticleTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(ParticleTypeNames.NUMBER).toString().replaceAll("\\.*", "");
    }
}
