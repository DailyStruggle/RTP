package io.github.dailystruggle.effectsapi.LocalEffects;

import io.github.dailystruggle.effectsapi.Effect;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.FireworkTypeNames;
import io.github.dailystruggle.effectsapi.LocalEffects.enums.PotionTypeNames;
import io.github.dailystruggle.effectsapi.SpigotListeners.FireworkSafetyListener;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.EnumMap;
import java.util.Objects;

public class FireworkEffect extends Effect<FireworkTypeNames> {
    public FireworkEffect() {
        super(new EnumMap<>(FireworkTypeNames.class));
        EnumMap<FireworkTypeNames, Object> data = getData();
        data.put(FireworkTypeNames.TYPE, org.bukkit.FireworkEffect.Type.BALL);
        data.put(FireworkTypeNames.NUMBER, 1);
        data.put(FireworkTypeNames.POWER, 0);
        data.put(FireworkTypeNames.DX, 0.0);
        data.put(FireworkTypeNames.DY, 1.0);
        data.put(FireworkTypeNames.DZ, 0.0);
        data.put(FireworkTypeNames.COLOR, Color.WHITE);
        data.put(FireworkTypeNames.FADE, Color.WHITE);
        data.put(FireworkTypeNames.FLICKER, false);
        data.put(FireworkTypeNames.TRAIL, false);
        data.put(FireworkTypeNames.SAFE, true);
        this.data = data;
        this.defaults = data.clone();
    }

    @Override
    public void run() {
        if (target instanceof Entity) target = ((Entity) target).getLocation();
        if(!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(EffectsAPI.fireworkSafetyListener.caller, this);
            return;
        }

        Location location = (Location) target;

        int numFireworks = 1;
        double dx=0, dy=1, dz=0;
        Color color = Color.WHITE, fade = Color.WHITE;
        boolean flicker=false, trail=false, safe=true;

        Object o = data.get(FireworkTypeNames.NUMBER);
        if(o instanceof Number) numFireworks = ((Number) o).intValue();


        o = data.get(FireworkTypeNames.DX);
        if(o instanceof Number) dx = ((Number)o).doubleValue();
        o = data.get(FireworkTypeNames.DY);
        if(o instanceof Number) dy = ((Number)o).doubleValue();
        o = data.get(FireworkTypeNames.DZ);
        if(o instanceof Number) dz = ((Number)o).doubleValue();

        o = data.get(FireworkTypeNames.COLOR);
        if(o instanceof Color) color = (Color) o;

        o = data.get(FireworkTypeNames.FADE);
        if(o instanceof Color) fade = (Color) o;

        o = data.get(FireworkTypeNames.FLICKER);
        if(o instanceof Boolean) flicker = (Boolean) o;

        o = data.get(FireworkTypeNames.TRAIL);
        if(o instanceof Boolean) trail = (Boolean) o;

        o = data.get(FireworkTypeNames.SAFE);
        if(o instanceof Boolean) safe = (Boolean) o;

        //start with one firework
        Firework f = (Firework) Objects.requireNonNull(location.getWorld())
                .spawnEntity(location.clone().add(0, 1, 0), EntityType.FIREWORK);
        if (dx != 0.0 || dy != 1.0 || dz != 0.0) {
            f.setShotAtAngle(true);
            f.setVelocity(new Vector(dx, dy, dz));
        }
        FireworkMeta fwm = f.getFireworkMeta();

        org.bukkit.FireworkEffect fireworkEffect = org.bukkit.FireworkEffect.builder()
                .with((org.bukkit.FireworkEffect.Type) data.get(FireworkTypeNames.TYPE))
                .withColor(color)
                .withFade(fade)
                .flicker(flicker)
                .trail(trail)
                .build();
        fwm.addEffect(fireworkEffect);
        fwm.setPower((Integer) data.get(FireworkTypeNames.POWER));
        f.setFireworkMeta(fwm);

        //if more than one, add to the list to duplicate the explosion (cheaper than multiple firework entities)
        if (numFireworks > 1) FireworkSafetyListener.addFirework(f.getEntityId(), numFireworks, safe);
        if (fwm.getPower() == 0) f.detonate();
    }

    @Override
    public void setData(String... data) {
        if(data.length>0) this.data.put(FireworkTypeNames.TYPE, data[0]);
        if(data.length>1) this.data.put(FireworkTypeNames.NUMBER, data[1]);
        if(data.length>2) this.data.put(FireworkTypeNames.POWER, data[2]);
        if(data.length>3) this.data.put(FireworkTypeNames.COLOR, data[3]);
        if(data.length>4) this.data.put(FireworkTypeNames.FADE, data[4]);
        if(data.length>5) this.data.put(FireworkTypeNames.FLICKER, data[5]);
        if(data.length>6) this.data.put(FireworkTypeNames.TRAIL, data[6]);
        if(data.length>7) this.data.put(FireworkTypeNames.SAFE, data[7]);
        if(data.length>8) this.data.put(FireworkTypeNames.DX, data[8]);
        if(data.length>9) this.data.put(FireworkTypeNames.DY, data[9]);
        if(data.length>10) this.data.put(FireworkTypeNames.DZ, data[10]);
        this.data = fixData(this.data);
    }

    @Override
    public String toPermission() {
        return this.data.get(FireworkTypeNames.TYPE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.NUMBER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.POWER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.COLOR).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.FADE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.FLICKER).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.TRAIL).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.SAFE).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DX).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DY).toString().replaceAll("\\.*", "") +
                this.data.get(FireworkTypeNames.DZ).toString().replaceAll("\\.*", "");

    }
}
