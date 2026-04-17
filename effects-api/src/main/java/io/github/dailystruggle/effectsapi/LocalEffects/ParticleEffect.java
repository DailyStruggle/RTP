package io.github.dailystruggle.effectsapi.LocalEffects;

import io.github.dailystruggle.effectsapi.Effect;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.ParticleTypeNames;
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
        if(data.length>0) this.data.put(ParticleTypeNames.TYPE, data[0]);
        if(data.length>1) this.data.put(ParticleTypeNames.NUMBER, data[1]);
        this.data = fixData(this.data);
    }

    @Override
    public String toPermission() {

        return this.data.get(ParticleTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(ParticleTypeNames.NUMBER).toString().replaceAll("\\.*", "");
    }
}
